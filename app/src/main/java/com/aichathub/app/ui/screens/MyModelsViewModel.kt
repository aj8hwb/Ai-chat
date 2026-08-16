package com.aichathub.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.ModelScanner
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MyModelsUiState(
    val models: List<CatalogModel> = emptyList(),
    val states: Map<String, ModelLifecycleState> = emptyMap(),
    val totalStorageBytes: Long = 0L,
    val scanning: Boolean = false,
    val discovered: List<ModelScanner.DiscoveredFile> = emptyList(),
    val scanMessage: String? = null
)

class MyModelsViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(MyModelsUiState())
    val state: StateFlow<MyModelsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                val models = installed.mapNotNull { LocalModelCatalog.byId(it.modelId) }
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
            container.chatCoordinator.unloadModel(modelId)
        }
    }

    fun delete(modelId: String) {
        viewModelScope.launch {
            container.downloadManager.clearForModel(modelId)
            container.modelRepository.remove(modelId)
        }
    }

    /** Scans device sources (private dir + shared Downloads + saved folder). */
    fun scan() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, scanMessage = null, discovered = emptyList())
        viewModelScope.launch {
            val results = mutableListOf<ModelScanner.DiscoveredFile>()
            runCatching {
                results += container.modelScanner.scanLocalDir()
                results += container.modelScanner.scanSharedDownloads()
                val folderUri = container.settingsRepository.settings.first().modelsFolderUri
                if (!folderUri.isNullOrBlank()) {
                    results += container.modelScanner.scanTree(Uri.parse(folderUri))
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    scanning = false,
                    scanMessage = "Scan failed: ${it.message}"
                )
                return@launch
            }
            // De-duplicate by (fileName, size) so the same file found in several
            // sources shows once (shared Downloads copy takes priority).
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
                container.settingsRepository.setModelsFolderUri(uri.toString())
                val folderResults = container.modelScanner.scanTree(uri)
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
        viewModelScope.launch {
            when (val result = container.modelScanner.import(file)) {
                is ModelScanner.ImportResult.Imported -> {
                    _state.value = _state.value.copy(
                        discovered = _state.value.discovered.filterNot { it.fileName == file.fileName },
                        scanMessage = "Imported ${result.modelId}"
                    )
                }
                is ModelScanner.ImportResult.AlreadyInstalled -> {
                    _state.value = _state.value.copy(
                        discovered = _state.value.discovered.filterNot { it.fileName == file.fileName },
                        scanMessage = "${result.modelId} is already installed"
                    )
                }
                is ModelScanner.ImportResult.NoMatch -> {
                    _state.value = _state.value.copy(
                        scanMessage = "${file.fileName} is not a supported model"
                    )
                }
                is ModelScanner.ImportResult.Failed -> {
                    _state.value = _state.value.copy(
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