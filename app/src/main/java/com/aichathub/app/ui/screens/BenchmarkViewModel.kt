package com.aichathub.app.ui.screens

import android.os.Debug
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
import java.io.File
import javax.inject.Inject

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
    val result: BenchmarkResult? = null,
    val error: String? = null
)

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val container_inferenceRuntime: InferenceRuntime,
    private val container_chatCoordinator: ChatCoordinator,
    private val container_modelRepository: ModelRepository,
    private val container_settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BenchmarkUiState())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val installed = container_modelRepository.installedModelsOnce()
            val model = installed.firstNotNullOfOrNull { catalogRepository.getModelById(it.modelId) }
            _state.value = _state.value.copy(selectedModel = model)
        }
    }

    fun runBenchmark() {
        val model = _state.value.selectedModel ?: return
        viewModelScope.launch {
            val installed = container_modelRepository.stateFor(model.id) ?: return@launch
            _state.value = _state.value.copy(running = true, statusText = "Loading model...", result = null, error = null)
            try {
                container_inferenceRuntime.clearCancellation()
                val settings = container_settingsRepository.settings.first()

                val pssBefore = pssBytes()
                container_chatCoordinator.loadModel(
                    model,
                    File(installed.filePath),
                    GenerationConfig(maxTokens = 64),
                    threads = com.aichathub.app.util.ModelThreads.recommended(settings.batteryConscious)
                )
                val memoryBytes = (pssBytes() - pssBefore).coerceAtLeast(0)

                val prompt = "Write a short paragraph about local AI."

                _state.value = _state.value.copy(statusText = "Warming up...")
                container_inferenceRuntime.generateStreaming(
                    prompt = prompt,
                    config = GenerationConfig(maxTokens = 64),
                    onToken = {}
                )

                _state.value = _state.value.copy(statusText = "Measuring...")
                val start = System.nanoTime()
                container_inferenceRuntime.generateStreaming(
                    prompt = prompt,
                    config = GenerationConfig(maxTokens = 256),
                    onToken = {}
                )
                val generationMs = (System.nanoTime() - start) / 1_000_000
                val perf = container_inferenceRuntime.performance.value
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
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    statusText = "Benchmark failed",
                    result = null,
                    error = e.message?.takeIf { it.isNotBlank() }
                        ?: "The benchmark could not run on this device. Check your storage and model file, then try again."
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
