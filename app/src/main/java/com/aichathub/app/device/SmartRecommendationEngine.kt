package com.aichathub.app.device

import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.DeviceProfile

/**
 * Thermal state reported by the Android PowerManager / ThermalStatusListener.
 */
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/**
 * GPU compute backend available on this device.
 */
enum class GpuBackend {
    NONE,
    OPENCL,
    VULKAN,
    METAL,
    CUDA,
    OPENCL_GL,
    UNKNOWN;

    val supportsGpuOffload: Boolean get() = this != NONE && this != UNKNOWN
}

/**
 * Risk level for running a model, combining memory, thermal and battery
 * considerations into a single actionable label.
 */
enum class RiskLevel(val label: String) {
    SAFE("Safe"),
    MODERATE("Moderate"),
    HEAVY("Heavy"),
    NOT_RECOMMENDED("Not Recommended")
}

/**
 * A rich recommendation result for a single model.
 *
 * @property score 0.0 (worst) to 1.0 (best) composite score.
 * @property expectedSpeed Human-readable speed estimate.
 * @property riskLevel Actionable risk classification.
 * @property bestFor Short tag explaining when to pick this model.
 * @property memoryScore Raw memory score (0..1) before weighting.
 * @property speedScore Raw speed estimate (0..1) before weighting.
 * @property storageScore Raw storage score (0..1) before weighting.
 * @property batteryThermalScore Raw battery/thermal score (0..1) before weighting.
 * @property reason Detailed explanation of the scoring.
 */
data class SmartRecommendation(
    val model: CatalogModel,
    val mode: RecommendationMode,
    val score: Float,
    val expectedSpeed: String,
    val riskLevel: RiskLevel,
    val bestFor: String,
    val memoryScore: Float,
    val speedScore: Float,
    val storageScore: Float,
    val batteryThermalScore: Float,
    val reason: String,
    val compatibilityLevel: CompatibilityLevel
)

/**
 * Enhanced recommendation engine that goes beyond simple RAM/compatibility
 * checks.  Weights four dimensions — memory, speed, storage, battery/thermal
 * — into a single 0..1 score tuned to the user's chosen [RecommendationMode].
 *
 * Pure logic — no Android framework dependencies, fully unit-testable.
 */
class SmartRecommendationEngine {

    // ── weight profiles per mode ──────────────────────────────────────
    // memory, speed, storage, battery/thermal
    private val weights: Map<RecommendationMode, FloatArray> = mapOf(
        RecommendationMode.BEST_BALANCE  to floatArrayOf(0.40f, 0.25f, 0.20f, 0.15f),
        RecommendationMode.FASTEST       to floatArrayOf(0.25f, 0.50f, 0.10f, 0.15f),
        RecommendationMode.BEST_QUALITY  to floatArrayOf(0.35f, 0.20f, 0.25f, 0.20f),
        RecommendationMode.SMALLEST      to floatArrayOf(0.30f, 0.10f, 0.50f, 0.10f)
    )

    // ── public API ────────────────────────────────────────────────────

    /**
     * Score a single model and return a rich [SmartRecommendation].
     *
     * @param model          The catalog model to evaluate.
     * @param profile        Current device hardware profile.
     * @param budget         Safe AI memory budget for this device.
     * @param mode           What the user cares about most.
     * @param thermalStatus  Current device thermal throttle level.
     * @param batteryLevel   0..100 percentage, or -1 if unknown.
     * @param measuredPss    Real measured PSS in bytes for this model, or null.
     * @param pastBenchmarks Map of model id → tokens-per-second from prior runs.
     */
    fun score(
        model: CatalogModel,
        profile: DeviceProfile,
        budget: AiMemoryBudget,
        mode: RecommendationMode = RecommendationMode.BEST_BALANCE,
        thermalStatus: ThermalStatus = ThermalStatus.NONE,
        batteryLevel: Int = -1,
        measuredPss: Map<String, Long> = emptyMap(),
        pastBenchmarks: Map<String, Float> = emptyMap()
    ): SmartRecommendation {
        val memory    = memoryScore(model, budget, measuredPss)
        val speed     = speedScore(model, profile, pastBenchmarks)
        val storage   = storageScore(model, profile)
        val batteryTh = batteryThermalScore(thermalStatus, batteryLevel)

        val w = weights.getValue(mode)
        val composite = clamp(w[0] * memory + w[1] * speed + w[2] * storage + w[3] * batteryTh)

        val risk   = deriveRiskLevel(composite, memory)
        val speedLabel = deriveSpeedLabel(speed)
        val bestForTag = deriveBestFor(model, mode)
        val compat = deriveCompatibilityLevel(composite)
        val reason = buildReason(model, memory, speed, storage, batteryTh, measuredPss, budget)

        return SmartRecommendation(
            model = model,
            mode = mode,
            score = composite,
            expectedSpeed = speedLabel,
            riskLevel = risk,
            bestFor = bestForTag,
            memoryScore = memory,
            speedScore = speed,
            storageScore = storage,
            batteryThermalScore = batteryTh,
            reason = reason,
            compatibilityLevel = compat
        )
    }

