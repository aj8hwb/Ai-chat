package com.aichathub.app.device

/**
 * Pure decision logic that separates a model's MAXIMUM supported context from
 * the context SELECTED for a particular device/session.
 *
 * Budget states are represented EXPLICITLY and never conflated:
 *  - [ContextBudget.Unknown]   — the memory-budget calculation produced no
 *    per-token KV data (e.g. model weights unknown). NO safety claim can be
 *    made, so the model's maximum context is NEVER selected automatically
 *    (an unverified configuration must not look past the safety policy);
 *    instead a documented CONSERVATIVE fallback ([CONSERVATIVE_FALLBACK_CONTEXT])
 *    bounds the allocation while keeping the session usable.
 *  - [ContextBudget.Failed]    — the calculation explicitly failed. Same
 *    treatment as Unknown, always logged as such by the caller.
 *  - [ContextBudget.Known(0)] or a negative/invalid budget — the budget IS
 *    known and there are NO tokens affordable. This is NEVER treated as
 *    unknown and NEVER allowed to degrade to the model's maximum context.
 *  - [ContextBudget.Known(k)] with k > 0 — hard cap.
 *
 * Invariants (all enforced):
 *  1. selected ≤ model's supported maximum.
 *  2. selected ≤ safe budget (when the budget is Known).
 *  3. selected ≥ [MIN_CONTEXT] (the llama.cpp runtime minimum) — but only
 *     when that minimum actually fits the budget; if the safe budget is
 *     smaller than the runtime minimum the selection FAILS explicitly instead
 *     of silently over-allocating.
 *  4. selected ≥ context needed by the session itself: prompt tokens plus
 *     reserved generation tokens. If even the model maximum or the safe
 *     budget cannot fit that, the selection FAILS explicitly.
 *
 * Arithmetic is performed in Long and coerced at the boundaries to protect
 * against Int overflow with extreme inputs.
 */
object ContextSelector {

    /** llama.cpp hard minimum context (mirrored in LlamaCppRuntime.load). */
    const val MIN_CONTEXT = 512

    /**
     * Documented conservative ceiling used when the memory budget is
     * [ContextBudget.Unknown] or [ContextBudget.Failed].
     *
     * Rationale (spec §2.7): an unknown budget is NOT unlimited memory, so
     * automatically selecting the model maximum would authorize a context
     * configuration nobody has verified fits. This value bounds the risk to a
     * small, always-stated allocation: even at the worst-case per-token KV
     * estimate used by [MemoryBudgetCalculator] (8 KiB/token), 2048 tokens cap
     * the KV cache at ~16 MB — small enough that any device able to hold the
     * model weights can hold it, and small enough that an OOM here is a
     * recoverable exception, not a device freeze.
     *
     * This is a BOUNDED fallback, not a safety guarantee: the load-eligibility
     * gate, the thermal policy and the runtime's own allocation failure
     * handling remain the actual safety mechanisms.
     */
    const val CONSERVATIVE_FALLBACK_CONTEXT = 2048

    // ── input states ────────────────────────────────────────────────────

    /**
     * How much context the device memory budget can afford.
     */
    sealed interface ContextBudget {
        /** No usable budget data — unknown, not zero. */
        data object Unknown : ContextBudget

        /** The budget calculation failed or produced incoherent data. */
        data object Failed : ContextBudget

        /**
         * A budget with real data. [maxSafeTokens] ≤ 0 means: known budget,
         * zero/negatively-sized headroom. NEVER remapped to "unknown".
         */
        data class Known(val maxSafeTokens: Int) : ContextBudget
    }

    /** Result of a context selection. */
    sealed interface ContextSelection {
        /** A safe, bounded context length to load. */
        data class Selected(val tokens: Int) : ContextSelection

        /**
         * No safe context exists for the requested session on this budget.
         * Reasons are user-actionable and never silently fall back to a
         * larger context.
         */
        data class Insufficient(
            val requiredMinimum: Long,
            val reason: String
        ) : ContextSelection
    }

    // ── public API ──────────────────────────────────────────────────────

