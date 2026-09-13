package com.aichathub.app.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thermal state monitoring for adaptive inference.
 *
 * Mobile LLM inference generates significant heat. This manager monitors
 * the device's thermal state and recommends thread counts to prevent
 * thermal throttling and maintain comfortable device temperature.
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

    companion object {
        private const val TAG = "ThermalManager"

        /**
         * Temperature thresholds for thermal states.
         * These are approximate skin temperature values in Celsius.
         */
        private const val THRESHOLD_WARM = 37.0
        private const val THRESHOLD_HOT = 40.0
        private const val THRESHOLD_CRITICAL = 43.0
    }

    enum class ThermalState {
        NORMAL, WARM, HOT, CRITICAL
    }

    data class ThermalInfo(
        val state: ThermalState,
        val temperature: Float?,
        val recommendedThreads: Int,
        val shouldThrottle: Boolean,
        val reason: String
    )

    @Volatile
    private var lastKnownState: ThermalState = ThermalState.NORMAL

    /**
     * Gets the current thermal state and recommended thread count.
     *
     * @param maxThreads the maximum threads the app would like to use
     * @return ThermalInfo with state and recommendations
     */
    fun getThermalInfo(maxThreads: Int): ThermalInfo {
        val temperature = readTemperature()
        val state = determineState(temperature)
        val recommendedThreads = calculateRecommendedThreads(state, maxThreads)
        val shouldThrottle = state == ThermalState.HOT || state == ThermalState.CRITICAL

        val reason = when (state) {
            ThermalState.NORMAL -> "Temperature normal — full performance"
            ThermalState.WARM -> "Device warm — reducing threads to prevent throttling"
            ThermalState.HOT -> "Device hot — significant thread reduction required"
            ThermalState.CRITICAL -> "Temperature critical — minimum threads or pause"
        }

        if (state != lastKnownState) {
            Log.i(TAG, "Thermal state changed: $lastKnownState -> $state (temp=${temperature}°C)")
            lastKnownState = state
        }

        return ThermalInfo(
            state = state,
            temperature = temperature,
            recommendedThreads = recommendedThreads,
            shouldThrottle = shouldThrottle,
            reason = reason
        )
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

    private fun readTemperature(): Float? {
        return try {
            // Try to read from battery temperature (most reliable on Android)
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
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

    private fun determineState(temperature: Float?): ThermalState {
        if (temperature == null) return ThermalState.NORMAL

        return when {
            temperature >= THRESHOLD_CRITICAL -> ThermalState.CRITICAL
            temperature >= THRESHOLD_HOT -> ThermalState.HOT
            temperature >= THRESHOLD_WARM -> ThermalState.WARM
            else -> ThermalState.NORMAL
        }
    }

    private fun calculateRecommendedThreads(state: ThermalState, maxThreads: Int): Int {
        return when (state) {
            ThermalState.NORMAL -> maxThreads
            ThermalState.WARM -> (maxThreads * 0.75).toInt().coerceAtLeast(1)
            ThermalState.HOT -> (maxThreads * 0.5).toInt().coerceAtLeast(1)
            ThermalState.CRITICAL -> 1
        }
    }
}
