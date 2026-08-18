package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.download.DownloadStartResult
import com.aichathub.app.download.DownloadStatus
import com.aichathub.app.ui.AiViewModel
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
    val filePath: String? = null,
    val isDefaultModel: Boolean = false,
    /** True when the current default model is NOT installed (edge case). */
    val defaultModelMissing: Boolean = false
)

class ModelDetailsViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(ModelDetailsUiState())
    val state: StateFlow<ModelDetailsUiState> = _state.asStateFlow()

    private var modelId: String? = null

    fun load(modelId: String) {
        this.modelId = modelId
        val model = LocalModelCatalog.byId(modelId) ?: return
        _state.value = _state.value.copy(model = model)
        viewModelScope.launch {
            val installed = container.modelRepository.stateFor(modelId)
            _state.value = _state.value.copy(
                lifecycle = installed?.state ?: ModelLifecycleState.NOT_INSTALLED,
                filePath = installed?.filePath
            )
            analyzeCompatibility(model)
            observeDownload(model)
            observeDefaultModel()
        }
    }

    /** Keep the "Set as default" toggle in sync with the stored preference. */
    private fun observeDefaultModel() {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { s ->
                val current = _state.value
                _state.value = current.copy(
                    isDefaultModel = s.defaultModelId == current.model?.id,
                    defaultModelMissing = s.defaultModelId != null &&
                        LocalModelCatalog.byId(s.defaultModelId!!) == null
                )
            }
        }
    }

    fun setAsDefault() {
        val model = _state.value.model ?: return
        viewModelScope.launch {
            container.settingsRepository.setDefaultModel(model.id)
        }
    }

    fun clearDefault() {
        viewModelScope.launch {
            container.settingsRepository.setDefaultModel(null)
        }
    }

    private suspend fun analyzeCompatibility(model: CatalogModel?) {
        if (model == null) return
        val profile = container.deviceInfoProvider.getDeviceProfile()
        val budget = MemoryBudgetCalculator.calculate(profile)
        val measured = container.settingsRepository.measuredMemoryOnce()
        val rec = container.compatibilityEngine.recommendAll(
            listOf(model),
            profile,
            budget,
            measured
        ).firstOrNull()
        val heavy = (rec?.level == CompatibilityLevel.HEAVY || rec?.level == CompatibilityLevel.NOT_RECOMMENDED)
        _state.value = _state.value.copy(
            compatibility = rec?.level,
            recommendation = rec,
            insufficientMemory = rec?.level == CompatibilityLevel.NOT_RECOMMENDED,
            warningMessage = when (rec?.level) {
                CompatibilityLevel.HEAVY ->
                    "This model uses more memory than is safely available right now. It will still load and run, but it may be slow or unstable."
                CompatibilityLevel.NOT_RECOMMENDED ->
                    "Your device cannot safely load this model — it needs more memory than is available. You can still download the file, but it cannot be started here. Try a lighter model."
                else -> null
            }
        )
    }

    private fun observeDownload(model: CatalogModel) {
        viewModelScope.launch {
            container.downloadManager.downloads.collect { downloads ->
                val d = downloads.firstOrNull { it.modelId == model.id }
                _state.value = _state.value.copy(download = d)
                when (d?.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
                        _state.value = _state.value.copy(lifecycle = ModelLifecycleState.DOWNLOADING)
                    DownloadStatus.PAUSED ->
                        _state.value = _state.value.copy(lifecycle = ModelLifecycleState.DOWNLOADING)
                    DownloadStatus.VERIFYING ->
                        _state.value = _state.value.copy(lifecycle = ModelLifecycleState.VERIFYING)
                    DownloadStatus.COMPLETED -> installCompleted(model, d)
                    DownloadStatus.FAILED ->
                        _state.value = _state.value.copy(
                            lifecycle = ModelLifecycleState.NOT_INSTALLED,
                            warningMessage = d.error
                        )
                    DownloadStatus.CANCELLED ->
                        _state.value = _state.value.copy(lifecycle = ModelLifecycleState.NOT_INSTALLED)
                    else -> {}
                }
            }
        }
    }

    fun startDownload() {
        val model = _state.value.model ?: return
        viewModelScope.launch {
            when (val result = container.downloadManager.startDownload(model)) {
                is DownloadStartResult.Started -> {
                    container.modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING)
                    _state.value = _state.value.copy(
                        lifecycle = ModelLifecycleState.DOWNLOADING,
                        warningMessage = null,
                        download = null
                    )
                }
                is DownloadStartResult.AlreadyActive -> Unit
                is DownloadStartResult.NoStorage -> {
                    _state.value = _state.value.copy(
                        warningMessage = "Not enough free space. You need ${Formatters.bytes(result.requiredBytes)} free (${Formatters.bytes(result.availableBytes)} available)."
                    )
                }
                is DownloadStartResult.Failed -> {
                    _state.value = _state.value.copy(warningMessage = result.message)
                }
            }
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
            // Unload FIRST: llama.cpp maps the model file into native memory;
            // deleting it while loaded can crash the process (native SIGSEGV).
            container.chatCoordinator.unloadModel(model.id)
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
                filePath = file.absolutePath,
                warningMessage = null
            )
        }
    }
}
