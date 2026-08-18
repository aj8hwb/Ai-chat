package com.aichathub.app.chat

import android.util.Log
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.device.LoadDecision
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ChatTemplate
import com.aichathub.app.domain.model.ModelLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Thrown when the memory preflight refuses a model load (native crash risk). */
class ModelLoadRefusedException(message: String) : Exception(message)

/**
 * Coordinates the chat experience: model load/unload lifecycle, prompt
 * formatting, streaming generation and conversation persistence.
 *
 * UI talks only to this class. The concrete runtime is hidden behind
 * [InferenceRuntime].
 */
class ChatCoordinator(
    private val runtime: InferenceRuntime,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val modelRepository: ModelRepository,
    /** Memory preflight decision for a model, supplied by the container so the
     *  gate is enforced identically on EVERY screen that loads a model. */
    private val preflight: (suspend (CatalogModel) -> LoadDecision)? = null
) {
    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    /** Serializes model load/unload so switching models never interleaves. */
    private val loadMutex = Mutex()

    /**
     * Loads a model into the runtime, switching from any previously loaded
     * model. Performs a memory preflight via the repository state. Loading is
     * serialized so a quick model A → model B switch cannot corrupt the
     * single-slot native runtime.
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
        // Centralized memory preflight: the SAME decision the compatibility
        // badge shows, enforced on every caller (Chat, Playground, Benchmark,
        // Compare). A model the UI calls HEAVY/NOT_RECOMMENDED is handled
        // exactly as promised on screen — never refused after a 2 GB download.
        preflight?.let { decide ->
            when (decide(model)) {
                LoadDecision.SAFE -> Unit
                LoadDecision.HEAVY -> Unit // allowed (badge warns it may be slow)
                LoadDecision.BLOCKED -> {
                    val msg = "❌ This model needs more memory than your device can safely provide. " +
                        "It cannot be loaded. Try a lighter model."
                    _state.value = _state.value.copy(
                        isLoadingModel = false,
                        generationState = ChatGenerationState.ERROR,
                        error = msg
                    )
                    throw ModelLoadRefusedException(msg)
                }
            }
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
     */
    suspend fun sendMessage(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String,
        model: CatalogModel,
        onStream: (String) -> Unit
    ): String {
        // Set the in-flight flag SYNCHRONOUSLY (before any suspension point).
        // This closes the check-then-set race: a second caller cannot observe
        // a non-GENERATING state while the first call is being set up.
        if (_state.value.generationState == ChatGenerationState.GENERATING) {
            throw IllegalStateException("Already generating")
        }
        _state.value = _state.value.copy(generationState = ChatGenerationState.GENERATING, error = null)

        var conversationId = _activeConversationId.value
        val streamed = StringBuilder()

        return try {
            withContext(Dispatchers.IO) {
                // A previously selected conversation may have been pruned (it
                // never received a message) or deleted — fall back to a fresh one.
                if (conversationId != null && conversationDao.byId(conversationId) == null) {
                    conversationId = null
                }
                if (conversationId == null) {
                    conversationId = createConversation(model.id, titleFromPrompt(prompt))
                }
                val convId = conversationId!!

                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "user",
                        content = prompt,
                        createdAt = System.currentTimeMillis(),
                        modelId = model.id
                    )
                )

                Log.i("ChatCoordinator", "INFERENCE_STARTED ${model.id}")

                val fullPrompt = buildPrompt(model, convId)
                val result = runtime.generateStreaming(
                    prompt = fullPrompt,
                    config = config,
                    systemPrompt = systemPrompt,
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
            // User pressed Stop: the in-flight response is discarded cleanly.
            _state.value = _state.value.copy(
                generationState = ChatGenerationState.DONE,
                error = null
            )
            Log.i("ChatCoordinator", "INFERENCE_STOPPED ${model.id}")
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
        _state.value = _state.value.copy(generationState = ChatGenerationState.DONE)
    }

    suspend fun resetToIdle() {
        _state.value = _state.value.copy(
            generationState = ChatGenerationState.IDLE,
            error = null
        )
    }

    /**
     * Builds a context-aware prompt from the recent messages so the model
     * can continue the conversation. Turns are wrapped in the model's OWN
     * chat template (ChatML / Gemma / Llama3 / Llama2) instead of a flat
     * "User:/Assistant:" transcript — instruct models answer measurably
     * better inside the format they were fine-tuned with.
     *
     * The prompt is kept within a strict size budget: an oversized prompt
     * overflows llama.cpp's native context and crashes the whole process,
     * so older turns are dropped first.
     *
     * The system prompt is intentionally NOT embedded here — the engine
     * receives it through its own [systemPrompt] channel, so duplicating it
     * here would make the model see it twice.
     */
    private suspend fun buildPrompt(
        model: CatalogModel,
        conversationId: Long
    ): String {
        val recent = messageDao.forConversation(conversationId)
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(8)

        // Render all turns (up to the budget) with the model's chat template,
        // then drop the OLDEST turns first until the whole prompt fits — the
        // newest message (the one the user just sent) always stays intact.
        var full = renderPrompt(model.chatTemplate, recent)
        if (full.length <= MAX_FULL_PROMPT_CHARS) return full

        val trimmed = renderPrompt(model.chatTemplate, recent.takeLast(MAX_HISTORY_TURNS))
        var cut = trimmed
        while (cut.length > MAX_FULL_PROMPT_CHARS) {
            // Drop the oldest rendered turn (first template block) until it fits.
            val idx = nextTurnBoundary(model.chatTemplate, cut)
            if (idx < 0 || idx > cut.length / 2) break
            cut = cut.substring(idx)
        }
        return cut.take(MAX_FULL_PROMPT_CHARS)
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

    /** Finds where the oldest turn in a template-wrapped prompt ends, so the
     *  prompt can be trimmed turn-by-turn (newest message always kept). */
    private fun nextTurnBoundary(template: ChatTemplate, prompt: String): Int {
        val marker = when (template) {
            ChatTemplate.CHATML -> "<|im_start|>"
            ChatTemplate.GEMMA -> "<start_of_turn>"
            ChatTemplate.LLAMA3 -> "<|start_header_id|>"
            ChatTemplate.LLAMA2 -> "[INST] "
            ChatTemplate.GENERIC -> "\n"
        }
        var from = marker.length
        if (template == ChatTemplate.GENERIC) {
            val nl = prompt.indexOf('\n')
            return if (nl < 0) -1 else nl + 1
        }
        val next = prompt.indexOf(marker, from)
        return if (next < 0) -1 else next
    }

    private companion object {
        const val MAX_HISTORY_TURNS = 4
        const val MAX_FULL_PROMPT_CHARS = 2200
    }

    private fun titleFromPrompt(prompt: String): String {
        val clean = prompt.trim().replace("\n", " ")
        return if (clean.length > 42) clean.take(42) + "…" else clean
    }
}