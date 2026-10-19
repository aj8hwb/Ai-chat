package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAggregatorTest {

    // ── decode-window tokens/s ──────────────────────────────────────────

    @Test
    fun `decode tps excludes prefill`() {
        // 100 tokens, first token at 500 ms, run ends at 1500 ms → decode
        // window = 99 decode tokens / 1.0 s = 99 tok/s (NOT 100/1.5 = 66).
        assertEquals(99f, BenchmarkAggregator.decodeTps(100, 500, 1500)!!, 0.5f)
    }

    @Test
    fun `zero or one generated tokens are invalid measurements`() {
        assertNull(BenchmarkAggregator.decodeTps(0, 100, 1000))
        assertNull(BenchmarkAggregator.decodeTps(1, 100, 1000))
    }

    @Test
    fun `non-positive ttft or ttft past end are invalid`() {
        assertNull(BenchmarkAggregator.decodeTps(50, 0, 1000))
        assertNull(BenchmarkAggregator.decodeTps(50, -5, 1000))
        assertNull(BenchmarkAggregator.decodeTps(50, 1500, 1000))
        assertNull(BenchmarkAggregator.decodeTps(50, 1000, 1000))
    }

    // ── aggregation ──────────────────────────────────────────────────────

    @Test
    fun `summary uses the median and reports the observed range`() {
        val trials = listOf(
            // 100 tok/s
            BenchmarkAggregator.Trial(tokens = 101, ttftMs = 1000, totalMs = 2000),
            // 150 tok/s
            BenchmarkAggregator.Trial(tokens = 151, ttftMs = 1000, totalMs = 2000),
            // 200 tok/s
            BenchmarkAggregator.Trial(tokens = 201, ttftMs = 1000, totalMs = 2000),
        )
        val s = BenchmarkAggregator.summarize(trials)!!
        assertEquals(150f, s.medianTps, 0.5f)
        assertEquals(100f, s.minTps, 0.5f)
        assertEquals(200f, s.maxTps, 0.5f)
        assertEquals(1000L, s.medianTtftMs)
        assertEquals(3, s.validTrials)
    }

    @Test
    fun `invalid trials are dropped from the aggregate`() {
        val trials = listOf(
            // failed run
            BenchmarkAggregator.Trial(tokens = 0, ttftMs = 0, totalMs = 500),
            // 50 tok/s
            BenchmarkAggregator.Trial(tokens = 51, ttftMs = 500, totalMs = 1500),
            // 100 tok/s
            BenchmarkAggregator.Trial(tokens = 101, ttftMs = 500, totalMs = 1500),
        )
        val s = BenchmarkAggregator.summarize(trials)!!
        assertEquals(75f, s.medianTps, 0.5f)
        assertEquals(2, s.validTrials)
    }

    @Test
    fun `all-invalid trials yield null - nothing is fabricated`() {
        val trials = listOf(
            BenchmarkAggregator.Trial(tokens = 0, ttftMs = 0, totalMs = 100),
            BenchmarkAggregator.Trial(tokens = 1, ttftMs = 10, totalMs = 20),
        )
        assertNull(BenchmarkAggregator.summarize(trials))
        assertNull(BenchmarkAggregator.summarize(emptyList()))
    }

    @Test
    fun `even trial count averages the middle pair`() {
        assertEquals(
            25f,
            BenchmarkAggregator.median(listOf(10f, 20f, 30f, 40f)),
            0.001f,
        )
    }

    // ── retention ────────────────────────────────────────────────────────

    @Test
    fun `retention keeps the newest lines and drops the oldest`() {
        val lines = (1..10).map { "record-$it" }
        val kept = BenchmarkAggregator.retention(lines, maxEntries = 5)
        assertEquals(listOf("record-6", "record-7", "record-8", "record-9", "record-10"), kept)
        assertTrue(kept.size <= 5)
    }

    @Test
    fun `retention with a zero cap empties the history`() {
        assertTrue(BenchmarkAggregator.retention(listOf("a"), 0).isEmpty())
    }

    @Test
    fun `retention keeps everything under the cap`() {
        val lines = listOf("a", "b", "c")
        assertEquals(lines, BenchmarkAggregator.retention(lines, 100))
    }
}
