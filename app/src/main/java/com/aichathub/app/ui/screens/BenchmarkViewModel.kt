package com.aichathub.app.ui.screens

import android.app.Application
import android.os.Debug
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
import java.io.File

data class BenchmarkResult(
    val tokensPerSecond: Float,
    val generationMs: Long,
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
                val settings = container.settingsRepository.settings.first()

                // Measure the REAL memory footprint: App PSS delta around the load.
                val pssBefore = pssBytes()
                container.chatCoordinator.loadModel(
                    model,
                    File(installed.filePath),
                    GenerationConfig(maxTokens = 64),
                    threads = com.aichathub.app.util.ModelThreads.recommended(settings.batteryConscious)
                )
                val memoryBytes = (pssBytes() - pssBefore).coerceAtLeast(0)

                val prompt = "Write a short paragraph about local AI."

                // Warm-up run so page-cache / lazy init don't skew the measurement.
                _state.value = _state.value.copy(statusText = "Warming up…")
                container.inferenceRuntime.generateStreaming(
                    prompt = prompt,
                    config = GenerationConfig(maxTokens = 64),
                    systemPrompt = null,
                    onToken = {}
                )

                // Timed run. All stats come from the runtime's real native result.
                _state.value = _state.value.copy(statusText = "Measuring…")
                val start = System.nanoTime()
                container.inferenceRuntime.generateStreaming(
                    prompt = prompt,
                    config = GenerationConfig(maxTokens = 256),
                    systemPrompt = null,
                    onToken = {}
                )
                val generationMs = (System.nanoTime() - start) / 1_000_000
                val perf = container.inferenceRuntime.performance.value
                val elapsedS = (generationMs.coerceAtLeast(1) / 1000f)
                val tps = if (perf.tokensPerSecond > 0f) perf.tokensPerSecond
                else if (elapsedS > 0f) perf.tokensGenerated / elapsedS else 0f

                _state.value = _state.value.copy(
                    running = false,
                    statusText = "Done",
                    result = BenchmarkResult(
                        tokensPerSecond = tps,
                        generationMs = generationMs,
                        tokens = perf.tokensGenerated,
                        memoryBytes = memoryBytes
                    )
                )
            } catch (e: com.aichathub.app.chat.ModelLoadRefusedException) {
                _state.value = _state.value.copy(
                    running = false,
                    statusText = e.message ?: "Model cannot be loaded on this device.",
                    result = null
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

    private fun pssBytes(): Long = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss * 1024L
    }.getOrDefault(0L)
}
