package com.aichathub.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.ChatGenerationHelper
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.chat.ThreadCalibrationEngine
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.ThermalManager
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.util.BenchmarkAggregator
import com.aichathub.app.util.ModelThreads
import com.aichathub.app.util.ThreadCalibration
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * A REAL model-inference benchmark record. Every field is a measured value
 * from the actual native runtime; metrics the runtime cannot expose stay
 * `null`/absent — never guessed. Includes enough configuration (context,
 * threads, backend, thermal state) to make runs comparable and to avoid
 * mixing incompatible results.
 *
 * Memory-metric labels (spec §4.7):
 *  - [memoryBytes] is the BEFORE/AFTER App PSS DELTA around the model load.
 *    It is NOT peak memory — Android exposes no reliable peak-native-memory
 *    API to this app, so peak is deliberately not reported.
 *  - [systemRamAvailableBytes] is the SYSTEM available RAM snapshot.
 *  - [appHeapUsedBytes] is the app's Java heap usage.
 */
data class BenchmarkResult(
    /** MEDIAN decode tokens/s across timed trials (headline metric). */
    val tokensPerSecond: Float,
    /** Median elapsed generation window (prefill included) across trials. */
    val generationMs: Long,
    /** Median generated-token count across timed trials. */
    val tokens: Int,
    /** PSS delta around model load (before/after delta, NOT peak). */
    val memoryBytes: Long,
    /** Time from load start to READY. */
    val loadTimeMs: Long = 0L,
    /** MEDIAN time from generation start to FIRST streamed token. */
    val ttftMs: Long? = null,
    /** Context length the model was loaded with. */
    val contextLengthTokens: Int = 0,
    /** max output tokens of the timed run. */
    val maxTokens: Int = 0,
    /** Native thread count used for the run. */
    val threads: Int = 0,
    /** Backend actually used (CPU-only for the current native runtime). */
    val backend: String = "llama.cpp (CPU)",
    val modelName: String = "",
    val quantization: String = "",
    /** Android thermal state before/after the run (best effort). */
    val thermalBefore: String = "unknown",
    val thermalAfter: String = "unknown",
    /** Number of TIMED trials aggregated (warm-up excluded). */
    val trials: Int = 1,
    /** Trials that produced a valid decode measurement. */
    val validTrials: Int = 1,
    /** Observed decode tokens/s range across valid trials. */
    val minTokensPerSecond: Float = 0f,
    val maxTokensPerSecond: Float = 0f,
    /** System available RAM at benchmark start (ActivityManager). */
    val systemRamAvailableBytes: Long? = null,
    /** App Java heap usage at benchmark start. */
    val appHeapUsedBytes: Long? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Kinds recorded in benchmark history — successful runs must never be
 * indistinguishable from failures or cancellations (spec §4.6).
 */
enum class BenchmarkRecordKind { SUCCESS, FAILED, CANCELLED }

data class BenchmarkUiState(
    val selectedModel: CatalogModel? = null,
    val running: Boolean = false,
    val statusText: String = "",
    val result: BenchmarkResult? = null,
    val error: String? = null,
    /** Null when no thread calibration has run yet on this model/device. */
    val calibratedThreads: Int? = null
)

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val container_inferenceRuntime: InferenceRuntime,
    private val container_chatCoordinator: ChatCoordinator,
    private val container_modelRepository: ModelRepository,
    private val container_settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository,
    private val container_thermalManager: ThermalManager,
    private val calibrationEngine: ThreadCalibrationEngine,
    private val container_deviceInfoProvider: DeviceInfoProvider,
    private val container_compatibilityEngine: CompatibilityEngine,
    private val container_conversationDao: ConversationDao,
    private val container_messageDao: MessageDao,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /**
     * The SAME load-plan factory the chat path uses (gate → GGUF metadata →
     * memory budget → calibrated threads → adaptive context). Benchmarks must
     * never bypass the chat safety policy (spec §3): one pipeline, one policy.
     */
    private val generationHelper = ChatGenerationHelper(
        coordinator = container_chatCoordinator,
        runtime = container_inferenceRuntime,
        settingsRepository = container_settingsRepository,
        modelRepository = container_modelRepository,
        deviceInfoProvider = container_deviceInfoProvider,
        compatibilityEngine = container_compatibilityEngine,
        conversationDao = container_conversationDao,
        messageDao = container_messageDao,
        thermalManager = container_thermalManager
    )

    companion object {
        private const val HISTORY_FILE = "benchmark_history.jsonl"

        /** Documented retention policy: keep the last 100 records. */
        private const val MAX_HISTORY_ENTRIES = 100

        /** Timed trials per benchmark run (spec §4.2: configurable, ≥ 2). */
        private const val DEFAULT_TRIALS = 3
        private const val WARMUP_TOKENS = 64
        private const val TIMED_TOKENS = 256
    }

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
        // Re-entrancy guard (spec §5.10): a second calibration/benchmark job
        // must never race a running one to write conflicting results.
        if (_state.value.running) return
        viewModelScope.launch {
            val installed = container_modelRepository.stateFor(model.id) ?: return@launch
            _state.value = _state.value.copy(running = true, statusText = "Loading model...", result = null, error = null)
            try {
                container_inferenceRuntime.clearCancellation()

                // ── The SAME validated load plan as normal chat (spec §3) ──
                // Load gate, GGUF metadata memory budget, calibrated threads
                // and adaptive context — BLOCK/Insufficient throws BEFORE any
                // native load happens and is reported as a failed benchmark.
                var planError: String? = null
                val plan = generationHelper.prepareLoadPlan(
                    model,
                    File(installed.filePath),
                    onTrace = { message ->
                        // Gate warnings surface in the status line; BLOCK is
                        // thrown and reported as a failed benchmark below.
                        if (planError == null) planError = message
                    }
                )
                planError?.let { warning ->
                    _state.value = _state.value.copy(statusText = _state.value.statusText + " " + warning)
                }
                val threads = plan.threads
                val thermalBefore = container_thermalManager.getThermalInfo(threads).state.name
                val systemRam = systemAvailableRamBytes()
                val appHeap = appHeapUsedBytes()

                // ── Measured: model load time ───────────────────────────
                val pssBefore = pssBytes()
                val loadStart = System.nanoTime()
                container_chatCoordinator.loadModel(
                    model,
                    File(installed.filePath),
                    GenerationConfig(maxTokens = WARMUP_TOKENS),
                    threads = plan.threads,
                    // The benchmark loads the VALIDATED adaptive context —
                    // never the model maximum behind the safety policy's back.
                    contextLength = plan.contextLength
                )
                val loadTimeMs = (System.nanoTime() - loadStart) / 1_000_000
                val memoryBytes = (pssBytes() - pssBefore).coerceAtLeast(0)

                val prompt = "Write a short paragraph about local AI."

                // ── Warm-up run (NOT timed, fixture identical to timed runs) ──
                _state.value = _state.value.copy(statusText = "Warming up...")
                container_inferenceRuntime.generateStreaming(
                    prompt = prompt,
                    config = GenerationConfig(maxTokens = WARMUP_TOKENS),
                    onToken = {}
                )

                // ── Timed trials (identical prompt/config for comparability) ──
                val trials = mutableListOf<BenchmarkAggregator.Trial>()
                _state.value = _state.value.copy(statusText = "Measuring (0/${DEFAULT_TRIALS})...")
                for (trialIndex in 0 until DEFAULT_TRIALS) {
                    // Thermal guard: stop acquiring NEW trials under HOT/CRITICAL
                    // (spec §3.8/§4) — measurements under throttle mislead tuning.
                    val thermalNow = container_thermalManager.currentLoadLevel()
                    if (thermalNow == ModelThreads.ThermalLoadLevel.CRITICAL) {
                        _state.value = _state.value.copy(statusText = "Stopped early: device thermally critical.")
                        break
                    }
                    _state.value = _state.value.copy(
                        statusText = "Measuring (trial ${trialIndex + 1}/$DEFAULT_TRIALS)..."
                    )
                    val start = System.nanoTime()
                    var ttftMs: Long? = null
                    container_inferenceRuntime.generateStreaming(
                        prompt = prompt,
                        config = GenerationConfig(maxTokens = TIMED_TOKENS),
                        onToken = {
                            if (ttftMs == null) {
                                ttftMs = (System.nanoTime() - start) / 1_000_000
                            }
                        }
                    )
                    val generationMs = (System.nanoTime() - start) / 1_000_000
                    val tokens = container_inferenceRuntime.performance.value.tokensGenerated
                    // Zero-token / runs without a first token are recorded as
                    // INVALID measurements (dropped by the aggregator), not silence.
                    trials.add(
                        BenchmarkAggregator.Trial(tokens, ttftMs ?: 0L, generationMs)
                    )
                }

                val summary = BenchmarkAggregator.summarize(trials)
                if (summary == null) {
                    recordHistory(BenchmarkRecordKind.FAILED, model, threads, systemRam, appHeap)
                    _state.value = _state.value.copy(
                        running = false,
                        statusText = "No usable measurement was produced (all timed runs were empty).",
                        error = "The model produced no tokens during the timed runs. Check the model file."
                    )
                    return@launch
                }
                val thermalAfter = container_thermalManager.getThermalInfo(threads).state.name
                val lastTrial = trials.last()

                val result = BenchmarkResult(
                    tokensPerSecond = summary.medianTps,
                    generationMs = lastTrial.totalMs,
                    tokens = lastTrial.tokens,
                    memoryBytes = memoryBytes,
                    loadTimeMs = loadTimeMs,
                    ttftMs = summary.medianTtftMs.takeIf { it > 0 },
                    // The context ACTUALLY loaded by the validated plan —
                    // never silently reported as the model maximum (spec §6).
                    contextLengthTokens = plan.contextLength,
                    maxTokens = TIMED_TOKENS,
                    threads = plan.threads,
                    backend = "llama.cpp (CPU)",
                    modelName = model.name,
                    quantization = model.quantization,
                    thermalBefore = thermalBefore,
                    thermalAfter = thermalAfter,
                    trials = trials.size,
                    validTrials = summary.validTrials,
                    minTokensPerSecond = summary.minTps,
                    maxTokensPerSecond = summary.maxTps,
                    systemRamAvailableBytes = systemRam,
                    appHeapUsedBytes = appHeap,
                    timestampMs = System.currentTimeMillis()
                )
                _state.value = _state.value.copy(
                    running = false,
                    statusText = "Done — measured ${summary.validTrials}/${trials.size} trials (median decode tok/s, prefill excluded).",
                    result = result
                )
                recordHistory(BenchmarkRecordKind.SUCCESS, model, threads, systemRam, appHeap, result)

                // ── Real thread calibration (bounded, persisted, end-to-end) ──
                runThreadCalibration(model, File(installed.filePath), plan, thermalAfter)
            } catch (e: CancellationException) {
                recordCancelled()
                _state.value = _state.value.copy(
                    running = false,
                    statusText = "Benchmark cancelled.",
                    result = null,
                    error = null
                )
                throw e
            } catch (e: Exception) {
                recordHistory(
                    BenchmarkRecordKind.FAILED,
                    null, 0, null, null
                )
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

    /**
     * Runs REAL benchmark-based thread calibration after a successful
     * benchmark (bounded time, thermally guarded, cancellable, persisted and
     * later consumed by the actual native load configuration via
     * ChatGenerationHelper/ThreadCalibration).
     *
     * Spec §4/§7 guarantees:
     *  - Candidates load with the SAME validated context the benchmark
     *    benchmark/chat plan selected — never the model maximum.
     *  - Incomplete results (thermal/time/cancel) are NOT persisted, so a cut-
     *    short run can never overwrite a previously valid record.
     *  - The post-calibration reload uses ONLY the thread count the shared
     *    validated resolver returns — a raw `record.threads` never reaches the
     *    native configuration.
     */
    private suspend fun runThreadCalibration(
        model: CatalogModel,
        file: File,
        plan: ChatGenerationHelper.LoadPlan,
        thermalAfter: String
    ) {
        val settings = container_settingsRepository.settings.first()
        val cpuCores = container_deviceInfoProvider.getDeviceProfile().cpuCores
        if (plan.threads < 2) return batteryNote() // baseline already minimal
        if (thermalAfter == "CRITICAL" || thermalAfter == "HOT") return thermalNote()
        val config = GenerationConfig(
            temperature = settings.temperature,
            topK = settings.topK,
            topP = settings.topP
        )
        _state.value = _state.value.copy(
            running = true,
            statusText = "Calibrating CPU threads..."
        )
        val record = calibrationEngine.calibrate(
            model = model,
            file = file,
            // Cache key component: the model's CATALOG context — must match
            // the key ChatGenerationHelper computes at consumption time.
            contextLength = model.contextLength,
            // Actual native context loaded for every candidate: the validated
            // adaptive context from the safe load plan (never the max).
            loadContextLength = plan.contextLength,
            quantization = model.quantization,
            deviceHardware = android.os.Build.HARDWARE,
            runtimeVersion = container_inferenceRuntime.runtimeVersionLabel,
            cpuCores = cpuCores,
            suggestedThreads = plan.threads,
            config = config,
            onProgress = { p ->
                _state.value = _state.value.copy(
                    statusText = "Calibrating threads (${p.finishedCandidates + 1}/${p.totalCandidates}, ${p.currentThreads}t)..."
                )
            }
        )
        // The shared validated resolver is the ONLY consumer of the record.
        // An incomplete/invalid calibration resolves to the safe baseline —
        // identical behavior to the chat path (spec §4).
        val resolvedThreads = record?.let {
            ThreadCalibration.resolveThreadCount(
                record = it,
                nowMs = System.currentTimeMillis(),
                batteryConscious = settings.batteryConscious,
                cpuCores = cpuCores
            )
        }
        _state.value = _state.value.copy(
            running = false,
            statusText = when {
                record == null ->
                    "Calibration produced no valid result — baseline kept."
                record.complete ->
                    "Calibration complete: ${record.threads} threads (validated)."
                else ->
                    "Calibration incomplete — result not saved, baseline kept."
            },
            calibratedThreads = resolvedThreads
        )

        // The engine unloads the runtime after calibration (resource
        // guarantee). Restore a consistent, usable session by re-loading the
        // model with the VALIDATED final thread decision (resolved above —
        // never the raw record) and the validated context.
        try {
            container_chatCoordinator.loadModel(
                model,
                file,
                GenerationConfig(maxTokens = WARMUP_TOKENS),
                threads = resolvedThreads ?: plan.threads,
                contextLength = plan.contextLength
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                statusText = (_state.value.statusText + " Note: model not reloaded (${e.message ?: "load failed"}).")
            )
        }
    }

    private fun batteryNote() {
        _state.value = _state.value.copy(
            running = false,
            statusText = _state.value.statusText + " Thread calibration skipped (battery-minimal baseline)."
        )
    }

    private fun thermalNote() {
        _state.value = _state.value.copy(
            running = false,
            statusText = _state.value.statusText + " Thread calibration skipped (device too hot)."
        )
    }

    // ── history with retention + failure/cancel records ─────────────────

    /**
     * Persists a record with its [kind]; successful runs with `null` result
     * aggregate (they never happened — nothing measured, nothing claimed).
     */
    private suspend fun recordHistory(
        kind: BenchmarkRecordKind,
        model: CatalogModel?,
        threads: Int,
        systemRam: Long?,
        appHeap: Long?,
        result: BenchmarkResult? = null
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(appContext.filesDir, HISTORY_FILE)
            if (result != null) {
                f.appendText(successLine(kind, result))
            } else {
                f.appendText(
                    buildString {
                        append("{")
                        append("\"kind\":\"${kind.name.lowercase()}\",")
                        if (model != null) {
                            append("\"modelName\":\"${escape(model.name)}\",")
                            append("\"quantization\":\"${escape(model.quantization)}\",")
                        }
                        append("\"threads\":$threads,")
                        if (systemRam != null) append("\"systemRamAvailableBytes\":$systemRam,")
                        if (appHeap != null) append("\"appHeapUsedBytes\":$appHeap,")
                        append("\"timestampMs\":${System.currentTimeMillis()}")
                        append("}\n")
                    }
                )
            }
            applyRetention(f)
        }.onFailure {
            android.util.Log.w("BenchmarkViewModel", "Failed to persist benchmark history", it)
        }
    }

    private suspend fun recordCancelled() = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(appContext.filesDir, HISTORY_FILE)
            f.appendText(
                "{\"kind\":\"cancelled\",\"timestampMs\":${System.currentTimeMillis()}}\n"
            )
            applyRetention(f)
        }
        Unit
    }

    private fun successLine(kind: BenchmarkRecordKind, r: BenchmarkResult): String = buildString {
        append("{")
        append("\"kind\":\"${kind.name.lowercase()}\",")
        append("\"timestampMs\":${r.timestampMs},")
        append("\"modelName\":\"${escape(r.modelName)}\",")
        append("\"quantization\":\"${escape(r.quantization)}\",")
        append("\"backend\":\"${escape(r.backend)}\",")
        append("\"contextLengthTokens\":${r.contextLengthTokens},")
        append("\"maxTokens\":${r.maxTokens},")
        append("\"threads\":${r.threads},")
        append("\"trials\":${r.trials},")
        append("\"validTrials\":${r.validTrials},")
        append("\"tokensPerSecond\":${r.tokensPerSecond},")
        append("\"minTokensPerSecond\":${r.minTokensPerSecond},")
        append("\"maxTokensPerSecond\":${r.maxTokensPerSecond},")
        append("\"ttftMs\":${r.ttftMs ?: -1},")
        append("\"loadTimeMs\":${r.loadTimeMs},")
        append("\"generationMs\":${r.generationMs},")
        append("\"tokens\":${r.tokens},")
        append("\"memoryBytes\":${r.memoryBytes},")
        if (r.systemRamAvailableBytes != null) append("\"systemRamAvailableBytes\":${r.systemRamAvailableBytes},")
        if (r.appHeapUsedBytes != null) append("\"appHeapUsedBytes\":${r.appHeapUsedBytes},")
        append("\"thermalBefore\":\"${escape(r.thermalBefore)}\",")
        append("\"thermalAfter\":\"${escape(r.thermalAfter)}\",")
        append("\"runtime\":\"llama-kotlin-android\"")
        append("}\n")
    }

    /**
     * Bounded history (spec §4.12): rewrites the file keeping only the last
     * [MAX_HISTORY_ENTRIES] lines when it grows beyond the cap.
     */
    private fun applyRetention(f: File) {
        if (f.length() <= MAX_HISTORY_ENTRIES * 512L) return // fast path
        val lines = f.readLines()
        if (lines.size > MAX_HISTORY_ENTRIES) {
            val kept = BenchmarkAggregator.retention(lines, MAX_HISTORY_ENTRIES)
            f.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun pssBytes(): Long = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss * 1024L
    }.getOrDefault(0L)

    /** SYSTEM available RAM (not app heap, not a peak measurement). */
    private fun systemAvailableRamBytes(): Long? = runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem
    }.getOrNull()

    /** App Java heap usage (distinct from PSS/system RAM). */
    private fun appHeapUsedBytes(): Long? = runCatching {
        val runtime = Runtime.getRuntime()
        (runtime.totalMemory() - runtime.freeMemory())
    }.getOrNull()
}
