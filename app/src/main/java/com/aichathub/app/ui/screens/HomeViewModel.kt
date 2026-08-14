package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.ui.applicationContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val deviceProfile: DeviceProfile? = null,
    val memoryBudget: AiMemoryBudget? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val analyzing: Boolean = true,
    val installedStates: Map<String, ModelLifecycleState> = emptyMap()
)

class HomeViewModel : ViewModel() {

    private val container = applicationContainer()
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        analyze()
        observeInstalled()
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

    fun analyze() {
        _state.value = _state.value.copy(analyzing = true)
        viewModelScope.launch {
            val profile = container.deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            val recommendations = container.compatibilityEngine.recommendAll(
                LocalModelCatalog.models,
                profile,
                budget
            )
            _state.value = HomeUiState(
                deviceProfile = profile,
                memoryBudget = budget,
                recommendations = recommendations,
                analyzing = false
            )
        }
    }
}