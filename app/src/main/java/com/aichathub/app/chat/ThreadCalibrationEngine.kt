package com.aichathub.app.chat

import android.util.Log
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.device.ThermalManager
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.util.ThreadCalibration
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Runs a REAL, bounded thread-count calibration on the loaded device:
 * loads the model once per candidate thread count, runs a warm-up
 * generation (excluded from results), then a fixed number of timed
 * generations, and persists the MEDIAN-throughput winner.
 *
 * Honest guarantees:
 *  - Thread counts are applied at LOAD time — the native binding fixes
 *    llama.cpp context threads at context creation (`LlamaConfig.threads`),
 *    so per-candidate measurement RELOADS the model rather than assuming a
 *    live-reconfigurable thread count.
 *  - Bounded by wall-clock budget, thermal state and coroutine cancellation;
 *    every exit path unloads the model, so native resources are never left
 *    behind (spec §3.15).
 *  - Short-circuits under CRITICAL thermal state and stops further loads
 *    under HOT — calibration never fights the device for thermals
 *    (spec §3.8). INCOMPLETE results are never persisted: a cut-short run
 *    must not overwrite a previously valid record (spec §5.8), and
 *    [ThreadCalibration.resolveThreadCount] would refuse it anyway — the
 *    incomplete record is returned to the caller for display only.
 *  - Every candidate loads the VALIDATED adaptive context passed by the
 *    caller ([loadContextLength]) — the calibration never raises the context
 *    back to the model maximum behind the safety policy's back (spec §7).
 *  - Measured tokens/sec is the DECODE window only (first token → end of the
 *    timed generation; prefill excluded — documented), computed from the real
 *    generated-token count; zero/invalid windows are discarded, never guessed.
 *  - The prompt and generation settings are IDENTICAL for every candidate.
 *  - The winner is selected by median tokens-per-second, so a single
 *    unusually fast trial cannot decide (spec §3.7).
 *
 * Consumers: results are persisted keyed by
 * [ThreadCalibration.cacheKey]; the load path
 * ([com.aichathub.app.chat.ChatGenerationHelper]) resolves the cached record
 * via [ThreadCalibration.resolveThreadCount] and passes the chosen thread
 * count into the real native load configuration (spec §3.12/§3.13).
 */
