package com.aichathub.app.device

import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartRecommendationEngineTest {

    private lateinit var engine: SmartRecommendationEngine

    private val highRamProfile = DeviceProfile(
        totalRamBytes = 16L * 1024 * 1024 * 1024,
        availableRamBytes = 10L * 1024 * 1024 * 1024,
        storageTotalBytes = 256L * 1024 * 1024 * 1024,
        storageAvailableBytes = 150L * 1024 * 1024 * 1024,
        cpuCores = 12,
        abi = "arm64-v8a",
        androidVersion = 14
    )

    private val lowRamProfile = DeviceProfile(
        totalRamBytes = 4L * 1024 * 1024 * 1024,
        availableRamBytes = 2L * 1024 * 1024 * 1024,
        storageTotalBytes = 64L * 1024 * 1024 * 1024,
        storageAvailableBytes = 30L * 1024 * 1024 * 1024,
        cpuCores = 4,
        abi = "arm64-v8a",
        androidVersion = 14,
        isLowRamDevice = true
    )

    private val generousBudget = AiMemoryBudget(
        availableBytes = 10L * 1024 * 1024 * 1024,
        reservedBytes = 1L * 1024 * 1024 * 1024,
        runtimeOverheadBytes = 400L * 1024 * 1024,
        safetyReserveBytes = 700L * 1024 * 1024,
        modelMemoryBytes = 6L * 1024 * 1024 * 1024
    )

    private val tightBudget = AiMemoryBudget(
        availableBytes = 4L * 1024 * 1024 * 1024,
        reservedBytes = 512L * 1024 * 1024,
        runtimeOverheadBytes = 300L * 1024 * 1024,
        safetyReserveBytes = 500L * 1024 * 1024,
        modelMemoryBytes = 1L * 1024 * 1024 * 1024
    )

    private fun model(
        id: String = "model-1",
        estimatedMemoryMb: Int = 500,
        fileSizeMb: Int = 300,
        contextLength: Int = 4096,
        parameterCount: Long = 0
    ) = CatalogModel(
        id = id,
        name = "Test Model",
        provider = "test",
        description = "test",
        parameters = "$parameterCount",
        category = "chat",
        format = ModelFormat.GGUF,
        quantization = "Q4_K_M",
        fileSizeBytes = fileSizeMb.toLong() * 1024 * 1024,
        estimatedMemoryBytes = estimatedMemoryMb.toLong() * 1024 * 1024,
        contextLength = contextLength,
        license = "MIT",
        licenseType = "MIT",
        officialRepositoryUrl = "",
        downloadUrl = "",
        fileName = "model.gguf",
        runtime = "llama",
        parameterCount = parameterCount,
        chatTemplate = com.aichathub.app.domain.model.ChatTemplate.CHATML
    )

    @Before
    fun setUp() {
        engine = SmartRecommendationEngine()
    }

    // ── scoring with RAM variations ──────────────────────────────────

    @Test
    fun `scoring with sufficient RAM produces high score`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 1000),
            profile = highRamProfile,
            budget = generousBudget,
            mode = RecommendationMode.BEST_BALANCE
        )

        assertTrue(rec.score > 0.5f)
        assertTrue(rec.memoryScore > 0.5f)
    }

    @Test
    fun `scoring with low RAM produces lower score`() {
        val recHigh = engine.score(
            model = model(estimatedMemoryMb = 500),
            profile = highRamProfile,
            budget = generousBudget
        )
        val recLow = engine.score(
            model = model(estimatedMemoryMb = 500),
            profile = lowRamProfile,
            budget = tightBudget
        )

        assertTrue(recHigh.score >= recLow.score)
    }

    @Test
    fun `model exceeding budget gets penalized memory score`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 8000),
            profile = highRamProfile,
            budget = tightBudget
        )

        assertTrue(rec.memoryScore < 0.5f)
    }

    // ── recommendation modes ─────────────────────────────────────────

    @Test
    fun `BEST_BALANCE mode uses balanced weights`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 500, fileSizeMb = 200),
            profile = highRamProfile,
            budget = generousBudget,
            mode = RecommendationMode.BEST_BALANCE
        )

        assertEquals(RecommendationMode.BEST_BALANCE, rec.mode)
        assertTrue(rec.bestFor == "Best balance")
    }

    @Test
    fun `FASTEST mode prioritizes speed`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 500, fileSizeMb = 200),
            profile = highRamProfile,
            budget = generousBudget,
            mode = RecommendationMode.FASTEST
        )

        assertEquals(RecommendationMode.FASTEST, rec.mode)
        assertTrue(rec.bestFor == "Fastest")
    }

    @Test
    fun `BEST_QUALITY mode prioritizes memory and storage`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 500, fileSizeMb = 200),
            profile = highRamProfile,
            budget = generousBudget,
            mode = RecommendationMode.BEST_QUALITY
        )

        assertEquals(RecommendationMode.BEST_QUALITY, rec.mode)
        assertTrue(rec.bestFor == "Best quality")
    }

    @Test
    fun `SMALLEST mode prioritizes file size`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 500, fileSizeMb = 200),
            profile = highRamProfile,
            budget = generousBudget,
            mode = RecommendationMode.SMALLEST
        )

        assertEquals(RecommendationMode.SMALLEST, rec.mode)
        assertTrue(rec.bestFor == "Smallest")
    }

    @Test
    fun `different modes produce different scores for same model`() {
        val m = model(estimatedMemoryMb = 500, fileSizeMb = 300)
        val scores = RecommendationMode.entries.map { mode ->
            engine.score(m, highRamProfile, generousBudget, mode).score
        }

        // At least some scores should differ between modes
        val uniqueScores = scores.toSet()
        assertTrue(uniqueScores.size > 1)
    }

    // ── risk level classification ────────────────────────────────────

    @Test
    fun `SAFE risk for comfortable model`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 500),
            profile = highRamProfile,
            budget = generousBudget
        )

        assertEquals(RiskLevel.SAFE, rec.riskLevel)
    }

    @Test
    fun `NOT_RECOMMENDED risk for very large model on tight budget`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 8000, fileSizeMb = 5000),
            profile = lowRamProfile,
            budget = tightBudget
        )

        assertEquals(RiskLevel.NOT_RECOMMENDED, rec.riskLevel)
    }

    @Test
    fun `MODERATE risk for borderline model`() {
        // Model using most of the budget
        val rec = engine.score(
            model = model(estimatedMemoryMb = 900),
            profile = highRamProfile,
            budget = tightBudget
        )

        // Should be MODERATE or HEAVY depending on exact calculation
        assertTrue(rec.riskLevel == RiskLevel.MODERATE || rec.riskLevel == RiskLevel.HEAVY)
    }

    // ── thermal and battery states ───────────────────────────────────

    @Test
    fun `thermal throttling reduces score`() {
        val cool = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget,
            thermalStatus = ThermalStatus.NONE
        )
        val hot = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget,
            thermalStatus = ThermalStatus.SEVERE
        )

        assertTrue(cool.batteryThermalScore > hot.batteryThermalScore)
        assertTrue(cool.score >= hot.score)
    }

    @Test
    fun `critical thermal produces very low thermal score`() {
        val rec = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget,
            thermalStatus = ThermalStatus.CRITICAL
        )

        assertTrue(rec.batteryThermalScore < 0.2f)
    }

    @Test
    fun `emergency thermal produces zero thermal score`() {
        val rec = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget,
            thermalStatus = ThermalStatus.EMERGENCY
        )

        assertEquals(0f, rec.batteryThermalScore)
    }

    @Test
    fun `low battery reduces score`() {
        val charged = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget,
            batteryLevel = 90
        )
        val dying = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget,
            batteryLevel = 10
        )

        assertTrue(charged.batteryThermalScore >= dying.batteryThermalScore)
    }

    @Test
    fun `unknown battery level gets mild default`() {
        val rec = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget,
            batteryLevel = -1
        )

        // Unknown battery gets 0.7 * 0.35 weight contribution
        assertTrue(rec.batteryThermalScore > 0.5f)
    }

    // ── past benchmarks ──────────────────────────────────────────────

    @Test
    fun `past benchmark overrides estimated speed`() {
        val withoutBench = engine.score(
            model = model(id = "bench-model"),
            profile = lowRamProfile,
            budget = generousBudget
        )
        val withBench = engine.score(
            model = model(id = "bench-model"),
            profile = lowRamProfile,
            budget = generousBudget,
            pastBenchmarks = mapOf("bench-model" to 45f)
        )

        assertTrue(withBench.speedScore > withoutBench.speedScore)
    }

    // ── recommendAll ─────────────────────────────────────────────────

    @Test
    fun `recommendAll returns models sorted by score`() {
        val models = listOf(
            model(id = "heavy", estimatedMemoryMb = 8000),
            model(id = "light", estimatedMemoryMb = 200),
            model(id = "medium", estimatedMemoryMb = 1500)
        )

        val recs = engine.recommendAll(models, highRamProfile, generousBudget)

        assertEquals(3, recs.size)
        for (i in 0 until recs.size - 1) {
            assertTrue(recs[i].score >= recs[i + 1].score)
        }
    }

    @Test
    fun `recommendAll with single model returns one result`() {
        val recs = engine.recommendAll(
            listOf(model()),
            highRamProfile,
            generousBudget
        )

        assertEquals(1, recs.size)
    }

    // ── compatibility level ──────────────────────────────────────────

    @Test
    fun `high score maps to EXCELLENT compatibility`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 200),
            profile = highRamProfile,
            budget = generousBudget
        )

        assertEquals(CompatibilityLevel.EXCELLENT, rec.compatibilityLevel)
    }

    @Test
    fun `low score maps to NOT_RECOMMENDED compatibility`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 10000, fileSizeMb = 8000),
            profile = lowRamProfile,
            budget = tightBudget
        )

        assertEquals(CompatibilityLevel.NOT_RECOMMENDED, rec.compatibilityLevel)
    }

    // ── speed label ──────────────────────────────────────────────────

    @Test
    fun `fast device gets Fast speed label`() {
        val rec = engine.score(
            model = model(),
            profile = highRamProfile,
            budget = generousBudget
        )

        assertEquals("Fast", rec.expectedSpeed)
    }

    @Test
    fun `low RAM device with large model gets Slow label`() {
        val rec = engine.score(
            model = model(estimatedMemoryMb = 500, parameterCount = 2_000_000_000),
            profile = lowRamProfile,
            budget = tightBudget
        )

        assertEquals("Slow", rec.expectedSpeed)
    }

    // ── measured PSS ─────────────────────────────────────────────────

    @Test
    fun `measured PSS overrides estimated memory`() {
        val m = model(id = "measured-model", estimatedMemoryMb = 200)
        val rec = engine.score(
            model = m,
            profile = highRamProfile,
            budget = tightBudget,
            measuredPss = mapOf("measured-model" to (3L * 1024 * 1024 * 1024))
        )

        // With 3GB measured vs 200MB estimated, memory score should be worse
        assertTrue(rec.memoryScore < 0.5f)
    }

    // ── score bounds ─────────────────────────────────────────────────

    @Test
    fun `score is always between 0 and 1`() {
        val models = listOf(
            model(estimatedMemoryMb = 100, fileSizeMb = 50),
            model(estimatedMemoryMb = 10000, fileSizeMb = 8000)
        )
        val profiles = listOf(highRamProfile, lowRamProfile)
        val budgets = listOf(generousBudget, tightBudget)
        val thermals = listOf(ThermalStatus.NONE, ThermalStatus.CRITICAL)

        for (m in models) {
            for (p in profiles) {
                for (b in budgets) {
                    for (t in thermals) {
                        val rec = engine.score(m, p, b, thermalStatus = t)
                        assertTrue("Score ${rec.score} out of range", rec.score in 0f..1f)
                        assertTrue("Memory ${rec.memoryScore} out of range", rec.memoryScore in 0f..1f)
                        assertTrue("Speed ${rec.speedScore} out of range", rec.speedScore in 0f..1f)
                        assertTrue("Storage ${rec.storageScore} out of range", rec.storageScore in 0f..1f)
                        assertTrue("Battery ${rec.batteryThermalScore} out of range", rec.batteryThermalScore in 0f..1f)
                    }
                }
            }
        }
    }

    // ── storage score ────────────────────────────────────────────────

    @Test
    fun `tight storage reduces storage score`() {
        val profileTight = highRamProfile.copy(
            storageAvailableBytes = 600L * 1024 * 1024 // 600MB free
        )
        val rec = engine.score(
            model = model(fileSizeMb = 400),
            profile = profileTight,
            budget = generousBudget
        )

        assertTrue(rec.storageScore < 0.5f)
    }

    @Test
    fun `plenty of storage gives high storage score`() {
        val rec = engine.score(
            model = model(fileSizeMb = 100),
            profile = highRamProfile,
            budget = generousBudget
        )

        assertEquals(1.0f, rec.storageScore)
    }
}
