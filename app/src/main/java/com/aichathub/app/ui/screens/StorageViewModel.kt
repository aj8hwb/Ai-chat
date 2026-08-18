package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.download.DownloadSegmentPolicy
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageUiState(
    val profile: DeviceProfile? = null,
    val models: List<CatalogModel> = emptyList(),
    val sizes: Map<String, Long> = emptyMap(),
    val modelsTotalBytes: Long = 0,
    val orphanFiles: List<String> = emptyList()
)

class StorageViewModel(application: Application) : AiViewModel(application) {

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
        refreshOrphans()
    }

    fun clearCache() {
        // Temporary download files only — never touches installed models or chats.
        // Covers single-stream `.part`, segmented `.part.N` AND the `.part.meta`
        // segment marker (the old clear missed the last two).
        val downloadsDir = container.downloadManager.downloadsDir()
        downloadsDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(DownloadSegmentPolicy.MERGED_PART_SUFFIX) ||
                f.name.contains(".part.")
            ) {
                f.delete()
            }
        }
        refreshOrphans()
    }

    /**
     * Removes files in the downloads directory that belong to NO catalog model.
     * Stray files (e.g. a manual .gguf copy) never get auto-deleted on startup;
     * this gives the user an explicit one-tap cleanup.
     */
    fun deleteUnknownFiles() {
        val downloadsDir = container.downloadManager.downloadsDir()
        val known = knownArtifactNames()
        downloadsDir.listFiles()?.forEach { f ->
            if (f.name !in known) f.delete()
        }
        refreshOrphans()
    }

    /** Files currently present in the downloads dir that match no catalog artifact. */
    private fun refreshOrphans() {
        viewModelScope.launch {
            val downloadsDir = container.downloadManager.downloadsDir()
            val known = knownArtifactNames()
            val orphans = downloadsDir.listFiles()
                ?.filter { it.isFile && it.name !in known }
                ?.map { it.name }
                ?.sorted() ?: emptyList()
            _state.value = _state.value.copy(orphanFiles = orphans)
        }
    }

    private fun knownArtifactNames(): Set<String> = buildSet {
        LocalModelCatalog.models.forEach { model ->
            add(DownloadSegmentPolicy.mergedPartFileName(model.fileName))
            add(DownloadSegmentPolicy.metaFileName(model.fileName))
            for (i in 0 until 64) {
                add(DownloadSegmentPolicy.partFileName(model.fileName, i))
            }
        }
    }
}
