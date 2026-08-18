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

    /** Prompts beyond this size are rejected: an oversized prompt can overflow
     *  llama.cpp's native context and crash the whole process. */
    companion object {
        const val MAX_PROMPT_CHARS = 3000
    }

    private val _state = MutableStateFlow(PlaygroundUiState())
    val state: StateFlow<PlaygroundUiState> = _state.asStateFlow()

    init {
        observeInstalled()
        observePerformance()
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                val states = installed.associate { it.modelId to it.state }
                _state.value = _state.value.copy(
                    states = states,
                    // Installed models first so a freshly downloaded model is
                    // visible immediately, not buried at the end of the list.
                    models = LocalModelCatalog.models.sortedByDescending { it.id in states.keys }
                )
                // Auto-select a usable model when none is selected yet, so a
                // freshly downloaded model is immediately ready to run without
                // hunting for it in the list. The newest READY model wins.
                if (_state.value.selectedModelId == null) {
                    val newest = installed
                        .filter { it.state == ModelLifecycleState.READY }
                        .maxByOrNull { it.installedAt }
                    if (newest != null) {
                        _state.value = _state.value.copy(selectedModelId = newest.modelId)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        // Leaving the page mid-run: flag the generation as cancelled so its
        // result is discarded once the (uninterruptible) native call returns,
        // and the next Chat send never waits on a stale Playground run.
        container.inferenceRuntime.cancelGeneration()
        _state.value = _state.value.copy(running = false)
        super.onCleared()
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
        if (_state.value.prompt.length > MAX_PROMPT_CHARS) {
            _state.value = _state.value.copy(
                error = "That prompt is too long (max $MAX_PROMPT_CHARS characters). Please shorten it."
            )
            return
        }
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
                val settings = container.settingsRepository.settings.first()
                val config = GenerationConfig(
                    temperature = _state.value.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    maxTokens = _state.value.maxTokens
                )
                if (!container.inferenceRuntime.isLoaded || container.inferenceRuntime.activeModelId != model.id) {
                    container.chatCoordinator.loadModel(
                        model,
                        java.io.File(installed.filePath),
                        config,
                        threads = com.aichathub.app.util.ModelThreads.recommended(settings.batteryConscious)
                    )
                }
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
            } catch (e: com.aichathub.app.chat.ModelLoadRefusedException) {
                _state.value = _state.value.copy(
                    running = false,
                    error = e.message
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