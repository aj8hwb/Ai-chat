package com.aichathub.app.download

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import android.util.Log
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED
}

data class DownloadInfo(
    val modelId: String,
    val modelName: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val speedBytesPerSec: Long = 0,
    val averageSpeedBytesPerSec: Long = 0,
    val etaSeconds: Long = 0,
    val segments: Int = 1,
    val networkType: String? = null,
    val error: String? = null
) {
    val progress: Int
        get() = if (totalBytes > 0) ((downloadedBytes.toDouble() / totalBytes) * 100).roundToInt() else 0
}

sealed interface DownloadStartResult {
    data object Started : DownloadStartResult
    data object AlreadyActive : DownloadStartResult
    data class NoStorage(val requiredBytes: Long, val availableBytes: Long) : DownloadStartResult
    data class Failed(val message: String) : DownloadStartResult
}

/**
 * Production-grade model downloader.
 *
 * Features:
 *  - Parallel segmented downloads over HTTP Range requests (4 segments when the
 *    server supports ranges and the file is large, single stream otherwise).
 *  - Pause / resume / cancel; resumable state is derived from the partial
 *    `.part` files on disk, so downloads survive app restarts.
 *  - SHA-256 checksum verification before install (no corrupted models).
 *  - Storage preflight and real-time stats: progress %, current/avg speed,
 *    ETA, active segments and network type.
 *  - Auto-install: on success the file is moved into the models directory and
 *    the model repository is updated.
 */
