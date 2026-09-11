package com.aichathub.app.util

/**
 * Pure decision logic for the llama.cpp native thread count.
 *
 * Battery-conscious mode throttles to 2 threads to save power. Otherwise the
 * thread count scales with the CPU core count (cores - 1) but is clamped to a
 * sensible range so tiny and huge devices both behave well.
 */
object ModelThreads {

    const val BATTERY_CONSERVATIVE = 2
    const val MIN_THREADS = 2
    const val MAX_THREADS = 4

    fun recommended(batteryConscious: Boolean): Int {
        if (batteryConscious) return BATTERY_CONSERVATIVE
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return (cores - 1).coerceIn(MIN_THREADS, MAX_THREADS)
    }
}
