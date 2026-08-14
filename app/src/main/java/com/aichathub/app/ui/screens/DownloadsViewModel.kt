package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val downloads: List<DownloadInfo> = emptyList()
)

class DownloadsViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.downloadManager.downloads.collect { downloads ->
                _state.value = DownloadsUiState(downloads = downloads)
            }
        }
    }

    fun pause(modelId: String) = container.downloadManager.pause(modelId)
    fun resume(modelId: String) = container.downloadManager.resume(modelId)
    fun cancel(modelId: String) = container.downloadManager.cancel(modelId)
}