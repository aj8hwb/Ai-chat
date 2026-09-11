package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsUiState(
    val downloads: List<DownloadInfo> = emptyList()
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val container_downloadManager: DownloadManager
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container_downloadManager.downloads.collect { downloads ->
                _state.value = DownloadsUiState(downloads = downloads)
            }
        }
    }

    fun pause(modelId: String) = container_downloadManager.pause(modelId)
    fun resume(modelId: String) = container_downloadManager.resume(modelId)
    fun cancel(modelId: String) = container_downloadManager.cancel(modelId)
}
