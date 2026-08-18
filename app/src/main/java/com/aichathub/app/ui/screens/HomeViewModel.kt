package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HomeUiState(
    val deviceProfile: DeviceProfile? = null,
    val memoryBudget: AiMemoryBudget? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val analyzing: Boolean = true,
    val installedStates: Map<String, ModelLifecycleState> = emptyMap(),
    val showHelp: Boolean = false
)

class HomeViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        analyze()
        observeInstalled()
        observeMeasuredMemory()
        viewModelScope.launch {
            val s = container.settingsRepository.settings.first()
            _state.value = _state.value.copy(showHelp = !s.helpDismissed)
        }
    }

    /** Re-analyzes whenever a model's real memory footprint is measured, so
     *  recommendations reflect what actually happens on this device. */
    private fun observeMeasuredMemory() {
        viewModelScope.launch {
            container.settingsRepository.measuredMemory.collect {
                analyze()
            }
        }
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                _state.value = _state.value.copy(
                    installedStates = installed.associate { it.modelId to it.state }
                )
            }
        }
    }

    fun dismissHelp() {
        _state.value = _state.value.copy(showHelp = false)
        viewModelScope.launch { container.settingsRepository.setHelpDismissed(true) }
    }

    fun analyze() {
        _state.value = _state.value.copy(analyzing = true)
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