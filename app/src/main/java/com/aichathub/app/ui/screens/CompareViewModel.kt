package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.domain.model.CatalogModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CompareResult(
    val modelId: String,
    val modelName: String,
    val output: String,
    val tokensPerSecond: Float,
    val tokens: Int,
    val failed: Boolean = false
)

data class CompareUiState(
    val models: List<CatalogModel> = emptyList(),
    val prompt: String = "Explain Kotlin coroutines.",
    val running: Boolean = false,
    val results: List<CompareResult> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class CompareViewModel @Inject constructor(
    private val container_inferenceRuntime: InferenceRuntime,
    private val container_chatCoordinator: ChatCoordinator,
    private val container_modelRepository: ModelRepository,
    private val container_settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    companion object {
        const val MAX_PROMPT_CHARS = 3000
    }

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container_modelRepository.installedModels.collect { installed ->
                val ids = installed.map { it.modelId }.toSet()
                _state.value = _state.value.copy(
                    models = catalogRepository.getAllModels().filter { it.id in ids }
                )
            }
        }
    }

    fun onPromptChange(v: String) {
        _state.value = _state.value.copy(prompt = v)
    }

    fun runComparison() {
        val prompt = _state.value.prompt
        val models = _state.value.models
        if (prompt.isBlank() || models.isEmpty()) return
        if (prompt.length > MAX_PROMPT_CHARS) {
            _state.value = _state.value.copy(
                error = "That prompt is too long (max $MAX_PROMPT_CHARS characters). Please shorten it."
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, results = emptyList(), error = null)
            val results = mutableListOf<CompareResult>()
            val settings = container_settingsRepository.settings.first()
            container_inferenceRuntime.clearCancellation()
            for (model in models) {
                try {
                    val installed = container_modelRepository.stateFor(model.id) ?: continue
                    val config = GenerationConfig(
                        temperature = settings.temperature,
                        topK = settings.topK,
                        topP = settings.topP,
                        maxTokens = 300
                    )
                    container_chatCoordinator.loadModel(
                        model,
                        java.io.File(installed.filePath),
                        config,
                        threads = com.aichathub.app.util.ModelThreads.recommended(settings.batteryConscious)
                    )
                    val output = container_inferenceRuntime.generateStreaming(
                        prompt = prompt,
                        config = config,
                        onToken = {}
                    )
                    val perf = container_inferenceRuntime.performance.value
                    results += CompareResult(
                        modelId = model.id,
                        modelName = model.name,
                        output = output,
                        tokensPerSecond = perf.tokensPerSecond,
                        tokens = perf.tokensGenerated
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
