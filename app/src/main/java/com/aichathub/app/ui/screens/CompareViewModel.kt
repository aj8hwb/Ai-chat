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
import kotlinx.coroutines.flow.first
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
    val models: List<CatalogModel> = emptyList(),
    val prompt: String = "Explain Kotlin coroutines.",
    val running: Boolean = false,
    val results: List<CompareResult> = emptyList(),
    val error: String? = null
)

class CompareViewModel(application: Application) : AiViewModel(application) {

    /** Prompts beyond this size are rejected: an oversized prompt can overflow
     *  llama.cpp's native context and crash the whole process. */
    companion object {
        const val MAX_PROMPT_CHARS = 3000
    }

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    init {
        // Reactively list ONLY installed models: the list updates as soon as a
        // model is installed / deleted, without a stale companion-object cache.
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                val ids = installed.map { it.modelId }.toSet()
                _state.value = _state.value.copy(
                    models = LocalModelCatalog.models.filter { it.id in ids }
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
            val settings = container.settingsRepository.settings.first()
            for (model in models) {
                try {
                    val installed = container.modelRepository.stateFor(model.id) ?: continue
                    val config = GenerationConfig(
                        temperature = settings.temperature,
                        topK = settings.topK,
                        topP = settings.topP,
                        maxTokens = 300
                    )
                    container.chatCoordinator.loadModel(
                        model,
                        java.io.File(installed.filePath),
                        config,
                        threads = com.aichathub.app.util.ModelThreads.recommended(settings.batteryConscious)
                    )
                    val output = container.inferenceRuntime.generateStreaming(
                        prompt = prompt,
                        config = config,
                        systemPrompt = null,
                        onToken = { /* full response arrives in one event */ }
                    )
                    // Real, measured stats come from the runtime's performance
                    // snapshot (native tokens / tokens-per-second) — never from
                    // dividing character length by a guessed constant.
                    val perf = container.inferenceRuntime.performance.value
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
