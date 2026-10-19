package com.aichathub.app.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds SYNTHETIC GGUF v2/v3 headers in memory and verifies
 * [GgufMetadataReader] against them: real-file parsing without real model
 * weights. The byte layout mirrors the GGUF v2/v3 spec (magic, u32 version,
 * u64 tensor count, u64 kv count, u64-length-prefixed string keys, u32 value
 * type, typed values).
 */
class GgufMetadataReaderTest {

    // ── GGUF byte writer ────────────────────────────────────────────────

    private class GgufWriter {
        private val out = ByteArrayOutputStream()

        private val le = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun u8(v: Int): GgufWriter {
            le.clear()
            le.put(v.toByte())
            out.write(le.array(), 0, 1)
            return this
        }

        fun u32(v: Int): GgufWriter {
            le.clear()
            le.putInt(v)
            out.write(le.array(), 0, 4)
            return this
        }

        fun u64(v: Long): GgufWriter {
            le.clear()
            le.putLong(v)
            out.write(le.array(), 0, 8)
            return this
        }

        fun str(s: String): GgufWriter {
            u64(s.length.toLong())
            out.write(s.toByteArray(Charsets.UTF_8))
            return this
        }

        fun keyType(key: String, type: Int): GgufWriter {
            str(key)
            u32(type)
            return this
        }

        fun magic(): GgufWriter {
            out.write("GGUF".toByteArray(Charsets.US_ASCII))
            return this
        }

        fun toFile(): File = File.createTempFile("synthetic", ".gguf").apply {
            deleteOnExit()
            writeBytes(out.toByteArray())
        }
    }

    private fun typeBool() = 7
    private fun typeF32() = 6

    private fun standardLlamaHeader(writer: GgufWriter): GgufWriter = writer
        .keyType("llama.block_count", TYPE_U32).let {
            it.u32(32)
            it
        }
        .keyType("llama.attention.head_count", TYPE_U32).let {
            it.u32(32)
            it
        }
        .keyType("llama.attention.head_count_kv", TYPE_U32).let {
            it.u32(8)
            it
        }
        .keyType("llama.attention.key_length", TYPE_U32).let {
            it.u32(128)
            it
        }
        .keyType("llama.attention.value_length", TYPE_U32).let {
            it.u32(128)
            it
        }

    // ── happy path ──────────────────────────────────────────────────────

    @Test
    fun `standard llama GGUF metadata produces the exact KvCacheSpec`() {
        val file = standardLlamaHeader(
            GgufWriter().magic()
                .let {
                    it.u32(3)
                    it
                } // version
                .let {
                    it.u64(0L)
                    it
                } // tensor count
                .let {
                    it.u64(5L)
                    it
                },
        ).toFile()

        val metadata = GgufMetadataReader.readMetadata(file)
        assertTrue(metadata != null)
        assertEquals(32L, metadata!!.blockCount)
        assertEquals(8L, metadata.headCountKv)
        val spec = GgufMetadataReader.toKvCacheSpec(metadata)
        assertEquals(32, spec!!.layers)
        assertEquals(8, spec.kvHeads)
        assertEquals(128, spec.headDim)
        assertEquals(2, spec.bytesPerElement)
        assertEquals(2L * 32 * 8 * 128 * 2, spec.bytesPerToken)
    }

