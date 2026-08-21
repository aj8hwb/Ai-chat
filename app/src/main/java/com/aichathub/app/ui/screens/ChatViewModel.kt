package com.aichathub.app.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.ChatGenerationState
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.device.LoadEligibility
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.aichathub.app.util.TokenEstimator

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val isLoadingModel: Boolean = false,
    val error: String? = null,
    val activeModelId: String? = null,
    val activeModelName: String? = null,
    val selectedConversationId: Long? = null,
    val lastStreamedText: String = "",
    /** Only models in the READY (verified + installed) state appear here. */
    val installedModels: List<CatalogModel> = emptyList(),
    /** All saved conversations, newest first — shown in the history drawer. */
    val conversations: List<ConversationEntity> = emptyList(),
    /** Thinking depth chosen by the user: INSTANT / DEFAULT / HARD. */
    val thinkingMode: String = "DEFAULT",
    /** Real generation stats of the last completed reply (Hard thinking trace). */
    val lastThinking: ThinkingInfo? = null,
    val isModelLoaded: Boolean = false,
    val generationPhase: String = ChatGenerationState.IDLE.name,
    /** Live "thinking" timer — ticks every second while the model generates. */
    val liveThinkingSec: Int = 0,
    /** User toggled the real-time thinking trace panel open. */
    val thinkingExpanded: Boolean = false,
    /** Live activity log of the current generation (real telemetry). */
    val thinkingTrace: List<String> = emptyList(),
    /** Native context window of the loaded model (tokens). */
    val contextTokensMax: Int = 0
)

/** Real, measured data about the last completed generation, shown in the
 *  "How the AI thought" panel when Hard thinking is enabled. */
data class ThinkingInfo(
    val mode: String,
    val tokens: Int,
    val elapsedMs: Long,
    val tokensPerSecond: Float,
    val responseChars: Int
) {
    val elapsedSec: Float get() = elapsedMs / 1000f
    val tps: Float get() = tokensPerSecond
}

/**
 * Chat screen state holder.
 *
 * Sources of truth:
 *  - the coordinator (active model, generation state),
 *  - the model repository (READY = verified + installed registry; ONLY these
 *    models are selectable),
 *  - the message DAO (persisted conversation; the user's message is written
 *    before inference starts so it always renders).
 */
class ChatViewModel(application: Application) : AiViewModel(application) {

    /** Local prompts beyond this size are rejected: sending a huge prompt into
     *  llama.cpp can overflow the native context and crash the whole process. */
    companion object {
        const val MAX_MESSAGE_CHARS = 1500
        private const val MAX_TRACE_LINES = 120
    }

    private val coordinator: ChatCoordinator = container.chatCoordinator

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Bumped on every model selection; stale asynchronous loads are ignored. */
    private var loadSession = 0L

    /** Bumped on every send; a finished older send must not clear the state of a newer one. */
    private var sendSeq = 0L

    /**
     * Main-thread in-flight guard. Unlike `_state.generating` it cannot be
     * overwritten by the coordinator-state observer, so a rapid double-tap on
     * Send is rejected deterministically.
     */
    @Volatile
    private var sendInFlight = false

    private var messagesJob: Job? = null

    init {
        observeCoordinator()
        autoSelectDefaultModel()
        viewModelScope.launch {
            container.settingsRepository.settings.first().thinkingMode.let { mode ->
                _state.value = _state.value.copy(thinkingMode = mode)
            }
        }
    }

    fun setThinkingMode(mode: String) {
        if (mode == _state.value.thinkingMode) return
        _state.value = _state.value.copy(thinkingMode = mode)
        viewModelScope.launch {
            container.settingsRepository.setThinkingMode(mode)
        }
    }

    /** Expands / collapses the real-time thinking trace panel. */
    fun toggleThinking() {
        _state.value = _state.value.copy(thinkingExpanded = !_state.value.thinkingExpanded)
    }

