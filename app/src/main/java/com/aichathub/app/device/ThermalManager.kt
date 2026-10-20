package com.aichathub.app.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure, Android-call-free policy for device thermal decisions.
 *
 * Two signals are combined CONSERVATIVELY (the worse state wins):
 *  1. Android PowerManager thermal status (API 29+) — the authoritative
 *     device thermal signal.
 *  2. Battery temperature from the sticky battery broadcast — supplementary
 *     only, because thresholds are approximate skin-temperature proxies and
 *     the value is missing on some devices or emulators.
 *
 * A missing signal NEVER implies a degraded state: unknown values map to
 * NORMAL so a device that cannot report thermals keeps full performance.
 */
object ThermalPolicy {

    /**
     * Approximate battery skin-temperature thresholds in Celsius.
     * Supplementary signal — PowerManager status takes precedence when both
     * are available.
     */
    const val THRESHOLD_WARM = 37.0f
    const val THRESHOLD_HOT = 40.0f
    const val THRESHOLD_CRITICAL = 43.0f

    fun stateFromBatteryTemp(celsius: Float?): ThermalManager.ThermalState {
        if (celsius == null) return ThermalManager.ThermalState.NORMAL
        return when {
            celsius >= THRESHOLD_CRITICAL -> ThermalManager.ThermalState.CRITICAL
            celsius >= THRESHOLD_HOT -> ThermalManager.ThermalState.HOT
            celsius >= THRESHOLD_WARM -> ThermalManager.ThermalState.WARM
            else -> ThermalManager.ThermalState.NORMAL
        }
    }

    /**
     * Maps a raw [PowerManager] thermal status code (THERMAL_STATUS_NONE 0 …
     * THERMAL_STATUS_SHUTDOWN 6) onto the app's ThermalState scale.
     * `null` (API < 29 or probe failed) → NORMAL.
     */
    fun stateFromPowerStatus(pmStatus: Int?): ThermalManager.ThermalState {
        if (pmStatus == null || pmStatus < 0) return ThermalManager.ThermalState.NORMAL
        return when (pmStatus) {
            0, 1 -> ThermalManager.ThermalState.NORMAL // NONE, LIGHT
            2 -> ThermalManager.ThermalState.WARM // MODERATE
            3 -> ThermalManager.ThermalState.HOT // SEVERE
            else -> ThermalManager.ThermalState.CRITICAL // CRITICAL, EMERGENCY, SHUTDOWN
        }
    }

