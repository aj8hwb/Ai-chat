package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val conversations: List<ConversationEntity> = emptyList()
)

class HistoryViewModel(application: Application) : AiViewModel(application) {

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