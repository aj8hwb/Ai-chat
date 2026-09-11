package com.aichathub.app.util

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Performance monitoring utility for tracking app performance metrics.
 */
object PerformanceMonitor {
    private const val TAG = "PerformanceMonitor"

    /**
     * Performance metric data class
     */
    data class PerformanceMetric(
        val name: String,
        val value: Long,
        val unit: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Memory usage data class
     */
    data class MemoryUsage(
        val totalHeap: Long,
        val freeHeap: Long,
        val usedHeap: Long,
        val nativeHeap: Long,
        val totalPss: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _metrics = MutableStateFlow<List<PerformanceMetric>>(emptyList())
    val metrics: StateFlow<List<PerformanceMetric>> = _metrics.asStateFlow()

    private val _memoryUsage = MutableStateFlow<MemoryUsage?>(null)
    val memoryUsage: StateFlow<MemoryUsage?> = _memoryUsage.asStateFlow()

    /**
     * Start timing an operation
     */
    fun startTiming(name: String): TimingSession {
        return TimingSession(name, SystemClock.elapsedRealtime())
    }

    /**
     * Record a performance metric
     */
    fun recordMetric(name: String, value: Long, unit: String = "ms") {
        val metric = PerformanceMetric(name, value, unit)
        _metrics.value = _metrics.value + metric
        Log.d(TAG, "Metric: $name = $value $unit")
    }

    /**
     * Get current memory usage
     */
    fun getMemoryUsage(): MemoryUsage {
        val runtime = Runtime.getRuntime()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val nativeHeap = Debug.getNativeHeapAllocatedSize()

        val debugMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemoryInfo)

        val usage = MemoryUsage(
            totalHeap = totalHeap,
            freeHeap = freeHeap,
            usedHeap = usedHeap,
            nativeHeap = nativeHeap,
            totalPss = debugMemoryInfo.totalPss
        )

        _memoryUsage.value = usage
        return usage
    }

    /**
     * Log current memory usage
     */
    fun logMemoryUsage() {
        val usage = getMemoryUsage()
        Log.i(TAG, "Memory Usage:")
        Log.i(TAG, "  Total Heap: ${usage.totalHeap / 1024 / 1024}MB")
        Log.i(TAG, "  Used Heap: ${usage.usedHeap / 1024 / 1024}MB")
        Log.i(TAG, "  Free Heap: ${usage.freeHeap / 1024 / 1024}MB")
        Log.i(TAG, "  Native Heap: ${usage.nativeHeap / 1024 / 1024}MB")
        Log.i(TAG, "  Total PSS: ${usage.totalPss / 1024}MB")
    }

    /**
     * Clear all recorded metrics
     */
    fun clearMetrics() {
        _metrics.value = emptyList()
    }

    /**
     * Get all recorded metrics
     */
    fun getMetrics(): List<PerformanceMetric> = _metrics.value

    /**
     * Timing session for measuring operation duration
     */
    class TimingSession(
        private val name: String,
        private val startTime: Long
    ) {
        /**
         * Stop timing and record the metric
         */
        fun stop(): Long {
            val duration = SystemClock.elapsedRealtime() - startTime
            recordMetric(name, duration)
            return duration
        }

        /**
         * Get elapsed time without stopping
         */
        fun elapsed(): Long {
            return SystemClock.elapsedRealtime() - startTime
        }
    }
}

/**
 * Extension function to time suspend operations
 */
suspend fun <T> PerformanceMonitor.measureTime(
    name: String,
    block: suspend () -> T
): Pair<T, Long> {
    val session = startTiming(name)
    val result = block()
    val duration = session.stop()
    return Pair(result, duration)
}
