package com.aichathub.app.chat

import android.content.Context
import android.os.Debug
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import dev.ffmpegkit.llama.LlamaResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real on-device inference via llama.cpp (dev.ffmpegkit-maintained:llama-android).
 *
 * Loads the GGUF model files downloaded by the app and runs them entirely
 * on-device. The free tier of llama-android returns the full response at once
 * (no token-by-token streaming), so [generateStreaming] delivers the real
 * model output as a single token event — nothing is simulated.
 *
 * IMPORTANT: this is the concrete engine. The rest of the app only talks to
 * the [InferenceRuntime] interface, so the engine can be swapped later.
 */
class LlamaCppRuntime(
    private val context: Context
) : InferenceRuntime {

    private val tag = "LlamaCppRuntime"
    private val _performance = MutableStateFlow(InferenceRuntime.Performance())
    override val performance: StateFlow<InferenceRuntime.Performance> = _performance.asStateFlow()

    @Volatile
    private var model: LlamaModel? = null

    /**
     * Serializes ALL native llama.cpp operations. llama.cpp (via llama-android)
     * is NOT safe to call from two threads at once: two concurrent
     * [Llama.complete] calls, or a [Llama.releaseModel] during a [Llama.complete],
     * crash the whole process with a native SIGSEGV that Kotlin cannot catch.
     * This mutex makes load / unload / generate mutually exclusive so the
     * process can never run two native operations at the same time.
     */
    private val nativeMutex = Mutex()

    override var isLoaded: Boolean = false
        private set

    override var activeModelId: String? = null
        private set

    private val cancelled = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override suspend fun load(modelId: String, file: File, contextLength: Int) = nativeMutex.withLock {
        withContext(Dispatchers.IO) {
            check(!closed.get()) { "Runtime already released" }
            unloadQuietly()

            Log.i(tag, "Loading model $modelId from ${file.absolutePath}")
            val config = LlamaConfig(
                contextSize = contextLength.coerceAtLeast(512),
                threads = 4,
                gpuLayers = 0,
                temperature = 0.7f,
                topP = 0.9f,
                topK = 40,
                seed = -1
            )
            val loaded = Llama.loadModel(file.absolutePath, config)
            model = loaded
            isLoaded = true
            activeModelId = modelId
            _performance.value = InferenceRuntime.Performance(contextUsed = 0)
            Log.i(tag, "Model $modelId loaded (${Llama.getSystemInfo()})")
            memoryLog("after load $modelId")
            Unit
        }
    }

    override suspend fun unload() = nativeMutex.withLock {
        withContext(Dispatchers.IO) {
            unloadQuietly()
            memoryLog("after unload")
        }
    }

    private fun unloadQuietly() {
        val m = model ?: return
        runCatching { Llama.releaseModel(m) }
            .onFailure { Log.w(tag, "Error releasing model", it) }
        model = null
        isLoaded = false
        activeModelId = null
        _performance.value = InferenceRuntime.Performance()
    }

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String?
    ): String = nativeMutex.withLock {
        check(!closed.get()) { "Runtime already released" }
        val m = requireModel()
        // A stale cancellation (e.g. the Playground was left mid-run) must bail
        // out BEFORE touching native code, so it never wastes a generation.
        if (cancelled.get()) throw CancellationException("Generation cancelled")
        cancelled.set(false)
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        try {
            val start = System.nanoTime()
            memoryLog("before generate")
            val result = withContext(Dispatchers.IO) {
                Llama.complete(m, prompt, systemPrompt.orEmpty(), config.maxTokens)
            }
            memoryLog("after generate")
            if (cancelled.get()) throw CancellationException("Generation cancelled")
            updatePerformance(result, start)
            result.text
        } finally {
            generating.set(false)
            _performance.value = _performance.value.copy(generationActive = false)
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String?,
        onToken: (String) -> Unit
    ): String = nativeMutex.withLock {
        check(!closed.get()) { "Runtime already released" }
        val m = requireModel()
        // A stale cancellation (e.g. the Playground was left mid-run) must bail
        // out BEFORE touching native code, so it never wastes a generation.
        if (cancelled.get()) throw CancellationException("Generation cancelled")
        cancelled.set(false)
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        try {
            val start = System.nanoTime()
            memoryLog("before generateStreaming")
            val result = withContext(Dispatchers.IO) {
                Llama.complete(m, prompt, systemPrompt.orEmpty(), config.maxTokens)
            }
            memoryLog("after generateStreaming")
            if (cancelled.get()) throw CancellationException("Generation cancelled")
            onToken(result.text)
            updatePerformance(result, start)
            result.text
        } finally {
            generating.set(false)
            _performance.value = _performance.value.copy(generationActive = false)
        }
    }

    override fun cancelGeneration() {
        // Native generation cannot be interrupted mid-call on the free tier;
        // flag the result so it is discarded instead of being delivered.
        cancelled.set(true)
    }

    override fun release() {
        closed.set(true)
        cancelGeneration()
        runCatching { unloadQuietly() }
    }

    private fun requireModel(): LlamaModel =
        model ?: throw IllegalStateException("Model is not loaded")

    /**
     * Records the app's real memory footprint (App PSS, native heap and Java
     * heap) to logcat with the tag AICHATHUB_MEM so a device-side test can tie
     * the recommendation system to ACTUAL runtime memory, not just file size.
     * Only usable when the system exposes native heap stats (it always does
     * for the debuggable/release runs here).
     */
    private fun memoryLog(label: String) {
        runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            val javaUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val pssMb = info.totalPss / 1024
            val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            Log.i("AICHATHUB_MEM", "$label -> PSS=${pssMb}MB nativeHeap=${nativeMb}MB javaHeap=${javaUsed}MB")
        }.onFailure {
            Log.i("AICHATHUB_MEM", "$label -> memory stats unavailable")
        }
    }

    private fun updatePerformance(result: LlamaResult, startNanos: Long) {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
        val tps = if (result.tokensPerSecond > 0f) result.tokensPerSecond
        else if (elapsed > 0f) result.tokensGenerated / elapsed else 0f
        _performance.value = _performance.value.copy(
            tokensPerSecond = tps,
            tokensGenerated = result.tokensGenerated,
            contextUsed = _performance.value.contextUsed
        )
    }
}
