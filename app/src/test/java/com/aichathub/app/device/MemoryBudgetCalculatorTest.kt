package com.aichathub.app.device

import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBudgetCalculatorTest {

    private fun gb(gb: Int): Long = gb * 1024L * 1024L * 1024L
    private fun mb(mb: Int): Long = mb * 1024L * 1024L

    private fun profile(totalGb: Int, availableMb: Int) = DeviceProfile(
        totalRamBytes = gb(totalGb),
        availableRamBytes = mb(availableMb),
        storageTotalBytes = gb(64),
        storageAvailableBytes = gb(40),
        cpuCores = 8,
        abi = "arm64-v8a",
        androidVersion = 14,
    )

    @Test
    fun `reserves overhead and safety for an 8GB device`() {
        val budget = MemoryBudgetCalculator.calculate(profile(8, 4096))

        // appOverhead = max(512MB, total/8 = 1GB)
        assertEquals(mb(1024), budget.reservedBytes)
        // total > 6GB
        assertEquals(mb(400), budget.runtimeOverheadBytes)
        assertEquals(mb(700), budget.safetyReserveBytes)
        // 4096 - 1024 - 400 - 700
        assertEquals(mb(1972), budget.modelMemoryBytes)
    }

    @Test
    fun `4GB device keeps a smaller reserve`() {
        val budget = MemoryBudgetCalculator.calculate(profile(4, 2048))

        assertEquals(mb(512), budget.reservedBytes)
        assertEquals(mb(300), budget.runtimeOverheadBytes)
        assertEquals(mb(500), budget.safetyReserveBytes)
        // 2048 - 512 - 300 - 500
        assertEquals(mb(736), budget.modelMemoryBytes)
    }

    @Test
    fun `budget is never negative`() {
        val budget = MemoryBudgetCalculator.calculate(profile(8, 256))
        assertEquals(0L, budget.modelMemoryBytes)
    }

    @Test
    fun `usableBytes excludes overhead but not the app reserve`() {
        val budget = MemoryBudgetCalculator.calculate(profile(8, 4096))
        // 4096 - 400 - 700
        assertEquals(mb(2996), budget.usableBytes)
    }

    // ── KV-cache metadata estimation ────────────────────────────────────

    /**
     * Typical GQA encoder: 32 layers, 8 KV heads, 128 head dim, f16 cache
     * → 2(K+V) × 32 × 8 × 128 × 2 B = 131072 bytes/token.
     */
    @Test
    fun `kv metadata derives exact bytes per token`() {
        val spec = MemoryBudgetCalculator.KvCacheSpec(
            layers = 32,
            kvHeads = 8,
            headDim = 128,
            bytesPerElement = 2,
        )
        assertEquals(131_072L, spec.bytesPerToken)
    }

    @Test
    fun `kv metadata overrides parameter-count heuristic`() {
        val weights = mb(2000)
        val withMetadata = MemoryBudgetCalculator.calculate(
            profile(8, 4096),
            weights,
            contextLength = 4096,
            parameterCount = 7_000_000_000L,
            kvCacheSpec = MemoryBudgetCalculator.KvCacheSpec(32, 8, 128),
        )
        // kvCacheMemory must be exactly spec.bytesPerToken × context
        assertEquals(131_072L * 4096, withMetadata.kvCacheMemoryBytes)
        // The 7B parameter heuristic would have produced the 512 B/token
        // reference — prove metadata actually changed the result.
        val withoutMetadata = MemoryBudgetCalculator.calculate(
            profile(8, 4096),
            weights,
            contextLength = 4096,
            parameterCount = 7_000_000_000L,
        )
        assertTrue(withoutMetadata.kvCacheMemoryBytes != withMetadata.kvCacheMemoryBytes)
    }

    @Test
    fun `parameter-count heuristic produces reference bytes for 7B`() {
        val budget = MemoryBudgetCalculator.calculate(
            profile(8, 4096),
            modelWeightsBytes = mb(4000),
            contextLength = 1024,
            parameterCount = 7_000_000_000L,
        )
        assertEquals(512L * 1024, budget.kvCacheMemoryBytes)
    }

    @Test
    fun `max context honors kv metadata`() {
        val spec = MemoryBudgetCalculator.KvCacheSpec(32, 8, 128) // 131072 B/token
        val weights = mb(2000)
        val budget = MemoryBudgetCalculator.calculate(
            profile(8, 4096),
            weights,
            contextLength = 8192,
            parameterCount = null,
            kvCacheSpec = spec,
        )
        // availableForContext = 1972MB - 2000MB - 128KB → negative → 0
        assertEquals(0, budget.maxContextTokens)
    }

    @Test
    fun `max context counts down remaining budget with kv bytes`() {
        val spec = MemoryBudgetCalculator.KvCacheSpec(20, 8, 128) // 81920 B/token
        val weights = mb(1000)
        val budget = MemoryBudgetCalculator.calculate(
            profile(8, 4096),
            weights,
            contextLength = 2048,
            parameterCount = null,
            kvCacheSpec = spec,
        )
        val availableForContext = mb(1972) - mb(1000) -
            AiMemoryBudget.THREAD_BUFFER_PER_THREAD * AiMemoryBudget.DEFAULT_THREADS
        assertEquals(availableForContext / 81_920L, budget.maxContextTokens.toLong())
    }

    @Test
    fun `kv spec rejects invalid metadata`() {
        try {
            MemoryBudgetCalculator.KvCacheSpec(layers = 0, kvHeads = 8, headDim = 128)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
