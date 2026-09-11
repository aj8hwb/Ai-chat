package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.domain.model.ChatTemplate
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.util.TokenEstimator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaygroundUiState(
    val models: List<com.aichathub.app.domain.model.CatalogModel> = emptyList(),
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
    val tokensPerSecond: Float = 0f,
    val contextTokensMax: Int = 0
)

@HiltViewModel
class PlaygroundViewModel @Inject constructor(
    private val container_inferenceRuntime: InferenceRuntime,
    private val container_chatCoordinator: ChatCoordinator,
    private val container_modelRepository: ModelRepository,
    private val container_settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

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
            container_modelRepository.installedModels.collect { installed ->
                val states = installed.associate { it.modelId to it.state }
                _state.value = _state.value.copy(
                    states = states,
                    models = catalogRepository.getAllModels().sortedByDescending { it.id in states.keys }
                )
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
        container_inferenceRuntime.cancelGeneration()
        _state.value = _state.value.copy(running = false)
        super.onCleared()
    }

    private fun observePerformance() {
        viewModelScope.launch {
            container_inferenceRuntime.performance.collect { p ->
                _state.value = _state.value.copy(
                    tokensPerSecond = p.tokensPerSecond,
                    generatedTokens = p.tokensGenerated,
                    contextTokensMax = p.contextTokensMax
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
        val model = _state.value.selectedModelId?.let { catalogRepository.getModelById(it) } ?: return
        runInFlight = true
        container_inferenceRuntime.clearCancellation()
        viewModelScope.launch {
            try {
                val installed = container_modelRepository.stateFor(model.id)
                if (installed?.filePath == null) {
                    _state.value = _state.value.copy(error = "This model is not installed yet. Install it first.")
                    return@launch
                }
                _state.value = _state.value.copy(running = true, error = null, output = "", stats = null)

                val ctxMax = _state.value.contextTokensMax.coerceAtLeast(model.contextLength)
                val promptTokens = TokenEstimator.estimate(_state.value.prompt)
                val headroom = ((ctxMax * 0.8).toInt() - promptTokens).coerceAtLeast(64)
                val maxTokens = _state.value.maxTokens.coerceIn(1, headroom)

                val settings = container_settingsRepository.settings.first()
                val config = GenerationConfig(
                    temperature = _state.value.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    maxTokens = maxTokens,
                    stopSequences = templateStopSequences(model.chatTemplate)
                )
                if (maxTokens != _state.value.maxTokens) {
                    _state.value = _state.value.copy(
                        stats = "Max tokens clamped to $maxTokens for this model's context."
                    )
                }
                if (!container_inferenceRuntime.isLoaded || container_inferenceRuntime.activeModelId != model.id) {
                    container_chatCoordinator.loadModel(
                        model,
                        java.io.File(installed.filePath),
                        config,
                        threads = com.aichathub.app.util.ModelThreads.recommended(settings.batteryConscious)
                    )
                }
                val start = System.nanoTime()
                val result = container_inferenceRuntime.generateStreaming(
                    prompt = _state.value.prompt,
                    config = config,
                    onToken = { partial ->
                        _state.value = _state.value.copy(output = partial)
                    }
                )
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                _state.value = _state.value.copy(
                    output = result,
                    running = false,
                    stats = "Completed in ${elapsedMs / 1000f}s ~${_state.value.tokensPerSecond} tok/s ${_state.value.generatedTokens} tokens"
                )
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(
                    running = false,
                    output = "",
                    stats = "Stopped by user"
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

    private fun templateStopSequences(template: ChatTemplate): List<String> = when (template) {
        ChatTemplate.CHATML -> listOf("\n\n")
        ChatTemplate.LLAMA2, ChatTemplate.LLAMA3 -> listOf("\n[INST]", "\n[/INST]")
        ChatTemplate.MISTRAL -> listOf("\n[/INST]")
        ChatTemplate.CHATGLM -> listOf("\n\n")
        ChatTemplate.VICUNA -> listOf("USER:")
        ChatTemplate.ALPACA -> listOf("### Human:")
        ChatTemplate.ZEPHYR -> listOf("\n</s>")
        ChatTemplate.GEMMA -> listOf("<end_of_turn>")
        ChatTemplate.UNKNOWN -> emptyList()
    }
}
