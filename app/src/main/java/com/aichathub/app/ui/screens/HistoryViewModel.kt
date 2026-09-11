package com.aichathub.app.ui.screens

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.ChatBackupManager
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.chat.ChatCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class HistoryUiState(
    val conversations: List<com.aichathub.app.data.local.ConversationEntity> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val application: Application,
    private val container_chatCoordinator: ChatCoordinator,
    private val container_conversationDao: ConversationDao,
    private val container_messageDao: MessageDao,
    private val container_chatBackupManager: ChatBackupManager
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container_conversationDao.observeAll().collect { conversations ->
                _state.value = HistoryUiState(conversations = conversations)
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            container_chatCoordinator.deleteConversation(id)
        }
    }

    fun rename(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            container_chatCoordinator.renameConversation(id, title.trim())
        }
    }

    fun export(id: Long) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            _state.value = _state.value.copy(
                message = "Export needs Android 10 (API 29) or newer."
            )
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conv = container_conversationDao.byId(id) ?: return@runCatching "Conversation not found"
                    val msgs = container_messageDao.forConversation(id)
                    val safeTitle = conv.title.ifBlank { "New chat" }
                        .replace(Regex("[^A-Za-z0-9 _-]"), "")
                        .trim()
                        .take(40)
                    val fileName = "AiChatHub-${conv.id}-${safeTitle.ifBlank { "conversation" }}.md"

                    val sb = StringBuilder()
                    sb.append("# ").append(safeTitle).append("\n\n")
                    msgs.forEach { m ->
                        val role = if (m.role == "user") "User" else if (m.role == "assistant") "Assistant" else m.role
                        sb.append("## ").append(role).append(" . ")
                        sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(m.createdAt)))
                        sb.append("\n\n").append(m.content).append("\n\n")
                    }

                    val resolver = application.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/AiChatHub/Exports")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching "Export failed - could not create the file"
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(sb.toString().toByteArray())
                    } ?: run {
                        resolver.delete(uri, null, null)
                        return@runCatching "Export failed - could not write the file"
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "Exported to Download/AiChatHub/Exports/$fileName"
                }.getOrElse { e -> "Export failed: ${e.message}" }
            }
            _state.value = _state.value.copy(message = result)
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun backup(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                container_chatBackupManager.export(uri)
            }
            val message = when (result) {
                is ChatBackupManager.BackupResult.Success -> result.summary
                is ChatBackupManager.BackupResult.Failure -> result.message
            }
            _state.value = _state.value.copy(message = message, busy = false)
        }
    }

    fun restore(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                container_chatBackupManager.import(uri)
            }
            val message = when (result) {
                is ChatBackupManager.BackupResult.Success -> result.summary
                is ChatBackupManager.BackupResult.Failure -> result.message
            }
            _state.value = _state.value.copy(message = message, busy = false)
        }
    }

    fun suggestedBackupFileName(): String =
        container_chatBackupManager.suggestedFileName()
}
