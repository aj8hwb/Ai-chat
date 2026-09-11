package com.aichathub.app.device

import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityEngineTest {

    private val engine = CompatibilityEngine()

    private val largeProfile = DeviceProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 4L * 1024 * 1024 * 1024,
        storageTotalBytes = 64L * 1024 * 1024 * 1024,
        storageAvailableBytes = 40L * 1024 * 1024 * 1024,
        cpuCores = 8,
        abi = "arm64-v8a",
        androidVersion = 14
    )

    private val lowRamProfile = DeviceProfile(
        totalRamBytes = 4L * 1024 * 1024 * 1024,
        availableRamBytes = 2L * 1024 * 1024 * 1024,
        storageTotalBytes = 32L * 1024 * 1024 * 1024,
        storageAvailableBytes = 20L * 1024 * 1024 * 1024,
        cpuCores = 4,
        abi = "arm64-v8a",
        androidVersion = 14,
        isLowRamDevice = true
    )

    private fun budget(modelMemoryMb: Int) = AiMemoryBudget(
        availableBytes = 4L * 1024 * 1024 * 1024,
        reservedBytes = 1L * 1024 * 1024 * 1024,
        runtimeOverheadBytes = 400L * 1024 * 1024,
        safetyReserveBytes = 700L * 1024 * 1024,
        modelMemoryBytes = modelMemoryMb.toLong() * 1024 * 1024
    )

    private fun model(
        estimatedMemoryMb: Int,
        parameterCount: Long = 0,
        fileSizeMb: Int = 0
    ) = CatalogModel(
        id = "test-model",
        name = "Test Model",
        provider = "test",
        description = "test",
        parameters = "${parameterCount}",
        category = "chat",
        format = ModelFormat.GGUF,
        quantization = "Q4_K_M",
        fileSizeBytes = fileSizeMb.toLong() * 1024 * 1024,
        estimatedMemoryBytes = estimatedMemoryMb.toLong() * 1024 * 1024,
        contextLength = 2048,
        license = "MIT",
        licenseType = "MIT",
        officialRepositoryUrl = "https://example.com",
        downloadUrl = "https://example.com/model.gguf",
        fileName = "model.gguf",
        runtime = "llama",
        parameterCount = parameterCount
    )

    @Test
    fun `small model on a comfortable budget is excellent`() {
        val level = engine.evaluate(model(400), largeProfile, budget(2048))
        assertEquals(CompatibilityLevel.EXCELLENT, level)
    }

    @Test
    fun `model using most of the budget is only recommended`() {
        // 1.5GB model, 2GB budget -> memoryScore 4; plenty of total RAM -> ramScore 5
        val level = engine.evaluate(model(1536), largeProfile, budget(2048))
        assertEquals(CompatibilityLevel.RECOMMENDED, level)
    }

    @Test
    fun `model far beyond the budget is not recommended`() {
        // 9GB model vs 2GB safe budget on an 8GB phone -> worst scores on both axes
        val level = engine.evaluate(model(9216), largeProfile, budget(2048))
        assertEquals(CompatibilityLevel.NOT_RECOMMENDED, level)
    }

    @Test
    fun `memory score uses the load-gate budget not total ram`() {
        // 1GB model on an 8GB device with only 1GB of safe AI budget:
        // the badge must NOT recommend it even though total RAM is huge.
        val level = engine.evaluate(model(1024), largeProfile, budget(1024))
        assertTrue(level.rank <= CompatibilityLevel.USABLE.rank)
    }

    @Test
    fun `low ram devices cap 1B+ models below excellent`() {
        val level = engine.evaluate(model(400, parameterCount = 1_000_000_000), lowRamProfile, budget(2048))
        assertEquals(CompatibilityLevel.USABLE, level)
    }

    @Test
    fun `quantization note appears only when over budget`() {
        val recs = engine.recommendAll(
            listOf(model(4096), model(200)),
            largeProfile,
            budget(2048)
        )
        val heavy = recs.first { it.model.estimatedMemoryBytes > budget(2048).modelMemoryBytes }
        assertNotNull(heavy.quantizationNote)
        val light = recs.first { it.model.estimatedMemoryBytes < budget(2048).modelMemoryBytes }
        assertNull(light.quantizationNote)
    }

    @Test
    fun `recommendations are ranked by level`() {
        val recs = engine.recommendAll(
            listOf(model(4096), model(200), model(1536)),
            largeProfile,
            budget(2048)
        )
        assertEquals(CompatibilityLevel.EXCELLENT, recs[0].level)
        assertEquals(CompatibilityLevel.RECOMMENDED, recs[1].level)
        // 4GB model vs 2GB budget: heavy but still within half of total RAM
        assertEquals(CompatibilityLevel.HEAVY, recs[2].level)
    }

    @Test
    fun `measured memory overrides the catalog estimate`() {
        // Catalog estimate 200MB (would be EXCELLENT) but the model actually
        // measured 5GB on this device -> the real footprint must win. 5GB on
        // an 8GB phone with a 2GB safe budget is HEAVY, not NOT_RECOMMENDED.
        val level = engine.evaluate(
            model(200),
            largeProfile,
            budget(2048),
            measuredMemory = mapOf("test-model" to (5L * 1024 * 1024 * 1024))
        )
        assertEquals(CompatibilityLevel.HEAVY, level)
    }

    @Test
    fun `measured memory is ignored for unknown models`() {
        // Measurement for a DIFFERENT model must not affect this one.
        val level = engine.evaluate(
            model(200),
            largeProfile,
            budget(2048),
            measuredMemory = mapOf("some-other-model" to (9L * 1024 * 1024 * 1024))
        )
        assertEquals(CompatibilityLevel.EXCELLENT, level)
    }

    @Test
    fun `load decision is safe within the safe budget`() {
        // 1.5GB model, 2GB usable budget: ≤ 1.0x -> SAFE. Same threshold the
        // badge calls USABLE or better — the gate can never refuse a model the
        // badge recommends.
        assertEquals(
            LoadDecision.SAFE,
            engine.loadDecision(model(1536), budget(2048))
        )
    }

    @Test
    fun `load decision is heavy but allowed up to 1_35x`() {
        // 2.4GB model, 2GB usable budget: 1.2x -> HEAVY band, still loadable.
        assertEquals(
            LoadDecision.HEAVY,
            engine.loadDecision(model(2458), budget(2048))
        )
        // Boundary: exactly 1.35x is still HEAVY (allowed).
        val boundary = (2048.0 * 1.35).toInt()
        assertEquals(
            LoadDecision.HEAVY,
            engine.loadDecision(model(boundary), budget(2048))
        )
    }

    @Test
    fun `load decision blocks beyond 1_35x`() {
        // 3.5GB model, 2GB usable budget: > 1.35x -> BLOCKED. This is the
        // exact band the badge calls NOT_RECOMMENDED — on-screen promise and
        // the load gate finally agree.
        assertEquals(
            LoadDecision.BLOCKED,
            engine.loadDecision(model(3584), budget(2048))
        )
        // Just past the boundary must flip to BLOCKED.
        val justOver = (2048.0 * 1.35 + 1).toInt()
        assertEquals(
            LoadDecision.BLOCKED,
            engine.loadDecision(model(justOver), budget(2048))
        )
    }

    @Test
    fun `load decision uses measured memory when available`() {
        // Catalog estimate 200MB (SAFE) but the model really measures 5GB on
        // this device: the gate must refuse based on the REAL footprint.
        assertEquals(
            LoadDecision.BLOCKED,
            engine.loadDecision(
                model(200),
                budget(2048),
                measuredMemory = mapOf("test-model" to (5L * 1024 * 1024 * 1024))
            )
        )
    }
}