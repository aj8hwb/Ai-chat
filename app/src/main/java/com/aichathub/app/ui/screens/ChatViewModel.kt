package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.applicationContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val lastStreamedText: String = ""
)

class ChatViewModel : ViewModel() {

    private val container = applicationContainer()
    private val coordinator: ChatCoordinator = container.chatCoordinator

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            val defaultModel = settings.defaultModelId?.let { LocalModelCatalog.byId(it) }
            if (defaultModel != null) {
                _state.value = _state.value.copy(
                    activeModelId = defaultModel.id,
                    activeModelName = defaultModel.name
                )
            }
        }
        observeCoordinator()
    }

    private fun observeCoordinator() {
        viewModelScope.launch {
            coordinator.state.collect { s ->
                _state.value = _state.value.copy(
                    activeModelId = s.activeModelId ?: _state.value.activeModelId,
                    activeModelName = s.activeModelName ?: _state.value.activeModelName,
                    generating = s.generationState == com.aichathub.app.chat.ChatGenerationState.GENERATING,
                    isLoadingModel = s.isLoadingModel,
                    error = s.error ?: _state.value.error
                )
            }
        }
        viewModelScope.launch {
            coordinator.activeConversationId.collect { id ->
                _state.value = _state.value.copy(selectedConversationId = id)
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
            container.messageDao.observeForConversation(id).collect { messages ->
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    fun newChat() {
        coordinator.newConversation()
        _state.value = _state.value.copy(
            conversationId = null,
            selectedConversationId = null,
            messages = emptyList(),
            error = null
        )
    }

    fun selectModel(model: CatalogModel) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingModel = true, error = null)
            val installed = container.modelRepository.stateFor(model.id)
            if (installed?.filePath == null) {
                _state.value = _state.value.copy(
                    isLoadingModel = false,
                    error = "This model is not installed yet."
                )
                return@launch
            }
            try {
                val file = java.io.File(installed.filePath)
                coordinator.loadModel(model, file)
                _state.value = _state.value.copy(
                    activeModelId = model.id,
                    activeModelName = model.name,
                    isLoadingModel = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoadingModel = false,
                    error = "The model could not be loaded with the current device resources."
                )
            }
        }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.generating) return
        val model = _state.value.activeModelId?.let { LocalModelCatalog.byId(it) }
        if (model == null) {
            _state.value = _state.value.copy(error = "Select a model to start chatting.")
            return
        }

        viewModelScope.launch {
            // Ensure model is loaded
            val installed = container.modelRepository.stateFor(model.id)
            if (installed?.filePath == null) {
                _state.value = _state.value.copy(error = "This model is not installed yet.")
                return@launch
            }
            if (!container.inferenceRuntime.isLoaded || container.inferenceRuntime.activeModelId != model.id) {
                _state.value = _state.value.copy(isLoadingModel = true)
                try {
                    coordinator.loadModel(model, java.io.File(installed.filePath))
                } catch (e: Exception) {
                    _state.value = _state.value.copy(isLoadingModel = false, error = "Couldn't start this model.")
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

            _state.value = _state.value.copy(input = "", generating = true, error = null)
            try {
                coordinator.sendMessage(
                    prompt = text,
                    config = config,
                    systemPrompt = settings.systemPrompt,
                    model = model,
                    onStream = { streamed ->
                        _state.value = _state.value.copy(lastStreamedText = streamed)
                    }
                )
                // Refresh messages
                val convId = coordinator.activeConversationId.value
                if (convId != null) loadConversation(convId)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    generating = false,
                    error = "Generation failed. Please try again."
                )
            } finally {
                _state.value = _state.value.copy(generating = false, lastStreamedText = "")
            }
        }
    }

    fun stopGeneration() {
        coordinator.stopGeneration()
        _state.value = _state.value.copy(generating = false)
    }
}