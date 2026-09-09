package com.aichathub.app.domain.model

/** The safe amount of memory the AI runtime may use. */
data class AiMemoryBudget(
    val availableBytes: Long,
    val reservedBytes: Long,
    val runtimeOverheadBytes: Long,
    val safetyReserveBytes: Long,
    val modelMemoryBytes: Long
) {
    val usableBytes: Long
        get() = (availableBytes - runtimeOverheadBytes - safetyReserveBytes)
            .coerceAtLeast(0)

    val usableMb: Long get() = usableBytes / (1024 * 1024)
    val usableGb: Float get() = usableBytes / (1024f * 1024f * 1024f)

    val modelMemoryMb: Long get() = modelMemoryBytes / (1024 * 1024)
    val modelMemoryGb: Float get() = modelMemoryBytes / (1024f * 1024f * 1024f)

    val availableGb: Float get() = availableBytes / (1024f * 1024f * 1024f)
}