@Singleton
class ThreadCalibrationEngine @Inject constructor(
    private val runtime: InferenceRuntime,
    private val settingsRepository: SettingsRepository,
    private val thermalManager: ThermalManager
) {

    companion object {
        private const val TAG = "ThreadCalibration"

        /** Identical prompt for every candidate (comparability requirement). */
        private const val CALIBRATION_PROMPT = "Explain in two sentences what a CPU does."

        /** Default wall-clock budget for the whole calibration. */
        const val DEFAULT_MAX_DURATION_MS = 90_000L
    }

    /** Real-time progress for status display (no fabricated values). */
    data class Progress(
        val finishedCandidates: Int,
        val totalCandidates: Int,
        val currentThreads: Int?
    )

    /**
     * Runs the calibration over the candidate thread counts.
     *
     * @param contextLength the model's CATALOG context — ONLY the cache-key
     *   component. It MUST match the key [ChatGenerationHelper] computes when
     *   consuming the record later, or the calibration result could never be
     *   found again.
     * @param loadContextLength the ACTUAL native context loaded for every
     *   candidate — the validated adaptive context from the safe load plan,
     *   never silently the model maximum (spec §7.1/§7.2).
     * @return the record (persisted ONLY when complete, see §5.8), or null
     *   when nothing measurable was produced (thermal abort with no samples,
     *   unreadable file, …).
     * @throws CancellationException when the caller is cancelled — native
     *   resources are still released in the finally block.
     */
    suspend fun calibrate(
        model: CatalogModel,
        file: File,
        contextLength: Int,
        loadContextLength: Int,
        quantization: String,
        deviceHardware: String,
        runtimeVersion: String,
        cpuCores: Int,
        suggestedThreads: Int,
        config: GenerationConfig,
        maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
        warmupTokens: Int = 32,
        measuredTokens: Int = 96,
        trialsPerCandidate: Int = 2,
        onProgress: (Progress) -> Unit = {}
    ): ThreadCalibration.CalibrationRecord? {
        // Thermal gate: never start under heavy thermal load.
        val startThermal = thermalManager.getThermalInfo(suggestedThreads).state
        if (startThermal == ThermalManager.ThermalState.CRITICAL) {
            Log.d(TAG, "Calibration skipped: device thermally critical")
            return null
        }
        if (!file.isFile || file.length() == 0L) {
            Log.d(TAG, "Calibration skipped: model file missing or empty")
            return null
        }

        val candidates = ThreadCalibration.candidates(cpuCores)
        val samples = LinkedHashMap<Int, MutableList<ThreadCalibration.CalibrationSample>>()
        val deadline = System.currentTimeMillis() + maxDurationMs
        var completedCandidates = 0
        var ambiguousFailure = false

        try {
            for (candidate in candidates) {
                if (System.currentTimeMillis() > deadline) break
                // Thermal gate on every candidate: stop under CRITICAL load.
                if (thermalManager.getThermalInfo(suggestedThreads).state ==
                    ThermalManager.ThermalState.CRITICAL
                ) {
                    Log.d(TAG, "Calibration stopped early: thermally critical at $candidate threads")
                    break
                }
                onProgress(Progress(completedCandidates, candidates.size, candidate))

                runtime.clearCancellation()
                // Load under the REAL model id so the measured-memory callback
                // attributes the PSS delta to the actual model, never to a
                // synthetic calibration id. Context is the VALIDATED adaptive
                // value from the load plan — same policy as chat/benchmark.
                runtime.load(model.id, file, loadContextLength, config, candidate)

                // Warm-up run — EXCLUDED from all measurements.
                runtime.generateStreaming(
                    prompt = CALIBRATION_PROMPT,
                    config = config.copy(maxTokens = warmupTokens),
                    onToken = {},
                )

                val candidateSamples = samples.getOrPut(candidate) { mutableListOf() }
                var trialsCompleted = 0
                while (trialsCompleted < trialsPerCandidate) {
                    if (System.currentTimeMillis() > deadline) break
                    val window = measureGeneration(measuredTokens, config)
                    if (window != null) {
                        candidateSamples.add(window.toSample(candidate))
                        trialsCompleted++
                    } else {
                        // Failed/cancelled/zero-token run — do NOT count it as
                        // a sample; stop this candidate so one broken trial
                        // cannot loop forever against the time budget.
                        ambiguousFailure = true
                        break
                    }
                }
                if (candidateSamples.isNotEmpty()) completedCandidates++
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Calibration cancelled")
            throw e
        } catch (e: Exception) {
            // A failing candidate measurement must not break the whole flow;
            // whatever samples exist still decide the winner (or nothing).
            Log.w(TAG, "Calibration interrupted: ${e.message}")
        } finally {
            // Release native resources no matter how we exit (success, early
            // stop, cancellation or failure).
            runCatching { runtime.cancelGeneration() }
            runCatching { runtime.unload() }
        }

        val winner = ThreadCalibration.best(samples)
        val winnerTps = winner?.let { ThreadCalibration.medianTps(samples[it].orEmpty()) }
        if (winner == null || winnerTps == null) {
            Log.d(TAG, "Calibration produced no valid measurements — no record saved")
            return null
        }

        val complete = completedCandidates == candidates.size && !ambiguousFailure
        val record = ThreadCalibration.CalibrationRecord(
            threads = winner,
            tokensPerSecond = winnerTps,
            timestampMs = System.currentTimeMillis(),
            trialsPerCandidate = trialsPerCandidate,
            complete = complete,
        )
        val key = ThreadCalibration.cacheKey(
            deviceHardware = deviceHardware,
            runtimeVersion = runtimeVersion,
            modelId = model.id,
            contextLength = contextLength,
            quantization = quantization,
        )
        if (!complete) {
            // Spec §5.8: a failed/interrupted calibration must not overwrite a
            // previously valid result with an unusable record. The resolver
            // would reject an incomplete record anyway, so there is no reason
            // to risk clobbering the last valid one.
            Log.w(
                TAG,
                "Thread calibration INCOMPLETE (winner=$winner threads would have been chosen) — " +
                    "record NOT saved; the previous valid record (if any) is kept. key=$key",
            )
            return record
        }
        settingsRepository.setThreadCalibration(key, record)
        Log.i(
            TAG,
            "Thread calibration saved: $winner threads ($winnerTps tok/s decode) " +
                "key=$key complete=$complete",
        )
        return record
    }

    /**
     * One controlled, timed generation. Returns (totalMs, ttftMs, tokens) or
     * null for failed/cancelled/zero-token runs.
     */
    private suspend fun measureGeneration(maxTokens: Int, config: GenerationConfig): Triple<Long, Long, Int>? {
        return try {
            val start = System.nanoTime()
            var ttftMs: Long? = null
            runtime.generateStreaming(
                prompt = CALIBRATION_PROMPT,
                config = config.copy(maxTokens = maxTokens),
                onToken = { if (ttftMs == null) ttftMs = (System.nanoTime() - start) / 1_000_000 },
            )
            val totalMs = (System.nanoTime() - start) / 1_000_000
            val tokens = runtime.performance.value.tokensGenerated
            val ttft = ttftMs
            if (ttft == null || tokens <= 0) null else Triple(totalMs, ttft, tokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Measured generation failed: ${e.message}")
            null
        }
    }

    private fun Triple<Long, Long, Int>.toSample(threads: Int): ThreadCalibration.CalibrationSample {
        val (totalMs, ttftMs, tokens) = this
        val decodeMs = (totalMs - ttftMs).coerceAtLeast(1)
        return ThreadCalibration.CalibrationSample(
            threads = threads,
            tokensPerSecond = (tokens - 1) / (decodeMs / 1000f),
            ttftMs = ttftMs,
        )
    }
}
