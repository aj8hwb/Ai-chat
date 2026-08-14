package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MyModelsUiState(
    val models: List<CatalogModel> = emptyList(),
    val states: Map<String, ModelLifecycleState> = emptyMap(),
    val totalStorageBytes: Long = 0L
)

class MyModelsViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(MyModelsUiState())
    val state: StateFlow<MyModelsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                val models = installed.mapNotNull { LocalModelCatalog.byId(it.modelId) }
                _state.value = MyModelsUiState(
                    models = models,
                    states = installed.associate { it.modelId to it.state },
                    totalStorageBytes = installed.sumOf { it.fileSizeBytes }
                )
            }
        }
    }

    fun unload(modelId: String) {
        viewModelScope.launch {
            val model = LocalModelCatalog.byId(modelId) ?: return@launch
            container.chatCoordinator.unloadModel(modelId)
            container.modelRepository.setState(modelId, ModelLifecycleState.INSTALLED)
        }
    }

    fun delete(modelId: String) {
        viewModelScope.launch {
            container.downloadManager.clearForModel(modelId)
            container.modelRepository.remove(modelId)
        }
    }
}