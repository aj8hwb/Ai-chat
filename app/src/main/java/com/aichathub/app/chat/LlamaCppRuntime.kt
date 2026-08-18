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
    private val context: Context,
    private val onModelMemoryMeasured: ((modelId: String, pssBytes: Long) -> Unit)? = null
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

    /** Source of the current load, so the context can be rebuilt fresh. */
    @Volatile
    private var reloadModelId: String? = null
    @Volatile
    private var reloadFile: File? = null
    @Volatile
    private var reloadContextLength: Int = 512
    @Volatile
    private var reloadSampling: GenerationConfig? = null
    @Volatile
    private var reloadThreads: Int = 4

    /**
     * Running estimate of the tokens currently held in the native KV cache.
     * llama.cpp reuses the KV cache across [Llama.complete] calls; once the
     * accumulated prompt+generation tokens approach the model's context size a
     * next call can overflow it, which makes the model go silent or crash
     * natively. So the model is rebuilt fresh BEFORE that overflow, instead of
     * reloading before every single message.
     */
    @Volatile
    private var estimatedContextUsed = 0

    override suspend fun load(
        modelId: String,
        file: File,
        contextLength: Int,
        sampling: GenerationConfig?,
        threads: Int
    ) = nativeMutex.withLock {
        withContext(Dispatchers.IO) {
            check(!closed.get()) { "Runtime already released" }
            reloadModelId = modelId
            reloadFile = file
            reloadContextLength = contextLength
            reloadSampling = sampling
            reloadThreads = threads.coerceAtLeast(1)
            unloadQuietly()
            doLoad(modelId, file, contextLength)
        }
    }

    private suspend fun doLoad(modelId: String, file: File, contextLength: Int) {
        Log.i(tag, "Loading model $modelId from ${file.absolutePath}")
        val s = reloadSampling ?: GenerationConfig()
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
        val loaded = Llama.loadModel(file.absolutePath, config)
        model = loaded
        isLoaded = true
        activeModelId = modelId
        estimatedContextUsed = 0
        _performance.value = InferenceRuntime.Performance(contextUsed = 0)
        // Report the REAL memory this model consumes on this device (App PSS
        // delta around the load). This feeds the recommendation system so it
        // scores models on measured footprint, not just the catalog estimate.
        val pssDelta = (pssBytes() - pssBefore).coerceAtLeast(0)
        if (pssDelta > 0) onModelMemoryMeasured?.invoke(modelId, pssDelta)
        Log.i(tag, "Model $modelId loaded (threads=${reloadThreads}, temp=${config.temperature}) ${Llama.getSystemInfo()}")
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
        runCatching { Llama.releaseModel(m) }
            .onFailure { Log.w(tag, "Error releasing model", it) }
        model = null
        isLoaded = false
        activeModelId = null
        estimatedContextUsed = 0
        _performance.value = InferenceRuntime.Performance()
    }

    /**
     * Rebuilds the native model from a clean state only when the accumulated
     * context is about to overflow the model's KV cache. No-op for a fresh
     * load and for short conversations — consecutive messages reuse the loaded
     * model (fast multi-turn) until the estimated context pressure makes a
     * reload necessary. Must run on the IO dispatcher.
     */
    private suspend fun ensureFreshContext(prompt: String) {
        val file = reloadFile ?: return
        val id = reloadModelId ?: return
        val newPromptTokens = (prompt.length / TOKENS_PER_CHAR).coerceAtLeast(1)
        if (estimatedContextUsed + newPromptTokens <= reloadContextLength * CONTEXT_SAFETY_FACTOR) return
        Log.i(tag, "Fresh context: reloading $id before next generation " +
            "(estimated=${estimatedContextUsed}+$newPromptTokens of $reloadContextLength)")
        unloadQuietly()
        doLoad(id, file, reloadContextLength)
    }

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String?
    ): String = nativeMutex.withLock {
        check(!closed.get()) { "Runtime already released" }
        // A stale cancellation (e.g. the Playground was left mid-run) must never
        // silently block a new generation: clear it and proceed normally. A live
        // cancellation of THIS generation is still honored after the call.
        cancelled.set(false)
        // If a fresh-context reload happens below, it must use the CURRENT
        // sampling config — the config baked in at load time may be stale.
        reloadSampling = config
        withContext(Dispatchers.IO) { ensureFreshContext(prompt) }
        val m = requireModel()
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
            updatePerformance(result, start, prompt.length)
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
        // See [generate]: stale cancellations are discarded, not fatal.
        cancelled.set(false)
        // Keep the fresh-context reload in sync with the current sampling.
        reloadSampling = config
        withContext(Dispatchers.IO) { ensureFreshContext(prompt) }
        val m = requireModel()
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
            updatePerformance(result, start, prompt.length)
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

    private fun pssBytes(): Long = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss * 1024L
    }.getOrDefault(0L)

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

    private fun updatePerformance(result: LlamaResult, startNanos: Long, promptLength: Int) {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
        val tps = if (result.tokensPerSecond > 0f) result.tokensPerSecond
        else if (elapsed > 0f) result.tokensGenerated / elapsed else 0f
        val promptTokens = (promptLength / TOKENS_PER_CHAR).coerceAtLeast(1)
        val contextUsed = promptTokens + result.tokensGenerated
        estimatedContextUsed = contextUsed
        _performance.value = _performance.value.copy(
            tokensPerSecond = tps,
            tokensGenerated = result.tokensGenerated,
            contextUsed = contextUsed
        )
    }

    private companion object {
        /** Conservative average characters per token for prompt size accounting. */
        const val TOKENS_PER_CHAR = 4
        /** Never let the estimated KV usage exceed this fraction of the context. */
        const val CONTEXT_SAFETY_FACTOR = 0.8f
    }
}