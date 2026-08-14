package com.aichathub.app.download

import com.aichathub.app.domain.model.CatalogModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

data class DownloadInfo(
    val modelId: String,
    val modelName: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val speedBytesPerSec: Long = 0,
    val error: String? = null
) {
    val progress: Int
        get() = if (totalBytes > 0) ((downloadedBytes.toDouble() / totalBytes) * 100).roundToInt() else 0
}

/**
 * Real HTTP(S) model downloader with pause / resume / cancel and progress
 * reporting. Downloads to a temporary file and only marks the model installed
 * after the file has been fully and successfully fetched.
 */
class DownloadManager(
    private val downloadsDir: File,
    private val client: OkHttpClient = defaultClient()
) {
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _downloads = MutableStateFlow<List<DownloadInfo>>(emptyList())
    val downloads: StateFlow<List<DownloadInfo>> = _downloads.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }

    fun startDownload(model: CatalogModel) {
        if (jobs.containsKey(model.id)) return
        cancelFlags[model.id] = false
        pauseFlags[model.id] = false

        val tmpFile = File(downloadsDir, "${model.fileName}.part")

        upsert(
            DownloadInfo(
                modelId = model.id,
                modelName = model.name,
                fileName = model.fileName,
                totalBytes = model.fileSizeBytes,
                downloadedBytes = existingLength(tmpFile),
                status = DownloadStatus.DOWNLOADING
            )
        )

        val job = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    downloadLoop(model, tmpFile)
                }
            }.onFailure { e ->
                upsert(
                    _downloads.value.firstOrNull { it.modelId == model.id }?.copy(
                        status = DownloadStatus.FAILED,
                        error = e.message ?: "Download failed"
                    ) ?: DownloadInfo(model.id, model.name, model.fileName, model.fileSizeBytes, 0, DownloadStatus.FAILED, error = e.message)
                )
            }
        }
        jobs[model.id] = job
    }

    private suspend fun downloadLoop(model: CatalogModel, tmpFile: File) {
        var offset = existingLength(tmpFile)
        while (true) {
            if (cancelFlags[model.id] == true) {
                tmpFile.delete()
                upsert(_downloads.value.first { it.modelId == model.id }.copy(status = DownloadStatus.CANCELLED))
                return
            }
            if (pauseFlags[model.id] == true) {
                upsert(_downloads.value.first { it.modelId == model.id }.copy(status = DownloadStatus.PAUSED))
                // wait until resumed or cancelled
                while (pauseFlags[model.id] == true && cancelFlags[model.id] != true) {
                    kotlinx.coroutines.delay(300)
                }
                if (cancelFlags[model.id] == true) continue
                upsert(_downloads.value.first { it.modelId == model.id }.copy(status = DownloadStatus.DOWNLOADING))
                offset = existingLength(tmpFile)
            }

            val request = Request.Builder()
                .url(model.downloadUrl)
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()

            val outcome = readChunk(model, tmpFile, request, offset)
            when (outcome) {
                ChunkOutcome.CANCELLED -> return
                ChunkOutcome.PAUSED -> {
                    upsert(_downloads.value.first { it.modelId == model.id }.copy(
                        downloadedBytes = offset,
                        status = DownloadStatus.PAUSED
                    ))
                    continue
                }
                ChunkOutcome.COMPLETED -> {
                    // Download finished: move .part to final file (atomic-ish move)
                    val finalBytes = tmpFile.length()
                    val finalFile = File(downloadsDir, model.fileName)
                    if (tmpFile.exists() && finalBytes > 0) {
                        if (finalFile.exists()) finalFile.delete()
                        tmpFile.renameTo(finalFile)
                    }
                    upsert(
                        _downloads.value.first { it.modelId == model.id }.copy(
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            status = DownloadStatus.COMPLETED
                        )
                    )
                    jobs.remove(model.id)
                    return
                }
                ChunkOutcome.NOT_FINISHED -> offset = existingLength(tmpFile)
            }
        }
    }

    private enum class ChunkOutcome { PAUSED, CANCELLED, COMPLETED, NOT_FINISHED }

    private suspend fun readChunk(
        model: CatalogModel,
        tmpFile: File,
        request: Request,
        offset0: Long
    ): ChunkOutcome {
        var offset = offset0
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val total = response.body?.contentLength() ?: 0L
            val totalBytes = if (offset + total > 0) offset + total else model.fileSizeBytes

            RandomAccessFile(tmpFile, "rw").use { raf ->
                raf.seek(offset)
                val body = response.body ?: throw IllegalStateException("Empty response body")
                val source = body.source()
                val buffer = okio.Buffer()
                var lastUpdate = System.currentTimeMillis()
                var lastBytes = offset
                var speed = 0L
                while (true) {
                    if (cancelFlags[model.id] == true) {
                        tmpFile.delete()
                        upsert(_downloads.value.first { it.modelId == model.id }.copy(status = DownloadStatus.CANCELLED))
                        return ChunkOutcome.CANCELLED
                    }
                    if (pauseFlags[model.id] == true) break
                    val read = source.read(buffer, 32 * 1024)
                    if (read == -1L) break
                    raf.write(buffer.readByteArray())
                    offset += read
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 500) {
                        val dt = (now - lastUpdate) / 1000.0
                        if (dt > 0) speed = ((offset - lastBytes) / dt).toLong()
                        lastUpdate = now
                        lastBytes = offset
                        upsert(
                            _downloads.value.first { it.modelId == model.id }.copy(
                                downloadedBytes = offset,
                                totalBytes = totalBytes,
                                speedBytesPerSec = speed,
                                status = DownloadStatus.DOWNLOADING
                            )
                        )
                    }
                }

                if (cancelFlags[model.id] == true) {
                    tmpFile.delete()
                    upsert(_downloads.value.first { it.modelId == model.id }.copy(status = DownloadStatus.CANCELLED))
                    return ChunkOutcome.CANCELLED
                }
                if (pauseFlags[model.id] == true) return ChunkOutcome.PAUSED
                return ChunkOutcome.COMPLETED
            }
        }
    }

    fun pause(modelId: String) {
        pauseFlags[modelId] = true
    }

    fun resume(modelId: String) {
        pauseFlags[modelId] = false
        val info = _downloads.value.firstOrNull { it.modelId == modelId } ?: return
        if (info.status == DownloadStatus.PAUSED) {
            // The loop will resume on its own.
        }
    }

    fun cancel(modelId: String) {
        cancelFlags[modelId] = true
        pauseFlags[modelId] = false
    }

    fun isActive(modelId: String): Boolean =
        _downloads.value.firstOrNull { it.modelId == modelId }?.status == DownloadStatus.DOWNLOADING

    fun clearCompleted(modelId: String) {
        _downloads.value = _downloads.value.filterNot { it.modelId == modelId && it.status == DownloadStatus.COMPLETED }
    }

    private fun existingLength(file: File): Long =
        if (file.exists()) file.length() else 0L

    private fun upsert(info: DownloadInfo) {
        val current = _downloads.value
        _downloads.value = if (current.any { it.modelId == info.modelId }) {
            current.map { if (it.modelId == info.modelId) info else it }
        } else {
            current + info
        }
    }

    fun clearForModel(modelId: String) {
        _downloads.value = _downloads.value.filterNot { it.modelId == modelId }
        jobs.remove(modelId)
        cancelFlags.remove(modelId)
        pauseFlags.remove(modelId)
    }

    /** Directory where download temp files are stored. */
    fun downloadsDir(): File = downloadsDir
}