    /** The worse (hotter) of two thermal states wins. */
    fun combine(a: ThermalManager.ThermalState, b: ThermalManager.ThermalState): ThermalManager.ThermalState {
        val order = listOf(
            ThermalManager.ThermalState.NORMAL,
            ThermalManager.ThermalState.WARM,
            ThermalManager.ThermalState.HOT,
            ThermalManager.ThermalState.CRITICAL,
        )
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    /**
     * Hysteresis filter (spec §6.4): prevailing/“stabilized” states escalate
     * INSTANTLY when the current state is worse, but only de-escalate when
     * the device has recovered by at least TWO ranks. Prevents configuration
     * flapping when battery temperature oscillates around a threshold.
     *
     * @param stabilized the previously accepted (sticky) state
     * @param current the freshly computed raw state
     */
    fun stabilize(
        stabilized: ThermalManager.ThermalState,
        current: ThermalManager.ThermalState
    ): ThermalManager.ThermalState = stabilize(stabilized, current, cleared = false)

    /**
     * Same hysteresis filter, with an explicit [cleared] event: when the OS
     * reports that thermal throttling was CLEARED (not just a sensor dip),
     * the sticky state fully de-escalates to the current reading — a one-rank
     * spontaneous dip caused by sensor noise would hold, but a clear event
     * is authoritative.
     */
    fun stabilize(
        stabilized: ThermalManager.ThermalState,
        current: ThermalManager.ThermalState,
        cleared: Boolean
    ): ThermalManager.ThermalState {
        val rank = { s: ThermalManager.ThermalState ->
            when (s) {
                ThermalManager.ThermalState.NORMAL -> 0
                ThermalManager.ThermalState.WARM -> 1
                ThermalManager.ThermalState.HOT -> 2
                ThermalManager.ThermalState.CRITICAL -> 3
            }
        }
        return when {
            rank(current) >= rank(stabilized) -> current // escalate immediately
            cleared -> current // explicit clear event → full de-escalation
            rank(stabilized) - rank(current) >= 2 -> current // clearly recovered
            else -> stabilized // hold — do not flap on 1-rank dips
        }
    }

    /** Thread scaling per state — mirrors [com.aichathub.app.util.ModelThreads] reductions. */
    fun recommendedThreads(state: ThermalManager.ThermalState, maxThreads: Int): Int {
        val base = maxThreads.coerceAtLeast(1)
        return when (state) {
            ThermalManager.ThermalState.NORMAL -> base
            ThermalManager.ThermalState.WARM -> (base * 0.75f).toInt().coerceAtLeast(1)
            ThermalManager.ThermalState.HOT -> (base * 0.5f).toInt().coerceAtLeast(1)
            ThermalManager.ThermalState.CRITICAL -> 1
        }
    }

    fun shouldThrottle(state: ThermalManager.ThermalState): Boolean =
        state == ThermalManager.ThermalState.HOT || state == ThermalManager.ThermalState.CRITICAL

    fun reason(state: ThermalManager.ThermalState, powerStatusKnown: Boolean): String = when (state) {
        ThermalManager.ThermalState.NORMAL ->
            if (powerStatusKnown) {
                "Thermal status normal — full performance"
            } else {
                "No thermal signal available — assuming normal"
            }
        ThermalManager.ThermalState.WARM -> "Device warm — reducing threads to prevent throttling"
        ThermalManager.ThermalState.HOT -> "Device hot — significant thread reduction required"
        ThermalManager.ThermalState.CRITICAL -> "Thermal state critical — minimum threads or pause"
    }

    /**
     * Maps the app-scale state to the pure thermal load level consumed by
     * [com.aichathub.app.util.ModelThreads]. Kept here so the util layer
     * stays independent of this android-touching class.
     */
    fun toLoadLevel(state: ThermalManager.ThermalState): com.aichathub.app.util.ModelThreads.ThermalLoadLevel =
        when (state) {
            ThermalManager.ThermalState.NORMAL -> com.aichathub.app.util.ModelThreads.ThermalLoadLevel.NORMAL
            ThermalManager.ThermalState.WARM -> com.aichathub.app.util.ModelThreads.ThermalLoadLevel.WARM
            ThermalManager.ThermalState.HOT -> com.aichathub.app.util.ModelThreads.ThermalLoadLevel.HOT
            ThermalManager.ThermalState.CRITICAL -> com.aichathub.app.util.ModelThreads.ThermalLoadLevel.CRITICAL
        }
}

/**
 * Thermal state monitoring for adaptive inference.
 *
 * Mobile LLM inference generates significant heat. This manager monitors
 * the device's thermal state and recommends thread counts to prevent
 * thermal throttling and maintain comfortable device temperature.
 *
 * Signals, in decreasing reliability:
 *  - Android PowerManager thermal status (API 29+), optionally observed
 *    continuously via [registerThermalListener] / [unregisterThermalListener].
 *  - Battery temperature from the sticky ACTION_BATTERY_CHANGED broadcast
 *    (read on demand via `registerReceiver(null, …)` — this registers NO
 *    persistent receiver and therefore needs no unregistration).
 *
 * Thermal states:
 *  - NORMAL: Full performance (all threads available)
 *  - WARM: Reduced threads (75% of max)
 *  - HOT: Significant reduction (50% of max)
 *  - CRITICAL: Minimum threads or pause generation
 */
@Singleton
class ThermalManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class ThermalState {
        NORMAL,
        WARM,
        HOT,
        CRITICAL
    }

    data class ThermalInfo(
        val state: ThermalState,
        val temperature: Float?,
        val powerStatusCode: Int?,
        val recommendedThreads: Int,
        val shouldThrottle: Boolean,
        val reason: String
    )

