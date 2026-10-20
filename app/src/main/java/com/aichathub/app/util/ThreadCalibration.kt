package com.aichathub.app.util

/**
 * Pure logic for REAL benchmark-based CPU thread calibration.
 *
 * Design:
 *  - Candidates are the thread counts the runtime actually accepts and that
 *    the device could plausibly run: 1..[ModelThreads.MAX_THREADS] capped by
 *    (cores − 1), with at least one candidate.
 *  - Each candidate is measured with [opsPerSecond] samples; the winner is
 *    chosen by MEDIAN throughput so one unusually fast trial (spec: "avoid
 *    selecting solely because of one unusually fast trial") cannot decide.
 *  - Ties break toward FEWER threads (battery/thermal friendly).
 *  - Results are cached under a key covering everything that materially
 *    changes the optimum: device model hardware, runtime version, model id,
 *    context length and quantization. A different key means "stale" — the
 *    baseline is used until a new calibration runs.
 *
 * No Android dependencies — fully unit-testable. Persistence uses the
 * same single-string DataStore pattern as [MeasuredMemory].
 */
object ThreadCalibration {

    /** Supported thread-count window (same clamp as the runtime policy). */
    const val MIN_CANDIDATE = 1
    const val MAX_CANDIDATE = ModelThreads.MAX_THREADS

