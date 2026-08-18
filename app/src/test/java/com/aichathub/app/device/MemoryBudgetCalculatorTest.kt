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
        androidVersion = 14
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
}