    /**
     * Bridges a [MemoryBudgetCalculator] result into a [ContextBudget].
     *
     * `maxContextTokens == 0` has two distinct meanings in the calculator:
     *  - weights unknown (`weightsMemoryBytes == 0`) → budget UNKNOWN — the
     *    per-token KV path never ran;
     *  - weights known but the remaining headroom is ≤ 0 → budget KNOWN and
     *    zero — an explicit insufficiency, not an unknown.
     */
    fun toBudget(maxSafeContextTokens: Int, budgetKnown: Boolean): ContextBudget = when {
        !budgetKnown -> ContextBudget.Unknown
        maxSafeContextTokens <= 0 -> ContextBudget.Known(0)
        else -> ContextBudget.Known(maxSafeContextTokens)
    }

    /**
     * Selects the context length to load for this session.
     *
     * @param modelMaxContext the maximum context the model supports; values
     *   < 1 mean the metadata is missing and are treated as the runtime
     *   minimum (documented fallback, same clamp the native runtime applies).
     * @param budget the device memory budget state (see [ContextBudget]).
     * @param reservedTokens tokens the session itself needs: prompt tokens
     *   plus reserved generation tokens. Must be ≥ 0; larger values are used
     *   as-is in Long arithmetic (no Int overflow).
     */
    fun selectContext(modelMaxContext: Int, budget: ContextBudget, reservedTokens: Long = 0L): ContextSelection {
        // Session requirement: it must fit the runtime minimum AND the
        // requested prompt + generation reservation.
        val needed = maxOf(MIN_CONTEXT.toLong(), reservedTokens.coerceAtLeast(0L))

        val modelMax: Long = if (modelMaxContext >= 1) modelMaxContext.toLong() else MIN_CONTEXT.toLong()

        // 1) The context can never exceed what the session itself needs to fit.
        if (modelMax < needed) {
            return insufficient(needed, modelMax, "the model's own context limit")
        }

        // 2) Unknown / failed budgets are NEVER treated as unlimited memory:
        //    the model maximum is not auto-selected (nobody verified it fits).
        //    A documented conservative ceiling bounds the allocation instead
        //    (see [CONSERVATIVE_FALLBACK_CONTEXT]). A session that does not
        //    even fit the ceiling fails explicitly instead of silently
        //    over-allocating on unverifiable data.
        if (budget is ContextBudget.Unknown || budget is ContextBudget.Failed) {
            val fallback = minOf(modelMax, CONSERVATIVE_FALLBACK_CONTEXT.toLong())
            if (fallback < needed) {
                return ContextSelection.Insufficient(
                    requiredMinimum = needed,
                    reason = "The device memory budget is unavailable, so context is limited to a safe " +
                        "$fallback-token fallback and this conversation needs at least $needed. " +
                        "Shorten the message or try again when more memory information is available.",
                )
            }
            return ContextSelection.Selected(fallback.toInt())
        }

        // 3) Known budget. A negative budget is invalid input, not "unknown";
        //    treat it as zero affordable tokens (conservative, documented).
        val safeTokens = (budget as ContextBudget.Known).maxSafeTokens
        val safeLong = safeTokens.coerceAtLeast(0).toLong()

        if (safeLong <= 0L) {
            return ContextSelection.Insufficient(
                requiredMinimum = needed,
                reason = "The safe memory budget for this model allows 0 context tokens. " +
                    "Close other apps or try a lighter model (needs at least $needed tokens).",
            )
        }

        // 4) Hard cap: min(model max, budget). Long math; result re-checked
        //    against the session requirement and the runtime minimum.
        val candidate = minOf(modelMax, safeLong)

        if (candidate >= 1 && candidate < MIN_CONTEXT) {
            return ContextSelection.Insufficient(
                requiredMinimum = needed,
                reason = "The safe memory budget allows only $candidate context tokens, " +
                    "below the ${MIN_CONTEXT}-token runtime minimum. Try a lighter model.",
            )
        }

        if (candidate < needed) {
            return insufficient(needed, candidate, "the device safe memory budget")
        }

        return ContextSelection.Selected(candidate.toInt())
    }

    private fun insufficient(needed: Long, available: Long, source: String): ContextSelection.Insufficient =
        ContextSelection.Insufficient(
            requiredMinimum = needed,
            reason = "This conversation needs at least $needed tokens of context, but only " +
                "$available are available from $source. Shorten the message or try a lighter model.",
        )
}
