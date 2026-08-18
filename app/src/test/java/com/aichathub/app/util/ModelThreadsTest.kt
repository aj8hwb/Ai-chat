package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelThreadsTest {

    @Test
    fun `battery conscious mode always uses two threads`() {
        assertEquals(2, ModelThreads.recommended(batteryConscious = true))
    }

    @Test
    fun `normal mode stays within the clamp range`() {
        val threads = ModelThreads.recommended(batteryConscious = false)
        assertTrue(threads in ModelThreads.MIN_THREADS..ModelThreads.MAX_THREADS)
    }

    @Test
    fun `normal mode is never battery-conservative`() {
        assertTrue(ModelThreads.recommended(batteryConscious = false) >= ModelThreads.MIN_THREADS)
    }
}
