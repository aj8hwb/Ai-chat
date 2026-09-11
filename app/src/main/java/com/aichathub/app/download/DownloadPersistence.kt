package com.aichathub.app.download

import android.util.Log
import com.aichathub.app.domain.model.CatalogModel
import java.io.File

/**
 * Manages download state persistence on disk.
 *
 * Responsibilities:
 *  - Read/write segment metadata (.part.meta files)
 *  - Track downloaded bytes per segment
 *  - Detect resumable downloads from disk state
 *  - Cleanup of stale segment files
 *
 * This class is thread-safe for read operations but write operations
 * should be synchronized externally if concurrent access is possible.
 */
class DownloadPersistence(
    private val downloadsDir: File
) {

    companion object {
        private const val TAG = "DownloadPersistence"
    }

    init {
        downloadsDir.mkdirs()
    }

    /**
     * Reads the segment count from the .part.meta file.
     * Returns null if no meta file exists.
     */
    fun readMeta(model: CatalogModel): Int? {
        val f = File(downloadsDir, DownloadSegmentPolicy.metaFileName(model.fileName))
        if (!f.exists()) return null
        return runCatching { f.readText().trim().toInt() }.getOrNull()
    }

    /**
     * Writes the segment count to the .part.meta file.
     */
    fun writeMeta(model: CatalogModel, segments: Int) {
        runCatching {
            File(downloadsDir, DownloadSegmentPolicy.metaFileName(model.fileName))
                .writeText(segments.toString())
        }.onFailure { Log.w(TAG, "Could not write segment marker for ${model.id}", it) }
    }

    /**
     * Calculates total bytes already downloaded for a model.
     * Considers both merged and segmented download states.
     */
    fun existingDownloadedBytes(model: CatalogModel): Long {
        val meta = readMeta(model)
        val merged = File(downloadsDir, DownloadSegmentPolicy.mergedPartFileName(model.fileName))
        if (meta != null && meta > 1) {
            return DownloadSegmentPolicy.bytesDownloaded(
                mergedBytes = if (merged.exists()) merged.length() else 0L,
                segmentBytes = (0 until 64).mapNotNull { i ->
                    val f = File(downloadsDir, DownloadSegmentPolicy.partFileName(model.fileName, i))
                    if (f.exists()) f.length() else null
                },
                segments = meta
            )
        }
        return if (merged.exists()) merged.length() else 0L
    }

    /**
     * Checks if any segment files exist for a model.
     */
    fun hasAnySegmentFiles(model: CatalogModel): Boolean {
        var i = 0
        while (i < 64) {
            if (File(downloadsDir, DownloadSegmentPolicy.partFileName(model.fileName, i)).exists()) return true
            i++
        }
        return false
    }

    /**
     * Returns the merged part file for a model.
     */
    fun mergedPartFile(model: CatalogModel): File {
        return File(downloadsDir, DownloadSegmentPolicy.mergedPartFileName(model.fileName))
    }

    /**
     * Returns the segment file for a specific index.
     */
    fun segmentFile(model: CatalogModel, index: Int): File {
        return File(downloadsDir, DownloadSegmentPolicy.partFileName(model.fileName, index))
    }

    /**
     * Returns all segment files for a model.
     */
    fun segmentFiles(model: CatalogModel, count: Int): List<File> {
        return (0 until count).map { segmentFile(model, it) }
    }

    /**
     * Cleans up all segment files for a model.
     */
    fun cleanupSegmentFiles(model: CatalogModel) {
        runCatching {
            var i = 0
            while (i < 64) {
                val seg = File(downloadsDir, DownloadSegmentPolicy.partFileName(model.fileName, i))
                if (seg.exists()) {
                    seg.delete()
                }
                i++
            }
        }
    }

    /**
     * Cleans up all part files (merged + segments + meta) for a model.
     */
    fun cleanupPartFiles(model: CatalogModel) {
        runCatching {
            File(downloadsDir, DownloadSegmentPolicy.mergedPartFileName(model.fileName)).delete()
            cleanupSegmentFiles(model)
            File(downloadsDir, DownloadSegmentPolicy.metaFileName(model.fileName)).delete()
        }
    }

    /**
     * Merges multiple segment files into a single merged file.
     */
    fun mergeSegments(parts: List<File>, target: File) {
        if (target.exists()) target.delete()
        java.io.FileOutputStream(target).use { out ->
            parts.forEach { p ->
                if (p.exists()) {
                    p.inputStream().use { it.copyTo(out) }
                }
            }
        }
        parts.forEach { it.delete() }
    }

    /**
     * Scans for resumable downloads and returns their state.
     */
    fun scanForResumable(
        models: List<CatalogModel>,
        networkType: String?
    ): List<DownloadInfo> {
        val resumable = mutableListOf<DownloadInfo>()
        for (model in models) {
            val bytes = existingDownloadedBytes(model)
            if (bytes > 0) {
                resumable.add(
                    DownloadInfo(
                        modelId = model.id,
                        modelName = model.name,
                        fileName = model.fileName,
                        totalBytes = model.fileSizeBytes,
                        downloadedBytes = bytes,
                        status = DownloadStatus.PAUSED,
                        networkType = networkType
                    )
                )
            }
        }
        return resumable
    }
}
