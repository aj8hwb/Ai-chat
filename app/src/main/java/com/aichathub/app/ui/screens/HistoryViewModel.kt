package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.ui.applicationContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val conversations: List<ConversationEntity> = emptyList()
)

class HistoryViewModel : ViewModel() {

    private val container = applicationContainer()
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.conversationDao.observeAll().collect { conversations ->
                _state.value = HistoryUiState(conversations = conversations)
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            container.chatCoordinator.deleteConversation(id)
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch {
            container.chatCoordinator.renameConversation(id, title)
        }
    }
}