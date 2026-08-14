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
        return CompatibilityLevel.fromRank(overall)
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
        val totalGb = profile.totalRamGb
        return when {
            model.estimatedMemoryBytes <= 1_000_000_000 && totalGb < 4 -> 4
            model.estimatedMemoryBytes <= 1_600_000_000 && totalGb >= 4 -> 5
            model.estimatedMemoryBytes <= 2_500_000_000 && totalGb >= 6 -> 5
            model.estimatedMemoryBytes <= 3_000_000_000 && totalGb >= 8 -> 4
            model.estimatedMemoryBytes <= 4_500_000_000 && totalGb >= 8 -> 3
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