package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PerformanceUiState(
    val tokensPerSecond: Float = 0f,
    val modelMemoryBytes: Long = 0,
    val contextUsed: Int = 0,
    val contextMax: Int = 0,
    val activeModelName: String? = null,
    val active: Boolean = false,
    val tokensGenerated: Int = 0
)

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val container_inferenceRuntime: InferenceRuntime,
    private val container_chatCoordinator: ChatCoordinator,
    private val container_settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PerformanceUiState())
    val state: StateFlow<PerformanceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container_inferenceRuntime.performance.collect { p ->
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
                container_chatCoordinator.state,
                container_settingsRepository.measuredMemory
            ) { s, measured ->
                val model = s.activeModelId?.let { catalogRepository.getModelById(it) }
                _state.value = _state.value.copy(
                    activeModelName = s.activeModelName,
                    modelMemoryBytes = model?.let { measured[it.id] ?: it.estimatedMemoryBytes } ?: 0,
                    contextMax = model?.contextLength ?: 0
                )
            }.collect { }
        }
    }
}
