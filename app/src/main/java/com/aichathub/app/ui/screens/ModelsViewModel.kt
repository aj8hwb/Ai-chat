package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.download.DownloadManager
import com.aichathub.app.download.DownloadStartResult
import com.aichathub.app.download.DownloadStatus
import com.aichathub.app.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelsUiState(
    val models: List<CatalogModel> = emptyList(),
    val states: Map<String, ModelLifecycleState> = emptyMap(),
    val compatibility: Map<String, CompatibilityLevel> = emptyMap(),
    val downloads: Map<String, DownloadInfo> = emptyMap(),
    val query: String = "",
    val selectedCategory: String = "All",
    val profile: DeviceProfile? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val catalogSource: CatalogRepository.CatalogSource = CatalogRepository.CatalogSource.LOCAL
) {
    val filtered: List<CatalogModel>
        get() = models.filter { model ->
            val matchesQuery = query.isBlank() ||
                model.name.contains(query, ignoreCase = true) ||
                model.provider.contains(query, ignoreCase = true) ||
                model.category.contains(query, ignoreCase = true)
            val matchesCategory = selectedCategory == "All" ||
                model.category == selectedCategory ||
                (selectedCategory == "Recommended" &&
                    (compatibility[model.id]?.rank ?: 0) >= 4)
            matchesQuery && matchesCategory
        }
}

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val container_downloadManager: DownloadManager,
    private val container_modelRepository: ModelRepository,
    private val container_deviceInfoProvider: DeviceInfoProvider,
    private val container_compatibilityEngine: CompatibilityEngine,
    private val container_settingsRepository: SettingsRepository,
    private val container_catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    val notificationPermissionNeeded = container_downloadManager.notificationPermissionNeeded

    init {
        refresh()
        observeInstalled()
        observeDownloads()
        observeMeasuredMemory()
        observeCatalog()
    }

    private fun observeCatalog() {
        viewModelScope.launch {
            container_catalogRepository.allModels.collect { models ->
                _state.value = _state.value.copy(models = models)
                // Re-run compatibility check when catalog changes
                refresh()
            }
        }
        
        viewModelScope.launch {
            container_catalogRepository.catalogState.collect { state ->
                when (state) {
                    is CatalogRepository.CatalogState.Ready -> {
                        _state.value = _state.value.copy(
                            catalogSource = state.source,
                            loading = false
                        )
                    }
                    is CatalogRepository.CatalogState.Loading -> {
                        _state.value = _state.value.copy(loading = true)
                    }
                    is CatalogRepository.CatalogState.Error -> {
                        _state.value = _state.value.copy(
                            error = state.message,
                            loading = false
                        )
                    }
                }
            }
        }
    }

    private fun observeMeasuredMemory() {
        viewModelScope.launch {
            container_settingsRepository.measuredMemory
                .debounce(1500)
                .collect {
                    refresh()
                }
        }
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            container_modelRepository.installedModels.collect { installed ->
                _state.value = _state.value.copy(
                    states = installed.associate { it.modelId to it.state }
                )
            }
        }
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            container_downloadManager.downloads.collect { downloads ->
                _state.value = _state.value.copy(
                    downloads = downloads.associateBy { it.modelId }
                )
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val profile = container_deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            val measured = container_settingsRepository.measuredMemoryOnce()
            val models = container_catalogRepository.getAllModels()
            val recommendations = container_compatibilityEngine.recommendAll(
                models,
                profile,
                budget,
                measured
            )
            _state.value = _state.value.copy(
                models = models,
                compatibility = recommendations.associate { it.model.id to it.level },
                profile = profile,
                loading = false
            )
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun onCategoryChange(c: String) {
        _state.value = _state.value.copy(selectedCategory = c)
    }

    fun download(model: CatalogModel) {
        viewModelScope.launch {
            when (val result = container_downloadManager.startDownload(model)) {
                is DownloadStartResult.Started -> {
                    container_modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING)
                    _state.value = _state.value.copy(error = null)
                }
                is DownloadStartResult.AlreadyActive -> Unit
                is DownloadStartResult.NoStorage -> {
                    _state.value = _state.value.copy(
                        error = "Not enough free space. You need ${Formatters.bytes(result.requiredBytes)} free (${Formatters.bytes(result.availableBytes)} available)."
                    )
                }
                is DownloadStartResult.Failed -> {
                    _state.value = _state.value.copy(error = result.message)
                }
            }
        }
    }

    fun pause(modelId: String) = container_downloadManager.pause(modelId)
    fun resume(modelId: String) = container_downloadManager.resume(modelId)
    fun cancel(modelId: String) = container_downloadManager.cancel(modelId)

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            container_catalogRepository.refreshCatalog()
        }
    }

    val categories: List<String>
        get() = listOf("All", "Recommended") + 
            _state.value.models.map { it.category }.distinct()
}
