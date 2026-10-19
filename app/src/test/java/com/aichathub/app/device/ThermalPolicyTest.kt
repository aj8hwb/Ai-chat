package com.aichathub.app.device

import com.aichathub.app.util.ModelThreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure thermal decision policy (no Android framework calls — the
 * PowerManager status is passed in as a plain int so API-29 fallbacks can be
 * simulated on the JVM).
 */
class ThermalPolicyTest {

    // ── battery temperature mapping ─────────────────────────────────────

    @Test
    fun `null temperature maps to normal`() {
        assertEquals(ThermalManager.ThermalState.NORMAL, ThermalPolicy.stateFromBatteryTemp(null))
    }

    @Test
    fun `temperature bands map to the documented states`() {
        assertEquals(ThermalManager.ThermalState.NORMAL, ThermalPolicy.stateFromBatteryTemp(25f))
        assertEquals(ThermalManager.ThermalState.NORMAL, ThermalPolicy.stateFromBatteryTemp(36.9f))
        assertEquals(ThermalManager.ThermalState.WARM, ThermalPolicy.stateFromBatteryTemp(37f))
        assertEquals(ThermalManager.ThermalState.HOT, ThermalPolicy.stateFromBatteryTemp(40f))
        assertEquals(ThermalManager.ThermalState.CRITICAL, ThermalPolicy.stateFromBatteryTemp(43f))
    }

    // ── PowerManager status mapping (API 29 fallback path) ─────────────

    @Test
    fun `null power status maps to normal`() {
        assertEquals(ThermalManager.ThermalState.NORMAL, ThermalPolicy.stateFromPowerStatus(null))
    }

    @Test
    fun `negative power status maps to normal`() {
        assertEquals(ThermalManager.ThermalState.NORMAL, ThermalPolicy.stateFromPowerStatus(-1))
    }

    @Test
    fun `power manager codes map onto app states`() {
        // NONE, LIGHT → NORMAL; MODERATE → WARM; SEVERE → HOT; CRITICAL+ → CRITICAL
        assertEquals(ThermalManager.ThermalState.NORMAL, ThermalPolicy.stateFromPowerStatus(0))
        assertEquals(ThermalManager.ThermalState.NORMAL, ThermalPolicy.stateFromPowerStatus(1))
        assertEquals(ThermalManager.ThermalState.WARM, ThermalPolicy.stateFromPowerStatus(2))
        assertEquals(ThermalManager.ThermalState.HOT, ThermalPolicy.stateFromPowerStatus(3))
        assertEquals(ThermalManager.ThermalState.CRITICAL, ThermalPolicy.stateFromPowerStatus(4))
        assertEquals(ThermalManager.ThermalState.CRITICAL, ThermalPolicy.stateFromPowerStatus(6))
    }

    // ── conservative combination ─────────────────────────────────────────

    @Test
    fun `combine picks the worse signal`() {
        assertEquals(
            ThermalManager.ThermalState.HOT,
            ThermalPolicy.combine(ThermalManager.ThermalState.NORMAL, ThermalManager.ThermalState.HOT)
        )
        assertEquals(
            ThermalManager.ThermalState.CRITICAL,
            ThermalPolicy.combine(ThermalManager.ThermalState.WARM, ThermalManager.ThermalState.CRITICAL)
        )
        assertEquals(
            ThermalManager.ThermalState.NORMAL,
            ThermalPolicy.combine(ThermalManager.ThermalState.NORMAL, ThermalManager.ThermalState.NORMAL)
        )
    }

    // ── thread recommendation ────────────────────────────────────────────

    @Test
    fun `normal state keeps full threads`() {
        assertEquals(4, ThermalPolicy.recommendedThreads(ThermalManager.ThermalState.NORMAL, 4))
    }

    @Test
    fun `warm halves nothing, reduces by a quarter`() {
        assertEquals(3, ThermalPolicy.recommendedThreads(ThermalManager.ThermalState.WARM, 4))
    }

    @Test
    fun `hot halves threads`() {
        assertEquals(2, ThermalPolicy.recommendedThreads(ThermalManager.ThermalState.HOT, 4))
    }

    @Test
    fun `critical forces one thread`() {
        assertEquals(1, ThermalPolicy.recommendedThreads(ThermalManager.ThermalState.CRITICAL, 4))
    }

    @Test
    fun `recommended threads never below one even for zero request`() {
        assertEquals(1, ThermalPolicy.recommendedThreads(ThermalManager.ThermalState.NORMAL, 0))
    }

    // ── throttle decision ────────────────────────────────────────────────

    @Test
    fun `throttle only on hot or critical`() {
        assertFalse(ThermalPolicy.shouldThrottle(ThermalManager.ThermalState.NORMAL))
        assertFalse(ThermalPolicy.shouldThrottle(ThermalManager.ThermalState.WARM))
        assertTrue(ThermalPolicy.shouldThrottle(ThermalManager.ThermalState.HOT))
        assertTrue(ThermalPolicy.shouldThrottle(ThermalManager.ThermalState.CRITICAL))
    }

    // ── load level bridge (keeps util layer decoupled) ──────────────────

    @Test
    fun `load level mapping covers all states`() {
        assertEquals(
            ModelThreads.ThermalLoadLevel.NORMAL,
            ThermalPolicy.toLoadLevel(ThermalManager.ThermalState.NORMAL)
        )
        assertEquals(
            ModelThreads.ThermalLoadLevel.WARM,
            ThermalPolicy.toLoadLevel(ThermalManager.ThermalState.WARM)
        )
        assertEquals(
            ModelThreads.ThermalLoadLevel.HOT,
            ThermalPolicy.toLoadLevel(ThermalManager.ThermalState.HOT)
        )
        assertEquals(
            ModelThreads.ThermalLoadLevel.CRITICAL,
            ThermalPolicy.toLoadLevel(ThermalManager.ThermalState.CRITICAL)
        )
    }

    // ── hysteresis (fluctuating readings must not flap configs) ─────────

    @Test
    fun `escalation applies immediately`() {
        assertEquals(
            ThermalManager.ThermalState.CRITICAL,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.NORMAL, ThermalManager.ThermalState.CRITICAL)
        )
        assertEquals(
            ThermalManager.ThermalState.WARM,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.NORMAL, ThermalManager.ThermalState.WARM)
        )
    }

    @Test
    fun `equal reading keeps the current state`() {
        assertEquals(
            ThermalManager.ThermalState.HOT,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.HOT, ThermalManager.ThermalState.HOT)
        )
    }

    @Test
    fun `one-rank dip does not de-escalate (no flapping)`() {
        assertEquals(
            ThermalManager.ThermalState.HOT,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.HOT, ThermalManager.ThermalState.WARM)
        )
        assertEquals(
            ThermalManager.ThermalState.WARM,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.WARM, ThermalManager.ThermalState.NORMAL)
        )
        assertEquals(
            ThermalManager.ThermalState.CRITICAL,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.CRITICAL, ThermalManager.ThermalState.HOT)
        )
    }

    @Test
    fun `clear recovery de-escalates`() {
        assertEquals(
            ThermalManager.ThermalState.NORMAL,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.WARM, ThermalManager.ThermalState.NORMAL)
        )
        assertEquals(
            ThermalManager.ThermalState.WARM,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.CRITICAL, ThermalManager.ThermalState.WARM)
        )
        assertEquals(
            ThermalManager.ThermalState.NORMAL,
            ThermalPolicy.stabilize(ThermalManager.ThermalState.HOT, ThermalManager.ThermalState.NORMAL)
        )
    }
}
