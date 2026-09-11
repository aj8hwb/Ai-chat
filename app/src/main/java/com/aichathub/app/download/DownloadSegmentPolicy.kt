package com.aichathub.app.download

/**
 * Pure decision logic for the segmented download strategy.
 *
 * Segmented downloads split a large file into [SEGMENT_COUNT] parallel HTTP
 * Range streams, each stored in a `.part.N` file. Small files (or servers that
 * ignore Range requests) use a single merged `.part` stream instead.
 *
 * The chosen mode is persisted in a tiny `.part.meta` marker. A download that
 * is resumed after a restart then always counts and writes the SAME files it
 * started with — silently switching between the merged and segmented layouts
 * mid-download would double-count progress and corrupt the merged file.
 *
 * Pure logic (no Android / file IO), unit-testable.
 */
object DownloadSegmentPolicy {

    const val SEGMENT_BYTES_THRESHOLD = 64L * 1024 * 1024
    const val SEGMENT_COUNT = 8

    const val META_SUFFIX = ".part.meta"
    const val MERGED_PART_SUFFIX = ".part"

    fun metaFileName(fileName: String): String = "$fileName$META_SUFFIX"

    fun partFileName(fileName: String, index: Int): String = "${fileName}.part.$index"

    fun mergedPartFileName(fileName: String): String = "$fileName$MERGED_PART_SUFFIX"

    /**
     * Resolves the segment count a download should use.
     *
     * @param meta persisted segment count (null when absent / legacy download)
     * @param hasMergedPart a single-stream `.part` file already exists
     * @param hasSegmentFiles any `.part.N` files already exist
     * @param supportsRange server honors HTTP Range requests
     * @param fileSizeBytes total size of the artifact
     * @return [SEGMENT_COUNT] or 1 for the normal cases, or 0 when a legacy
     *   unknown state must be cleaned up and re-probed fresh.
     */
    fun resolveSegments(
        meta: Int?,
        hasMergedPart: Boolean,
        hasSegmentFiles: Boolean,
        supportsRange: Boolean,
        fileSizeBytes: Long
    ): Int {
        meta?.let { return if (it > 1) it else 1 }
        if (hasMergedPart) return 1
        if (hasSegmentFiles) return 0 // legacy segmented state without a marker
        return resolveFresh(supportsRange, fileSizeBytes)
    }

    fun resolveFresh(supportsRange: Boolean, fileSizeBytes: Long): Int =
        if (supportsRange && fileSizeBytes >= SEGMENT_BYTES_THRESHOLD) SEGMENT_COUNT else 1

    /**
     * Bytes already on disk for the active mode. Only the files that belong to
     * the active mode are counted, so a single-stream `.part` and a set of
     * `.part.N` segments can never be double-counted.
     */
    fun bytesDownloaded(mergedBytes: Long, segmentBytes: List<Long>, segments: Int): Long {
        if (segments <= 1) return mergedBytes
        return segmentBytes.sum()
    }
}