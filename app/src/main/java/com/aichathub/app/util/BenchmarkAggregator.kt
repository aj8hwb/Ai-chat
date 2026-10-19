package com.aichathub.app.util

/**
 * Pure aggregation for REAL inference benchmarks (spec §4).
 *
 * Definitions every consumer must honor:
 *  - tokens/s is the DECODE window only: from the first generated token to
 *    the end of the timed run (prefill excluded). tokens/total would mix
 *    prompt evaluation into the number.
 *  - The median is the reported value; min/max are labeled as the observed
 *    range. A single warm trial can never decide the headline number.
 *  - Trials with zero/one tokens, zero TTFT or a non-positive decode window
 *    are INVALID measurements — dropped, never averaged in as zeros.
 *  - Peak process memory is not measurable through the public Android APIs
 *    used by the app; there is deliberately NO peak-memory helper here. A
 *    before/after PSS delta is a delta, not a peak.
 *
 * No Android dependencies — fully unit-testable.
 */
object BenchmarkAggregator {

    /** Hard floor: a timed run must generate more tokens than this to count. */
    private const val MIN_VALID_TOKENS = 1

    data class Trial(val tokens: Int, val ttftMs: Long, val totalMs: Long)

    data class Summary(
        val medianTps: Float,
        val minTps: Float,
        val maxTps: Float,
        val medianTtftMs: Long,
        val validTrials: Int
    )

    /**
     * Decode-window tokens/s for one timed run.
     *
     * @param tokens total generated tokens (from the runtime's real counter)
     * @param ttftMs ms from generation start to the FIRST token
     * @param totalMs ms from generation start to the end of the run
     * @return decode tokens/s, or null for an invalid measurement.
     */
    fun decodeTps(tokens: Int, ttftMs: Long, totalMs: Long): Float? {
        if (tokens <= MIN_VALID_TOKENS) return null
        if (ttftMs <= 0 || totalMs <= ttftMs) return null
        val decodeMs = totalMs - ttftMs
        // (tokens - 1): the first token marks t0 of the decode window, it is
        // not itself a decode product of that window.
        return (tokens - 1) / (decodeMs / 1000f)
    }

    /**
     * Aggregates valid trials. Returns null when no trial produced a usable
     * measurement (all failed/cancelled/zero-token).
     */
    fun summarize(trials: List<Trial>): Summary? {
        val tps = trials.mapNotNull { decodeTps(it.tokens, it.ttftMs, it.totalMs) }
            .filter { it.isFinite() && it > 0f }
        if (tps.isEmpty()) return null
        val ttfts = trials
            .filter { it.tokens > MIN_VALID_TOKENS && it.ttftMs > 0 }
            .map { it.ttftMs }
        return Summary(
            medianTps = median(tps),
            minTps = tps.min(),
            maxTps = tps.max(),
            medianTtftMs = if (ttfts.isEmpty()) 0L else medianL(ttfts),
            validTrials = tps.size
        )
    }

    /** Median of a finite, non-empty list of Floats. */
    fun median(values: List<Float>): Float {
        require(values.isNotEmpty()) { "median of empty list" }
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    private fun medianL(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2L
    }

    /**
     * History retention: keeps at most the LAST [maxEntries] JSONL lines.
     * Oldest records are dropped first (documented policy: bounded history,
     * spec §4.12).
     */
    fun retention(rawLines: List<String>, maxEntries: Int): List<String> {
        if (maxEntries <= 0) return emptyList()
        return rawLines.takeLast(maxEntries)
    }
}
