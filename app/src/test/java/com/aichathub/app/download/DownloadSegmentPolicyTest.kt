package com.aichathub.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSegmentPolicyTest {

    private fun bytes(size: Long): Long = size

    @Test
    fun `persisted meta wins even when the file is small`() {
        assertEquals(8, DownloadSegmentPolicy.resolveSegments(8, false, false, true, bytes(1024)))
        assertEquals(4, DownloadSegmentPolicy.resolveSegments(4, false, false, true, bytes(1024)))
        assertEquals(1, DownloadSegmentPolicy.resolveSegments(1, false, false, true, bytes(1024 * 1024)))
    }

    @Test
    fun `no meta but a merged part exists means single stream`() {
        assertEquals(1, DownloadSegmentPolicy.resolveSegments(null, true, false, true, bytes(8L * 1024 * 1024 * 1024)))
    }

    @Test
    fun `no meta and no files falls back to probing`() {
        assertEquals(8, DownloadSegmentPolicy.resolveSegments(null, false, false, true, bytes(600L * 1024 * 1024)))
        assertEquals(1, DownloadSegmentPolicy.resolveSegments(null, false, false, false, bytes(600L * 1024 * 1024)))
        assertEquals(8, DownloadSegmentPolicy.resolveSegments(null, false, false, true, bytes(100L * 1024 * 1024)))
        assertEquals(1, DownloadSegmentPolicy.resolveSegments(null, false, false, true, bytes(10L * 1024 * 1024)))
    }

    @Test
    fun `small files never segment`() {
        assertEquals(1, DownloadSegmentPolicy.resolveFresh(true, bytes(DownloadSegmentPolicy.SEGMENT_BYTES_THRESHOLD - 1)))
    }

    @Test
    fun `large files with range support segment`() {
        assertEquals(8, DownloadSegmentPolicy.resolveFresh(true, bytes(DownloadSegmentPolicy.SEGMENT_BYTES_THRESHOLD)))
    }

    @Test
    fun `legacy segment files without a marker must be cleaned`() {
        assertEquals(0, DownloadSegmentPolicy.resolveSegments(null, false, true, true, bytes(2L * 1024 * 1024 * 1024)))
    }

    @Test
    fun `single stream counts only the merged part`() {
        assertEquals(5_000, DownloadSegmentPolicy.bytesDownloaded(5_000, listOf(3_000, 4_000), 1))
    }

    @Test
    fun `segmented mode counts only the segment files, never the merged part`() {
        assertEquals(7_000, DownloadSegmentPolicy.bytesDownloaded(5_000, listOf(3_000, 4_000), 4))
    }

    @Test
    fun `no downloaded bytes is zero`() {
        assertEquals(0L, DownloadSegmentPolicy.bytesDownloaded(0L, emptyList(), 4))
    }
}