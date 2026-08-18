package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PerformanceUiState(
    val tokensPerSecond: Float = 0f,
    val modelMemoryBytes: Long = 0,
    val contextUsed: Int = 0,
    val contextMax: Int = 0,
    val activeModelName: String? = null,
    val active: Boolean = false,
    val tokensGenerated: Int = 0
)

class PerformanceViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(PerformanceUiState())
    val state: StateFlow<PerformanceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.inferenceRuntime.performance.collect { p ->
                _state.value = _state.value.copy(
                    tokensPerSecond = p.tokensPerSecond,
                    tokensGenerated = p.tokensGenerated,
                    contextUsed = p.contextUsed,
                    active = p.generationActive
                )
            }
        }
        viewModelScope.launch {
            combine(
                container.chatCoordinator.state,
                container.settingsRepository.measuredMemory
            ) { s, measured ->
                val model = s.activeModelId?.let { LocalModelCatalog.byId(it) }
                _state.value = _state.value.copy(
                    activeModelName = s.activeModelName,
                    // Prefer the REAL measured footprint over the catalog estimate
                    // so the monitor shows what the model actually uses on-device.
                    modelMemoryBytes = model?.let { measured[it.id] ?: it.estimatedMemoryBytes } ?: 0,
                    contextMax = model?.contextLength ?: 0
                )
            }.collect { /* state updated inside the transform */ }
        }
    }
}