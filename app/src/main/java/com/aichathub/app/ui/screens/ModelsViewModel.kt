package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.DeviceProfile
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class ModelsUiState(
    val models: List<CatalogModel> = LocalModelCatalog.models,
    val states: Map<String, ModelLifecycleState> = emptyMap(),
    val compatibility: Map<String, CompatibilityLevel> = emptyMap(),
    val downloads: Map<String, DownloadInfo> = emptyMap(),
    val query: String = "",
    val selectedCategory: String = "All",
    val profile: DeviceProfile? = null,
    val loading: Boolean = true,
    val error: String? = null
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

class ModelsViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    init {
        refresh()
        observeInstalled()
        observeDownloads()
        observeMeasuredMemory()
    }

    /** Re-runs the compatibility analysis when a model's real memory is measured.
     *  Debounced so a burst of measurements triggers a single refresh. */
    private fun observeMeasuredMemory() {
        viewModelScope.launch {
            container.settingsRepository.measuredMemory
                .debounce(1500)
                .collect {
                    refresh()
                }
        }
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                _state.value = _state.value.copy(
                    states = installed.associate { it.modelId to it.state }
                )
            }
        }
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            container.downloadManager.downloads.collect { downloads ->
                _state.value = _state.value.copy(
                    downloads = downloads.associateBy { it.modelId }
                )
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val profile = container.deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            val measured = container.settingsRepository.measuredMemoryOnce()
            val recommendations = container.compatibilityEngine.recommendAll(
                LocalModelCatalog.models,
                profile,
                budget,
                measured
            )
            _state.value = _state.value.copy(
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
            when (val result = container.downloadManager.startDownload(model)) {
                is DownloadStartResult.Started -> {
                    container.modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING)
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

    fun pause(modelId: String) = container.downloadManager.pause(modelId)
    fun resume(modelId: String) = container.downloadManager.resume(modelId)
    fun cancel(modelId: String) = container.downloadManager.cancel(modelId)

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    val categories: List<String> =
        listOf("All", "Recommended") + LocalModelCatalog.models.map { it.category }.distinct()
}