    /**
     * Score every model in [models] and return them sorted best-to-worst.
     */
    fun recommendAll(
        models: List<CatalogModel>,
        profile: DeviceProfile,
        budget: AiMemoryBudget,
        mode: RecommendationMode = RecommendationMode.BEST_BALANCE,
        thermalStatus: ThermalStatus = ThermalStatus.NONE,
        batteryLevel: Int = -1,
        measuredPss: Map<String, Long> = emptyMap(),
        pastBenchmarks: Map<String, Float> = emptyMap()
    ): List<SmartRecommendation> =
        models.map { model ->
            score(model, profile, budget, mode, thermalStatus, batteryLevel, measuredPss, pastBenchmarks)
        }.sortedByDescending { it.score }

    // ── individual scoring dimensions ─────────────────────────────────

    /**
     * Memory score: how comfortably the model fits in the safe budget.
     * 1.0 = uses < 60 % of budget, 0.0 = exceeds budget by > 35 %.
     */
    private fun memoryScore(
        model: CatalogModel,
        budget: AiMemoryBudget,
        measuredPss: Map<String, Long>
    ): Float {
        val usable = budget.modelMemoryBytes.toDouble()
        if (usable <= 0.0) return 0f
        val required = effectiveMemory(model, measuredPss).toDouble()
        val ratio = required / usable
        return when {
            ratio <= 0.6  -> 1.0f
            ratio <= 0.75 -> lerp(0.75f, 1.0f, (ratio - 0.6) / 0.15)
            ratio <= 1.0  -> lerp(0.5f, 0.75f, (ratio - 0.75) / 0.25)
            ratio <= 1.35 -> lerp(0.2f, 0.5f, (ratio - 1.0) / 0.35)
            else          -> clamp(0.2f - ((ratio - 1.35).toFloat() * 0.3f))
        }
    }

    /**
     * Speed estimate: blend of CPU cores, RAM headroom, and historical
     * benchmark data when available.
     *
     * NOTE: GPU backend scoring is intentionally excluded because the current
     * runtime is CPU-only (gpuLayers=0). GPU acceleration will be factored in
     * only when a GPU-capable runtime is actually functional.
     */
    private fun speedScore(
        model: CatalogModel,
        profile: DeviceProfile,
        pastBenchmarks: Map<String, Float>
    ): Float {
        // Historical benchmark is the strongest signal.
        val benchTps = pastBenchmarks[model.id]
        if (benchTps != null && benchTps > 0f) {
            // 60 tok/s = perfect, 5 tok/s = floor
            return clamp((benchTps - 5f) / 55f)
        }

        var score = 0f

        // CPU core contribution (0..0.55)
        val coreFactor = profile.cpuCores.coerceIn(1, 16) / 16f
        score += coreFactor * 0.55f

        // RAM headroom contributes to throughput (less swapping = faster) (0..0.45)
        val ramGb = profile.totalRamGb
        val ramFactor = when {
            ramGb >= 16f -> 1.0f
            ramGb >= 12f -> 0.85f
            ramGb >= 8f  -> 0.65f
            ramGb >= 6f  -> 0.45f
            ramGb >= 4f  -> 0.25f
            else         -> 0.1f
        }
        score += ramFactor * 0.45f

        // Penalise large parameter counts on low-RAM devices.
        if (profile.isLowRamDevice && model.parameterCount >= 1_000_000_000L) {
            score *= 0.5f
        }

        return clamp(score)
    }

