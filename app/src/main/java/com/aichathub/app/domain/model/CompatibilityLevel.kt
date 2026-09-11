package com.aichathub.app.domain.model

/**
 * Compatibility level of a model for the user's device.
 * UI-facing, kept simple.
 */
enum class CompatibilityLevel(val label: String, val rank: Int) {
    EXCELLENT("Excellent", 5),
    RECOMMENDED("Recommended", 4),
    USABLE("Good", 3),
    HEAVY("Heavy", 2),
    NOT_RECOMMENDED("Not Recommended", 1);

    companion object {
        fun fromRank(rank: Int): CompatibilityLevel =
            entries.firstOrNull { it.rank == rank } ?: NOT_RECOMMENDED
    }
}
