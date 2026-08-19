package com.aichathub.app.chat

import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaConfig
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real on-device inference via llama.cpp (org.codeshipping:llama-kotlin-android).
 *
 * Loads the GGUF model files downloaded by the app and runs them entirely
 * on-device. The engine supports REAL token-by-token streaming
 * ([LlamaModel.generateStream]) and true cancellation
 * ([LlamaModel.cancelGeneration]) — nothing is simulated or buffered.
 *
 * IMPORTANT: this is the concrete engine. The rest of the app only talks to
 * the [InferenceRuntime] interface, so the engine can be swapped later.
 */
class LlamaCppRuntime(
    private val context: Context,
    private val onModelMemoryMeasured: ((modelId: String, pssBytes: Long) -> Unit)? = null
) : InferenceRuntime {

    private val tag = "LlamaCppRuntime"
    private val _performance = MutableStateFlow(InferenceRuntime.Performance())
    override val performance: StateFlow<InferenceRuntime.Performance> = _performance.asStateFlow()

    @Volatile
    private var model: LlamaModel? = null

    /**
     * Serializes ALL native llama.cpp operations. llama.cpp (via
     * llama-kotlin-android) is NOT safe to call from two threads at once: two
     * concurrent generations, or a release during a generation, crash the
     * whole process with a native SIGSEGV that Kotlin cannot catch. This mutex
     * makes load / unload / generate mutually exclusive so the process can
     * never run two native operations at the same time.
     */
    private val nativeMutex = Mutex()

    override var isLoaded: Boolean = false
        private set

    override var activeModelId: String? = null
        private set

    private val cancelled = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** Load-time settings reused when building per-generation native configs. */
    @Volatile
    private var reloadContextLength: Int = 512
    @Volatile
    private var reloadThreads: Int = 4

    override suspend fun load(
        modelId: String,
        file: File,
        contextLength: Int,
        sampling: GenerationConfig?,
        threads: Int
    ) = nativeMutex.withLock {
        withContext(Dispatchers.IO) {
            check(!closed.get()) { "Runtime already released" }
            reloadContextLength = contextLength
            reloadThreads = threads.coerceAtLeast(1)
            unloadQuietly()
            doLoad(modelId, file, contextLength, sampling)
        }
    }

    private suspend fun doLoad(
        modelId: String,
        file: File,
        contextLength: Int,
        sampling: GenerationConfig?
    ) {
        Log.i(tag, "Loading model $modelId from ${file.absolutePath}")
        val s = sampling ?: GenerationConfig()
        val config = LlamaConfig(
            contextSize = contextLength.coerceAtLeast(512),
            threads = reloadThreads.coerceAtLeast(1),
            gpuLayers = 0,
            temperature = s.temperature,
            topP = s.topP,
            topK = s.topK,
            seed = if (s.randomSeed == 0) -1 else s.randomSeed
        )
        val pssBefore = pssBytes()
        val loaded = LlamaModel.load(file.absolutePath, config)
        model = loaded
        isLoaded = true
        activeModelId = modelId
        _performance.value = InferenceRuntime.Performance(contextUsed = 0)
        // Report the REAL memory this model consumes on this device (App PSS
        // delta around the load). This feeds the recommendation system so it
        // scores models on measured footprint, not just the catalog estimate.
        val pssDelta = (pssBytes() - pssBefore).coerceAtLeast(0)
        if (pssDelta > 0) onModelMemoryMeasured?.invoke(modelId, pssDelta)
        Log.i(tag, "Model $modelId loaded (threads=${reloadThreads}, temp=${config.temperature}) ${LlamaModel.getVersion()}")
        memoryLog("after load $modelId")
    }

    override suspend fun unload() = nativeMutex.withLock {
        withContext(Dispatchers.IO) {
            unloadQuietly()
            memoryLog("after unload")
        }
    }

    private fun unloadQuietly() {
        val m = model ?: return
        runCatching { m.close() }
            .onFailure { Log.w(tag, "Error releasing model", it) }
        model = null
        isLoaded = false
        activeModelId = null
        _performance.value = InferenceRuntime.Performance()
    }

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig
    ): String = nativeMutex.withLock {
        check(!closed.get()) { "Runtime already released" }
        // A stale cancellation (e.g. the Playground was left mid-run) must never
        // silently block a new generation: clear it and proceed normally.
        cancelled.set(false)
        val m = requireModel()
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        try {
            val start = System.nanoTime()
            memoryLog("before generate")
            val nativeConfig = toNativeConfig(config)
            val sb = StringBuilder()
            var tokens = 0
            withContext(Dispatchers.IO) {
                m.generateStream(prompt, nativeConfig).collect { token ->
                    sb.append(token)
                    tokens++
                }
            }
            memoryLog("after generate")
            if (cancelled.get()) throw CancellationException("Generation cancelled")
            val result = sb.toString()
            updatePerformance(result, start, prompt.length, tokens)
            result
        } finally {
            generating.set(false)
            _performance.value = _performance.value.copy(generationActive = false)
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        config: GenerationConfig,
        onToken: (String) -> Unit
    ): String = nativeMutex.withLock {
        check(!closed.get()) { "Runtime already released" }
        // See [generate]: stale cancellations are discarded, not fatal.
        cancelled.set(false)
        val m = requireModel()
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        try {
            val start = System.nanoTime()
            memoryLog("before generateStreaming")
            val nativeConfig = toNativeConfig(config)
            val sb = StringBuilder()
            var tokens = 0
            withContext(Dispatchers.IO) {
                m.generateStream(prompt, nativeConfig).collect { token ->
                    sb.append(token)
                    tokens++
                    onToken(token)
                }
            }
            memoryLog("after generateStreaming")
            if (cancelled.get()) throw CancellationException("Generation cancelled")
            val result = sb.toString()
            updatePerformance(result, start, prompt.length, tokens)
            result
        } finally {
            generating.set(false)
            _performance.value = _performance.value.copy(generationActive = false)
        }
    }

    override fun cancelGeneration() {
        // Real native cancellation: the engine checks the cancel flag between
        // generated tokens and stops promptly. The in-flight coroutine then
        // sees `cancelled` and discards the partial result cleanly.
        cancelled.set(true)
        model?.cancelGeneration()
    }

    override fun release() {
        closed.set(true)
        cancelGeneration()
        runCatching { unloadQuietly() }
    }

    private fun requireModel(): LlamaModel =
        model ?: throw IllegalStateException("Model is not loaded")

    /** Builds a full native config for one generation, preserving the load-time
     *  context size / threads while applying the per-generation sampling. */
    private fun toNativeConfig(config: GenerationConfig): LlamaConfig = LlamaConfig(
        contextSize = reloadContextLength.coerceAtLeast(512),
        threads = reloadThreads.coerceAtLeast(1),
        gpuLayers = 0,
        temperature = config.temperature,
        topP = config.topP,
        topK = config.topK,
        maxTokens = config.maxTokens.coerceAtLeast(1),
        seed = if (config.randomSeed == 0) -1 else config.randomSeed
    )

    private fun pssBytes(): Long = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss * 1024L
    }.getOrDefault(0L)

    /**
     * Records the app's real memory footprint (App PSS, native heap and Java
     * heap) to logcat with the tag AICHATHUB_MEM so a device-side test can tie
     * the recommendation system to ACTUAL runtime memory, not just file size.
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

    private fun updatePerformance(result: String, startNanos: Long, promptLength: Int, tokensGenerated: Int) {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
        val tps = if (elapsed > 0f) tokensGenerated / elapsed else 0f
        val promptTokens = (promptLength / TOKENS_PER_CHAR).coerceAtLeast(1)
        val contextUsed = promptTokens + tokensGenerated
        _performance.value = _performance.value.copy(
            tokensPerSecond = tps,
            tokensGenerated = tokensGenerated,
            contextUsed = contextUsed
        )
    }

    private companion object {
        /** Conservative average characters per token for prompt size accounting. */
        const val TOKENS_PER_CHAR = 4
    }
}