    /**
     * Storage score: how much free space remains after downloading.
     * 1.0 = plenty, 0.0 = would leave < 500 MB free.
     */
    private fun storageScore(model: CatalogModel, profile: DeviceProfile): Float {
        val freeAfter = profile.storageAvailableBytes - model.fileSizeBytes
        val freeMb = freeAfter / (1024.0 * 1024.0)
        return when {
            freeMb >= 4096  -> 1.0f
            freeMb >= 2048  -> 0.85f
            freeMb >= 1024  -> 0.65f
            freeMb >= 500   -> 0.4f
            freeMb >= 200   -> 0.2f
            else            -> 0.05f
        }
    }

    /**
     * Battery / thermal score: penalise when the device is hot or nearly flat.
     * 1.0 = cool & charged, 0.0 = critical thermal or dead.
     */
    private fun batteryThermalScore(thermal: ThermalStatus, batteryLevel: Int): Float {
        val thermalScore = when (thermal) {
            ThermalStatus.NONE     -> 1.0f
            ThermalStatus.LIGHT    -> 0.85f
            ThermalStatus.MODERATE -> 0.6f
            ThermalStatus.SEVERE   -> 0.3f
            ThermalStatus.CRITICAL -> 0.1f
            ThermalStatus.EMERGENCY,
            ThermalStatus.SHUTDOWN -> 0.0f
        }
        val batteryScore = when {
            batteryLevel < 0  -> 0.7f   // unknown → mild default
            batteryLevel >= 50 -> 1.0f
            batteryLevel >= 30 -> 0.8f
            batteryLevel >= 15 -> 0.5f
            batteryLevel >= 5  -> 0.25f
            else               -> 0.05f
        }
        return clamp(thermalScore * 0.65f + batteryScore * 0.35f)
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun effectiveMemory(model: CatalogModel, measuredPss: Map<String, Long>): Long =
        measuredPss[model.id] ?: model.estimatedMemoryBytes

    private fun deriveRiskLevel(composite: Float, memory: Float): RiskLevel = when {
        composite >= 0.75f && memory >= 0.5f -> RiskLevel.SAFE
        composite >= 0.55f                   -> RiskLevel.MODERATE
        composite >= 0.3f                    -> RiskLevel.HEAVY
        else                                 -> RiskLevel.NOT_RECOMMENDED
    }

    private fun deriveSpeedLabel(speed: Float): String = when {
        speed >= 0.7f -> "Fast"
        speed >= 0.4f -> "Moderate"
        else          -> "Slow"
    }

    private fun deriveBestFor(model: CatalogModel, mode: RecommendationMode): String = when (mode) {
        RecommendationMode.BEST_BALANCE  -> "Best balance"
        RecommendationMode.FASTEST       -> "Fastest"
        RecommendationMode.BEST_QUALITY  -> "Best quality"
        RecommendationMode.SMALLEST      -> "Smallest"
    }

    private fun deriveCompatibilityLevel(composite: Float): CompatibilityLevel = when {
        composite >= 0.8f -> CompatibilityLevel.EXCELLENT
        composite >= 0.6f -> CompatibilityLevel.RECOMMENDED
        composite >= 0.4f -> CompatibilityLevel.USABLE
        composite >= 0.2f -> CompatibilityLevel.HEAVY
        else              -> CompatibilityLevel.NOT_RECOMMENDED
    }

    private fun buildReason(
        model: CatalogModel,
        memory: Float,
        speed: Float,
        storage: Float,
        batteryTh: Float,
        measuredPss: Map<String, Long>,
        budget: AiMemoryBudget
    ): String {
        val required = effectiveMemory(model, measuredPss)
        val requiredMb = (required / (1024.0 * 1024.0)).round1()
        val usableMb = (budget.modelMemoryBytes / (1024.0 * 1024.0)).round1()
        val measured = measuredPss[model.id] != null
        val source = if (measured) "measured" else "estimated"

        val parts = mutableListOf<String>()
        parts.add("Memory: ${pct(memory)} of budget (~$requiredMb MB $source vs $usableMb MB safe)")
        parts.add("Speed: ${pct(speed)}")
        parts.add("Storage: ${pct(storage)}")
        parts.add("Battery/Thermal: ${pct(batteryTh)}")
        return parts.joinToString(". ")
    }

    private fun pct(f: Float): String = "${(f * 100).toInt()}%"

    private fun clamp(v: Float): Float = v.coerceIn(0f, 1f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * clamp(t)
}

private fun Double.round1(): String = (kotlin.math.round(this * 10) / 10).toString()
