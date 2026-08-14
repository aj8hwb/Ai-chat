package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CompareResult(
    val modelId: String,
    val modelName: String,
    val output: String,
    val tokensPerSecond: Float,
    val tokens: Int,
    val failed: Boolean = false
)

data class CompareUiState(
    val models: List<CatalogModel> = LocalModelCatalog.models.filter { it.id in installedIds },
    val prompt: String = "Explain Kotlin coroutines.",
    val running: Boolean = false,
    val results: List<CompareResult> = emptyList(),
    val error: String? = null
) {
    companion object {
        var installedIds: Set<String> = emptySet()
    }
}

class CompareViewModel(application: Application) : AiViewModel(application) {


    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val installed = container.modelRepository.installedModelsOnce()
            CompareUiState.installedIds = installed.map { it.modelId }.toSet()
            _state.value = _state.value.copy(
                models = LocalModelCatalog.models.filter { it.id in CompareUiState.installedIds }
            )
        }
    }

    fun onPromptChange(v: String) {
        _state.value = _state.value.copy(prompt = v)
    }

    fun runComparison() {
        val prompt = _state.value.prompt
        val models = _state.value.models
        if (prompt.isBlank() || models.isEmpty()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, results = emptyList(), error = null)
            val results = mutableListOf<CompareResult>()
            for (model in models) {
                try {
                    val installed = container.modelRepository.stateFor(model.id) ?: continue
                    container.chatCoordinator.loadModel(model, java.io.File(installed.filePath))
                    val start = System.nanoTime()
                    var tokens = 0
                    val output = container.inferenceRuntime.generateStreaming(
                        prompt = prompt,
                        config = GenerationConfig(maxTokens = 300),
                        systemPrompt = null,
                        onToken = { partial -> tokens = partial.length / 4 }
                    )
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    val tps = if (elapsedMs > 0) (tokens.toFloat() / elapsedMs.toFloat()) * 1000f else 0f
                    results += CompareResult(
                        modelId = model.id,
                        modelName = model.name,
                        output = output,
                        tokensPerSecond = tps,
                        tokens = tokens
                    )
                } catch (e: Exception) {
                    results += CompareResult(
                        modelId = model.id,
                        modelName = model.name,
                        output = "Failed to run.",
                        tokensPerSecond = 0f,
                        tokens = 0,
                        failed = true
                    )
                }
            }
            _state.value = _state.value.copy(running = false, results = results)
        }
    }
}