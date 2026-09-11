package com.aichathub.app.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.device.ModelScanner
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyModelsUiState(
    val models: List<CatalogModel> = emptyList(),
    val states: Map<String, ModelLifecycleState> = emptyMap(),
    val totalStorageBytes: Long = 0L,
    val scanning: Boolean = false,
    val discovered: List<ModelScanner.DiscoveredFile> = emptyList(),
    val scanMessage: String? = null
)

@HiltViewModel
class MyModelsViewModel @Inject constructor(
    private val container_chatCoordinator: ChatCoordinator,
    private val container_modelRepository: ModelRepository,
    private val container_downloadManager: DownloadManager,
    private val container_modelScanner: ModelScanner,
    private val container_settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MyModelsUiState())
    val state: StateFlow<MyModelsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container_modelRepository.installedModels.collect { installed ->
                val models = installed.mapNotNull { catalogRepository.getModelById(it.modelId) }
                _state.value = _state.value.copy(
                    models = models,
                    states = installed.associate { it.modelId to it.state },
                    totalStorageBytes = installed.sumOf { it.fileSizeBytes }
                )
            }
        }
    }

    fun unload(modelId: String) {
        viewModelScope.launch {
            container_chatCoordinator.unloadModel(modelId)
        }
    }

    fun delete(modelId: String) {
        viewModelScope.launch {
            container_chatCoordinator.unloadModel(modelId)
            container_downloadManager.clearForModel(modelId)
            container_modelRepository.remove(modelId)
        }
    }

    fun scan() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, scanMessage = null, discovered = emptyList())
        viewModelScope.launch {
            val results = mutableListOf<ModelScanner.DiscoveredFile>()
            runCatching {
                results += container_modelScanner.scanLocalDir()
                results += container_modelScanner.scanSharedDownloads()
                val folderUri = container_settingsRepository.settings.first().modelsFolderUri
                if (!folderUri.isNullOrBlank()) {
                    results += container_modelScanner.scanTree(Uri.parse(folderUri))
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    scanning = false,
                    scanMessage = "Scan failed: ${it.message}"
                )
                return@launch
            }
            val dedup = results
                .distinctBy { it.fileName to it.sizeBytes }
                .sortedBy { it.fileName }
            _state.value = _state.value.copy(
                scanning = false,
                discovered = dedup,
                scanMessage = if (dedup.isEmpty()) "No model files found on this device." else null
            )
        }
    }

    fun onFolderPicked(uri: Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                container_settingsRepository.setModelsFolderUri(uri.toString())
                val folderResults = container_modelScanner.scanTree(uri)
                _state.value = _state.value.copy(
                    discovered = folderResults.sortedBy { it.fileName },
                    scanMessage = if (folderResults.isEmpty()) "No known model files in that folder." else null
                )
            } else {
                _state.value = _state.value.copy(scanMessage = "Folder selection cancelled.")
            }
        }
    }

    fun import(file: ModelScanner.DiscoveredFile) {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, scanMessage = null)
        viewModelScope.launch {
            when (val result = container_modelScanner.import(file)) {
                is ModelScanner.ImportResult.Imported -> {
                    _state.value = _state.value.copy(
                        scanning = false,
                        discovered = _state.value.discovered.filterNot { it.fileName == file.fileName },
                        scanMessage = "Imported ${result.modelId}"
                    )
                }
                is ModelScanner.ImportResult.AlreadyInstalled -> {
                    _state.value = _state.value.copy(
                        scanning = false,
                        discovered = _state.value.discovered.filterNot { it.fileName == file.fileName },
                        scanMessage = "${result.modelId} is already installed"
                    )
                }
                is ModelScanner.ImportResult.NoMatch -> {
                    _state.value = _state.value.copy(
                        scanning = false,
                        scanMessage = "${file.fileName} is not a supported model"
                    )
                }
                is ModelScanner.ImportResult.Failed -> {
                    _state.value = _state.value.copy(
                        scanning = false,
                        scanMessage = result.message
                    )
                }
            }
        }
    }

    fun clearScanMessage() {
        _state.value = _state.value.copy(scanMessage = null)
    }
}
