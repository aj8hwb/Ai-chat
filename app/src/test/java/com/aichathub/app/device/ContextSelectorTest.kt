package com.aichathub.app.device

import com.aichathub.app.device.ContextSelector.ContextBudget
import com.aichathub.app.device.ContextSelector.ContextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSelectorTest {

    @Test
    fun `unknown budget falls back to the documented conservative ceiling, never the model max`() {
        val result = ContextSelector.selectContext(8192, ContextBudget.Unknown)
        assertEquals(
            ContextSelection.Selected(ContextSelector.CONSERVATIVE_FALLBACK_CONTEXT),
            result,
        )
        assertTrue(result !is ContextSelection.Insufficient)
        // The model maximum is NEVER auto-selected for an unknown budget.
        assertTrue(
            (result as ContextSelection.Selected).tokens <
                8192,
        )
    }

    @Test
    fun `failed budget behaves like unknown and never like zero or unlimited`() {
        val result = ContextSelector.selectContext(8192, ContextBudget.Failed)
        assertEquals(
            ContextSelection.Selected(ContextSelector.CONSERVATIVE_FALLBACK_CONTEXT),
            result,
        )
    }

    @Test
    fun `unknown budget with a small model max is bounded by the model`() {
        assertEquals(
            ContextSelection.Selected(1024),
            ContextSelector.selectContext(1024, ContextBudget.Unknown),
        )
    }

    @Test
    fun `unknown budget rejects a session larger than the conservative fallback`() {
        val result = ContextSelector.selectContext(
            8192,
            ContextBudget.Unknown,
            reservedTokens = ContextSelector.CONSERVATIVE_FALLBACK_CONTEXT + 1L,
        ) as ContextSelection.Insufficient
        assertTrue(result.reason.contains("fallback"))
    }

    @Test
    fun `known zero budget is an explicit insufficiency, never the model max`() {
        val result = ContextSelector.selectContext(8192, ContextBudget.Known(0)) as ContextSelection.Insufficient
        assertTrue(result.reason.contains("0 context tokens"))
    }

    @Test
    fun `negative budget is invalid input treated as known-zero, not unknown`() {
        val result = ContextSelector.selectContext(8192, ContextBudget.Known(-100)) as ContextSelection.Insufficient
        assertTrue(result.reason.contains("0 context tokens"))
    }

    @Test
    fun `known budget smaller than the runtime minimum fails explicitly`() {
        val result = ContextSelector.selectContext(8192, ContextBudget.Known(100)) as ContextSelection.Insufficient
        assertTrue(result.reason.contains("below the 512-token runtime minimum"))
    }

    @Test
    fun `budget exactly equal to the minimum context is selectable`() {
        val result = ContextSelector.selectContext(8192, ContextBudget.Known(ContextSelector.MIN_CONTEXT))
        assertEquals(ContextSelection.Selected(ContextSelector.MIN_CONTEXT), result)
    }

    @Test
    fun `known budget restricts a larger model max`() {
        assertEquals(
            ContextSelection.Selected(2048),
            ContextSelector.selectContext(8192, ContextBudget.Known(2048)),
        )
    }

    @Test
    fun `known budget larger than the model max never exceeds the model`() {
        assertEquals(
            ContextSelection.Selected(4096),
            ContextSelector.selectContext(4096, ContextBudget.Known(32_768)),
        )
    }

    @Test
    fun `budget equal to model max passes through`() {
        assertEquals(
            ContextSelection.Selected(4096),
            ContextSelector.selectContext(4096, ContextBudget.Known(4096)),
        )
    }

    // ── prompt + reserved generation tokens ────────────────────────────

    @Test
    fun `reserved tokens above the budget fail with the required minimum`() {
        val result = ContextSelector.selectContext(
            8192,
            ContextBudget.Known(2048),
            reservedTokens = 4000L,
        ) as ContextSelection.Insufficient
        assertEquals(4000L, result.requiredMinimum)
        assertTrue(result.reason.contains("safe memory budget"))
    }

    @Test
    fun `reserved tokens above the model maximum fail`() {
        val result = ContextSelector.selectContext(
            2048,
            ContextBudget.Unknown,
            reservedTokens = 3072L,
        ) as ContextSelection.Insufficient
        assertEquals(3072L, result.requiredMinimum)
        assertTrue(result.reason.contains("model's own context limit"))
    }

    @Test
    fun `reserved tokens below the minimum select at least the minimum`() {
        // Budget allows 4096; reservation 300 < 512 → still selects ≥ 512.
        val result = ContextSelector.selectContext(8192, ContextBudget.Known(4096), reservedTokens = 300L)
        assertEquals(ContextSelection.Selected(4096), result)
    }

    @Test
    fun `selection never drops below the reservation when reservation exceeds minimum`() {
        // Budget 2048, reservation 1000 → selection capped by budget but must
        // still be ≥ reservation since the session needs to fit.
        // 2048 ≥ 1000, so Selected(2048) is correct.
        assertEquals(
            ContextSelection.Selected(2048),
            ContextSelector.selectContext(8192, ContextBudget.Known(2048), reservedTokens = 1000L),
        )
    }

    // ── invalid / boundary inputs ───────────────────────────────────────

    @Test
    fun `negative reserved tokens are treated as zero`() {
        assertEquals(
            ContextSelection.Selected(ContextSelector.CONSERVATIVE_FALLBACK_CONTEXT),
            ContextSelector.selectContext(8192, ContextBudget.Unknown, reservedTokens = -50L),
        )
    }

    @Test
    fun `unknown model max falls back to the runtime minimum`() {
        assertEquals(
            ContextSelection.Selected(ContextSelector.MIN_CONTEXT),
            ContextSelector.selectContext(0, ContextBudget.Unknown),
        )
        assertEquals(
            ContextSelection.Selected(ContextSelector.MIN_CONTEXT),
            ContextSelector.selectContext(-500, ContextBudget.Unknown),
        )
    }

    @Test
    fun `very large budgets are clamped to Int-safe values`() {
        // Int.MAX_VALUE budget with 4096 model max → model max wins.
        assertEquals(
            ContextSelection.Selected(4096),
            ContextSelector.selectContext(4096, ContextBudget.Known(Int.MAX_VALUE)),
        )
    }

    @Test
    fun `very large reservation does not overflow`() {
        // Long reservation far beyond Int range → plain insufficiency.
        val result = ContextSelector.selectContext(
            8192,
            ContextBudget.Known(2048),
            reservedTokens = Long.MAX_VALUE,
        ) as ContextSelection.Insufficient
        assertEquals(Long.MAX_VALUE, result.requiredMinimum)
    }

    // ── budget-state bridge ─────────────────────────────────────────────

    @Test
    fun `toBudget maps unknown weights to Unknown`() {
        assertEquals(ContextBudget.Unknown, ContextSelector.toBudget(0, budgetKnown = false))
    }

    @Test
    fun `toBudget maps known-weights zero headroom to Known zero`() {
        assertEquals(ContextBudget.Known(0), ContextSelector.toBudget(0, budgetKnown = true))
        assertTrue(ContextSelector.toBudget(0, budgetKnown = true) !is ContextBudget.Unknown)
    }

    @Test
    fun `toBudget maps known positive headroom to Known`() {
        assertEquals(ContextBudget.Known(2048), ContextSelector.toBudget(2048, budgetKnown = true))
    }

    @Test
    fun `insufficient memory after weights and kv cache yields Known zero`() {
        // Simulate the calculator semantics: weights known (budgetKnown=true),
        // headroom after weights+KV leaves no tokens → Known(0) → Insufficient.
        val budget = ContextSelector.toBudget(0, budgetKnown = true)
        val result = ContextSelector.selectContext(8192, budget)
        assertTrue(result is ContextSelection.Insufficient && result !is ContextSelection.Selected)
    }
}