class DownloadManager(
    private val context: Context,
    private val downloadsDir: File,
    private val modelsDir: File,
    private val modelRepository: ModelRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val settingsRepository: com.aichathub.app.data.SettingsRepository? = null,
    private val client: OkHttpClient = defaultClient()
) {
    private val tag = "DownloadManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _downloads = MutableStateFlow<List<DownloadInfo>>(emptyList())
    val downloads: StateFlow<List<DownloadInfo>> = _downloads.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()
    private val startedAt = ConcurrentHashMap<String, Long>()
    private val phaseStartBytes = ConcurrentHashMap<String, Long>()
    private val updaterJobs = ConcurrentHashMap<String, Job>()

    companion object {
        private const val SEGMENT_BYTES_THRESHOLD = 512L * 1024 * 1024
        private const val SEGMENT_COUNT = 4

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }

    init {
        downloadsDir.mkdirs()
        modelsDir.mkdirs()
        scanForResumable()
        scope.launch {
            // Startup reconciliation: make the persisted model registry agree
            // with the real filesystem (crash / reinstall recovery).
            runCatching { modelRepository.reconcile() }
                .onFailure { Log.w(tag, "Reconcile failed", it) }
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    suspend fun startDownload(model: CatalogModel): DownloadStartResult {
        val existing = _downloads.value.firstOrNull { it.modelId == model.id }
        val activeState = existing?.status
        if (jobs[model.id]?.isActive == true ||
            activeState == DownloadStatus.DOWNLOADING ||
            activeState == DownloadStatus.QUEUED ||
            activeState == DownloadStatus.VERIFYING
        ) {
            return DownloadStartResult.AlreadyActive
        }

        val already = existingDownloadedBytes(model)
        val required = (model.fileSizeBytes - already).coerceAtLeast(0)
        val profile = deviceInfoProvider.getDeviceProfile()
        if (required > profile.storageAvailableBytes) {
            return DownloadStartResult.NoStorage(required, profile.storageAvailableBytes)
        }

        cancelFlags[model.id] = false
        pauseFlags[model.id] = false
        startedAt[model.id] = System.currentTimeMillis()
        phaseStartBytes[model.id] = already

        upsert(
            DownloadInfo(
                modelId = model.id,
                modelName = model.name,
                fileName = model.fileName,
                totalBytes = model.fileSizeBytes,
                downloadedBytes = already,
                status = DownloadStatus.DOWNLOADING,
                segments = 1,
                networkType = currentNetworkType()
            )
        )
modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING)
        Log.i(tag, "MODEL_DOWNLOAD_STARTED ${model.id} ($required bytes remaining)")

        val job = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { downloadLoop(model) }
            }.onFailure { e ->
                Log.e(tag, "Download failed for ${model.id}", e)
                markStatus(model.id, DownloadStatus.FAILED, error = e.message ?: "Download failed")
                modelRepository.setState(model.id, ModelLifecycleState.NOT_INSTALLED)
            }
        }
        jobs[model.id] = job
        return DownloadStartResult.Started
    }

    fun pause(modelId: String) {
        pauseFlags[modelId] = true
    }

    fun cancel(modelId: String) {
        cancelFlags[modelId] = true
        pauseFlags[modelId] = false
    }

    fun resume(modelId: String) {
        val info = _downloads.value.firstOrNull { it.modelId == modelId } ?: return
        if (info.status != DownloadStatus.PAUSED && info.status != DownloadStatus.FAILED) return
        val model = LocalModelCatalog.byId(modelId) ?: return
        pauseFlags[modelId] = false
        cancelFlags[modelId] = false
        startedAt[modelId] = System.currentTimeMillis()
        phaseStartBytes[modelId] = existingDownloadedBytes(model)

        val job = jobs[modelId]
        if (job == null || !job.isActive) {
            upsert(info.copy(status = DownloadStatus.DOWNLOADING, error = null))
            scope.launch { modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING) }
            jobs[modelId] = scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { downloadLoop(model) }
                }.onFailure { e ->
                    Log.e(tag, "Download failed for ${model.id}", e)
                    markStatus(modelId, DownloadStatus.FAILED, error = e.message ?: "Download failed")
                    modelRepository.setState(model.id, ModelLifecycleState.NOT_INSTALLED)
                }
            }
        } else {
            upsert(info.copy(status = DownloadStatus.DOWNLOADING, error = null))
        }
    }

    fun isActive(modelId: String): Boolean =
        _downloads.value.firstOrNull { it.modelId == modelId }?.status == DownloadStatus.DOWNLOADING

    fun clearCompleted(modelId: String) {
        _downloads.value = _downloads.value.filterNot { it.modelId == modelId && it.status == DownloadStatus.COMPLETED }
    }

    fun clearForModel(modelId: String) {
        val model = LocalModelCatalog.byId(modelId)
        if (model != null) cleanupPartFiles(model)
        removeRecord(modelId)
    }

    fun downloadsDir(): File = downloadsDir

    // ------------------------------------------------------------------
    // Download loop
    // ------------------------------------------------------------------

    private suspend fun downloadLoop(model: CatalogModel) {
        val supportsRange = probeRangeSupport(model)
        val segments = if (supportsRange && model.fileSizeBytes >= SEGMENT_BYTES_THRESHOLD) SEGMENT_COUNT else 1
        val partFiles = List(segments) { File(downloadsDir, "${model.fileName}.part.$it") }
        val mergedPart = File(downloadsDir, "${model.fileName}.part")
        val downloadedTotal = AtomicLong(existingDownloadedBytes(model))

        upsert(
            _downloads.value.first { it.modelId == model.id }.copy(
                downloadedBytes = downloadedTotal.get(),
                segments = segments,
                status = DownloadStatus.DOWNLOADING
            )
        )
        startProgressUpdater(model)

        if (segments == 1) {
            val outcome = downloadSegment(
                model = model,
                segFile = mergedPart,
                rangeStart = 0,
                rangeEnd = model.fileSizeBytes - 1,
                downloadStart = downloadedTotal.get(),
                downloadedTotal = downloadedTotal
            )
            if (outcome == SegmentOutcome.INTERRUPTED) {
                stopProgressUpdater(model.id)
                handleInterrupt(model)
                return
            }
        } else {
            val segSize = ceil(model.fileSizeBytes.toDouble() / segments).toLong()
            val segmentJobs = partFiles.indices.map { i ->
                scope.launch {
                    val start = i * segSize
                    val end = minOf(start + segSize - 1, model.fileSizeBytes - 1)
                    val segFile = partFiles[i]
                    val localProgress = if (segFile.exists()) segFile.length() else 0L
                    if (localProgress >= (end - start + 1)) return@launch
                    downloadSegment(
                        model = model,
                        segFile = segFile,
                        rangeStart = start,
                        rangeEnd = end,
                        downloadStart = start + localProgress,
                        downloadedTotal = downloadedTotal
                    )
                }
            }
            segmentJobs.forEach { it.join() }
            stopProgressUpdater(model.id)

            if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                handleInterrupt(model)
                return
            }
            mergeSegments(partFiles, mergedPart)
            downloadedTotal.set(mergedPart.length())
        }

        if (cancelFlags[model.id] == true) {
            cleanupPartFiles(model)
            removeRecord(model.id)
            return
        }
        if (pauseFlags[model.id] == true) {
            markStatus(model.id, DownloadStatus.PAUSED)
            return
        }

        // Verify integrity before installing.
        markStatus(model.id, DownloadStatus.VERIFYING)
        modelRepository.setState(model.id, ModelLifecycleState.VERIFYING)
        Log.i(tag, "MODEL_VERIFY_STARTED ${model.id}")
        if (!verifyChecksum(model, mergedPart)) {
            cleanupPartFiles(model)
            markStatus(model.id, DownloadStatus.FAILED, error = "Download verification failed — checksum mismatch. Please download again.")
            modelRepository.setState(model.id, ModelLifecycleState.NOT_INSTALLED)
            Log.w(tag, "MODEL_VERIFY_FAILED ${model.id}")
            return
        }
        Log.i(tag, "MODEL_VERIFY_SUCCESS ${model.id}")

        install(model, mergedPart)
    }

    private suspend fun handleInterrupt(model: CatalogModel) {
        when {
            cancelFlags[model.id] == true -> {
                cleanupPartFiles(model)
                removeRecord(model.id)
            }
            pauseFlags[model.id] == true -> {
                markStatus(model.id, DownloadStatus.PAUSED)
            }
        }
    }

    private enum class SegmentOutcome { COMPLETE, INTERRUPTED }

    private suspend fun downloadSegment(
        model: CatalogModel,
        segFile: File,
        rangeStart: Long,
        rangeEnd: Long,
        downloadStart: Long,
        downloadedTotal: AtomicLong
    ): SegmentOutcome = withContext(Dispatchers.IO) {
        if (downloadStart > rangeEnd) return@withContext SegmentOutcome.COMPLETE

        val request = Request.Builder()
            .url(model.downloadUrl)
            .header("Range", "bytes=$downloadStart-$rangeEnd")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 200 && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty response body")
            RandomAccessFile(segFile, "rw").use { raf ->
                raf.seek(segFile.length())
                val source = body.source()
                val buffer = okio.Buffer()
                while (true) {
                    if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                        return@use SegmentOutcome.INTERRUPTED
                    }
                    val read = source.read(buffer, 64 * 1024)
                    if (read == -1L) break
                    raf.write(buffer.readByteArray())
                    downloadedTotal.addAndGet(read)
                    if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                        return@use SegmentOutcome.INTERRUPTED
                    }
                }
                SegmentOutcome.COMPLETE
            }
        }
    }

    private suspend fun probeRangeSupport(model: CatalogModel): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(model.downloadUrl)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { resp ->
                resp.code == 206 && (resp.header("Accept-Ranges")?.contains("bytes", true) ?: false)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun mergeSegments(parts: List<File>, target: File) {
        if (target.exists()) target.delete()
        FileOutputStream(target).use { out ->
            parts.forEach { p ->
                if (p.exists()) {
                    p.inputStream().use { it.copyTo(out) }
                }
            }
        }
        parts.forEach { it.delete() }
    }

    // ------------------------------------------------------------------
    // Verification + install
    // ------------------------------------------------------------------

    private fun verifyChecksum(model: CatalogModel, file: File): Boolean {
        val expected = model.checksumSha256 ?: return true
        if (!file.exists()) return false
        val actual = sha256Hex(file)
        Log.i(tag, "Checksum ${model.id}: expected=$expected actual=$actual")
        return actual.equals(expected, ignoreCase = true)
    }

    private fun sha256Hex(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { String.format("%02x", it) }
    } catch (e: Exception) {
        Log.w(tag, "Checksum computation failed", e)
        ""
    }

    private suspend fun install(model: CatalogModel, mergedPart: File) {
        val finalFile = File(modelsDir, model.fileName)
        withContext(Dispatchers.IO) {
            if (finalFile.exists()) finalFile.delete()
            val moved = mergedPart.renameTo(finalFile)
            if (!moved) {
                mergedPart.copyTo(finalFile, overwrite = true)
                mergedPart.delete()
            }
        }
        if (finalFile.exists() && finalFile.length() > 0) {
            modelRepository.markInstalled(model.id, finalFile, finalFile.length())
            markStatus(model.id, DownloadStatus.COMPLETED, downloadedBytes = finalFile.length(), totalBytes = finalFile.length())
            modelRepository.setState(model.id, ModelLifecycleState.READY)
            Log.i(tag, "MODEL_REGISTERED ${model.id} -> READY (${finalFile.absolutePath})")
            copyToSharedDownloads(model, finalFile)
        } else {
            markStatus(model.id, DownloadStatus.FAILED, error = "Could not install the model file.")
        }
    }

    /**
     * Mirrors the installed model into the shared Downloads folder
     * (Download/AiChatHub/Models) via MediaStore. The copy is visible in the
     * system file manager and survives app reinstall, so the file can be
     * recovered with a rescan + re-import. Best-effort: failures are logged
     * and never break the install.
     */
    private fun copyToSharedDownloads(model: CatalogModel, file: File) {
        val enabled = settingsRepository == null || runCatching {
            settingsRepository.settings.first().storeInSharedDownloads
        }.getOrDefault(true)
        if (!enabled) return
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, model.fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/AiChatHub/Models")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(tag, "MODEL_SHARED_COPY ${model.id} -> Download/AiChatHub/Models/${model.fileName}")
        } catch (e: Exception) {
            Log.w(tag, "Shared Downloads copy failed for ${model.id}", e)
        }
    }

    // ------------------------------------------------------------------
    // Progress reporting
    // ------------------------------------------------------------------

    private fun startProgressUpdater(model: CatalogModel) {
        stopProgressUpdater(model.id)
        updaterJobs[model.id] = scope.launch {
            var lastBytes = existingDownloadedBytes(model)
            var lastTime = System.currentTimeMillis()
            var lastSpeed = 0L
            while (isActive && cancelFlags[model.id] != true && pauseFlags[model.id] != true) {
                delay(500)
                val current = existingDownloadedBytes(model)
                val now = System.currentTimeMillis()
                val dtMs = (now - lastTime).coerceAtLeast(1)
                val instSpeed = ((current - lastBytes) * 1000L) / dtMs
                if (instSpeed >= 0) lastSpeed = instSpeed
                lastBytes = current
                lastTime = now

                val start = startedAt[model.id] ?: now
                val phaseStart = phaseStartBytes[model.id] ?: 0L
                val elapsedS = ((now - start).coerceAtLeast(1)) / 1000.0
                val avgSpeed = if (elapsedS > 0) ((current - phaseStart) / elapsedS).toLong() else 0L
                val remaining = (model.fileSizeBytes - current).coerceAtLeast(0)
                val eta = if (avgSpeed > 0) remaining / avgSpeed else 0L

                upsert(
                    _downloads.value.firstOrNull { it.modelId == model.id }?.copy(
                        downloadedBytes = current,
                        totalBytes = model.fileSizeBytes,
                        speedBytesPerSec = lastSpeed,
                        averageSpeedBytesPerSec = avgSpeed,
                        etaSeconds = eta,
                        status = DownloadStatus.DOWNLOADING,
                        networkType = currentNetworkType()
                    ) ?: return@launch
                )
            }
        }
    }

    private fun stopProgressUpdater(modelId: String) {
        updaterJobs.remove(modelId)?.cancel()
    }

    // ------------------------------------------------------------------
    // Resume-from-disk / helpers
    // ------------------------------------------------------------------

    private fun scanForResumable() {
        LocalModelCatalog.models.forEach { model ->
            val bytes = existingDownloadedBytes(model)
            if (bytes > 0) {
                upsert(
                    DownloadInfo(
                        modelId = model.id,
                        modelName = model.name,
                        fileName = model.fileName,
                        totalBytes = model.fileSizeBytes,
                        downloadedBytes = bytes,
                        status = DownloadStatus.PAUSED,
                        networkType = currentNetworkType()
                    )
                )
            }
        }
    }

    private fun existingDownloadedBytes(model: CatalogModel): Long {
        val merged = File(downloadsDir, "${model.fileName}.part")
        var total = if (merged.exists()) merged.length() else 0L
        var i = 0
        while (true) {
            val seg = File(downloadsDir, "${model.fileName}.part.$i")
            if (!seg.exists()) break
            total += seg.length()
            i++
        }
        return total
    }

    private fun cleanupPartFiles(model: CatalogModel) {
        runCatching {
            File(downloadsDir, "${model.fileName}.part").delete()
            var i = 0
            while (true) {
                val seg = File(downloadsDir, "${model.fileName}.part.$i")
                if (!seg.exists()) break
                seg.delete()
                i++
            }
        }
    }

    private fun currentNetworkType(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> null
        }
    }

    private fun upsert(info: DownloadInfo) {
        val current = _downloads.value
        _downloads.value = if (current.any { it.modelId == info.modelId }) {
            current.map { if (it.modelId == info.modelId) info else it }
        } else {
            current + info
        }
    }

    private fun markStatus(
        modelId: String,
        status: DownloadStatus,
        error: String? = null,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null
    ) {
        val info = _downloads.value.firstOrNull { it.modelId == modelId } ?: return
        upsert(
            info.copy(
                status = status,
                error = error,
                downloadedBytes = downloadedBytes ?: info.downloadedBytes,
                totalBytes = totalBytes ?: info.totalBytes
            )
        )
    }

    private fun removeRecord(modelId: String) {
        stopProgressUpdater(modelId)
        jobs.remove(modelId)?.cancel()
        cancelFlags.remove(modelId)
        pauseFlags.remove(modelId)
        startedAt.remove(modelId)
        phaseStartBytes.remove(modelId)
        _downloads.value = _downloads.value.filterNot { it.modelId == modelId }
    }
}
