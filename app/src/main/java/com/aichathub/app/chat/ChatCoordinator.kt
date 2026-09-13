package com.aichathub.app.chat

import android.util.Log
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ChatTemplate
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.privacy.PrivacyCenter
import com.aichathub.app.util.TokenEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class ChatGenerationState {
    IDLE, LOADING, GENERATING, STOPPING, DONE, ERROR
}

data class ChatSessionState(
    val activeModelId: String? = null,
    val activeModelName: String? = null,
    val generationState: ChatGenerationState = ChatGenerationState.IDLE,
    val error: String? = null,
    val isLoadingModel: Boolean = false
)

/**
 * Coordinates the chat experience: model load/unload lifecycle, prompt
 * formatting, streaming generation and conversation persistence.
 *
 * UI talks only to this class. The concrete runtime is hidden behind
 * [InferenceRuntime].
 *
 * @param appScope Application-scoped coroutine scope. ChatCoordinator does NOT
 *   own this scope — it is injected so that process death is safe by design.
 *   When the process is killed by the OS, all coroutines are cancelled
 *   automatically; model files on disk remain valid and can be reloaded on
 *   next launch.
 */
class ChatCoordinator(
    private val runtime: InferenceRuntime,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val modelRepository: ModelRepository,
    private val appScope: CoroutineScope
) {
    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    /** Serializes model load/unload so switching models never interleaves. */
    private val loadMutex = Mutex()

    // Lifecycle management — uses injected appScope, NOT a private scope.
    // The coordinator never owns the scope, so there is nothing to "destroy".
    private var autoUnloadJob: Job? = null
    private val autoUnloadDelayMs = 30_000L // 30 seconds
    @Volatile private var isInBackground = false

    /**
     * Loads a model into the runtime, switching from any previously loaded
     * model. Loading is serialized so a quick model A → model B switch cannot
     * corrupt the single-slot native runtime.
     */
    suspend fun loadModel(
        model: CatalogModel,
        file: File,
        config: GenerationConfig? = null,
        threads: Int = 4
    ) {
        _state.value = _state.value.copy(
            isLoadingModel = true,
            generationState = ChatGenerationState.LOADING,
            error = null
        )
        if (!file.exists() || file.length() == 0L) {
            _state.value = _state.value.copy(
                isLoadingModel = false,
                generationState = ChatGenerationState.ERROR,
                error = "The model file is missing or incomplete. Please re-download it."
            )
            throw IllegalStateException("Model file missing or empty: ${file.absolutePath}")
        }
        try {
            loadMutex.withLock {
                // The runtime's activeModelId is authoritative: it is cleared by
                // background trim-unloads, so a stale "loaded" cache can never
                // cause us to skip a required reload.
                if (runtime.activeModelId != model.id) {
                    runtime.unload()
                    runtime.load(model.id, file, model.contextLength, config, threads)
                    Log.i("ChatCoordinator", "MODEL_LOAD_SUCCESS ${model.id}")
                }
            }
            modelRepository.setState(model.id, ModelLifecycleState.READY)
            _state.value = _state.value.copy(
                activeModelId = model.id,
                activeModelName = model.name,
                isLoadingModel = false,
                generationState = ChatGenerationState.IDLE
            )
        } catch (e: OutOfMemoryError) {
            Log.e("ChatCoordinator", "MODEL_LOAD_FAILED OOM ${model.id}", e)
            runCatching { runtime.unload() }
            _state.value = _state.value.copy(
                isLoadingModel = false,
                generationState = ChatGenerationState.ERROR,
                error = "❌ Insufficient memory to load this model safely. Try a lighter model."
            )
            throw e
        } catch (e: Exception) {
            Log.e("ChatCoordinator", "MODEL_LOAD_FAILED ${model.id}", e)
            runCatching { runtime.unload() }
            _state.value = _state.value.copy(
                isLoadingModel = false,
                generationState = ChatGenerationState.ERROR,
                error = "The model could not be loaded with the current device resources."
            )
            throw e
        }
    }

    suspend fun unloadModel(modelId: String) {
        loadMutex.withLock {
            if (runtime.activeModelId == modelId) {
                runtime.unload()
            }
        }
        modelRepository.setState(modelId, ModelLifecycleState.READY)
        _state.value = _state.value.copy(
            activeModelId = null,
            activeModelName = null,
            generationState = ChatGenerationState.IDLE
        )
    }

    suspend fun createConversation(modelId: String, title: String = "New chat"): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = conversationDao.insert(
                ConversationEntity(
                    title = title,
                    modelId = modelId,
                    createdAt = now,
                    updatedAt = now
                )
            )
            _activeConversationId.value = id
            id
        }

    suspend fun selectConversation(id: Long) {
        _activeConversationId.value = id
    }

    fun newConversation() {
        _activeConversationId.value = null
    }

    suspend fun deleteConversation(id: Long) {
        messageDao.deleteForConversation(id)
        conversationDao.delete(id)
        if (_activeConversationId.value == id) _activeConversationId.value = null
    }

    suspend fun renameConversation(id: Long, title: String) {
        conversationDao.rename(id, title)
    }

    /**
     * Sends a user message and streams the assistant response.
     * The response is saved to the active conversation when complete.
     *
     * @param persistUserMessage when false, the user message is NOT inserted
     *   again (used by Regenerate, where the prompt already exists in Room).
     */
    suspend fun sendMessage(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String,
        model: CatalogModel,
        onStream: (String) -> Unit,
        persistUserMessage: Boolean = true,
        historyTurns: Int = DEFAULT_HISTORY_TURNS
    ): String {
        // Set the in-flight flag SYNCHRONOUSLY (before any suspension point).
        if (_state.value.generationState == ChatGenerationState.GENERATING) {
            throw IllegalStateException("Already generating")
        }
        _state.value = _state.value.copy(generationState = ChatGenerationState.GENERATING, error = null)
        runtime.clearCancellation()

        val incognito = PrivacyCenter.isIncognitoMode()
        var conversationId = _activeConversationId.value
        val streamed = StringBuilder()

        return try {
            withContext(Dispatchers.IO) {
                // Incognito mode: skip all Room persistence, use in-memory only
                if (incognito) {
                    // Generate a temporary conversation ID for UI state only
                    if (conversationId == null) {
                        conversationId = -System.currentTimeMillis() // Negative = ephemeral
                        _activeConversationId.value = conversationId
                    }
                    val convId = conversationId!!

                    Log.i("ChatCoordinator", "INFERENCE_STARTED (incognito) ${model.id}")

                    // Build prompt from in-memory context (no Room history in incognito)
                    val systemBlock = renderSystemBlock(model.chatTemplate, systemPrompt)
                    val fullPrompt = systemBlock + renderPrompt(model.chatTemplate, listOf(
                        MessageEntity(
                            conversationId = convId,
                            role = "user",
                            content = prompt,
                            createdAt = System.currentTimeMillis(),
                            modelId = model.id
                        )
                    ))

                    val effectiveConfig = config.copy(
                        stopSequences = templateStopSequences(model.chatTemplate)
                    )
                    val result = runtime.generateStreaming(
                        prompt = fullPrompt,
                        config = effectiveConfig,
                        onToken = { token ->
                            streamed.append(token)
                            onStream(streamed.toString())
                        }
                    )

                    _state.value = _state.value.copy(generationState = ChatGenerationState.DONE)
                    Log.i("ChatCoordinator", "INFERENCE_COMPLETED (incognito) ${model.id} tokens=${result.length}")
                    return@withContext result
                }

                // Normal (non-incognito) path: persist to Room
                if (conversationId != null && conversationDao.byId(conversationId) == null) {
                    conversationId = null
                }
                if (conversationId == null) {
                    conversationId = createConversation(model.id, com.aichathub.app.util.TextUtils.titleFromPrompt(prompt))
                }
                val convId = conversationId!!

                if (persistUserMessage) {
                    messageDao.insert(
                        MessageEntity(
                            conversationId = convId,
                            role = "user",
                            content = prompt,
                            createdAt = System.currentTimeMillis(),
                            modelId = model.id
                        )
                    )
                }

                Log.i("ChatCoordinator", "INFERENCE_STARTED ${model.id}")

                val fullPrompt = buildPrompt(model, convId, systemPrompt, config.maxTokens, historyTurns)
                // Apply the model's own template stop sequences so generation
                // ends at a natural boundary instead of running to maxTokens.
                val effectiveConfig = config.copy(
                    stopSequences = templateStopSequences(model.chatTemplate)
                )
                val result = runtime.generateStreaming(
                    prompt = fullPrompt,
                    config = effectiveConfig,
                    onToken = { token ->
                        streamed.append(token)
                        onStream(streamed.toString())
                    }
                )

                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "assistant",
                        content = result,
                        createdAt = System.currentTimeMillis(),
                        modelId = model.id
                    )
                )
                conversationDao.touch(convId, System.currentTimeMillis())

                _state.value = _state.value.copy(generationState = ChatGenerationState.DONE)
                Log.i("ChatCoordinator", "INFERENCE_COMPLETED ${model.id} tokens=${result.length}")
                result
            }
        } catch (e: OutOfMemoryError) {
            Log.e("ChatCoordinator", "INFERENCE_FAILED OOM ${model.id}", e)
            // Native state may be corrupt after an OOM; drop the model so the
            // next attempt reloads from a clean state instead of crashing.
            runCatching { runtime.unload() }
            _state.value = _state.value.copy(
                generationState = ChatGenerationState.ERROR,
                error = "Generation ran out of memory. Try a lighter model or a shorter message."
            )
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User pressed Stop: keep whatever was already streamed so a long
            // interrupted reply is not silently lost, then unwind cleanly.
            // In incognito mode, never persist partial results.
            val partial = streamed.toString().trim()
            if (partial.isNotEmpty() && conversationId != null && !incognito) {
                runCatching {
                    messageDao.insert(
                        MessageEntity(
                            conversationId = conversationId!!,
                            role = "assistant",
                            content = partial + "\n\n_(stopped)_",
                            createdAt = System.currentTimeMillis(),
                            modelId = model.id
                        )
                    )
                    conversationDao.touch(conversationId!!, System.currentTimeMillis())
                }
            }
            // If state is STOPPING (user-initiated), keep it as STOPPING briefly
            // then let resetToIdle() handle transition to IDLE
            if (_state.value.generationState != ChatGenerationState.STOPPING) {
                _state.value = _state.value.copy(generationState = ChatGenerationState.DONE)
            }
            Log.i("ChatCoordinator", "INFERENCE_STOPPED ${model.id} partial=${partial.length}")
            throw e
        } catch (e: Exception) {
            Log.e("ChatCoordinator", "INFERENCE_FAILED ${model.id}", e)
            _state.value = _state.value.copy(
                generationState = ChatGenerationState.ERROR,
                error = "Generation failed. Please try again."
            )
            throw e
        } finally {
            // Safety net: no matter what happened, the coordinator must never
            // stay stuck in GENERATING — otherwise every later send would be
            // rejected with "Already generating" and the chat would go silent.
            if (_state.value.generationState == ChatGenerationState.GENERATING) {
                _state.value = _state.value.copy(generationState = ChatGenerationState.IDLE)
            }
        }
    }

    fun stopGeneration() {
        Log.i("ChatCoordinator", "INFERENCE_STOPPING requested")
        _state.value = _state.value.copy(generationState = ChatGenerationState.STOPPING)
        runtime.cancelGeneration()
        // Don't set DONE here - the sendMessage coroutine's CancellationException
        // handler will transition to DONE when the coroutine actually completes.
    }

    suspend fun resetToIdle() {
        _state.value = _state.value.copy(
            generationState = ChatGenerationState.IDLE,
            error = null
        )
    }

    // ------------------------------------------------------------------
    // Lifecycle management (single authoritative owner)
    // ------------------------------------------------------------------

    /**
     * Handles background/foreground transitions. When the app goes to background,
     * schedules auto-unload after [autoUnloadDelayMs] if no generation is active.
     * When the app returns to foreground, cancels the auto-unload timer.
     */
    fun handleBackgroundTransition(inBackground: Boolean) {
        isInBackground = inBackground
        Log.i(TAG, "handleBackgroundTransition: inBackground=$inBackground")
        appScope.launch {
            loadMutex.withLock {
                if (inBackground && _state.value.generationState != ChatGenerationState.GENERATING) {
                    scheduleAutoUnload()
                } else {
                    cancelAutoUnloadTimer()
                }
            }
        }
    }

    /**
     * Handles memory pressure from the system. On critical pressure, unloads
     * the model immediately if no generation is active.
     */
    fun handleMemoryPressure(level: Int) {
        appScope.launch {
            loadMutex.withLock {
                Log.i(TAG, "handleMemoryPressure: level=$level")
                when {
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                        if (_state.value.generationState != ChatGenerationState.GENERATING && runtime.isLoaded) {
                            Log.i(TAG, "Memory pressure critical - unloading model")
                            runtime.unload()
                            _state.value = _state.value.copy(
                                activeModelId = null,
                                activeModelName = null
                            )
                        }
                    }
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                        if (_state.value.generationState != ChatGenerationState.GENERATING) {
                            scheduleAutoUnload()
                        }
                    }
                }
            }
        }
    }

    private fun scheduleAutoUnload() {
        cancelAutoUnloadTimer()
        autoUnloadJob = appScope.launch {
            delay(autoUnloadDelayMs)
            loadMutex.withLock {
                if (_state.value.generationState != ChatGenerationState.GENERATING && runtime.isLoaded) {
                    Log.i(TAG, "Auto-unloading model after inactivity")
                    try {
                        runtime.unload()
                        _state.value = _state.value.copy(
                            activeModelId = null,
                            activeModelName = null
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-unload failed", e)
                    }
                }
            }
        }
    }

    private fun cancelAutoUnloadTimer() {
        autoUnloadJob?.cancel()
        autoUnloadJob = null
    }

    /**
     * Builds a context-aware prompt from the recent messages so the model
     * can continue the conversation. The system prompt is embedded as the
     * FIRST turn using the model's OWN chat template (ChatML / Gemma / Llama3
     * / Llama2) — instruct models answer measurably better inside the format
     * they were fine-tuned with.
     *
     * The prompt is kept within a TOKEN budget derived from the model's native
     * context (token-dense languages included). Oversizing a llama.cpp context
     * crashes the whole native process, so older turns are dropped first while
     * the newest message and the system prompt are always preserved.
     *
     * Context pipeline: System prompt + Summary (if available) + Recent messages
     */
    private suspend fun buildPrompt(
        model: CatalogModel,
        conversationId: Long,
        systemPrompt: String,
        maxOutputTokens: Int,
        historyTurns: Int = DEFAULT_HISTORY_TURNS
    ): String {
        // Use DAO-level LIMIT to avoid loading full conversation history
        val recent = messageDao.recentForConversation(conversationId, historyTurns.coerceIn(2, 20))
            .filter { it.role == "user" || it.role == "assistant" }
            .sortedBy { it.createdAt } // Restore chronological order after DESC query

        val systemBlock = renderSystemBlock(model.chatTemplate, systemPrompt)
        val turnsBudgetTokens =
            (TokenEstimator.promptBudgetTokens(model.contextLength, maxOutputTokens) -
                TokenEstimator.estimate(systemBlock)).coerceAtLeast(64)

        // Check for conversation summary
        val summary = conversationDao.getSummary(conversationId)

        var turns = recent.toMutableList()

        // If summary exists, inject it as a system message at the beginning
        if (!summary.isNullOrBlank()) {
            val summaryMessage = MessageEntity(
                conversationId = conversationId,
                role = "system",
                content = "[Previous conversation summary: $summary]",
                createdAt = System.currentTimeMillis(),
                modelId = model.id
            )
            turns = (listOf(summaryMessage) + turns).toMutableList()
        }

        // Drop the OLDEST turns until the whole prompt fits in the token budget.
        while (turns.size > 1 && TokenEstimator.estimate(renderPrompt(model.chatTemplate, turns)) > turnsBudgetTokens) {
            turns.removeAt(0)
        }
        var rendered = renderPrompt(model.chatTemplate, turns)
        // Absolute safety net: hard-cut the rendered turns at a conservative
        // char bound (for CJK 1 token ≈ 1 char, so this can never overflow).
        // The cut never splits a UTF-16 surrogate pair, so emoji survive intact.
        if (TokenEstimator.estimate(rendered) > turnsBudgetTokens) {
            rendered = com.aichathub.app.util.TextUtils.takeNoSplit(rendered, turnsBudgetTokens)
        }
        return systemBlock + rendered
    }

    /** Template end tokens used to stop generation at a natural boundary. */
    private fun templateStopSequences(template: ChatTemplate): List<String> = when (template) {
        ChatTemplate.CHATML -> listOf("<|im_end|>")
        ChatTemplate.GEMMA -> listOf("<end_of_turn>")
        ChatTemplate.LLAMA3 -> listOf("<|eot_id|>")
        ChatTemplate.LLAMA2 -> listOf("</s>", "[/INST]")
        ChatTemplate.GENERIC -> emptyList()
    }

    private fun renderSystemBlock(template: ChatTemplate, systemPrompt: String): String {
        val sys = systemPrompt.trim()
        if (sys.isEmpty()) return ""
        return when (template) {
            ChatTemplate.CHATML -> "<|im_start|>system\n$sys\n<|im_end|>\n"
            ChatTemplate.GEMMA -> "<start_of_turn>system\n$sys\n<end_of_turn>\n"
            ChatTemplate.LLAMA3 -> "<|start_header_id|>system<|end_header_id|>\n\n$sys<|eot_id|>"
            ChatTemplate.LLAMA2 -> "<<SYS>>\n$sys\n<</SYS>>\n\n"
            ChatTemplate.GENERIC -> "System: $sys\n\n"
        }
    }

    private fun renderPrompt(template: ChatTemplate, messages: List<MessageEntity>): String {
        val sb = StringBuilder()
        when (template) {
            ChatTemplate.CHATML -> {
                messages.forEach { m ->
                    val role = if (m.role == "user") "user" else "assistant"
                    sb.append("<|im_start|>$role\n").append(m.content).append("\n<|im_end|>\n")
                }
                sb.append("<|im_start|>assistant\n")
            }
            ChatTemplate.GEMMA -> {
                messages.forEach { m ->
                    val role = if (m.role == "user") "user" else "model"
                    sb.append("<start_of_turn>$role\n").append(m.content).append("\n<end_of_turn>\n")
                }
                sb.append("<start_of_turn>model\n")
            }
            ChatTemplate.LLAMA3 -> {
                messages.forEach { m ->
                    val role = if (m.role == "user") "user" else "assistant"
                    sb.append("<|start_header_id|>$role<|end_header_id|>\n\n")
                        .append(m.content).append("\n<|eot_id|>")
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            ChatTemplate.LLAMA2 -> {
                messages.forEach { m ->
                    val content = m.content
                    if (m.role == "user") {
                        sb.append("[INST] ").append(content).append(" [/INST] ")
                    } else {
                        sb.append(content).append(" </s><s>")
                    }
                }
                sb.append(" ")
            }
            ChatTemplate.GENERIC -> {
                messages.forEach { m ->
                    val role = if (m.role == "user") "User" else "Assistant"
                    sb.append(role).append(": ").append(m.content).append("\n")
                }
                sb.append("Assistant: ")
            }
        }
        return sb.toString()
    }

    private companion object {
        private const val TAG = "ChatCoordinator"
        const val DEFAULT_HISTORY_TURNS = 8
    }

}