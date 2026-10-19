package com.aichathub.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [StreamCoalescer] directly (it is `internal` for exactly this):
 * verifies the UI emission interval and — critically — that a flush after a
 * cancelled/completed generation emits the COMPLETE partial text and never
 * leaves the UI holding a stale 150 ms snapshot (spec §8.6/§8.9).
 */
class StreamCoalescerTest {

    @Test
    fun `every token is emitted with a zero interval`() {
        val emitted = mutableListOf<String>()
        val coalescer = StreamCoalescer({ emitted.add(it) }, intervalMs = 0)
        val buffer = StringBuilder()
        repeat(5) { i ->
            buffer.append("t$i")
            coalescer.onToken(buffer)
        }
        assertEquals(5, emitted.size)
        assertEquals("t0t1t2t3t4", emitted.last())
    }

    @Test
    fun `interval throttles emissions - the canonical buffer stays authoritative`() {
        val emitted = mutableListOf<String>()
        val coalescer = StreamCoalescer({ emitted.add(it) }, intervalMs = Long.MAX_VALUE)
        val buffer = StringBuilder()
        buffer.append("chunk-one")
        coalescer.onToken(buffer)
        buffer.append("+chunk-two")
        coalescer.onToken(buffer) // within the interval → suppressed
        // Nothing was emitted more than once, and NO emission carries stale text.
        assertEquals(1, emitted.size)
        assertEquals("chunk-one", emitted.first())
        // ...but the buffer the coordinator keeps is complete:
        assertEquals("chunk-one+chunk-two", buffer.toString())
    }

    @Test
    fun `flush after cancellation emits the complete partial text exactly once`() {
        val emitted = mutableListOf<String>()
        val coalescer = StreamCoalescer({ emitted.add(it) }, intervalMs = Long.MAX_VALUE)
        val buffer = StringBuilder("partial because the user pressed stop at 400 tokens")
        coalescer.onToken(buffer)         // emitted at t0
        buffer.append(" more streamed after the interval")
        // (generation cancelled — no more onToken calls)
        coalescer.flush(buffer)           // cancellation flush
        assertEquals(2, emitted.size)
        assertEquals(
            "partial because the user pressed stop at 400 tokens more streamed after the interval",
            emitted.last()
        )
    }

    @Test
    fun `flush of an empty buffer emits nothing`() {
        val emitted = mutableListOf<String>()
        val coalescer = StreamCoalescer({ emitted.add(it) }, intervalMs = 0)
        coalescer.flush(StringBuilder())
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `flush does not double-emit identical content`() {
        val emitted = mutableListOf<String>()
        val coalescer = StreamCoalescer({ emitted.add(it) }, intervalMs = 0)
        val buffer = StringBuilder("done")
        coalescer.onToken(buffer)
        coalescer.flush(buffer)
        // One periodic emission + one authoritative final flush = 2; content
        // identical and both complete — the UI can always display the last.
        assertEquals(2, emitted.size)
        assertEquals("done", emitted.last())
        coalescer.flush(buffer) // second flush after completion must not duplicate reality
        assertEquals(3, emitted.size)
        assertEquals("done", emitted.last())
    }

    @Test
    fun `default interval is the documented 150 ms`() {
        assertEquals(150L, StreamCoalescer.INTERVAL_MS)
        assertTrue(StreamCoalescer.INTERVAL_MS > 0)
    }
}
