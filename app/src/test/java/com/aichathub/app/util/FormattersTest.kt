package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `bytes with zero and sub-kilobyte values`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("0 B", Formatters.bytes(-5))
        assertEquals("500 B", Formatters.bytes(500))
    }

    @Test
    fun `bytes steps through units`() {
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.0 MB", Formatters.bytes(1024L * 1024))
        assertEquals("1.5 GB", Formatters.bytes((1536L * 1024 * 1024).toLong()))
    }

    @Test
    fun `bytesMb and bytesGb`() {
        assertEquals("1 MB", Formatters.bytesMb(1024L * 1024))
        assertEquals("1.00 GB", Formatters.bytesGb(1024L * 1024 * 1024))
        assertEquals("3.00 GB", Formatters.bytesGb(3L * 1024 * 1024 * 1024))
    }
}