package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.ui.applicationContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class PerformanceViewModel : ViewModel() {

    private val container = applicationContainer()
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
            container.chatCoordinator.state.collect { s ->
                val model = s.activeModelId?.let { LocalModelCatalog.byId(it) }
                _state.value = _state.value.copy(
                    activeModelName = s.activeModelName,
                    modelMemoryBytes = model?.estimatedMemoryBytes ?: 0,
                    contextMax = model?.contextLength ?: 0
                )
            }
        }
    }
}