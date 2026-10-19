package com.aichathub.app.util

/**
 * Pure decision logic for the llama.cpp native thread count.
 *
 * Bounded device-aware policy — NOT "use every core":
 *  - Base count scales with the CPU core count (cores − 1), clamped to
 *    [MIN_THREADS]..[MAX_THREADS] so tiny and huge devices both behave well.
 *  - Battery-conscious mode throttles to [BATTERY_CONSERVATIVE] threads.
 *  - Memory pressure caps the count at [MIN_THREADS].
 *  - Thermal load levels reduce the count progressively (75% → 50% → 1),
 *    matching [com.aichathub.app.device.ThermalPolicy].
 *
 * The result never exceeds [MAX_THREADS]: on big.LITTLE phones, driving every
 * core with llama.cpp is counterproductive (thermal throttling dominates) and
 * leaves no headroom for the UI thread.
 */
object ModelThreads {

    enum class ThermalLoadLevel { NORMAL, WARM, HOT, CRITICAL }

    const val BATTERY_CONSERVATIVE = 2
    const val MIN_THREADS = 2
    const val MAX_THREADS = 4

    /** Backward-compatible entry point using the real CPU core count. */
    fun recommended(batteryConscious: Boolean): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return recommended(batteryConscious, cores)
    }

    /**
     * Full device-aware policy.
     *
     * @param cpuCores logical CPU cores from the device (never unbounded)
     * @param thermalLevel current thermal load level (NORMAL when unknown)
     * @param memoryPressure true when the device is under memory pressure
     */
    fun recommended(
        batteryConscious: Boolean,
        cpuCores: Int,
        thermalLevel: ThermalLoadLevel = ThermalLoadLevel.NORMAL,
        memoryPressure: Boolean = false
    ): Int {
        val cores = cpuCores.coerceAtLeast(1)
        val base = if (batteryConscious) {
            BATTERY_CONSERVATIVE
        } else {
            (cores - 1).coerceIn(MIN_THREADS, MAX_THREADS)
        }

        var threads = base

        // Memory pressure: keep every byte for the model, use few threads.
        threads = withMemoryPressure(threads, memoryPressure)

        // Thermal state reduces the count progressively; CRITICAL forces 1.
        threads = withThermal(threads, thermalLevel)

        return threads.coerceIn(1, MAX_THREADS)
    }

    /** Memory-pressure cap, composable with a benchmark-calibrated base count. */
    fun withMemoryPressure(threads: Int, memoryPressure: Boolean): Int =
        if (memoryPressure) minOf(threads, MIN_THREADS) else threads

    /**
     * Thermal reduction, composable with a benchmark-calibrated base count.
     * A calibration record NEVER overrides thermal limits (spec §9.4) — the
     * reduction is applied on top of whatever base value wins.
     */
    fun withThermal(threads: Int, thermalLevel: ThermalLoadLevel): Int {
        val reduced = when (thermalLevel) {
            ThermalLoadLevel.NORMAL -> threads
            ThermalLoadLevel.WARM -> (threads * 0.75f).toInt().coerceAtLeast(1)
            ThermalLoadLevel.HOT -> (threads * 0.5f).toInt().coerceAtLeast(1)
            ThermalLoadLevel.CRITICAL -> 1
        }
        return reduced.coerceIn(1, MAX_THREADS)
    }
}