    @Test
    fun `architecture name is read from general dot architecture`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(2)
            .keyType("general.architecture", TYPE_STRING).let {
                it.str("qwen2")
                it
            }
            .keyType("qwen2.block_count", TYPE_U32).let {
                it.u32(24)
                it
            }
            .toFile()
        assertEquals("qwen2", GgufMetadataReader.readMetadata(file)!!.architecture)
    }

    @Test
    fun `missing head_count_kv falls back to the MHA assumption`() {
        // A plain GGUF model without a KV-head key is treated as full-MHA:
        // kvHeads = headCount (conservative upper bound, documented).
        val file = GgufWriter().magic().u32(3).u64(0).u64(2)
            .keyType("m.block_count", TYPE_U32).let {
                it.u32(20)
                it
            }
            .keyType("m.attention.head_count", TYPE_U32).let {
                it.u32(16)
                it
            }
            .keyType("m.attention.key_length", TYPE_U32).let {
                it.u32(64)
                it
            }
            .toFile()
        val spec = GgufMetadataReader.toKvCacheSpec(GgufMetadataReader.readMetadata(file))
        assertEquals(16, spec!!.kvHeads)
        assertEquals(64, spec.headDim)
    }

    @Test
    fun `key length derives from embedding and head count when absent`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(2)
            .keyType("m.block_count", TYPE_U32).let {
                it.u32(20)
                it
            }
            .keyType("m.attention.head_count", TYPE_U32).let {
                it.u32(16)
                it
            }
            .keyType("m.embedding_length", TYPE_U32).let {
                it.u32(2048)
                it
            }
            .toFile()
        val spec = GgufMetadataReader.toKvCacheSpec(GgufMetadataReader.readMetadata(file))
        assertEquals(2048 / 16, spec!!.headDim)
    }

    @Test
    fun `differing K and V dimensions use the max`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(3)
            .keyType("m.block_count", TYPE_U32).let {
                it.u32(20)
                it
            }
            .keyType("m.attention.head_count_kv", TYPE_U32).let {
                it.u32(4)
                it
            }
            .keyType("m.attention.key_length", TYPE_U32).let {
                it.u32(96)
                it
            }
            .keyType("m.attention.value_length", TYPE_U32).let {
                it.u32(192)
                it
            }
            .toFile()
        val spec = GgufMetadataReader.toKvCacheSpec(GgufMetadataReader.readMetadata(file))
        assertEquals(192, spec!!.headDim)
    }

    @Test
    fun `v1 file is unsupported`() {
        val file = GgufWriter().magic().u32(1).u64(0).u64(0).toFile()
        assertNull(GgufMetadataReader.readMetadata(file))
    }

    // ── hostile inputs (all must fall back, never throw) ────────────────

    @Test
    fun `wrong magic is unreadable`() {
        val file = File.createTempFile("notgguf", ".gguf").apply {
            writeBytes("XXXX".toByteArray() + ByteArray(24))
            deleteOnExit()
        }
        assertNull(GgufMetadataReader.readMetadata(file))
    }

    @Test
    fun `absurd kv count aborts parsing`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(Long.MAX_VALUE).toFile()
        assertNull(GgufMetadataReader.readMetadata(file))
    }

    @Test
    fun `cross-layer kv sharing (negative head_count_kv) yields no spec`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(3)
            .keyType("m.block_count", TYPE_U32).let {
                it.u32(20)
                it
            }
            .keyType("m.attention.head_count_kv", TYPE_U32).let {
                it.u32(0xFF_FF_FFFEu.toInt())
                it
            }
            .keyType("m.attention.key_length", TYPE_U32).let {
                it.u32(128)
                it
            }
            .toFile()
        val metadata = GgufMetadataReader.readMetadata(file)
        // -2 * may come through as a huge u32 → invalid → validator returns null
        assertNull(GgufMetadataReader.toKvCacheSpec(metadata))
    }

    @Test
    fun `fixed-size arrays are skipped and parsing continues`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(3)
            .keyType("m.some_u32_array", TYPE_ARRAY).let {
                it.u32(TYPE_U32)
                it.u64(4L)
                repeat(4) { it.u32(1) }
                it
            }
            .keyType("m.block_count", TYPE_U32).let {
                it.u32(10)
                it
            }
            .keyType("m.attention.head_count_kv", TYPE_U32).let {
                it.u32(5)
                it
            }
            .keyType("m.attention.key_length", TYPE_U32).let {
                it.u32(128)
                it
            }
            .toFile()
        val spec = GgufMetadataReader.toKvCacheSpec(GgufMetadataReader.readMetadata(file))
        assertEquals(10, spec!!.layers)
        assertEquals(5, spec.kvHeads)
    }

    @Test
    fun `string array after needed fields still yields partial metadata`() {
        // Vocab (huge string array) comes AFTER the arch fields in real GGUF
        // produced by llama.cpp; even if it aborts, the scalar fields survive.
        val file = GgufWriter().magic().u32(3).u64(0).u64(4)
            .keyType("m.block_count", TYPE_U32).let {
                it.u32(10)
                it
            }
            .keyType("m.attention.head_count_kv", TYPE_U32).let {
                it.u32(5)
                it
            }
            .keyType("m.attention.key_length", TYPE_U32).let {
                it.u32(128)
                it
            }
            .keyType("tokenizer.ggml.tokens", TYPE_ARRAY).let {
                it.u32(TYPE_STRING)
                it.u64(32000L)
                // Truncated string lengths would be too much data for the
                // 1 MiB window → skipArray fails → partial return.
                it.u64(10_000_000L)
                it
            }
            .toFile()
        val spec = GgufMetadataReader.toKvCacheSpec(GgufMetadataReader.readMetadata(file))
        assertEquals(10, spec!!.layers)
        assertEquals(5, spec.kvHeads)
    }

    @Test
    fun `metadata after insane string keys aborts without usable fields`() {
        // A key with a 10 MB declared length aborts; nothing usable collected.
        val file = GgufWriter().magic().u32(3).u64(0).u64(2)
            .keyType("m.attention.head_count", TYPE_U32).let {
                it.u32(16)
                it
            }
            .keyType("m.".padEnd(600, 'x'), TYPE_U32).let {
                it.u32(5)
                it
            }
            // key length 600 > cap 512 → abort → only head_count survives,
            // which alone cannot build a spec (no layers).
            .toFile()
        val metadata = GgufMetadataReader.readMetadata(file)
        assertNull(GgufMetadataReader.toKvCacheSpec(metadata))
    }

    @Test
    fun `missing block count yields no spec even with heads present`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(1)
            .keyType("m.attention.head_count_kv", TYPE_U32).let {
                it.u32(8)
                it
            }
            .toFile()
        assertNull(GgufMetadataReader.toKvCacheSpec(GgufMetadataReader.readMetadata(file)))
    }

    @Test
    fun `null metadata yields no spec`() {
        assertNull(GgufMetadataReader.toKvCacheSpec(null))
    }

    @Test
    fun `empty file is unreadable`() {
        val file = File.createTempFile("empty", ".gguf").apply { deleteOnExit() }
        assertNull(GgufMetadataReader.readMetadata(file))
    }

    @Test
    fun `nonexistent file is unreadable`() {
        assertNull(GgufMetadataReader.readMetadata(File("/nonexistent/nope.gguf")))
    }

    @Test
    fun `bool and float32 values are traversed without breaking the scan`() {
        val file = GgufWriter().magic().u32(3).u64(0).u64(4)
            .keyType("m.some_flag", typeBool()).let {
                it.u8(1)
                it
            }
            .keyType("m.some_float", typeF32()).let {
                it.u32(0x3F800000)
                it
            } // 1.0f bits
            .keyType("m.block_count", TYPE_U32).let {
                it.u32(6)
                it
            }
            .keyType("m.attention.head_count_kv", TYPE_U32).let {
                it.u32(3)
                it
            }
            .keyType("m.attention.key_length", TYPE_U32).let {
                it.u32(64)
                it
            }
            .toFile()
        val spec = GgufMetadataReader.toKvCacheSpec(GgufMetadataReader.readMetadata(file))
        assertEquals(6, spec!!.layers)
        assertEquals(3, spec.kvHeads)
    }

    private companion object {
        const val TYPE_U32 = 4
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
    }
}
