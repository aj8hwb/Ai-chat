package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val deviceProfile: DeviceProfile? = null,
    val memoryBudget: AiMemoryBudget? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val analyzing: Boolean = true,
    val installedStates: Map<String, ModelLifecycleState> = emptyMap(),
    val showHelp: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val deviceInfoProvider: DeviceInfoProvider,
    private val compatibilityEngine: CompatibilityEngine,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        analyze()
        observeInstalled()
        observeMeasuredMemory()
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            _state.value = _state.value.copy(showHelp = !s.helpDismissed)
        }
    }

    private fun observeMeasuredMemory() {
        viewModelScope.launch {
            settingsRepository.measuredMemory
                .debounce(1500)
                .collect {
                    analyze()
                }
        }
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            modelRepository.installedModels.collect { installed ->
                _state.value = _state.value.copy(
                    installedStates = installed.associate { it.modelId to it.state }
                )
            }
        }
    }

    fun dismissHelp() {
        _state.value = _state.value.copy(showHelp = false)
        viewModelScope.launch { settingsRepository.setHelpDismissed(true) }
    }

    fun analyze() {
        _state.value = _state.value.copy(analyzing = true)
        viewModelScope.launch {
            val profile = deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            val measured = settingsRepository.measuredMemoryOnce()
            val recommendations = compatibilityEngine.recommendAll(
                catalogRepository.getAllModels(),
                profile,
                budget,
                measured
            )
            _state.value = HomeUiState(
                deviceProfile = profile,
                memoryBudget = budget,
                recommendations = recommendations,
                analyzing = false,
                installedStates = _state.value.installedStates,
                showHelp = _state.value.showHelp
            )
        }
    }
}
