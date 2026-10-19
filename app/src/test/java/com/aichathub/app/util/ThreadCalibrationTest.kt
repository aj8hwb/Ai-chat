package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadCalibrationTest {

    private val now = 1_000_000_000L

    private fun record(threads: Int, tps: Float = 10f, complete: Boolean = true, ts: Long = now) =
        ThreadCalibration.CalibrationRecord(threads, tps, ts, 2, complete)

    // ── candidates ──────────────────────────────────────────────────────

    @Test
    fun `candidates stay within the supported runtime window`() {
        val candidates = ThreadCalibration.candidates(cpuCores = 8)
        assertTrue(candidates.all { it in 1..ModelThreads.MAX_THREADS })
        assertEquals(listOf(1, 2, 3, 4), candidates)
    }

    @Test
    fun `candidates never exceed cores minus one`() {
        val candidates = ThreadCalibration.candidates(cpuCores = 3)
        assertTrue(candidates.max() <= 2)
        assertFalse(candidates.contains(3))
    }

    @Test
    fun `single-core device still yields one candidate`() {
        assertEquals(listOf(1), ThreadCalibration.candidates(cpuCores = 1))
        assertEquals(listOf(1), ThreadCalibration.candidates(cpuCores = 0))
    }

    // ── median-based selection (robust statistic) ───────────────────────

    @Test
    fun `best uses median not a single fast outlier`() {
        // Candidate 2: one outlier-fast trial (100 tok/s) + two slow (10).
        // Candidate 3: consistent 30 tok/s. Median 2 = 10 → 3 must win even
        // though candidate 2 has the fastest single trial (spec §3.7).
        val samples = mapOf(
            2 to listOf(
                ThreadCalibration.CalibrationSample(2, 100f),
                ThreadCalibration.CalibrationSample(2, 10f),
                ThreadCalibration.CalibrationSample(2, 10f),
            ),
            3 to listOf(
                ThreadCalibration.CalibrationSample(3, 30f),
                ThreadCalibration.CalibrationSample(3, 30f),
            ),
        )
        assertEquals(3, ThreadCalibration.best(samples))
    }

    @Test
    fun `ties resolve to fewer threads`() {
        val samples = mapOf(
            3 to listOf(ThreadCalibration.CalibrationSample(3, 20f)),
            1 to listOf(ThreadCalibration.CalibrationSample(1, 20f)),
            2 to listOf(ThreadCalibration.CalibrationSample(2, 20f)),
        )
        assertEquals(1, ThreadCalibration.best(samples))
    }

    @Test
    fun `non-positive and invalid samples are rejected`() {
        assertNull(
            ThreadCalibration.medianTps(
                listOf(
                    ThreadCalibration.CalibrationSample(2, 0f),
                    ThreadCalibration.CalibrationSample(2, -5f),
                    ThreadCalibration.CalibrationSample(2, Float.NaN),
                ),
            ),
        )
        assertNull(ThreadCalibration.medianTps(emptyList()))
    }

    @Test
    fun `no valid measurements yield null - never an invalid thread count`() {
        assertNull(ThreadCalibration.best(emptyMap()))
        assertNull(
            ThreadCalibration.best(
                mapOf(2 to listOf(ThreadCalibration.CalibrationSample(2, 0f))),
            ),
        )
    }

    @Test
    fun `candidate with a failing trial still competes on its valid ones`() {
        // One broken (zero-tps) sample is dropped; the other two decide.
        assertEquals(
            15f,
            ThreadCalibration.medianTps(
                listOf(
                    ThreadCalibration.CalibrationSample(2, 0f),
                    ThreadCalibration.CalibrationSample(2, 10f),
                    ThreadCalibration.CalibrationSample(2, 20f),
                ),
            )!!,
            0.001f,
        )
    }

    // ── cache key (staleness by configuration change) ───────────────────

    @Test
    fun `cache key changes when a material property changes`() {
        val base = ThreadCalibration.cacheKey("rk3588", "llama/1.0", "m1", 4096, "Q4_K_M")
        assertTrue(base != ThreadCalibration.cacheKey("rk3576", "llama/1.0", "m1", 4096, "Q4_K_M"))
        assertTrue(base != ThreadCalibration.cacheKey("rk3588", "llama/2.0", "m1", 4096, "Q4_K_M"))
        assertTrue(base != ThreadCalibration.cacheKey("rk3588", "llama/1.0", "m2", 4096, "Q4_K_M"))
        assertTrue(base != ThreadCalibration.cacheKey("rk3588", "llama/1.0", "m1", 2048, "Q4_K_M"))
        assertTrue(base != ThreadCalibration.cacheKey("rk3588", "llama/1.0", "m1", 4096, "Q8_0"))
    }

    @Test
    fun `cache key is serialization-safe`() {
        val key = ThreadCalibration.cacheKey("a;b|c@", "v", "m", 4096, "Q4")
        assertFalse(key.contains(";"))
        assertFalse(key.contains("@"))
    }

    // ── resolveThreadCount (record validity gates) ──────────────────────

    @Test
    fun `missing record falls back to the baseline`() {
        val baseline = ModelThreads.recommended(batteryConscious = false, cpuCores = 8)
        assertEquals(
            baseline,
            ThreadCalibration.resolveThreadCount(null, now, false, 8),
        )
    }

    @Test
    fun `valid complete record wins`() {
        assertEquals(
            3,
            ThreadCalibration.resolveThreadCount(record(threads = 3), now + 1000, false, 8),
        )
    }

    @Test
    fun `incomplete record never applies - baseline used`() {
        val baseline = ModelThreads.recommended(batteryConscious = false, cpuCores = 8)
        assertEquals(
            baseline,
            ThreadCalibration.resolveThreadCount(record(threads = 4, complete = false), now + 1000, false, 8),
        )
    }

    @Test
    fun `stale record never applies`() {
        val baseline = ModelThreads.recommended(batteryConscious = false, cpuCores = 8)
        assertEquals(
            baseline,
            ThreadCalibration.resolveThreadCount(
                record(threads = 4, ts = now - ThreadCalibration.VALIDITY_MS - 1),
                now,
                false,
                8,
            ),
        )
    }

    @Test
    fun `out-of-range or zero thread counts in a record are rejected`() {
        val baseline = ModelThreads.recommended(batteryConscious = false, cpuCores = 8)
        assertEquals(
            baseline,
            ThreadCalibration.resolveThreadCount(record(threads = 0), now + 1, false, 8),
        )
        assertEquals(
            baseline,
            ThreadCalibration.resolveThreadCount(record(threads = 99), now + 1, false, 8),
        )
    }

    @Test
    fun `record with invalid throughput is never applied`() {
        val baseline = ModelThreads.recommended(batteryConscious = false, cpuCores = 8)
        for (tps in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(
                "tps=$tps must resolve to the baseline",
                baseline,
                ThreadCalibration.resolveThreadCount(record(threads = 3, tps = tps), now + 1, false, 8),
            )
        }
    }

    @Test
    fun `record with insufficient trials is never applied`() {
        val baseline = ModelThreads.recommended(batteryConscious = false, cpuCores = 8)
        assertEquals(
            baseline,
            ThreadCalibration.resolveThreadCount(
                ThreadCalibration.CalibrationRecord(
                    threads = 3,
                    tokensPerSecond = 10f,
                    timestampMs = now,
                    trialsPerCandidate = 0,
                    complete = true,
                ),
                now + 1,
                false,
                8,
            ),
        )
    }

    @Test
    fun `record for more threads than the current core count supports is not applied`() {
        // MAX_CANDIDATE threads (valid window value) but the CURRENT device only
        // has 3 cores: candidates cap at cores − 1, so 4 threads is not
        // supported by this runtime state → baseline (spec §4.6).
        val baseline = ModelThreads.recommended(batteryConscious = false, cpuCores = 3)
        assertEquals(
            baseline,
            ThreadCalibration.resolveThreadCount(record(threads = 4), now + 1, false, 3),
        )
        // The same record applies fine on an 8-core device (4 ≤ cores − 1).
        assertEquals(4, ThreadCalibration.resolveThreadCount(record(threads = 4), now + 1, false, 8))
    }

    // ── persistence encode/decode ───────────────────────────────────────

    @Test
    fun `encode-decode round trip`() {
        val map = mapOf(
            "a|b|c|4096|Q4" to record(threads = 3, tps = 12.5f, ts = 555L),
            "x" to record(threads = 1, tps = 3f, ts = 7L, complete = false),
        )
        val decoded = ThreadCalibration.decode(ThreadCalibration.encode(map))
        assertEquals(2, decoded.size)
        assertEquals(3, decoded["a|b|c|4096|Q4"]!!.threads)
        assertEquals(12.5f, decoded["a|b|c|4096|Q4"]!!.tokensPerSecond, 0.001f)
        assertEquals(555L, decoded["a|b|c|4096|Q4"]!!.timestampMs)
        assertTrue(decoded["a|b|c|4096|Q4"]!!.complete)
        assertFalse(decoded["x"]!!.complete)
    }

    @Test
    fun `decode handles garbage and null input`() {
        assertTrue(ThreadCalibration.decode(null).isEmpty())
        assertTrue(ThreadCalibration.decode("").isEmpty())
        assertTrue(ThreadCalibration.decode("no-separator-here;also-bad,@,1,2").isEmpty())
    }

    @Test
    fun `decode drops structurally impossible records at the trust boundary`() {
        fun line(key: String, fields: String) = "$key@$fields"
        val raw = listOf(
            // Valid control entry.
            line("ok|k", "3,10.0,1234,2,1"),
            // Zero / negative threads.
            line("t0|k", "0,10.0,1234,2,1"),
            line("tneg|k", "-2,10.0,1234,2,1"),
            // Non-finite / non-positive throughput.
            line("nan|k", "3,NaN,1234,2,1"),
            line("inf|k", "3,Infinity,1234,2,1"),
            line("tps0|k", "3,0.0,1234,2,1"),
            line("tpsneg|k", "3,-5.0,1234,2,1"),
            // Zero trials.
            line("trials|k", "3,10.0,1234,0,1"),
            // Non-positive timestamp.
            line("ts|k", "3,10.0,0,2,1"),
            line("tsneg|k", "3,10.0,-1,2,1"),
        ).joinToString(";")
        val decoded = ThreadCalibration.decode(raw)
        assertEquals(setOf("ok|k"), decoded.keys)
        assertEquals(3, decoded["ok|k"]!!.threads)
    }
}