    /** Appends a line to the live thinking trace (bounded so it cannot grow forever). */
    private fun appendTrace(line: String) {
        val capped = (_state.value.thinkingTrace + line).takeLast(MAX_TRACE_LINES)
        _state.value = _state.value.copy(thinkingTrace = capped)
    }

    /**
     * Persists an edited message. When a USER message is edited, every message
     * that followed it was generated from the OLD text, so those replies are
     * now inconsistent with the edit. They are removed (the user can re-send);
     * editing an assistant message is a manual correction and is kept as-is.
     */
    fun updateMessage(message: MessageEntity) {
        viewModelScope.launch {
            container.messageDao.update(message)
            if (message.role == "user") {
                val convId = message.conversationId
                val later = container.messageDao.forConversation(convId)
                    .filter { it.createdAt > message.createdAt }
                later.forEach { container.messageDao.delete(it.id) }
            }
        }
    }

    /**
     * Prepares the chat on startup: drops ghost (empty) conversations and
     * picks the best model to use by default. The default is the user's saved
     * default model when it is installed and READY, otherwise the most
     * compatible installed model (falls back to the first installed model).
     * The user can still override it from the model selector at any time.
     */
    private fun autoSelectDefaultModel() {
        viewModelScope.launch {
            runCatching { container.conversationDao.pruneEmpty() }
            val ready = readyModels()
            val best = pickBestModel(ready)
            if (best != null && _state.value.activeModelId == null) {
                _state.value = _state.value.copy(
                    activeModelId = best.id,
                    activeModelName = best.name
                )
            }
        }
    }

    private suspend fun readyModels(): List<CatalogModel> =
        container.modelRepository.installedModels.first()
            .filter { it.state == ModelLifecycleState.READY && it.filePath != null }
            .mapNotNull { LocalModelCatalog.byId(it.modelId) }

    private suspend fun pickBestModel(ready: List<CatalogModel>): CatalogModel? {
        if (ready.isEmpty()) return null
        val savedDefault = container.settingsRepository.settings.first().defaultModelId
        savedDefault?.let { id -> ready.firstOrNull { it.id == id }?.let { return it } }
        return runCatching {
            val profile = container.deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            val measured = container.settingsRepository.measuredMemoryOnce()
            container.compatibilityEngine.recommendAll(ready, profile, budget, measured)
                .firstOrNull()?.model
        }.getOrNull() ?: ready.first()
    }

