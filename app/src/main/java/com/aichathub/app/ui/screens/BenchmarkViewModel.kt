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

data class BenchmarkResult(
    val tokensPerSecond: Float,
    val firstTokenMs: Long,
    val tokens: Int,
    val memoryBytes: Long
)

data class BenchmarkUiState(
    val selectedModel: CatalogModel? = null,
    val running: Boolean = false,
    val statusText: String = "",
    val result: BenchmarkResult? = null
)

class BenchmarkViewModel(application: Application) : AiViewModel(application) {


    private val _state = MutableStateFlow(BenchmarkUiState())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val installed = container.modelRepository.installedModelsOnce()
            val model = installed.firstNotNullOfOrNull { LocalModelCatalog.byId(it.modelId) }
            _state.value = _state.value.copy(selectedModel = model)
        }
    }

    fun runBenchmark() {
        val model = _state.value.selectedModel ?: return
        viewModelScope.launch {
            val installed = container.modelRepository.stateFor(model.id) ?: return@launch
            _state.value = _state.value.copy(running = true, statusText = "Loading model…", result = null)
            try {
                container.chatCoordinator.loadModel(model, java.io.File(installed.filePath))

                val prompt = "Write a short paragraph about local AI."
                _state.value = _state.value.copy(statusText = "Warming up…")
                container.inferenceRuntime.generateStreaming(
                    prompt = prompt,
                    config = GenerationConfig(maxTokens = 64),
                    systemPrompt = null,
                    onToken = {}
                )

                _state.value = _state.value.copy(statusText = "Measuring…")
                val start = System.nanoTime()
                var firstTokenTime: Long? = null
                var tokenCount = 0
                container.inferenceRuntime.generateStreaming(
                    prompt = prompt,
                    config = GenerationConfig(maxTokens = 256),
                    systemPrompt = null,
                    onToken = { partial ->
                        tokenCount = partial.length / 4
                        if (firstTokenTime == null) {
                            firstTokenTime = (System.nanoTime() - start) / 1_000_000
                        }
                    }
                )
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                val tps = if (elapsedMs > 0) (tokenCount.toFloat() / elapsedMs.toFloat()) * 1000f else 0f

                _state.value = _state.value.copy(
                    running = false,
                    statusText = "Done",
                    result = BenchmarkResult(
                        tokensPerSecond = tps,
                        firstTokenMs = firstTokenTime ?: elapsedMs,
                        tokens = tokenCount,
                        memoryBytes = model.estimatedMemoryBytes
                    )
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    statusText = "Benchmark failed",
                    result = null
                )
            }
        }
    }
}