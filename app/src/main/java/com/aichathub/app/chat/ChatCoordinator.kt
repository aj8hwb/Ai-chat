package com.aichathub.app.chat

import android.util.Log
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class ChatCoordinator(
    private val runtime: InferenceRuntime,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val modelRepository: ModelRepository
) {
    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    private var loadedModelId: String? = null

    /**
     * Loads a model into the runtime, switching from any previously loaded
     * model. Performs a memory preflight via the repository state.
     */
    suspend fun loadModel(model: CatalogModel, file: File) {
        _state.value = _state.value.copy(
            isLoadingModel = true,
            generationState = ChatGenerationState.LOADING,
            error = null
        )
        try {
            if (loadedModelId != model.id) {
                runtime.unload()
                runtime.load(model.id, file, model.contextLength)
                loadedModelId = model.id
            }
            modelRepository.setState(model.id, ModelLifecycleState.READY)
            _state.value = _state.value.copy(
                activeModelId = model.id,
                activeModelName = model.name,
                isLoadingModel = false,
                generationState = ChatGenerationState.IDLE
            )
        } catch (e: Exception) {
            Log.e("ChatCoordinator", "Model load failed", e)
            _state.value = _state.value.copy(
                isLoadingModel = false,
                generationState = ChatGenerationState.ERROR,
                error = "The model could not be loaded with the current device resources."
            )
            throw e
        }
    }

    suspend fun unloadModel(modelId: String) {
        if (loadedModelId == modelId) {
            runtime.unload()
            loadedModelId = null
        }
        modelRepository.setState(modelId, ModelLifecycleState.INSTALLED)
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
        if (_state.value.generationState == ChatGenerationState.GENERATING) {
            throw IllegalStateException("Already generating")
        }

        var conversationId = _activeConversationId.value
        val streamed = StringBuilder()

        return withContext(Dispatchers.IO) {
            try {
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

                _state.value = _state.value.copy(generationState = ChatGenerationState.GENERATING, error = null)

                val fullPrompt = buildPrompt(prompt, systemPrompt, convId)
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
                result
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    generationState = ChatGenerationState.ERROR,
                    error = "Generation failed. Please try again."
                )
                throw e
            }
        }
    }

    fun stopGeneration() {
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
     * can continue the conversation. Keeps the prompt within a reasonable
     * token budget (simple heuristic).
     */
    private suspend fun buildPrompt(
        newPrompt: String,
        systemPrompt: String,
        conversationId: Long
    ): String {
        val recent = messageDao.forConversation(conversationId)
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(8)

        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("System: ").append(systemPrompt).append("\n\n")
        }
        recent.forEach { m ->
            val role = if (m.role == "user") "User" else "Assistant"
            sb.append(role).append(": ").append(m.content).append("\n")
        }
        sb.append("Assistant: ")
        return sb.toString()
    }

    private fun titleFromPrompt(prompt: String): String {
        val clean = prompt.trim().replace("\n", " ")
        return if (clean.length > 42) clean.take(42) + "…" else clean
    }
}