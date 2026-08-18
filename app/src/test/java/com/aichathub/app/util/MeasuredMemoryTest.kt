package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasuredMemoryTest {

    @Test
    fun `empty and null decode to an empty map`() {
        assertEquals(emptyMap<String, Long>(), MeasuredMemory.decode(null))
        assertEquals(emptyMap<String, Long>(), MeasuredMemory.decode(""))
    }

    @Test
    fun `round trips a map`() {
        val map = mapOf("qwen2-5-0-5b" to 123_456_789L, "llama-3-2-1b" to 987_654_321L)
        val encoded = MeasuredMemory.encode(map)
        assertEquals(map, MeasuredMemory.decode(encoded))
    }

    @Test
    fun `malformed entries are skipped`() {
        val decoded = MeasuredMemory.decode("model-a:100;garbage;model-b:abc;-bad:-5")
        assertEquals(mapOf("model-a" to 100L), decoded)
    }

    @Test
    fun `non-positive values are dropped on decode`() {
        val decoded = MeasuredMemory.decode("model-a:0")
        assertEquals(emptyMap<String, Long>(), decoded)
    }
}
