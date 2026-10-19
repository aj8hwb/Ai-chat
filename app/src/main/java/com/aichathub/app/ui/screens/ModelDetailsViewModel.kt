package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.download.DownloadManager
import com.aichathub.app.download.DownloadStartResult
import com.aichathub.app.download.DownloadStatus
import com.aichathub.app.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ModelDetailsUiState(
    val model: CatalogModel? = null,
    val lifecycle: ModelLifecycleState = ModelLifecycleState.NOT_INSTALLED,
    val compatibility: CompatibilityLevel? = null,
    val recommendation: Recommendation? = null,
    val download: DownloadInfo? = null,
    val warningMessage: String? = null,
    val filePath: String? = null,
    val isDefaultModel: Boolean = false,
    val defaultModelMissing: Boolean = false,
    val loading: Boolean = false
)

@HiltViewModel
class ModelDetailsViewModel @Inject constructor(
    private val container_chatCoordinator: ChatCoordinator,
    private val container_modelRepository: ModelRepository,
    private val container_downloadManager: DownloadManager,
    private val container_deviceInfoProvider: DeviceInfoProvider,
    private val container_compatibilityEngine: CompatibilityEngine,
    private val container_settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ModelDetailsUiState())
    val state: StateFlow<ModelDetailsUiState> = _state.asStateFlow()

    val notificationPermissionNeeded = container_downloadManager.notificationPermissionNeeded

    private var modelId: String? = null

    fun load(modelId: String) {
        this.modelId = modelId
        val model = catalogRepository.getModelById(modelId)
        if (model == null) {
            _state.value = ModelDetailsUiState(model = null, loading = false)
            return
        }
        _state.value = ModelDetailsUiState(model = model, loading = true)
        viewModelScope.launch {
            val installed = container_modelRepository.stateFor(modelId)
            _state.value = _state.value.copy(
                lifecycle = installed?.state ?: ModelLifecycleState.NOT_INSTALLED,
                filePath = installed?.filePath
            )
            analyzeCompatibility(model)
            observeDownload(model)
            observeDefaultModel()
            _state.value = _state.value.copy(loading = false)
        }
    }

    private fun observeDefaultModel() {
        viewModelScope.launch {
            container_settingsRepository.settings.collect { s ->
                val current = _state.value
                _state.value = current.copy(
                    isDefaultModel = s.defaultModelId == current.model?.id,
                    defaultModelMissing = s.defaultModelId != null &&
                        catalogRepository.getModelById(s.defaultModelId!!) == null
                )
            }
        }
    }

    fun setAsDefault() {
        val model = _state.value.model ?: return
        viewModelScope.launch {
            container_settingsRepository.setDefaultModel(model.id)
        }
    }

    fun clearDefault() {
        viewModelScope.launch {
            container_settingsRepository.setDefaultModel(null)
        }
    }

    private suspend fun analyzeCompatibility(model: CatalogModel?) {
        if (model == null) return
        val profile = container_deviceInfoProvider.getDeviceProfile()
        val budget = MemoryBudgetCalculator.calculate(profile)
        val measured = container_settingsRepository.measuredMemoryOnce()
        val rec = container_compatibilityEngine.recommendAll(
            listOf(model),
            profile,
            budget,
            measured
        ).firstOrNull()
        _state.value = _state.value.copy(
            compatibility = rec?.level,
            recommendation = rec,
            warningMessage = when (rec?.level) {
                CompatibilityLevel.HEAVY ->
                    "This model uses more memory than is safely available right now. It will still load and run, but it may be slow or unstable."
                CompatibilityLevel.NOT_RECOMMENDED ->
                    "This model is heavy for your device's memory. It will still load and run, but it may be slow or unstable."
                else -> null
            }
        )
    }

    private fun observeDownload(model: CatalogModel) {
        viewModelScope.launch {
            container_downloadManager.downloads.collect { downloads ->
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
            when (val result = container_downloadManager.startDownload(model)) {
                is DownloadStartResult.Started -> {
                    container_modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING)
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
        container_downloadManager.pause(_state.value.model?.id ?: return)
    }

    fun resumeDownload() {
        container_downloadManager.resume(_state.value.model?.id ?: return)
    }

    fun cancelDownload() {
        container_downloadManager.cancel(_state.value.model?.id ?: return)
    }

    fun deleteModel() {
        val model = _state.value.model ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container_chatCoordinator.unloadModel(model.id)
                container_downloadManager.clearForModel(model.id)
                container_modelRepository.remove(model.id)
            }
            _state.value = _state.value.copy(
                lifecycle = ModelLifecycleState.NOT_INSTALLED,
                filePath = null,
                download = null
            )
        }
    }

    private suspend fun installCompleted(model: CatalogModel, d: DownloadInfo) {
        val file = container_modelRepository.modelFile(model)
        if (file.exists() && file.length() == d.totalBytes) {
            container_modelRepository.markInstalled(model.id, file, d.totalBytes)
            _state.value = _state.value.copy(
                lifecycle = ModelLifecycleState.INSTALLED,
                filePath = file.absolutePath,
                warningMessage = null
            )
        }
    }
}