    private fun observeCoordinator() {
        viewModelScope.launch {
            coordinator.state.collect { s ->
                _state.value = _state.value.copy(
                    activeModelId = s.activeModelId ?: _state.value.activeModelId,
                    activeModelName = s.activeModelName ?: _state.value.activeModelName,
                    generating = s.generationState == ChatGenerationState.GENERATING,
                    isLoadingModel = s.isLoadingModel,
                    // Coordinator errors are shown as-is; while the coordinator is
                    // IDLE/DONE (no active operation) a stale error is never
                    // resurrected and never wipes a message error shown by the VM.
                    error = if (s.generationState == ChatGenerationState.LOADING ||
                        s.generationState == ChatGenerationState.GENERATING
                    ) {
                        s.error
                    } else {
                        _state.value.error
                    },
                    isModelLoaded = container.inferenceRuntime.isLoaded && s.activeModelId != null,
                    generationPhase = s.generationState.name
                )
            }
        }
        viewModelScope.launch {
            container.inferenceRuntime.performance.collect { p ->
                _state.value = _state.value.copy(contextTokensMax = p.contextTokensMax)
            }
        }
        viewModelScope.launch {
            coordinator.activeConversationId.collect { id ->
                _state.value = _state.value.copy(selectedConversationId = id)
            }
        }
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                // ONLY verified + installed (READY) models may appear in the
                // Chat selector. Downloading / paused / verifying / failed
                // models are never shown here.
                val ready = installed
                    .filter { it.state == ModelLifecycleState.READY && it.filePath != null }
                    .mapNotNull { LocalModelCatalog.byId(it.modelId) }
                _state.value = _state.value.copy(installedModels = ready)
                // If nothing is selected yet, auto-select the best installed
                // model so a freshly downloaded model is immediately usable.
                if (_state.value.activeModelId == null && ready.isNotEmpty()) {
                    val best = pickBestModel(ready)
                    if (best != null) {
                        _state.value = _state.value.copy(
                            activeModelId = best.id,
                            activeModelName = best.name
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            container.conversationDao.observeAll().collect { conversations ->
                _state.value = _state.value.copy(conversations = conversations)
            }
        }
    }

    fun setInput(v: String) {
        _state.value = _state.value.copy(input = v)
    }

    fun loadConversation(id: Long) {
        viewModelScope.launch {
            coordinator.selectConversation(id)
            _state.value = _state.value.copy(conversationId = id, selectedConversationId = id)
            observeMessages(id)
            // Switch to the conversation's model when it is still installed so
            // the reply continues in the same model the user originally used.
            val conv = container.conversationDao.byId(id)
            if (conv != null) {
                val catalog = LocalModelCatalog.byId(conv.modelId)
                val st = container.modelRepository.stateFor(conv.modelId)
                if (catalog != null && st?.state == ModelLifecycleState.READY && st.filePath != null
                    && _state.value.activeModelId != catalog.id
                ) {
                    selectModel(catalog)
                }
            }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            coordinator.deleteConversation(id)
            if (_state.value.conversationId == id) newChat()
        }
    }

    /**
     * Sets the per-conversation system prompt override. Passing null/blank
     * clears the override and falls back to the global system prompt.
     */
    fun setConversationSystemPrompt(prompt: String) {
        val convId = _state.value.conversationId ?: return
        viewModelScope.launch {
            val trimmed = prompt.trim().ifBlank { null }
            container.conversationDao.setSystemPrompt(convId, trimmed)
        }
    }

    fun newChat() {
        coordinator.newConversation()
        messagesJob?.cancel()
        _state.value = _state.value.copy(
            conversationId = null,
            selectedConversationId = null,
            messages = emptyList(),
            error = null,
            lastStreamedText = ""
        )
    }

    fun selectModel(model: CatalogModel) {
        val session = ++loadSession
        viewModelScope.launch {
            val st = container.modelRepository.stateFor(model.id)
            val file = st?.filePath?.let { File(it) }
            if (st?.state != ModelLifecycleState.READY || file == null || !file.isFile) {
                _state.value = _state.value.copy(
                    error = "This model is not ready yet. Download and verify it first."
                )
                return@launch
            }
            if (session != loadSession) return@launch
            _state.value = _state.value.copy(isLoadingModel = true, error = null)
            try {
                val settings = container.settingsRepository.settings.first()
                val gate = loadGate(model)
                if (gate.eligibility == LoadEligibility.BLOCK) {
                    _state.value = _state.value.copy(
                        isLoadingModel = false,
                        error = gate.message
                    )
                    return@launch
                }
                if (gate.eligibility == LoadEligibility.WARN) {
                    _state.value = _state.value.copy(error = gate.message)
                }
                coordinator.loadModel(
                    model,
                    file,
                    GenerationConfig(
                        temperature = settings.temperature,
                        topK = settings.topK,
                        topP = settings.topP,
                        maxTokens = settings.maxTokens
                    ),
                    threads = nativeThreads(settings)
                )
                if (session != loadSession) return@launch
                _state.value = _state.value.copy(
                    activeModelId = model.id,
                    activeModelName = model.name,
                    isLoadingModel = false
                )
            } catch (e: OutOfMemoryError) {
                _state.value = _state.value.copy(
                    isLoadingModel = false,
                    error = "❌ Insufficient memory to load this model safely. Try a lighter model."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoadingModel = false,
                    error = "The model could not be loaded. Please select another installed model."
                )
            }
        }
    }

    /**
     * Selects a model by its catalog id (used when the Model Store / My Models
     * "Chat" button opens the chat with a specific model pre-selected).
     */
    fun selectModelById(modelId: String) {
        if (modelId.isBlank()) return
        if (modelId == _state.value.activeModelId) return
        val catalog = LocalModelCatalog.byId(modelId) ?: return
        viewModelScope.launch {
            val st = container.modelRepository.stateFor(modelId)
            if (st?.state != ModelLifecycleState.READY || st.filePath == null) {
                _state.value = _state.value.copy(
                    error = "This model is not ready yet. Download and verify it first."
                )
                return@launch
            }
            selectModel(catalog)
        }
    }

    fun send() {
        sendInternal(persistUser = true)
    }

    fun sendInternal(persistUser: Boolean) {
        val text = _state.value.input.trim()
        if (text.isEmpty() || sendInFlight || _state.value.generating) return
        if (text.length > MAX_MESSAGE_CHARS) {
            _state.value = _state.value.copy(
                error = "That message is too long (max $MAX_MESSAGE_CHARS characters). Please shorten it."
            )
            return
        }
        val model = _state.value.activeModelId?.let { LocalModelCatalog.byId(it) }
        if (model == null) {
            _state.value = _state.value.copy(error = "Select a model to start chatting.")
            return
        }

        // Set the in-flight flag SYNCHRONOUSLY so a rapid double-tap on Send is
        // rejected before it can launch a second coroutine. All native
        // generation is additionally serialized inside the runtime, so two
        // concurrent llama.cpp calls can never overlap (that would SIGSEGV).
        sendInFlight = true
        val seq = ++sendSeq
        _state.value = _state.value.copy(
            generating = true,
            error = null,
            lastStreamedText = "",
            thinkingTrace = listOf("Preparing message…")
        )

        viewModelScope.launch {
            var thinkingTicker: Job? = null
            // The ENTIRE body is inside try/finally so every failure path —
            // including the early returns below — resets the in-flight state.
            // Otherwise the chat would permanently lock itself (disabled input,
            // dead Stop button) until the app is restarted.
            try {
                val installed = container.modelRepository.stateFor(model.id)
                val file = installed?.filePath?.let { File(it) }
                if (installed?.state != ModelLifecycleState.READY || file == null || !file.isFile) {
                    _state.value = _state.value.copy(
                        error = "This model is not ready yet. Download and verify it first."
                    )
                    return@launch
                }

                // Load the model if it is not the active one.
                if (container.inferenceRuntime.activeModelId != model.id) {
                    _state.value = _state.value.copy(
                        isLoadingModel = true,
                        error = null,
                        thinkingTrace = _state.value.thinkingTrace + "Loading model…"
                    )
                    val settings = container.settingsRepository.settings.first()
                    val gate = loadGate(model)
                    if (gate.eligibility == LoadEligibility.BLOCK) {
                        _state.value = _state.value.copy(
                            isLoadingModel = false,
                            error = gate.message
                        )
                        return@launch
                    }
                    if (gate.eligibility == LoadEligibility.WARN) {
                        appendTrace("⚠ " + gate.message)
                    }
                    try {
                        coordinator.loadModel(
                            model,
                            file,
                            GenerationConfig(
                                temperature = settings.temperature,
                                topK = settings.topK,
                                topP = settings.topP,
                                maxTokens = settings.maxTokens
                            ),
                            threads = nativeThreads(settings)
                        )
                    } catch (e: OutOfMemoryError) {
                        _state.value = _state.value.copy(
                            isLoadingModel = false,
                            error = "❌ Insufficient memory to load this model safely. Try a lighter model."
                        )
                        return@launch
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            isLoadingModel = false,
                            error = "Couldn't start this model."
                        )
                        return@launch
                    }
                    _state.value = _state.value.copy(
                        isLoadingModel = false,
                        thinkingTrace = _state.value.thinkingTrace + "Model loaded"
                    )
                }

                val settings = container.settingsRepository.settings.first()
                val mode = _state.value.thinkingMode
                // Instant answers cheap, Hard thinking gives the model more room.
                val baseMaxTokens = when (mode) {
                    "INSTANT" -> 96
                    "HARD" -> 1024
                    else -> settings.maxTokens
                }
                // Clamp the generation budget to the model's native context
                // headroom (like the Playground already does). Without this, a
                // large maxTokens on a small-context model silently collapses the
                // prompt budget to its floor and wipes conversation history, and
                // generation can run into the native context ceiling mid-reply.
                val promptTokens = TokenEstimator.estimate(text) +
                    TokenEstimator.estimate(settings.systemPrompt)
                val headroom = ((model.contextLength * 0.8).toInt() - promptTokens).coerceAtLeast(64)
                val maxTokens = baseMaxTokens.coerceIn(1, headroom)
                val config = GenerationConfig(
                    temperature = settings.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    maxTokens = maxTokens
                )
                if (maxTokens != baseMaxTokens) {
                    appendTrace("⚠ Max output clamped to $maxTokens tokens for this model's context window.")
                }

                // Ensure the conversation exists BEFORE inference so the user
                // message renders immediately and survives any crash mid-run.
                var convId = _state.value.conversationId
                if (convId != null && container.conversationDao.byId(convId) == null) {
                    // The saved id was pruned/deleted — start a fresh chat.
                    convId = null
                    _state.value = _state.value.copy(
                        conversationId = null,
                        selectedConversationId = null
                    )
                }
                if (convId == null) {
                    convId = coordinator.createConversation(model.id, titleFromPrompt(text))
                    _state.value = _state.value.copy(
                        conversationId = convId,
                        selectedConversationId = convId
                    )
                }
                observeMessages(convId)

                _state.value = _state.value.copy(input = "")
                val startNanos = System.nanoTime()
                appendTrace("Generating… ($mode mode)")
                // Per-conversation system prompt override wins over the global
                // default when the conversation has one configured.
                val convSystemPrompt =
                    container.conversationDao.byId(convId)?.systemPrompt?.takeIf { it.isNotBlank() }
                        ?: settings.systemPrompt
                // Live "thinking" ticker: real-time feedback between tokens while
                // the model works (tokens themselves stream in via lastStreamedText).
                thinkingTicker = viewModelScope.launch {
                    var tick = 0
                    while (isActive) {
                        _state.value = _state.value.copy(liveThinkingSec = tick)
                        tick++
                        kotlinx.coroutines.delay(1000)
                    }
                }
                var lastTraceAt = 0L
                val result = coordinator.sendMessage(
                    prompt = text,
                    config = config,
                    systemPrompt = convSystemPrompt,
                    model = model,
                    persistUserMessage = persistUser,
                    historyTurns = settings.historyTurns,
                    onStream = { streamed ->
                        _state.value = _state.value.copy(lastStreamedText = streamed)
                        val now = System.currentTimeMillis()
                        if (now - lastTraceAt > 500) {
                            lastTraceAt = now
                            val perf = container.inferenceRuntime.performance.value
                            val tps = (perf.tokensPerSecond * 10).toInt() / 10f
                            appendTrace("▸ ${perf.tokensGenerated} tokens · ${tps} tok/s")
                        }
                    }
                )
                // Capture the real generation stats for the "How the AI thought"
                // panel (Hard mode) — honest measured data, not a simulation.
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                val perf = container.inferenceRuntime.performance.value
                val tps = (perf.tokensPerSecond * 10).toInt() / 10f
                _state.value = _state.value.copy(
                    lastThinking = ThinkingInfo(
                        mode = mode,
                        tokens = perf.tokensGenerated,
                        elapsedMs = elapsedMs,
                        tokensPerSecond = perf.tokensPerSecond,
                        responseChars = result.length
                    ),
                    // Keep the completed text until the DAO has emitted it as a
                    // message (the UI hides the streaming bubble via a content
                    // match), so there is no gap where the reply disappears.
                    lastStreamedText = result,
                    thinkingTrace = _state.value.thinkingTrace +
                        "Done · ${perf.tokensGenerated} tokens · ${tps} tok/s · ${elapsedMs / 1000f}s"
                )
            } catch (e: OutOfMemoryError) {
                // OOM is an Error, not an Exception — catch it explicitly or the
                // coroutine would propagate it to the uncaught handler and crash
                // the whole app.
                _state.value = _state.value.copy(
                    error = "Generation ran out of memory. Try a lighter model or a shorter message."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = "Generation failed. Please try again."
                )
            } finally {
                // Only the LATEST send clears the in-flight flag, so a stopped
                // (cancelled) older send can never wipe the state of a newer one.
                if (seq == sendSeq) {
                    sendInFlight = false
                    _state.value = _state.value.copy(
                        generating = false,
                        isLoadingModel = false,
                        liveThinkingSec = 0
                    )
                    // Guarantee the coordinator returns to IDLE so the next
                    // message can always start a fresh generation.
                    runCatching { coordinator.resetToIdle() }
                }
                thinkingTicker?.cancel()
            }
        }
    }

    fun stopGeneration() {
        // The coordinator flips to DONE and observes into the UI; the in-flight
        // coroutine clears `generating` in its finally once the native call
        // returns. We do NOT clear it here, or the UI state could be raced by a
        // subsequent send.
        coordinator.stopGeneration()
    }

    /**
     * Regenerates the last assistant reply: the trailing assistant message (and
     * any messages after the last user turn) is removed and the SAME user prompt
     * is re-sent, so a failed or unsatisfying reply can be retried in place.
     */
    fun regenerate() {
        val messages = _state.value.messages
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return
        if (sendInFlight || _state.value.generating) return
        viewModelScope.launch {
            // Remove every message created after the last user prompt so the
            // regenerated reply replaces the old one instead of stacking on it.
            val stale = messages.filter { it.createdAt > lastUser.createdAt }
            stale.forEach { container.messageDao.delete(it.id) }
            // Reuse the exact original prompt through the normal send path. The
            // user message already exists in Room, so it must not be inserted
            // again (persistUserMessage = false).
            _state.value = _state.value.copy(input = lastUser.content)
            sendInternal(persistUser = false)
        }
    }

    /** Cancels the previous conversation observer and observes the new one. */
    private fun observeMessages(convId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            container.messageDao.observeForConversation(convId).collect { msgs ->
                _state.value = _state.value.copy(messages = msgs)
            }
        }
    }

    /** Hard memory gate: refuses to load models that are MEASURED to exceed the
     *  device's safe AI memory budget. Estimates only produce a warning. */
    private suspend fun loadGate(model: CatalogModel): com.aichathub.app.device.LoadEligibilityResult {
        val profile = container.deviceInfoProvider.getDeviceProfile()
        val measured = container.settingsRepository.measuredMemoryOnce()
        return container.compatibilityEngine.loadEligibility(model, profile, measured)
    }

    /** Battery-conscious mode throttles the native thread count to save power;
     *  otherwise the count scales with the device's CPU core count. */
    private fun nativeThreads(settings: com.aichathub.app.data.SettingsRepository.Settings): Int =
        com.aichathub.app.util.ModelThreads.recommended(settings.batteryConscious)

    private fun titleFromPrompt(prompt: String): String {
        val clean = prompt.trim().replace("\n", " ")
        return if (clean.length > 42) clean.take(42) + "…" else clean
    }
}