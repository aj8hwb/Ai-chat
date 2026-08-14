package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.ui.applicationContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageUiState(
    val profile: DeviceProfile? = null,
    val models: List<CatalogModel> = emptyList(),
    val sizes: Map<String, Long> = emptyMap(),
    val modelsTotalBytes: Long = 0
)

class StorageViewModel : ViewModel() {

    private val container = applicationContainer()
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(profile = container.deviceInfoProvider.getDeviceProfile())
        }
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                val sizes = installed.associate { it.modelId to it.fileSizeBytes }
                _state.value = _state.value.copy(
                    models = installed.mapNotNull { LocalModelCatalog.byId(it.modelId) },
                    sizes = sizes,
                    modelsTotalBytes = sizes.values.sum()
                )
            }
        }
    }

    fun clearCache() {
        // Temporary .part files only — never touches installed models or chats.
        val downloadsDir = container.downloadManager.downloadsDir()
        downloadsDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part")) f.delete()
        }
    }
}