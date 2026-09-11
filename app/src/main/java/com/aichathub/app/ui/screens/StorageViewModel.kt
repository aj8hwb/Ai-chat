package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.download.DownloadManager
import com.aichathub.app.download.DownloadSegmentPolicy
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.DeviceProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class StorageUiState(
    val profile: DeviceProfile? = null,
    val models: List<CatalogModel> = emptyList(),
    val sizes: Map<String, Long> = emptyMap(),
    val modelsTotalBytes: Long = 0,
    val orphanFiles: List<String> = emptyList()
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val container_deviceInfoProvider: DeviceInfoProvider,
    private val container_modelRepository: ModelRepository,
    private val container_downloadManager: DownloadManager,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(profile = container_deviceInfoProvider.getDeviceProfile())
        }
        viewModelScope.launch {
            container_modelRepository.installedModels.collect { installed ->
                val sizes = installed.associate { it.modelId to it.fileSizeBytes }
                _state.value = _state.value.copy(
                    models = installed.mapNotNull { catalogRepository.getModelById(it.modelId) },
                    sizes = sizes,
                    modelsTotalBytes = sizes.values.sum()
                )
            }
        }
        refreshOrphans()
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val downloadsDir = container_downloadManager.downloadsDir()
                downloadsDir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(DownloadSegmentPolicy.MERGED_PART_SUFFIX) ||
                        f.name.contains(".part.")
                    ) {
                        f.delete()
                    }
                }
            }
            refreshOrphans()
        }
    }

    fun deleteUnknownFiles() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val downloadsDir = container_downloadManager.downloadsDir()
                val known = knownArtifactNames()
                downloadsDir.listFiles()?.forEach { f ->
                    if (f.name !in known) f.delete()
                }
            }
            refreshOrphans()
        }
    }

    private fun refreshOrphans() {
        viewModelScope.launch {
            val orphans = withContext(Dispatchers.IO) {
                val downloadsDir = container_downloadManager.downloadsDir()
                val known = knownArtifactNames()
                downloadsDir.listFiles()
                    ?.filter { it.isFile && it.name !in known }
                    ?.map { it.name }
                    ?.sorted() ?: emptyList()
            }
            _state.value = _state.value.copy(orphanFiles = orphans)
        }
    }

    private fun knownArtifactNames(): Set<String> = buildSet {
        catalogRepository.getAllModels().forEach { model ->
            add(DownloadSegmentPolicy.mergedPartFileName(model.fileName))
            add(DownloadSegmentPolicy.metaFileName(model.fileName))
            for (i in 0 until 64) {
                add(DownloadSegmentPolicy.partFileName(model.fileName, i))
            }
        }
    }
}
