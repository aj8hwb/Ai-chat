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
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

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
    val isModelLoaded: Boolean = false,
    val generationPhase: String = ChatGenerationState.IDLE.name
)

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
            container.compatibilityEngine.recommendAll(ready, profile, budget)
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
                    error = s.error ?: _state.value.error,
                    isModelLoaded = container.inferenceRuntime.isLoaded && s.activeModelId != null,
                    generationPhase = s.generationState.name
                )
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
            if (!memorySuitable(model)) {
                _state.value = _state.value.copy(
                    error = "❌ Insufficient memory to load this model safely. Try a lighter model."
                )
                return@launch
            }
            _state.value = _state.value.copy(isLoadingModel = true, error = null)
            try {
                coordinator.loadModel(model, file)
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

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || sendInFlight || _state.value.generating) return
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
        _state.value = _state.value.copy(generating = true, error = null, lastStreamedText = "")

        viewModelScope.launch {
            try {
                // The selected model must be genuinely READY before we touch it.
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
                    if (!memorySuitable(model)) {
                        _state.value = _state.value.copy(
                            error = "❌ Insufficient memory to load this model safely. Try a lighter model."
                        )
                        return@launch
                    }
                    _state.value = _state.value.copy(isLoadingModel = true, error = null)
                    try {
                        coordinator.loadModel(model, file)
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
                    _state.value = _state.value.copy(isLoadingModel = false)
                }

                val settings = container.settingsRepository.settings.first()
                val config = GenerationConfig(
                    temperature = settings.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    maxTokens = settings.maxTokens
                )

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
                coordinator.sendMessage(
                    prompt = text,
                    config = config,
                    systemPrompt = settings.systemPrompt,
                    model = model,
                    onStream = { streamed ->
                        _state.value = _state.value.copy(lastStreamedText = streamed)
                    }
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
                        lastStreamedText = ""
                    )
                    // Guarantee the coordinator returns to IDLE so the next
                    // message can always start a fresh generation.
                    runCatching { coordinator.resetToIdle() }
                }
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

    /** Cancels the previous conversation observer and observes the new one. */
    private fun observeMessages(convId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            container.messageDao.observeForConversation(convId).collect { msgs ->
                _state.value = _state.value.copy(messages = msgs)
            }
        }
    }

    /** Memory preflight before loading: never attempt an unsafe model load. */
    private suspend fun memorySuitable(model: CatalogModel): Boolean {
        return runCatching {
            val profile = container.deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            model.estimatedMemoryBytes <= budget.modelMemoryBytes
        }.getOrDefault(true)
    }

    private fun titleFromPrompt(prompt: String): String {
        val clean = prompt.trim().replace("\n", " ")
        return if (clean.length > 42) clean.take(42) + "…" else clean
    }
}