    /** Cached results older than this are not trusted. */
    const val VALIDITY_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * Minimum number of valid timed trials per candidate a record must
     * declare before the resolver will trust it (spec §4.6: "The trial count
     * satisfies the minimum calibration requirement").
     */
    const val MIN_TRIALS_PER_CANDIDATE = 1

    data class CalibrationSample(val threads: Int, val tokensPerSecond: Float, val ttftMs: Long? = null)

    data class CalibrationRecord(
        val threads: Int,
        val tokensPerSecond: Float,
        val timestampMs: Long,
        val trialsPerCandidate: Int,
        // false when the calibration was cut short (thermal/time/cancel)
        val complete: Boolean
    )

    /** Thread counts to compare, ascending, within the runtime's clamps. */
    fun candidates(cpuCores: Int): List<Int> {
        val cores = cpuCores.coerceAtLeast(1)
        val upper = minOf(MAX_CANDIDATE, (cores - 1).coerceAtLeast(1))
        return (MIN_CANDIDATE..upper).toList()
    }

    /**
     * Median tokens-per-second achieved by a candidate's sample list.
     * Non-positive / NaN / infinite samples are rejected; complete garbage
     * yields null (the candidate cannot win on nothing).
     */
    fun medianTps(samples: List<CalibrationSample>): Float? {
        val valid = samples
            .map { it.tokensPerSecond }
            .filter { it.isFinite() && it > 0f }
        if (valid.isEmpty()) return null
        val sorted = valid.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    /**
     * Picks the winning thread count from samples per candidate.
     * null when no candidate produced a valid measurement.
     */
    fun best(samplesByThreads: Map<Int, List<CalibrationSample>>): Int? {
        var winner: Int? = null
        var winnerTps = Float.MIN_VALUE
        // Deterministic order: ties always resolve to the fewer-thread candidate.
        for (threads in samplesByThreads.keys.sorted()) {
            val median = medianTps(samplesByThreads[threads].orEmpty()) ?: continue
            if (median > winnerTps) {
                winner = threads
                winnerTps = median
            }
        }
        return winner
    }

    /**
     * The persisted cache key. Two runs with the same key are comparable;
     * any changed component invalidates the cached optimum.
     */
    fun cacheKey(
        deviceHardware: String,
        runtimeVersion: String,
        modelId: String,
        contextLength: Int,
        quantization: String
    ): String = sanitize(deviceHardware) +
        "|" + sanitize(runtimeVersion) +
        "|" + sanitize(modelId) +
        "|" + contextLength +
        "|" + sanitize(quantization)

    /** Keeps the cache key safe as a serialized identifier (no separators or special chars). */
    private fun sanitize(part: String): String = part.replace(Regex("[^A-Za-z0-9._/+-]"), "_")

    /**
     * THE single validation + resolution point for calibration records.
     * Every consumer (chat load plan, benchmark post-calibration reload)
     * MUST resolve its thread count through this function — a raw
     * `record.threads` must never reach the native configuration.
     *
     * A record is rejected (→ baseline) unless ALL hold:
     *  1. marked complete (never cancelled/cut short),
     *  2. not expired ([VALIDITY_MS]),
     *  3. thread count within the runtime's candidate window AND supported
     *     by the current core count (`threads ≤ cores − 1`, same cap the
     *     candidate factory uses),
     *  4. measured throughput is finite and strictly positive (NaN,
     *     infinite, zero and negative throughputs are never trusted),
     *  5. the declared trial count meets [MIN_TRIALS_PER_CANDIDATE].
     *
     * Cache-key matching (device/model/runtime) happens BEFORE this function:
     * callers look the record up by [cacheKey], so a record created for a
     * different device, model or runtime is simply not found → baseline.
     *
     * A calibration result NEVER overrides thermal or memory-pressure
     * reductions — those are applied by the caller on top of this value.
     */
    fun resolveThreadCount(record: CalibrationRecord?, nowMs: Long, batteryConscious: Boolean, cpuCores: Int): Int {
        val baseline = ModelThreads.recommended(batteryConscious, cpuCores)
        if (record == null) return baseline
        if (!record.complete) return baseline
        if (nowMs - record.timestampMs > VALIDITY_MS) return baseline
        if (record.threads !in MIN_CANDIDATE..MAX_CANDIDATE) return baseline
        // Must be a candidate the CURRENT core count supports (same cap as
        // [candidates]: at most cores − 1); covers exotic records written by
        // a device state with more cores visible.
        if (record.threads > (cpuCores - 1).coerceAtLeast(1)) return baseline
        if (!record.tokensPerSecond.isFinite() || record.tokensPerSecond <= 0f) return baseline
        if (record.trialsPerCandidate < MIN_TRIALS_PER_CANDIDATE) return baseline
        return record.threads
    }

    // ── persistence format: "key@record;key@record" ──────────────────────
    // key already contains no ';' or '@' (built from pipe-joined fields that
    // are sanitized at write time); record fields are numeric + flags joined
    // by ','.

    fun encode(map: Map<String, CalibrationRecord>): String = map.entries.joinToString(";") { (key, r) ->
        sanitizeKey(key) + "@" + listOf(
            r.threads.toString(),
            r.tokensPerSecond.toString(),
            r.timestampMs.toString(),
            r.trialsPerCandidate.toString(),
            if (r.complete) "1" else "0",
        ).joinToString(",")
    }

    /**
     * Persisted-record READ validation (spec §5): malformed, corrupted or
     * nonsensical entries are dropped at the trust boundary instead of
     * reaching the resolver (or crashing). completeness/freshness are
     * semantic checks enforced by [resolveThreadCount]; here only records
     * that are structurally impossible are rejected (non-finite or
     * non-positive throughput; non-positive threads/timestamp/trials).
     */
    fun decode(raw: String?): Map<String, CalibrationRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, CalibrationRecord>()
        raw.split(";").forEach { entry ->
            if (entry.isBlank()) return@forEach
            val at = entry.lastIndexOf('@')
            if (at <= 0) return@forEach
            val key = entry.substring(0, at)
            val parts = entry.substring(at + 1).split(',')
            if (parts.size != 5) return@forEach
            val threads = parts[0].toIntOrNull() ?: return@forEach
            val tps = parts[1].toFloatOrNull() ?: return@forEach
            val ts = parts[2].toLongOrNull() ?: return@forEach
            val trials = parts[3].toIntOrNull() ?: return@forEach
            val complete = parts[4] == "1"
            // Structural sanity: garbage numbers can never describe a valid
            // calibration. Reject early — the resolver would reject them
            // anyway, but keeping them out of the map keeps every consumer
            // (not just the resolver) safe.
            if (threads <= 0 || ts <= 0L || trials <= 0) return@forEach
            if (!tps.isFinite() || tps <= 0f) return@forEach
            result[key] = CalibrationRecord(threads, tps, ts, trials, complete)
        }
        return result
    }

    private fun sanitizeKey(key: String): String = key.replace(";", "|").replace("@", "_")
}
