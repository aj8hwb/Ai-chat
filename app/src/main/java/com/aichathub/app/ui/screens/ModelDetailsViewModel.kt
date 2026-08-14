package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.download.DownloadStatus
import com.aichathub.app.ui.applicationContainer
import com.aichathub.app.util.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelDetailsUiState(
    val model: CatalogModel? = null,
    val lifecycle: ModelLifecycleState = ModelLifecycleState.NOT_INSTALLED,
    val compatibility: CompatibilityLevel? = null,
    val recommendation: Recommendation? = null,
    val download: DownloadInfo? = null,
    val warningMessage: String? = null,
    val insufficientMemory: Boolean = false,
    val filePath: String? = null
)

class ModelDetailsViewModel : ViewModel() {

    private val container = applicationContainer()
    private val _state = MutableStateFlow(ModelDetailsUiState())
    val state: StateFlow<ModelDetailsUiState> = _state.asStateFlow()

    private var modelId: String? = null

    fun load(modelId: String) {
        this.modelId = modelId
        val model = LocalModelCatalog.byId(modelId)
        _state.value = _state.value.copy(model = model)
        viewModelScope.launch {
            val installed = container.modelRepository.stateFor(modelId)
            _state.value = _state.value.copy(
                lifecycle = installed?.state ?: ModelLifecycleState.NOT_INSTALLED,
                filePath = installed?.filePath
            )
            analyzeCompatibility(model)
            observeDownload(model)
        }
    }

    private suspend fun analyzeCompatibility(model: CatalogModel?) {
        if (model == null) return
        val profile = container.deviceInfoProvider.getDeviceProfile()
        val budget = MemoryBudgetCalculator.calculate(profile)
        val rec = container.compatibilityEngine.recommendAll(
            listOf(model),
            profile,
            budget
        ).firstOrNull()
        val heavy = (rec?.level == CompatibilityLevel.HEAVY || rec?.level == CompatibilityLevel.NOT_RECOMMENDED)
        _state.value = _state.value.copy(
            compatibility = rec?.level,
            recommendation = rec,
            insufficientMemory = rec?.level == CompatibilityLevel.NOT_RECOMMENDED,
            warningMessage = when (rec?.level) {
                CompatibilityLevel.HEAVY ->
                    "This model may perform slowly and use significant memory on your device."
                CompatibilityLevel.NOT_RECOMMENDED ->
                    "Your device currently doesn't have enough safe memory for this model."
                else -> null
            }
        )
    }

    private fun observeDownload(model: CatalogModel) {
        viewModelScope.launch {
            container.downloadManager.downloads.collect { downloads ->
                val d = downloads.firstOrNull { it.modelId == model.id }
                _state.value = _state.value.copy(download = d)
                if (d?.status == DownloadStatus.COMPLETED) {
                    // Move download to installed
                    installCompleted(model, d)
                }
            }
        }
    }

    fun startDownload() {
        val model = _state.value.model ?: return
        viewModelScope.launch {
            // Storage preflight
            val profile = container.deviceInfoProvider.getDeviceProfile()
            if (profile.storageAvailableBytes < model.fileSizeBytes) {
                _state.value = _state.value.copy(
                    warningMessage = "You need ${Formatters.bytes(model.fileSizeBytes)} free space to download this model."
                )
                return@launch
            }
            container.downloadManager.startDownload(model)
            container.modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING)
            _state.value = _state.value.copy(
                lifecycle = ModelLifecycleState.DOWNLOADING,
                warningMessage = null
            )
        }
    }

    fun pauseDownload() {
        container.downloadManager.pause(_state.value.model?.id ?: return)
    }

    fun resumeDownload() {
        container.downloadManager.resume(_state.value.model?.id ?: return)
    }

    fun cancelDownload() {
        container.downloadManager.cancel(_state.value.model?.id ?: return)
    }

    fun deleteModel() {
        val model = _state.value.model ?: return
        viewModelScope.launch {
            container.downloadManager.clearForModel(model.id)
            container.modelRepository.remove(model.id)
            _state.value = _state.value.copy(
                lifecycle = ModelLifecycleState.NOT_INSTALLED,
                filePath = null,
                download = null
            )
        }
    }

    private suspend fun installCompleted(model: CatalogModel, d: DownloadInfo) {
        val file = container.modelRepository.modelFile(model)
        if (file.exists() && file.length() == d.totalBytes) {
            container.modelRepository.markInstalled(model.id, file, d.totalBytes)
            _state.value = _state.value.copy(
                lifecycle = ModelLifecycleState.INSTALLED,
                filePath = file.absolutePath
            )
        }
    }
}