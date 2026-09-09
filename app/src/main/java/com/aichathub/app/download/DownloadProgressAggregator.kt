package com.aichathub.app.download

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Aggregates and reports download progress for active downloads.
 *
 * Responsibilities:
 *  - Track download speed (instantaneous and average)
 *  - Calculate ETA
 *  - Update download state with progress information
 *  - Handle progress updater lifecycle
 *
 * Progress is updated every 500ms for smooth UI updates.
 */
class DownloadProgressAggregator(
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "DownloadProgressAggregator"
        private const val UPDATE_INTERVAL_MS = 500L
    }

    data class ProgressState(
        val speedBytesPerSec: Long = 0,
        val averageSpeedBytesPerSec: Long = 0,
        val etaSeconds: Long = 0
    )

    private val updaterJobs = ConcurrentHashMap<String, Job>()
    private val startedAt = ConcurrentHashMap<String, Long>()
    private val phaseStartBytes = ConcurrentHashMap<String, Long>()

    /**
     * Starts tracking progress for a download.
     *
     * @param modelId the model being downloaded
     * @param fileSize total size of the file
     * @param initialBytes bytes already downloaded
     * @param progressCallback called with updated progress info
     */
    fun startTracking(
        modelId: String,
        fileSize: Long,
        initialBytes: Long,
        progressCallback: (ProgressState) -> Unit
    ) {
        stopTracking(modelId)
        startedAt[modelId] = System.currentTimeMillis()
        phaseStartBytes[modelId] = initialBytes

        updaterJobs[modelId] = scope.launch {
            var lastBytes = initialBytes
            var lastTime = System.currentTimeMillis()
            var lastSpeed = 0L

            while (isActive) {
                delay(UPDATE_INTERVAL_MS)
                val currentBytes = getDownloadedBytes(modelId)
                val now = System.currentTimeMillis()
                val dtMs = (now - lastTime).coerceAtLeast(1)

                val instSpeed = ((currentBytes - lastBytes) * 1000L) / dtMs
                if (instSpeed >= 0) lastSpeed = instSpeed
                lastBytes = currentBytes
                lastTime = now

                val start = startedAt[modelId] ?: now
                val phaseStart = phaseStartBytes[modelId] ?: 0L
                val elapsedS = ((now - start).coerceAtLeast(1)) / 1000.0
                val avgSpeed = if (elapsedS > 0) ((currentBytes - phaseStart) / elapsedS).toLong() else 0L
                val remaining = (fileSize - currentBytes).coerceAtLeast(0)
                val eta = if (avgSpeed > 0) remaining / avgSpeed else 0L

                progressCallback(
                    ProgressState(
                        speedBytesPerSec = lastSpeed,
                        averageSpeedBytesPerSec = avgSpeed,
                        etaSeconds = eta
                    )
                )
            }
        }
    }

    /**
     * Stops tracking progress for a download.
     */
    fun stopTracking(modelId: String) {
        updaterJobs.remove(modelId)?.cancel()
        startedAt.remove(modelId)
        phaseStartBytes.remove(modelId)
    }

    /**
     * Stops all tracking.
     */
    fun stopAll() {
        updaterJobs.values.forEach { it.cancel() }
        updaterJobs.clear()
        startedAt.clear()
        phaseStartBytes.clear()
    }

    /**
     * Gets the current progress state for a download.
     */
    fun getProgressState(modelId: String): ProgressState? {
        // This is a simplified version - in production, you'd track this
        // from the last callback
        return null
    }

    /**
     * Callback interface for progress updates.
     * Subclasses should implement this to get actual byte counts.
     */
    interface DownloadProgressProvider {
        fun getDownloadedBytes(modelId: String): Long
    }

    private var progressProvider: DownloadProgressProvider? = null

    fun setProgressProvider(provider: DownloadProgressProvider) {
        this.progressProvider = provider
    }

    private fun getDownloadedBytes(modelId: String): Long {
        return progressProvider?.getDownloadedBytes(modelId) ?: 0L
    }
}
