package com.aichathub.app.device

import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.Recommendation

/**
 * Evaluates how compatible a model is with the current device state.
 * Pure logic — no Android dependencies, unit-testable.
 */
class CompatibilityEngine {

    /** Overall compatibility of a model for the current device. */
    fun evaluate(
        model: CatalogModel,
        profile: DeviceProfile,
        budget: AiMemoryBudget
    ): CompatibilityLevel {
        val memoryScore = memoryScore(model, budget)
        val ramScore = ramScore(model, profile)
        val overall = (memoryScore * 2 + ramScore) / 3
        var level = CompatibilityLevel.fromRank(overall)

        // 4 GB RAM ceiling: parameter counts ≥ 1B are memory-hungry on
        // low-RAM devices. They may still run (USABLE) but are never
        // auto-recommended; the user must opt in.
        if (profile.isLowRamDevice && model.parameterCount >= 1_000_000_000L) {
            level = CompatibilityLevel.fromRank(minOf(level.rank, CompatibilityLevel.USABLE.rank))
        }
        return level
    }

    /**
     * Returns a list of recommendations for all catalog models, ranked.
     * Static device-analysis is combined with model characteristics.
     */
    fun recommendAll(
        models: List<CatalogModel>,
        profile: DeviceProfile,
        budget: AiMemoryBudget
    ): List<Recommendation> =
        models
            .map { model ->
                val level = evaluate(model, profile, budget)
                Recommendation(
                    model = model,
                    level = level,
                    score = level.rank,
                    reason = reasonFor(model, level, budget),
                    quantizationNote = quantizationNote(model, budget)
                )
            }
            .sortedWith(
                compareByDescending<Recommendation> { it.level.rank }
                    .thenBy { it.model.fileSizeBytes }
            )

    private fun memoryScore(model: CatalogModel, budget: AiMemoryBudget): Int {
        val usable = budget.usableBytes
        val required = model.estimatedMemoryBytes
        return when {
            required <= usable * 0.6 -> 5
            required <= usable * 0.8 -> 4
            required <= usable * 1.0 -> 3
            required <= usable * 1.35 -> 2
            else -> 1
        }
    }

    private fun ramScore(model: CatalogModel, profile: DeviceProfile): Int {
        // Compare the model's expected memory footprint against total device RAM.
        val totalGb = profile.totalRamGb.toDouble()
        val requiredGb = model.estimatedMemoryBytes / (1024.0 * 1024.0 * 1024.0)
        return when {
            requiredGb <= totalGb * 0.6 -> 5
            requiredGb <= totalGb * 0.75 -> 4
            requiredGb <= totalGb * 0.9 -> 3
            requiredGb <= totalGb * 1.1 -> 2
            else -> 1
        }
    }

    private fun reasonFor(
        model: CatalogModel,
        level: CompatibilityLevel,
        budget: AiMemoryBudget
    ): String = when (level) {
        CompatibilityLevel.EXCELLENT ->
            "Fits your current memory budget with room to spare."
        CompatibilityLevel.RECOMMENDED ->
            "This model is well suited to your device."
        CompatibilityLevel.USABLE ->
            "Will run, but performance may be limited."
        CompatibilityLevel.HEAVY ->
            "Requires more memory than is safely available right now."
        CompatibilityLevel.NOT_RECOMMENDED ->
            "Too heavy for your current device memory."
    }

    private fun quantizationNote(model: CatalogModel, budget: AiMemoryBudget): String? {
        val ratio = model.estimatedMemoryBytes.toDouble() / budget.usableBytes.toDouble()
        return if (ratio > 1.0) {
            "A smaller quantization or a lighter model is recommended."
        } else null
    }
}