package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlaygroundUiState(
    val models: List<CatalogModel> = LocalModelCatalog.models,
    val states: Map<String, ModelLifecycleState> = emptyMap(),
    val selectedModelId: String? = null,
    val prompt: String = "Write a Python function to calculate Fibonacci numbers.",
    val temperature: Float = 0.8f,
    val maxTokens: Int = 512,
    val running: Boolean = false,
    val output: String = "",
    val stats: String? = null,
    val error: String? = null,
    val generatedTokens: Int = 0,
    val tokensPerSecond: Float = 0f
)

class PlaygroundViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(PlaygroundUiState())
    val state: StateFlow<PlaygroundUiState> = _state.asStateFlow()

    init {
        observeInstalled()
        observePerformance()
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

    private fun observePerformance() {
        viewModelScope.launch {
            container.inferenceRuntime.performance.collect { p ->
                _state.value = _state.value.copy(
                    tokensPerSecond = p.tokensPerSecond,
                    generatedTokens = p.tokensGenerated
                )
            }
        }
    }

    fun selectModel(id: String) {
        _state.value = _state.value.copy(selectedModelId = id)
    }

    fun onPromptChange(v: String) {
        _state.value = _state.value.copy(prompt = v)
    }

    fun onTemperatureChange(v: Float) {
        _state.value = _state.value.copy(temperature = v)
    }

    fun onMaxTokensChange(v: Int) {
        _state.value = _state.value.copy(maxTokens = v)
    }

    /** Main-thread in-flight guard so a rapid double-tap cannot double-run. */
    @Volatile
    private var runInFlight = false

    fun run() {
        if (runInFlight || _state.value.running) return
        val model = _state.value.selectedModelId?.let { LocalModelCatalog.byId(it) } ?: return
        runInFlight = true
        viewModelScope.launch {
            try {
                val installed = container.modelRepository.stateFor(model.id)
                if (installed?.filePath == null) {
                    _state.value = _state.value.copy(error = "This model is not installed yet. Install it first.")
                    return@launch
                }
                _state.value = _state.value.copy(running = true, error = null, output = "", stats = null)
                if (!container.inferenceRuntime.isLoaded || container.inferenceRuntime.activeModelId != model.id) {
                    container.chatCoordinator.loadModel(model, java.io.File(installed.filePath))
                }
                val config = GenerationConfig(
                    temperature = _state.value.temperature,
                    maxTokens = _state.value.maxTokens
                )
                val start = System.nanoTime()
                val result = container.inferenceRuntime.generateStreaming(
                    prompt = _state.value.prompt,
                    config = config,
                    systemPrompt = null,
                    onToken = { partial ->
                        _state.value = _state.value.copy(output = partial)
                    }
                )
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                _state.value = _state.value.copy(
                    output = result,
                    running = false,
                    stats = "Completed in ${elapsedMs / 1000f}s · ~${_state.value.tokensPerSecond} tok/s · ${_state.value.generatedTokens} tokens"
                )
            } catch (e: OutOfMemoryError) {
                _state.value = _state.value.copy(
                    running = false,
                    error = "Insufficient memory for this generation. Try a lighter model."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    error = "Generation failed. Please try again."
                )
            } finally {
                runInFlight = false
            }
        }
    }

    fun stop() {
        container.inferenceRuntime.cancelGeneration()
        // The in-flight coroutine clears `running` in its finally once the
        // native call returns; do not race the state here.
    }
}