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
        budget: AiMemoryBudget,
        measuredMemory: Map<String, Long> = emptyMap()
    ): CompatibilityLevel {
        val memoryScore = memoryScore(model, budget, measuredMemory)
        val ramScore = ramScore(model, profile, measuredMemory)
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
     *
     * @param measuredMemory real PSS bytes measured on this device for some
     *   models; when present it overrides the catalog's [CatalogModel.estimatedMemoryBytes].
     */
    fun recommendAll(
        models: List<CatalogModel>,
        profile: DeviceProfile,
        budget: AiMemoryBudget,
        measuredMemory: Map<String, Long> = emptyMap()
    ): List<Recommendation> =
        models
            .map { model ->
                val level = evaluate(model, profile, budget, measuredMemory)
                Recommendation(
                    model = model,
                    level = level,
                    score = level.rank,
                    reason = reasonFor(model, level, budget),
                    quantizationNote = quantizationNote(model, budget, measuredMemory)
                )
            }
            .sortedWith(
                compareByDescending<Recommendation> { it.level.rank }
                    .thenBy { it.model.fileSizeBytes }
            )

    /**
     * Memory headroom against the SAME budget the load preflight uses
     * ([AiMemoryBudget.modelMemoryBytes]). The compatibility badge, the
     * "Download Anyway" warning and the actual load gate therefore always
     * agree — a model can never be recommended on screen and then refused
     * at load time.
     */
    private fun memoryScore(model: CatalogModel, budget: AiMemoryBudget, measuredMemory: Map<String, Long>): Int {
        val usable = budget.modelMemoryBytes
        val required = effectiveMemory(model, measuredMemory)
        return when {
            required <= usable * 0.6 -> 5
            required <= usable * 0.8 -> 4
            required <= usable * 1.0 -> 3
            required <= usable * 1.35 -> 2
            else -> 1
        }
    }

    private fun ramScore(model: CatalogModel, profile: DeviceProfile, measuredMemory: Map<String, Long>): Int {
        // Compare the model's expected memory footprint against total device RAM.
        val totalGb = profile.totalRamGb.toDouble()
        val requiredGb = effectiveMemory(model, measuredMemory) / (1024.0 * 1024.0 * 1024.0)
        return when {
            requiredGb <= totalGb * 0.6 -> 5
            requiredGb <= totalGb * 0.75 -> 4
            requiredGb <= totalGb * 0.9 -> 3
            requiredGb <= totalGb * 1.1 -> 2
            else -> 1
        }
    }

    /**
     * Real measured PSS wins over the catalog estimate when available — a model
     * measured to use far more (or far less) memory than estimated must be
     * scored on what actually happens on THIS device.
     */
    private fun effectiveMemory(model: CatalogModel, measuredMemory: Map<String, Long>): Long =
        measuredMemory[model.id] ?: model.estimatedMemoryBytes

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

    private fun quantizationNote(model: CatalogModel, budget: AiMemoryBudget, measuredMemory: Map<String, Long>): String? {
        val ratio = effectiveMemory(model, measuredMemory).toDouble() / budget.modelMemoryBytes.toDouble()
        return if (ratio > 1.0) {
            "A smaller quantization or a lighter model is recommended."
        } else null
    }
}