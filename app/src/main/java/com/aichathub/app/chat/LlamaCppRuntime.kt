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

    /**
     * Guards the [model] reference swap performed by [unloadQuietly] against the
     * native [LlamaModel.cancelGeneration] call performed by [cancelGeneration].
     *
     * A generation holds [nativeMutex] for its whole duration, so load/unload
     * can never race it. But [cancelGeneration] must be callable WHILE a
     * generation is running (that is its entire purpose), so it cannot take the
     * mutex. The dangerous window is therefore: cancel reads a non-null
     * `model`, the generation finishes and releases the mutex, a load() for a
     * different model closes that instance and swaps in a new one, and cancel
     * then invokes a native method on the CLOSED instance — a use-after-free
     * SIGSEGV. This lock makes the read-and-cancel and the close-and-swap
     * mutually exclusive so cancel can never touch a released model.
     */
    private val modelAccessLock = Any()

    @Volatile
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
        _performance.value = InferenceRuntime.Performance(
            contextUsed = 0,
            contextTokensMax = contextLength.coerceAtLeast(512)
        )
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
        val m = synchronized(modelAccessLock) {
            val current = model ?: return
            model = null
            current
        }
        runCatching { m.close() }
            .onFailure { Log.w(tag, "Error releasing model", it) }
        isLoaded = false
        activeModelId = null
        _performance.value = InferenceRuntime.Performance()
    }

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig
    ): String = nativeMutex.withLock {
        check(!closed.get()) { "Runtime already released" }
        // See [generateStreaming]: a Stop pressed during caller-side preparation
        // must abort this generation, not be silently dropped. The caller clears
        // stale cancellations via [clearCancellation] when it starts its flow.
        if (cancelled.get()) throw CancellationException("Generation cancelled before start")
        val m = requireModel()
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        try {
            val start = System.nanoTime()
            memoryLog("before generate")
            val nativeConfig = toNativeConfig(config)
            val stream = collectStream(m, prompt, nativeConfig, config.stopSequences) { }
            val result = truncateAtStop(stream.text, config.stopSequences)
            memoryLog("after generate")
            if (cancelled.get()) throw CancellationException("Generation cancelled")
            updatePerformance(result, start, prompt, stream.tokens)
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
        // See [generate]: a Stop pressed during caller-side preparation must
        // abort this generation. The caller clears stale cancellations via
        // [clearCancellation] when it starts its own flow.
        if (cancelled.get()) throw CancellationException("Generation cancelled before start")
        val m = requireModel()
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        try {
            val start = System.nanoTime()
            memoryLog("before generateStreaming")
            val nativeConfig = toNativeConfig(config)
            val stream = collectStream(m, prompt, nativeConfig, config.stopSequences, onToken)
            val result = truncateAtStop(stream.text, config.stopSequences)
            memoryLog("after generateStreaming")
            if (cancelled.get()) throw CancellationException("Generation cancelled")
            updatePerformance(result, start, prompt, stream.tokens)
            result
        } finally {
            generating.set(false)
            _performance.value = _performance.value.copy(generationActive = false)
        }
    }

    /**
     * Consumes the native token stream, forwarding every token to [onToken] and
     * stopping the native generation the moment a template stop sequence shows
     * up in the output. This is a belt-and-suspenders fallback on top of the
     * native [LlamaConfig.stopSequences]: some GGUF files do not declare a
     * usable EOS, so without this the model would happily generate until
     * maxTokens (a multi-hundred-second runaway on small models).
     *
     * The native cancellation is used directly (NOT [cancelGeneration]) so the
     * app's own [cancelled] flag stays clear and a natural stop is not mistaken
     * for a user-initiated stop.
     *
     * Live performance is updated as tokens arrive (not just at the end), so the
     * UI shows honest tok/s and token counts WHILE the model is generating.
     */
    private suspend fun collectStream(
        m: LlamaModel,
        prompt: String,
        nativeConfig: LlamaConfig,
        stopSequences: List<String>,
        onToken: (String) -> Unit
    ): StreamResult {
        val startNanos = System.nanoTime()
        val sb = StringBuilder()
        var tokens = 0
        var lastLiveUpdateMs = 0L
        val collectBlock: suspend (String) -> Unit = { token ->
            sb.append(token)
            tokens++
            onToken(token)
            // Refresh live telemetry ~4x per second so the streaming UI shows
            // real tok/s and token counts instead of zeros until the end.
            val now = System.currentTimeMillis()
            if (now - lastLiveUpdateMs >= 250) {
                lastLiveUpdateMs = now
                val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
                _performance.value = _performance.value.copy(
                    tokensPerSecond = if (elapsed > 0f) tokens / elapsed else 0f,
                    tokensGenerated = tokens,
                    generationActive = true
                )
            }
        }
        if (stopSequences.isEmpty()) {
            withContext(Dispatchers.IO) {
                m.generateStream(prompt, nativeConfig).collect { token ->
                    collectBlock(token)
                }
            }
            return StreamResult(sb.toString(), tokens)
        }
        val maxStopLen = stopSequences.maxOf { it.length }
        val window = maxStopLen + 64
        withContext(Dispatchers.IO) {
            m.generateStream(prompt, nativeConfig).collect { token ->
                collectBlock(token)
                // Only inspect the recent tail — cheap regardless of reply length.
                val tail = if (sb.length > window) sb.substring(sb.length - window) else sb.toString()
                if (stopSequences.any { tail.contains(it) }) {
                    m.cancelGeneration()
                }
            }
        }
        return StreamResult(sb.toString(), tokens)
    }

    /** The raw streamed text plus the REAL number of tokens the native engine
     *  emitted (each stream emission is one token). */
    private data class StreamResult(val text: String, val tokens: Int)

    /** Cuts the reply at the earliest template stop sequence. */
    private fun truncateAtStop(raw: String, stopSequences: List<String>): String {
        if (stopSequences.isEmpty()) return raw
        var first = -1
        for (s in stopSequences) {
            val idx = raw.indexOf(s)
            if (idx >= 0 && (first < 0 || idx < first)) first = idx
        }
        if (first < 0) return raw
        return raw.substring(0, first).trimEnd()
    }

    override fun cancelGeneration() {
        // Real native cancellation: the engine checks the cancel flag between
        // generated tokens and stops promptly. The in-flight coroutine then
        // sees `cancelled` and discards the partial result cleanly.
        cancelled.set(true)
        val m = synchronized(modelAccessLock) { model }
        m?.cancelGeneration()
    }

    override fun clearCancellation() {
        cancelled.set(false)
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
        seed = if (config.randomSeed == 0) -1 else config.randomSeed,
        stopSequences = config.stopSequences
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

    private fun updatePerformance(result: String, startNanos: Long, prompt: String, tokensGenerated: Int) {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
        val tps = if (elapsed > 0f) tokensGenerated / elapsed else 0f
        val promptTokens = com.aichathub.app.util.TokenEstimator.estimate(prompt)
        _performance.value = _performance.value.copy(
            tokensPerSecond = tps,
            tokensGenerated = tokensGenerated,
            contextUsed = (promptTokens + tokensGenerated),
            contextTokensMax = reloadContextLength.coerceAtLeast(512)
        )
    }
}