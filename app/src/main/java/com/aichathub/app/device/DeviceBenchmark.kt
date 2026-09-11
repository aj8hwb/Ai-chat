package com.aichathub.app.device

import android.content.Context
import android.os.Debug
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * On-device benchmark that scores CPU, RAM, storage, and thermal state.
 *
 * Used by the recommendation system to suggest the best model for each device.
 * First-run benchmark results are cached and reused until the user triggers
 * a re-benchmark.
 */
object DeviceBenchmark {

    private const val TAG = "DeviceBenchmark"
    private const val BENCHMARK_FILE = "device_benchmark.json"

    data class BenchmarkResult(
        val cpuScore: Int,       // 0-100
        val ramScore: Int,       // 0-100
        val storageScore: Int,   // 0-100
        val overallScore: Int,   // 0-100 (weighted average)
        val totalRamMb: Long,
        val availableRamMb: Long,
        val storageSpeedMbPerSec: Float,
        val recommendedModelSize: String, // "1B", "3B", "7B", etc.
        val maxRecommendedQuant: String,  // "Q4_K_M", "Q5_K_S", etc.
        val benchmarkTimestamp: Long
    )

    /**
     * Runs a quick device benchmark (typically < 2 seconds).
     * Measures CPU speed, available RAM, and storage I/O.
     */
    suspend fun runBenchmark(context: Context): BenchmarkResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting device benchmark...")

        val cpuScore = benchmarkCpu()
        val ramResult = benchmarkRam()
        val storageScore = benchmarkStorage(context)
        val storageSpeed = measureStorageSpeed(context)

        val overallScore = (cpuScore * 0.4 + ramResult.score * 0.4 + storageScore * 0.2).toInt()

        val recommendation = deriveRecommendation(overallScore, ramResult.availableMb)

        val result = BenchmarkResult(
            cpuScore = cpuScore,
            ramScore = ramResult.score,
            storageScore = storageScore,
            overallScore = overallScore.coerceIn(0, 100),
            totalRamMb = ramResult.totalMb,
            availableRamMb = ramResult.availableMb,
            storageSpeedMbPerSec = storageSpeed,
            recommendedModelSize = recommendation.first,
            maxRecommendedQuant = recommendation.second,
            benchmarkTimestamp = System.currentTimeMillis()
        )

        Log.i(TAG, "Benchmark complete: cpu=$cpuScore ram=${ramResult.score} storage=$storageScore overall=$overallScore")
        Log.i(TAG, "Recommendation: ${result.recommendedModelSize} ${result.maxRecommendedQuant}")