    /** API 29+: the PowerManager push listener must be registered per-tab lifecycle. */
    private val powerStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
        listenerStatus = status
    }

    @Volatile
    private var lastKnownState: ThermalState = ThermalState.NORMAL

    private val inlineExecutor = Executor { it.run() }

    @Volatile
    private var listenerStatus: Int? = null

    @Volatile
    private var listenerRegistered = false

    /**
     * Registers a PowerManager thermal status listener so the latest status is
     * observed continuously instead of only when polled.
     *
     * NO-OP on API < 29 (the listener API does not exist there — polling the
     * battery broadcast still works). Safe to call multiple times; the second
     * call is ignored. MUST be paired with [unregisterThermalListener]
     * (e.g. from an Activity/Service onStop) to avoid leaking the callback.
     */
    fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (listenerRegistered) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.addThermalStatusListener(inlineExecutor, powerStatusListener)
            listenerStatus = pm.currentThermalStatus
            listenerRegistered = true
        } catch (e: Exception) {
            // Some OEM builds throw on thermal listener registration; polling
            // still works, so this must never be fatal.
            Log.d(TAG, "Could not register thermal status listener: ${e.message}")
        }
    }

    /** Paired with [registerThermalListener]. Safe to call even if not registered. */
    fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!listenerRegistered) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.removeThermalStatusListener(powerStatusListener)
        } catch (e: Exception) {
            Log.d(TAG, "Could not unregister thermal status listener: ${e.message}")
        } finally {
            listenerRegistered = false
            listenerStatus = null
        }
    }

    /**
     * Gets the current thermal state and recommended thread count.
     *
     * @param maxThreads the maximum threads the app would like to use
     * @return ThermalInfo with state and recommendations
     */
    fun getThermalInfo(maxThreads: Int): ThermalInfo {
        val temperature = readTemperature()
        val powerStatus = currentPowerStatus()

        val tempState = ThermalPolicy.stateFromBatteryTemp(temperature)
        val powerState = ThermalPolicy.stateFromPowerStatus(powerStatus)
        // Conservative combine: whichever signal is worse determines behavior.
        val rawState = ThermalPolicy.combine(tempState, powerState)
        // Hysteresis: escalations apply instantly, de-escalations require a
        // clear recovery (§6.4) so fluctuating readings do not flap configs.
        val state = ThermalPolicy.stabilize(lastKnownState, rawState)
        val recommendedThreads = ThermalPolicy.recommendedThreads(state, maxThreads)
        val shouldThrottle = ThermalPolicy.shouldThrottle(state)
        val reason = ThermalPolicy.reason(state, powerStatus != null || temperature != null)

        if (state != lastKnownState) {
            Log.i(TAG, "Thermal state changed: $lastKnownState -> $state (temp=$temperature°C, power=$powerStatus)")
            lastKnownState = state
        }

        return ThermalInfo(
            state = state,
            temperature = temperature,
            powerStatusCode = powerStatus,
            recommendedThreads = recommendedThreads,
            shouldThrottle = shouldThrottle,
            reason = reason,
        )
    }

    /**
     * Cheap synchronous probe of the CURRENT thermal load level, used by the
     * generation watchdog in [com.aichathub.app.chat.LlamaCppRuntime] — it must
     * not depend on the heavier [getThermalInfo] result object. Returns the
     * raw combined state (no hysteresis): a generation stop decision must
     * react to a real CRITICAL reading immediately, never stick to a stale
     * stabilized value.
     */
    fun currentLoadLevel(): com.aichathub.app.util.ModelThreads.ThermalLoadLevel {
        val rawState = ThermalPolicy.combine(
            ThermalPolicy.stateFromBatteryTemp(readTemperature()),
            ThermalPolicy.stateFromPowerStatus(currentPowerStatus()),
        )
        return ThermalPolicy.toLoadLevel(rawState)
    }

    /**
     * Gets the recommended number of threads for inference.
     */
    fun getRecommendedThreads(maxThreads: Int): Int {
        return getThermalInfo(maxThreads).recommendedThreads
    }

    /**
     * Checks if generation should be paused due to thermal conditions.
     */
    fun shouldPauseGeneration(): Boolean {
        return getThermalInfo(4).state == ThermalState.CRITICAL
    }

    /** Raw PowerManager thermal status; null on API < 29 or when unavailable. */
    fun currentPowerStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus
        } catch (e: Exception) {
            Log.d(TAG, "Could not read thermal status: ${e.message}")
            null
        }
    }

    /**
     * Reads the supplementary battery temperature via the sticky battery
     * broadcast. `registerReceiver(null, filter)` registers NO receiver — it
     * only queries the existing sticky broadcast, so nothing needs to be
     * unregistered afterwards.
     */
    private fun readTemperature(): Float? {
        return try {
            val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp != null && temp > 0) {
                temp / 10.0f // Convert from tenths of degree to Celsius
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read temperature: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ThermalManager"
    }
}
