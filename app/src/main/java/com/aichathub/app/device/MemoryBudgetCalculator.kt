package com.aichathub.app.device

import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.DeviceProfile

/**
 * Computes a conservative "safe AI memory budget".
 *
 * Free RAM is NOT equal to usable AI RAM. We reserve an app overhead,
 * a runtime overhead and a safety reserve before deciding how much of the
 * current available memory the inference runtime may safely use.
 */
object MemoryBudgetCalculator {

    private const val KB = 1024L
    private const val MB = 1024L * KB
    private const val GB = 1024L * MB

    fun calculate(profile: DeviceProfile): AiMemoryBudget {
        val available = profile.availableRamBytes
        val total = profile.totalRamBytes

        // App + system overhead: ~12% of total RAM, min 512 MB.
        val appOverhead = maxOf(512L * MB, total / 8L)

        // Runtime native allocation overhead estimate.
        val runtimeOverhead = when {
            total <= 4L * GB -> 300L * MB
            total <= 6L * GB -> 350L * MB
            else -> 400L * MB
        }

        // Safety reserve so the OS is never left starved.
        val safetyReserve = when {
            total <= 4L * GB -> 500L * MB
            total <= 6L * GB -> 600L * MB
            else -> 700L * MB
        }

        val usable = (available - appOverhead - runtimeOverhead - safetyReserve)
            .coerceAtLeast(0L)

        return AiMemoryBudget(
            availableBytes = available,
            reservedBytes = appOverhead,
            runtimeOverheadBytes = runtimeOverhead,
            safetyReserveBytes = safetyReserve,
            modelMemoryBytes = usable
        )
    }
}