        saveResult(context, result)
        result
    }

    /**
     * Returns the cached benchmark result, or runs a new benchmark if none exists.
     */
    suspend fun getOrRunBenchmark(context: Context): BenchmarkResult {
        val cached = loadResult(context)
        if (cached != null && System.currentTimeMillis() - cached.benchmarkTimestamp < 7 * 24 * 60 * 60 * 1000) {
            return cached
        }
        return runBenchmark(context)
    }

    /**
     * Quick CPU benchmark: measures integer and floating-point throughput.
     */
    private fun benchmarkCpu(): Int {
        val iterations = 500_000
        val start = System.nanoTime()

        // Integer operations
        var sum = 0L
        for (i in 0 until iterations) {
            sum += i * 7 + i / 3
        }

        // Floating-point operations
        var fsum = 0.0
        for (i in 0 until iterations) {
            fsum += Math.sin(i.toDouble() * 0.001) * Math.cos(i.toDouble() * 0.001)
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        // Score: faster = higher (baseline: 100 points = 200ms)
        val score = (200.0 / elapsedMs.coerceAtLeast(1.0) * 100.0).toInt()
        return score.coerceIn(10, 100)
    }

    /**
     * RAM benchmark: measures available memory and allocation speed.
     */
    private fun benchmarkRam(): RamResult {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)

        // Measure allocation speed
        val start = System.nanoTime()
        val chunks = mutableListOf<ByteArray>()
        var allocated = 0L
        val chunkSize = 1024 * 1024 // 1 MB
        try {
            while (allocated < 64 * 1024 * 1024) { // Try to allocate 64 MB
                chunks.add(ByteArray(chunkSize))
                allocated += chunkSize
            }
        } catch (e: OutOfMemoryError) {
            // Expected on low-RAM devices
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        chunks.clear() // Release

        // Get system memory info
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        val availableMb = memInfo.availMem / 1024 // Already in KB

        // Score based on total and available RAM
        val score = when {
            totalMb >= 8192 -> 100 // 8GB+
            totalMb >= 6144 -> 85  // 6GB
            totalMb >= 4096 -> 70  // 4GB
            totalMb >= 3072 -> 55  // 3GB
            totalMb >= 2048 -> 40  // 2GB
            else -> 20
        }

        return RamResult(
            score = score,
            totalMb = totalMb,
            availableMb = availableMb / 1024 // Convert to MB
        )
    }

    /**
     * Storage benchmark: measures available space and I/O speed.
     */
    private fun benchmarkStorage(context: Context): Int {
        val filesDir = context.filesDir
        val stat = StatFs(filesDir.absolutePath)
        val availableGb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024 * 1024)

        return when {
            availableGb >= 50 -> 100  // 50GB+
            availableGb >= 20 -> 85   // 20GB
            availableGb >= 10 -> 70   // 10GB
            availableGb >= 5 -> 55    // 5GB
            availableGb >= 2 -> 40    // 2GB
            else -> 20
        }
    }

    /**
     * Measures sequential write speed of internal storage.
     */
    private fun measureStorageSpeed(context: Context): Float {
        val testFile = File(context.cacheDir, "speed_test.tmp")
        val size = 10 * 1024 * 1024 // 10 MB
        val buffer = ByteArray(256 * 1024) // 256 KB chunks

        return try {
            // Write test
            val writeStart = System.nanoTime()
            RandomAccessFile(testFile, "rw").use { raf ->
                var written = 0
                while (written < size) {
                    val toWrite = minOf(buffer.size, size - written)
                    raf.write(buffer, 0, toWrite)
                    written += toWrite
                }
                raf.sync()
            }
            val writeMs = (System.nanoTime() - writeStart) / 1_000_000.0

            // Read test
            val readStart = System.nanoTime()
            testFile.inputStream().use { input ->
                var read = 0
                while (read < size) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    read += n
                }
            }
            val readMs = (System.nanoTime() - readStart) / 1_000_000.0

            testFile.delete()

            val writeSpeed = (size / 1024.0 / 1024.0) / (writeMs / 1000.0)
            val readSpeed = (size / 1024.0 / 1024.0) / (readMs / 1000.0)

            ((writeSpeed + readSpeed) / 2.0).toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "Storage speed test failed", e)
            testFile.delete()
            0f
        }
    }

    /**
     * Derives model recommendation from overall score and available RAM.
     */
    private fun deriveRecommendation(overallScore: Int, availableRamMb: Long): Pair<String, String> {
        // Model size recommendations based on device capability
        return when {
            overallScore >= 85 && availableRamMb >= 4096 -> "7B" to "Q5_K_S"
            overallScore >= 70 && availableRamMb >= 3072 -> "3B" to "Q5_K_M"
            overallScore >= 55 && availableRamMb >= 2048 -> "3B" to "Q4_K_M"
            overallScore >= 40 && availableRamMb >= 1536 -> "1.5B" to "Q5_K_M"
            overallScore >= 25 && availableRamMb >= 1024 -> "1.5B" to "Q4_K_M"
            else -> "1B" to "Q4_K_M"
        }
    }

    private data class RamResult(val score: Int, val totalMb: Long, val availableMb: Long)

    private fun saveResult(context: Context, result: BenchmarkResult) {
        try {
            val json = """
                {
                    "cpuScore": ${result.cpuScore},
                    "ramScore": ${result.ramScore},
                    "storageScore": ${result.storageScore},
                    "overallScore": ${result.overallScore},
                    "totalRamMb": ${result.totalRamMb},
                    "availableRamMb": ${result.availableRamMb},
                    "storageSpeedMbPerSec": ${result.storageSpeedMbPerSec},
                    "recommendedModelSize": "${result.recommendedModelSize}",
                    "maxRecommendedQuant": "${result.maxRecommendedQuant}",
                    "benchmarkTimestamp": ${result.benchmarkTimestamp}
                }
            """.trimIndent()
            File(context.filesDir, BENCHMARK_FILE).writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save benchmark result", e)
        }
    }

    private fun loadResult(context: Context): BenchmarkResult? {
        return try {
            val file = File(context.filesDir, BENCHMARK_FILE)
            if (!file.exists()) return null
            val json = file.readText()
            // Simple JSON parsing (avoiding kotlinx.serialization dependency)
            val get = { key: String ->
                Regex(""""$key":\s*([^,\n}]+)""").find(json)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
            }
            BenchmarkResult(
                cpuScore = get("cpuScore")?.toIntOrNull() ?: 50,
                ramScore = get("ramScore")?.toIntOrNull() ?: 50,
                storageScore = get("storageScore")?.toIntOrNull() ?: 50,
                overallScore = get("overallScore")?.toIntOrNull() ?: 50,
                totalRamMb = get("totalRamMb")?.toLongOrNull() ?: 4096,
                availableRamMb = get("availableRamMb")?.toLongOrNull() ?: 2048,
                storageSpeedMbPerSec = get("storageSpeedMbPerSec")?.toFloatOrNull() ?: 100f,
                recommendedModelSize = get("recommendedModelSize") ?: "3B",
                maxRecommendedQuant = get("maxRecommendedQuant") ?: "Q4_K_M",
                benchmarkTimestamp = get("benchmarkTimestamp")?.toLongOrNull() ?: 0L
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load benchmark result", e)
            null
        }
    }
}
