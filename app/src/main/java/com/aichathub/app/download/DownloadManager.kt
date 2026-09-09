package com.aichathub.app.download

import android.content.Context
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

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
        get() = if (totalBytes > 0) ((downloadedBytes.toDouble() / totalBytes) * 100).toInt() else 0
}

sealed interface DownloadStartResult {
    data object Started : DownloadStartResult
    data object AlreadyActive : DownloadStartResult
    data class NoStorage(val requiredBytes: Long, val availableBytes: Long) : DownloadStartResult
    data class Failed(val message: String) : DownloadStartResult
}

/**
 * Production-grade model downloader with extracted components.
 *
 * Components:
 *  - DownloadPersistence: disk state management
 *  - DownloadVerifier: checksum and GGUF validation
 *  - DownloadInstaller: file installation
 *  - DownloadNetworkMonitor: network state
 *  - DownloadProgressAggregator: progress reporting
 *
 * Features:
 *  - Parallel segmented downloads over HTTP Range requests
 *  - Pause / resume / cancel with disk persistence
 *  - SHA-256 checksum verification
 *  - Auto-install with shared Downloads mirror
 *  - Real-time progress stats
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

    // Extracted components
    private val persistence = DownloadPersistence(downloadsDir)
    private val verifier = DownloadVerifier()
    private val installer = DownloadInstaller(context, modelsDir, modelRepository, settingsRepository)
    private val networkMonitor = DownloadNetworkMonitor(context, settingsRepository)
    private val progressAggregator = DownloadProgressAggregator(scope)

    private val _downloads = MutableStateFlow<List<DownloadInfo>>(emptyList())
    val downloads: StateFlow<List<DownloadInfo>> = _downloads.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()

    companion object {
        /**
         * Creates an OkHttpClient with automatic redirects DISABLED.
         * This is critical for security: the download pipeline must validate
         * each redirect hop against the trusted domain allowlist before following
         * it. With followRedirects(true), OkHttp would follow redirects silently
         * before our validation code ever sees the response.
         */
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_SECONDS = 2
        /** Maximum number of redirect hops to follow before giving up. */
        private const val MAX_REDIRECT_HOPS = 10
    }

    init {
        downloadsDir.mkdirs()
        modelsDir.mkdirs()
        scanForResumable()
        scope.launch {
            runCatching { modelRepository.reconcile() }
                .onFailure { Log.w(tag, "Reconcile failed", it) }
        }
        scope.launch {
            delay(1500)
            val resumable = _downloads.value
                .filter { it.status == DownloadStatus.PAUSED && it.downloadedBytes > 0 }
                .mapNotNull { LocalModelCatalog.byId(it.modelId) }
            resumable.forEach { model ->
                if (networkMonitor.isNetworkBlocked()) {
                    Log.i(tag, "Auto-resume deferred for ${model.id} (Wi-Fi-only + mobile data)")
                    return@forEach
                }
                Log.i(tag, "AUTO_RESUME ${model.id}")
                resume(model.id)
            }
        }
        registerNetworkWatcher()
    }

    private fun registerNetworkWatcher() {
        networkMonitor.startWatching(object : DownloadNetworkMonitor.NetworkCallback {
            override fun onNetworkAvailable() {
                if (!networkMonitor.isNetworkBlocked()) {
                    val parked = _downloads.value
                        .filter { it.status == DownloadStatus.PAUSED && it.downloadedBytes > 0 }
                        .mapNotNull { LocalModelCatalog.byId(it.modelId) }
                    parked.forEach { model ->
                        Log.i(tag, "NETWORK_AVAILABLE auto-resume ${model.id}")
                        resume(model.id)
                    }
                }
            }

            override fun onNetworkLost() {
                if (settingsRepository?.cachedWifiOnlyDownloads == true) {
                    _downloads.value.filter { it.status == DownloadStatus.DOWNLOADING }.forEach { info ->
                        pause(info.modelId)
                    }
                }
            }
        })
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

        if (!com.aichathub.app.data.SecurityValidator.isValidDownloadUrl(model.downloadUrl)) {
            Log.w(tag, "MODEL_DOWNLOAD_REJECTED ${model.id} - invalid URL")
            return DownloadStartResult.Failed("Download URL failed security validation.")
        }

        if (!com.aichathub.app.data.SecurityValidator.isValidFileName(model.fileName)) {
            Log.w(tag, "MODEL_DOWNLOAD_REJECTED ${model.id} - invalid filename")
            return DownloadStartResult.Failed("Model filename failed security validation.")
        }

        val already = persistence.existingDownloadedBytes(model)
        val profile = deviceInfoProvider.getDeviceProfile()

        if (networkMonitor.isNetworkBlocked()) {
            return DownloadStartResult.Failed(
                "Wi-Fi-only mode is on and the device is on mobile data. Enable Wi-Fi or turn off Wi-Fi-only in Settings."
            )
        }

        val sharedEnabled = settingsRepository?.let { repo ->
            runCatching { repo.settings.first().storeInSharedDownloads }.getOrDefault(true)
        } ?: true
        val sharedReserve = if (sharedEnabled) model.fileSizeBytes else 0L
        val required = (model.fileSizeBytes - already) + sharedReserve
        if (required > profile.storageAvailableBytes) {
            return DownloadStartResult.NoStorage(required, profile.storageAvailableBytes)
        }

        cancelFlags[model.id] = false
        pauseFlags[model.id] = false

        upsert(
            DownloadInfo(
                modelId = model.id,
                modelName = model.name,
                fileName = model.fileName,
                totalBytes = model.fileSizeBytes,
                downloadedBytes = already,
                status = DownloadStatus.DOWNLOADING,
                segments = 1,
                networkType = networkMonitor.currentNetworkType()
            )
        )
        modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING)
        DownloadForegroundService.start(context)
        Log.i(tag, "MODEL_DOWNLOAD_STARTED ${model.id} ($required bytes remaining)")

        val job = scope.launch {
            try {
                withContext(Dispatchers.IO) { downloadLoop(model) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(tag, "Download job cancelled for ${model.id} (resumable)")
                throw e
            } catch (e: Exception) {
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

        val job = jobs[modelId]
        if (job == null || !job.isActive) {
            upsert(info.copy(status = DownloadStatus.DOWNLOADING, error = null))
            scope.launch { modelRepository.setState(model.id, ModelLifecycleState.DOWNLOADING) }
            DownloadForegroundService.start(context)
            jobs[modelId] = scope.launch {
                try {
                    withContext(Dispatchers.IO) { downloadLoop(model) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.i(tag, "Resume job cancelled for ${model.id} (resumable)")
                    throw e
                } catch (e: Exception) {
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
        if (model != null) persistence.cleanupPartFiles(model)
        removeRecord(modelId)
    }

    fun downloadsDir(): File = downloadsDir

    // ------------------------------------------------------------------
    // Download loop
    // ------------------------------------------------------------------

    private suspend fun downloadLoop(model: CatalogModel) {
        val supportsRange = probeRangeSupport(model)
        val mergedPart = persistence.mergedPartFile(model)

        if (networkMonitor.isNetworkBlocked()) {
            pauseFlags[model.id] = true
            markStatus(model.id, DownloadStatus.PAUSED)
            return
        }

        var segments = DownloadSegmentPolicy.resolveSegments(
            meta = persistence.readMeta(model),
            hasMergedPart = mergedPart.exists(),
            hasSegmentFiles = persistence.hasAnySegmentFiles(model),
            supportsRange = supportsRange,
            fileSizeBytes = model.fileSizeBytes
        )
        if (segments == 0) {
            Log.w(tag, "Legacy segments without marker for ${model.id} — discarding and re-probing")
            persistence.cleanupSegmentFiles(model)
            segments = DownloadSegmentPolicy.resolveFresh(supportsRange, model.fileSizeBytes)
        }
        persistence.writeMeta(model, segments)

        val partFiles = persistence.segmentFiles(model, segments)
        val downloadedTotal = AtomicLong(persistence.existingDownloadedBytes(model))

        upsert(
            _downloads.value.first { it.modelId == model.id }.copy(
                downloadedBytes = downloadedTotal.get(),
                segments = segments,
                status = DownloadStatus.DOWNLOADING
            )
        )

        if (segments == 1) {
            val outcome = downloadSegmentWithRetry(
                model = model,
                segFile = mergedPart,
                rangeStart = 0,
                rangeEnd = model.fileSizeBytes - 1,
                downloadedTotal = downloadedTotal
            )
            if (outcome == SegmentOutcome.INTERRUPTED) {
                handleInterrupt(model)
                return
            }
        } else {
            val segSize = ceil(model.fileSizeBytes.toDouble() / segments).toLong()
            var fellBackToSingle = false
            try {
                coroutineScope {
                    partFiles.indices.map { i ->
                        launch {
                            val start = i * segSize
                            val end = minOf(start + segSize - 1, model.fileSizeBytes - 1)
                            val segFile = partFiles[i]
                            downloadSegmentWithRetry(
                                model = model,
                                segFile = segFile,
                                rangeStart = start,
                                rangeEnd = end,
                                downloadedTotal = downloadedTotal
                            )
                        }
                    }.forEach { it.join() }
                }
            } catch (e: RangeUnsupportedException) {
                Log.w(tag, "Range not supported for ${model.id} during segmented download; " +
                    "falling back to single-stream")
                persistence.cleanupSegmentFiles(model)
                persistence.cleanupPartFiles(model)
                downloadedTotal.set(0)
                fellBackToSingle = true
            }

            if (fellBackToSingle) {
                segments = 1
                persistence.writeMeta(model, 1)
                val singlePart = persistence.mergedPartFile(model)
                upsert(
                    _downloads.value.first { it.modelId == model.id }.copy(
                        downloadedBytes = 0,
                        segments = 1,
                        status = DownloadStatus.DOWNLOADING
                    )
                )
                val retryOutcome = downloadSegmentWithRetry(
                    model = model,
                    segFile = singlePart,
                    rangeStart = 0,
                    rangeEnd = model.fileSizeBytes - 1,
                    downloadedTotal = downloadedTotal
                )
                if (retryOutcome == SegmentOutcome.INTERRUPTED) {
                    handleInterrupt(model)
                    return
                }
            } else {
                if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                    handleInterrupt(model)
                    return
                }
                persistence.mergeSegments(partFiles, mergedPart)
                downloadedTotal.set(mergedPart.length())
            }
        }

        if (cancelFlags[model.id] == true) {
            persistence.cleanupPartFiles(model)
            removeRecord(model.id)
            return
        }
        if (pauseFlags[model.id] == true) {
            markStatus(model.id, DownloadStatus.PAUSED)
            return
        }

        // Verify
        markStatus(model.id, DownloadStatus.VERIFYING)
        modelRepository.setState(model.id, ModelLifecycleState.VERIFYING)
        Log.i(tag, "MODEL_VERIFY_STARTED ${model.id}")

        if (cancelFlags[model.id] == true) {
            persistence.cleanupPartFiles(model)
            removeRecord(model.id)
            return
        }
        if (pauseFlags[model.id] == true) {
            markStatus(model.id, DownloadStatus.PAUSED)
            return
        }

        val verifyResult = verifier.verifyDownload(mergedPart, model) {
            cancelFlags[model.id] == true || pauseFlags[model.id] == true
        }

        when (verifyResult) {
            is DownloadVerifier.VerificationResult.ABORTED -> {
                persistence.cleanupPartFiles(model)
                removeRecord(model.id)
                return
            }
            is DownloadVerifier.VerificationResult.FAILED -> {
                persistence.cleanupPartFiles(model)
                markStatus(model.id, DownloadStatus.FAILED, error = verifyResult.reason)
                modelRepository.setState(model.id, ModelLifecycleState.NOT_INSTALLED)
                Log.w(tag, "MODEL_VERIFY_FAILED ${model.id}: ${verifyResult.reason}")
                return
            }
            is DownloadVerifier.VerificationResult.SUCCESS -> {
                Log.i(tag, "MODEL_VERIFY_SUCCESS ${model.id}")
            }
        }

        if (cancelFlags[model.id] == true) {
            persistence.cleanupPartFiles(model)
            removeRecord(model.id)
            return
        }
        if (pauseFlags[model.id] == true) {
            markStatus(model.id, DownloadStatus.PAUSED)
            return
        }

        // Install
        val installSuccess = installer.install(model, mergedPart)
        if (installSuccess) {
            markStatus(model.id, DownloadStatus.COMPLETED, downloadedBytes = model.fileSizeBytes, totalBytes = model.fileSizeBytes)
        } else {
            markStatus(model.id, DownloadStatus.FAILED, error = "Could not install the model file.")
        }
    }

    private suspend fun handleInterrupt(model: CatalogModel) {
        when {
            cancelFlags[model.id] == true -> {
                persistence.cleanupPartFiles(model)
                removeRecord(model.id)
            }
            pauseFlags[model.id] == true -> {
                markStatus(model.id, DownloadStatus.PAUSED)
            }
        }
    }

    private enum class SegmentOutcome { COMPLETE, INTERRUPTED }

    private class RangeUnsupportedException(message: String) : IllegalStateException(message)

    private suspend fun downloadSegment(
        model: CatalogModel,
        segFile: File,
        rangeStart: Long,
        rangeEnd: Long,
        downloadedTotal: AtomicLong
    ): SegmentOutcome = withContext(Dispatchers.IO) {
        val start = rangeStart + (if (segFile.exists()) segFile.length() else 0L)
        if (start > rangeEnd) return@withContext SegmentOutcome.COMPLETE

        // Follow redirects manually with domain validation
        var currentUrl = model.downloadUrl
        var redirectCount = 0
        while (true) {
            if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                return@withContext SegmentOutcome.INTERRUPTED
            }

            val request = Request.Builder()
                .url(currentUrl)
                .header("Range", "bytes=$start-$rangeEnd")
                .build()

            val response = client.newCall(request).execute()

            // Handle redirects (301, 302, 303, 307, 308)
            if (response.code in 301..308) {
                val redirectUrl = response.header("Location")
                response.body?.close()

                if (redirectUrl == null) {
                    throw IllegalStateException("Redirect ${response.code} without Location header")
                }
                redirectCount++
                if (redirectCount > MAX_REDIRECT_HOPS) {
                    throw IllegalStateException("Too many redirects ($redirectCount) for ${model.id}")
                }

                // Validate the redirect target
                val isRedirectValid = com.aichathub.app.data.SecurityValidator.isValidRedirect(
                    originalUrl = model.downloadUrl,
                    redirectUrl = redirectUrl,
                    trustedDomains = com.aichathub.app.data.RemoteCatalogRepository.TRUSTED_DOMAINS
                )
                if (!isRedirectValid) {
                    throw IllegalStateException("Redirect to untrusted domain rejected: $redirectUrl")
                }

                Log.i(tag, "Following redirect $redirectCount for ${model.id}: $currentUrl -> $redirectUrl")
                currentUrl = redirectUrl
                continue
            }

            // Not a redirect — process the response
            val isRangeRequest = start > rangeStart
            if (response.code != 200 && response.code != 206) {
                response.body?.close()
                throw IllegalStateException("HTTP ${response.code}")
            }

            if (isRangeRequest && response.code == 200) {
                Log.w(tag, "Server returned 200 instead of 206 for Range request on ${model.id}; " +
                    "server does not support range requests — aborting segmented mode")
                response.body?.close()
                throw RangeUnsupportedException(
                    "Server returned HTTP 200 for Range request — " +
                    "Range requests are not supported by this server"
                )
            }

            if (response.code == 206 && isRangeRequest) {
                val contentRange = response.header("Content-Range")
                if (contentRange != null) {
                    val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)").find(contentRange)
                    if (match != null) {
                        val respStart = match.groupValues[1].toLong()
                        val respEnd = match.groupValues[2].toLong()
                        if (respStart != start || respEnd != rangeEnd) {
                            response.body?.close()
                            Log.w(tag, "Content-Range mismatch on ${model.id}: " +
                                "expected $start-$rangeEnd, got $respStart-$respEnd")
                            throw IllegalStateException(
                                "Server returned unexpected Content-Range: $contentRange"
                            )
                        }
                    }
                }
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")

            if (response.code == 206) {
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                if (contentLength != null) {
                    val expectedLength = rangeEnd - start + 1
                    if (contentLength != expectedLength) {
                        body.close()
                        Log.w(tag, "Content-Length mismatch on ${model.id}: " +
                            "expected $expectedLength, got $contentLength")
                        throw IllegalStateException(
                            "Server returned unexpected Content-Length: $contentLength"
                        )
                    }
                }
            }

            RandomAccessFile(segFile, "rw").use { raf ->
                raf.seek(segFile.length())
                val source = body.source()
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                        return@use SegmentOutcome.INTERRUPTED
                    }
                    val read = source.read(buf, 0, buf.size)
                    if (read == -1) break
                    raf.write(buf, 0, read)
                    downloadedTotal.addAndGet(read.toLong())
                    if (networkMonitor.isNetworkBlocked()) pauseFlags[model.id] = true
                    if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                        return@use SegmentOutcome.INTERRUPTED
                    }
                }
                SegmentOutcome.COMPLETE
            }
            break // Response processed, exit the redirect loop
        }
    }

    private suspend fun downloadSegmentWithRetry(
        model: CatalogModel,
        segFile: File,
        rangeStart: Long,
        rangeEnd: Long,
        downloadedTotal: AtomicLong
    ): SegmentOutcome {
        var attempt = 0
        while (true) {
            if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                return SegmentOutcome.INTERRUPTED
            }
            try {
                val outcome = downloadSegment(model, segFile, rangeStart, rangeEnd, downloadedTotal)
                if (outcome == SegmentOutcome.INTERRUPTED) return outcome
                return outcome
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_RETRY_ATTEMPTS) throw e
                val backoffSec = RETRY_BACKOFF_SECONDS * attempt
                Log.w(tag, "Segment ${model.id} failed (attempt $attempt), retrying in ${backoffSec}s", e)
                repeat(backoffSec) {
                    if (cancelFlags[model.id] == true || pauseFlags[model.id] == true) {
                        return SegmentOutcome.INTERRUPTED
                    }
                    delay(1000L)
                }
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
                if (resp.code != 206) return@use false
                val contentRange = resp.header("Content-Range") ?: return@use false
                val match = Regex("bytes\\s+\\d+-\\d+/(\\d+)").find(contentRange)
                match != null
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Resume-from-disk / helpers
    // ------------------------------------------------------------------

    private fun scanForResumable() {
        val resumable = persistence.scanForResumable(LocalModelCatalog.models, networkMonitor.currentNetworkType())
        resumable.forEach { upsert(it) }
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
        jobs.remove(modelId)?.cancel()
        cancelFlags.remove(modelId)
        pauseFlags.remove(modelId)
        _downloads.value = _downloads.value.filterNot { it.modelId == modelId }
    }
}
