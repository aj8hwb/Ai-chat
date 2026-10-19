package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelThreadsTest {

    @Test
    fun `battery conscious mode always uses two threads`() {
        assertEquals(2, ModelThreads.recommended(batteryConscious = true))
    }

    @Test
    fun `normal mode stays within the clamp range`() {
        val threads = ModelThreads.recommended(batteryConscious = false)
        assertTrue(threads in ModelThreads.MIN_THREADS..ModelThreads.MAX_THREADS)
    }

    @Test
    fun `normal mode is never battery-conservative`() {
        assertTrue(ModelThreads.recommended(batteryConscious = false) >= ModelThreads.MIN_THREADS)
    }

    // ── full device-aware policy ────────────────────────────────────────

    @Test
    fun `single core device still gets the minimum`() {
        assertEquals(
            ModelThreads.MIN_THREADS,
            ModelThreads.recommended(batteryConscious = false, cpuCores = 1),
        )
    }

    @Test
    fun `many core devices are clamped to the maximum`() {
        // 16 cores → base = 15 → clamped to MAX_THREADS
        assertEquals(
            ModelThreads.MAX_THREADS,
            ModelThreads.recommended(batteryConscious = false, cpuCores = 16),
        )
    }

    @Test
    fun `eight core device uses cores minus one`() {
        assertEquals(
            7.coerceIn(ModelThreads.MIN_THREADS, ModelThreads.MAX_THREADS),
            ModelThreads.recommended(batteryConscious = false, cpuCores = 8),
        )
    }

    @Test
    fun `battery conscious ignores core count`() {
        assertEquals(
            ModelThreads.BATTERY_CONSERVATIVE,
            ModelThreads.recommended(batteryConscious = true, cpuCores = 16),
        )
        assertEquals(
            ModelThreads.BATTERY_CONSERVATIVE,
            ModelThreads.recommended(batteryConscious = true, cpuCores = 2),
        )
    }

    @Test
    fun `warm thermal state reduces threads by a quarter`() {
        val base = ModelThreads.recommended(
            batteryConscious = false,
            cpuCores = 8,
            thermalLevel = ModelThreads.ThermalLoadLevel.NORMAL,
        )
        val warm = ModelThreads.recommended(
            batteryConscious = false,
            cpuCores = 8,
            thermalLevel = ModelThreads.ThermalLoadLevel.WARM,
        )
        assertEquals((base * 0.75f).toInt().coerceAtLeast(1), warm)
        assertTrue(warm < base)
    }

    @Test
    fun `hot thermal state halves threads`() {
        val base = ModelThreads.recommended(
            batteryConscious = false,
            cpuCores = 8,
            thermalLevel = ModelThreads.ThermalLoadLevel.NORMAL,
        )
        val hot = ModelThreads.recommended(
            batteryConscious = false,
            cpuCores = 8,
            thermalLevel = ModelThreads.ThermalLoadLevel.HOT,
        )
        assertEquals((base * 0.5f).toInt().coerceAtLeast(1), hot)
        assertTrue(hot >= 1)
    }

    @Test
    fun `critical thermal forces one thread`() {
        assertEquals(
            1,
            ModelThreads.recommended(
                batteryConscious = false,
                cpuCores = 8,
                thermalLevel = ModelThreads.ThermalLoadLevel.CRITICAL,
            ),
        )
        assertEquals(
            1,
            ModelThreads.recommended(
                batteryConscious = true,
                cpuCores = 8,
                thermalLevel = ModelThreads.ThermalLoadLevel.CRITICAL,
            ),
        )
    }

    @Test
    fun `memory pressure caps threads at the minimum`() {
        assertEquals(
            ModelThreads.MIN_THREADS,
            ModelThreads.recommended(
                batteryConscious = false,
                cpuCores = 8,
                memoryPressure = true,
            ),
        )
        // Pressure never INCREASES the count.
        assertEquals(
            ModelThreads.BATTERY_CONSERVATIVE,
            ModelThreads.recommended(
                batteryConscious = true,
                cpuCores = 8,
                memoryPressure = true,
            ),
        )
    }

    @Test
    fun `thermal reduction dominates memory pressure`() {
        // Pressure caps at 2, then CRITICAL forces 1.
        assertEquals(
            1,
            ModelThreads.recommended(
                batteryConscious = false,
                cpuCores = 8,
                thermalLevel = ModelThreads.ThermalLoadLevel.CRITICAL,
                memoryPressure = true,
            ),
        )
    }

    @Test
    fun `result is always within one to max`() {
        val levels = ModelThreads.ThermalLoadLevel.values()
        for (cores in intArrayOf(1, 2, 4, 6, 8, 12, 16)) {
            for (level in levels) {
                for (pressure in booleanArrayOf(false, true)) {
                    val threads = ModelThreads.recommended(false, cores, level, pressure)
                    assertTrue("cores=$cores level=$level pressure=$pressure", threads in 1..ModelThreads.MAX_THREADS)
                }
            }
        }
    }

    // ── composable helpers (used by the calibrated thread path) ─────────

    @Test
    fun `calibrated value survives no-pressure normal thermal conditions`() {
        assertEquals(3, ModelThreads.withMemoryPressure(3, memoryPressure = false))
        assertEquals(3, ModelThreads.withThermal(3, ModelThreads.ThermalLoadLevel.NORMAL))
    }

    @Test
    fun `calibrated value is reduced under memory pressure regardless of its source`() {
        assertEquals(ModelThreads.MIN_THREADS, ModelThreads.withMemoryPressure(4, memoryPressure = true))
        assertEquals(ModelThreads.MIN_THREADS, ModelThreads.withMemoryPressure(2, memoryPressure = true))
    }

    @Test
    fun `a calibration result never overrides thermal reductions`() {
        // Even a benchmark-measured optimum of 4 is halved when HOT — a
        // benchmark NEVER wins over a safety limit (spec §9.4).
        assertEquals(2, ModelThreads.withThermal(4, ModelThreads.ThermalLoadLevel.HOT))
        assertEquals(3, ModelThreads.withThermal(4, ModelThreads.ThermalLoadLevel.WARM))
        assertEquals(1, ModelThreads.withThermal(4, ModelThreads.ThermalLoadLevel.CRITICAL))
    }

    @Test
    fun `composable reductions stay in the runtime clamp`() {
        assertEquals(
            1,
            ModelThreads.withMemoryPressure(
                ModelThreads.withThermal(4, ModelThreads.ThermalLoadLevel.CRITICAL),
                memoryPressure = true,
            ),
        )
    }
}
