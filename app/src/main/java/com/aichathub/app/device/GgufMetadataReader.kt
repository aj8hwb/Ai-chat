package com.aichathub.app.device

import com.aichathub.app.device.MemoryBudgetCalculator.KvCacheSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads REAL architecture metadata from the selected GGUF file header.
 *
 * Why this exists: the packaged native binding (`llama-kotlin-android` 0.1.7)
 * exposes NO model-metadata API — its JNI surface is version/create/load/
 * generate/cancel/chat-template only (verified against the AAR). llama.cpp
 * itself reads this same metadata from the GGUF header, so parsing the file
 * header is the supported, verifiable source for KV-cache estimation:
 *
 *   KV bytes/token = 2 (K+V) × block_count × head_count_kv × head_dim × bytes_per_elem
 *
 * Only STANDARD GGUF metadata keys are read (no invented field names):
 *   general.architecture, {arch}.block_count, {arch}.embedding_length,
 *   {arch}.attention.head_count, {arch}.attention.head_count_kv,
 *   {arch}.attention.key_length, {arch}.attention.value_length
 *
 * Format support: GGUF v2/v3 (uint64 counts) — the format llama.cpp writes
 * today. v1 is obsolete and unsupported → treated as unreadable → fallback.
 *
 * SAFETY: parsing is strictly bounded (1 MiB header window, ≤ 1024 KVs, keys
 * ≤ 512 chars, values capped). Fixed-size arrays are skipped arithmetically;
 * string/nested arrays abort with the fields collected so far. Every abnormal
 * exit yields null or a partial result WITHOUT throwing — the caller always
 * falls back to the conservative parameter-count heuristic. No value is ever
 * fabricated: an absent field stays null.
 *
 * Cache dtype assumption: GGUF metadata does not carry the runtime's KV cache
 * quantization (the binding exposes no type_k/type_v config), so f16
 * (2 bytes/element) is assumed for BOTH K and V — the llama.cpp default.
 * This is a documented assumption, not a measured fact.
 */
object GgufMetadataReader {

    private const val MAGIC = "GGUF"

    /** Sanity bounds; exceeding any aborts parsing (caller falls back). */
    private const val MAX_KV_PAIRS = 1024L
    private const val MAX_KEY_LENGTH = 512
    private const val MAX_STRING_VALUE = 4096
    private const val HEADER_WINDOW_BYTES = 1L shl 20 // 1 MiB
    private const val MAX_ARRAY_ELEMS = 4_000_000L

    /** Validation bounds for real transformer metadata. */
    private const val MAX_BLOCK_COUNT = 512L
    private const val MAX_HEAD_COUNT = 256L
    private const val MAX_HEAD_DIM = 4096L
    private const val MIN_HEAD_DIM = 8L

    /** GGUF value-type ids (standard enum order). */
    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    /** Standard `general.architecture` key (string-typed). */
    private const val ARCH_KEY = "general.architecture"

    /** Raw GGUF header metadata, straight from the file (null = field absent). */
    data class GgufKvMetadata(
        val version: Int,
        val architecture: String?,
        val blockCount: Long?,
        val embeddingLength: Long?,
        val headCount: Long?,
        val headCountKv: Long?,
        val keyLength: Long?,
        val valueLength: Long?
    )

    /** Mutable collector for the scalar fields we recognize. */
    private class Collector {
        var architecture: String? = null
        var blockCount: Long? = null
        var embeddingLength: Long? = null
        var headCount: Long? = null
        var headCountKv: Long? = null
        var keyLength: Long? = null
        var valueLength: Long? = null

        /** Standard `{arch}.<field>` key → field; unknown keys are ignored. */
        fun add(key: String, value: Long) {
            val dot = key.indexOf('.')
            if (dot <= 0 || dot >= key.length - 1) return
            when (key.substring(dot + 1)) {
                "block_count" -> blockCount = value
                "embedding_length" -> embeddingLength = value
                "attention.head_count" -> headCount = value
                "attention.head_count_kv" -> headCountKv = value
                "attention.key_length" -> keyLength = value
                "attention.value_length" -> valueLength = value
            }
        }

        fun toMetadata(version: Int): GgufKvMetadata? =
            if (blockCount == null && headCountKv == null && keyLength == null && embeddingLength == null) {
                null // nothing usable was collected
            } else {
                GgufKvMetadata(
                    version,
                    architecture,
                    blockCount,
                    embeddingLength,
                    headCount,
                    headCountKv,
                    keyLength,
                    valueLength,
                )
            }
    }

    /**
     * Parses the GGUF header of [file]. Returns null when the file is not
     * GGUF, uses an unsupported version, is unreadable, or produced no usable
     * metadata — in ALL cases the caller must fall back rather than fabricate.
     */
    fun readMetadata(file: File): GgufKvMetadata? {
        return try {
            if (!file.isFile) return null
            val windowBytes = minOf(file.length(), HEADER_WINDOW_BYTES)
            if (windowBytes < 24) return null
            val buffer = ByteBuffer.allocate(windowBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            java.io.RandomAccessFile(file, "r").use { raf ->
                var total = 0
                val chunk = ByteArray(64 * 1024)
                while (total < windowBytes) {
                    val n = raf.read(chunk, 0, minOf(chunk.size, windowBytes.toInt() - total))
                    if (n <= 0) break
                    buffer.put(chunk, 0, n)
                    total += n
                }
                if (total < 24) return null
            }
            buffer.flip()
            parseHeader(buffer)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    // ── header parsing ──────────────────────────────────────────────────

    private fun parseHeader(buf: ByteBuffer): GgufKvMetadata? {
        if (buf.remaining() < 4) return null
        val magic = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        if (magic != MAGIC) return null

        if (buf.remaining() < 4 + 8 + 8) return null
        val versionRaw = buf.int.toLong()
        val version = if (versionRaw in 2..3) versionRaw.toInt() else return null

        buf.long // tensor_count — irrelevant to KV estimation
        val kvCount = buf.long
        if (kvCount < 0 || kvCount > MAX_KV_PAIRS) return null

        val c = Collector()
        var i = 0L
        while (i < kvCount) {
            val key = readString(buf, MAX_KEY_LENGTH)
                ?: return c.toMetadata(0)
            if (buf.remaining() < 4) return c.toMetadata(0)
            val type = buf.int
            when (type) {
                TYPE_UINT8, TYPE_INT8 -> {
                    val v = readFixedScalar(buf, 1) ?: return c.toMetadata(0)
                    c.add(key, v)
                }
                TYPE_UINT16, TYPE_INT16 -> {
                    val v = readFixedScalar(buf, 2) ?: return c.toMetadata(0)
                    c.add(key, v)
                }
                TYPE_UINT32, TYPE_INT32 -> {
                    val v = readFixedScalar(buf, 4) ?: return c.toMetadata(0)
                    c.add(key, v)
                }
                TYPE_UINT64, TYPE_INT64 -> {
                    val v = readFixedScalar(buf, 8) ?: return c.toMetadata(0)
                    c.add(key, v)
                }
                TYPE_BOOL -> if (buf.remaining() < 1) return c.toMetadata(0) else buf.get()
                TYPE_FLOAT32 -> if (buf.remaining() < 4) return null else buf.position(buf.position() + 4)
                TYPE_FLOAT64 -> if (buf.remaining() < 8) return null else buf.position(buf.position() + 8)
                TYPE_STRING -> {
                    val s = readString(buf, MAX_STRING_VALUE)
                        ?: return c.toMetadata(0)
                    if (key == ARCH_KEY) c.architecture = s
                }
                TYPE_ARRAY -> {
                    if (buf.remaining() < 4 + 8) return c.toMetadata(0)
                    val elemType = buf.int
                    val count = buf.long
                    if (count < 0 || !skipArray(buf, elemType, count)) {
                        return c.toMetadata(0)
                    }
                }
                else -> return c.toMetadata(0)
            }
            i++
        }
        return GgufKvMetadata(
            version,
            c.architecture,
            c.blockCount,
            c.embeddingLength,
            c.headCount,
            c.headCountKv,
            c.keyLength,
            c.valueLength,
        )
    }

    /** Reads a fixed-size LE scalar as Long; null when truncated. */
    private fun readFixedScalar(buf: ByteBuffer, size: Int): Long? {
        if (buf.remaining() < size) return null
        return when (size) {
            1 -> buf.get().toLong()
            2 -> buf.short.toLong()
            4 -> buf.int.toLong()
            8 -> buf.long
            else -> null
        }
    }

    /** Reads a GGUF string (u64 length + UTF-8 bytes) with a hard cap. */
    private fun readString(buf: ByteBuffer, cap: Int): String? {
        if (buf.remaining() < 8) return null
        val len = buf.long
        if (len < 0 || len > cap) return null
        if (buf.remaining() < len) return null
        val bytes = ByteArray(len.toInt())
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Advances past an array. Fixed-size elements are skipped arithmetically;
     * string arrays walk lengths with caps; nested/unknown arrays abort.
     * Never throws.
     */
    private fun skipArray(buf: ByteBuffer, elemType: Int, count: Long): Boolean {
        val elemSize = when (elemType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1L
            TYPE_UINT16, TYPE_INT16 -> 2L
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4L
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8L
            TYPE_STRING -> -1L
            else -> return false
        }
        if (count < 0 || count > MAX_ARRAY_ELEMS) return false
        if (elemSize > 0) {
            val bytes = Math.multiplyExact(count, elemSize)
            if (bytes > buf.remaining()) return false
            buf.position(buf.position() + bytes.toInt())
            return true
        }
        var i = 0L
        while (i < count) {
            if (buf.remaining() < 8) return false
            val len = buf.long
            if (len < 0 || len > buf.remaining()) return false
            buf.position(buf.position() + len.toInt())
            i++
        }
        return true
    }

    // ── validation → KvCacheSpec ────────────────────────────────────────

    /**
     * Validates raw metadata into a production [KvCacheSpec], applying
     * documented conservative fallbacks per missing field:
     *  - `head_count_kv` absent → full-MHA assumption using `head_count`
     *    (conservative UPPER bound on KV memory: a model without a
     *    KV-head key is very likely MHA; a real GQA model would use less,
     *    so the estimate stays safe).
     *  - `head_count_kv` ≤ 0 (the GGUF encoding for cross-layer KV sharing)
     *    → not representable by this simple spec → null → safe fallback.
     *  - key/value lengths absent → derived from embedding_length / head_count
     *    (the standard transformer identity), else invalid → null.
     *  - the effective head dimension is max(K, V), covering differing K/V sizes.
     */
    fun toKvCacheSpec(metadata: GgufKvMetadata?): KvCacheSpec? {
        if (metadata == null) return null
        val layers = metadata.blockCount ?: return null
        if (layers < 1 || layers > MAX_BLOCK_COUNT) return null

        val kvHeads: Long = when {
            metadata.headCountKv != null ->
                if (metadata.headCountKv in 1..MAX_HEAD_COUNT) metadata.headCountKv else return null
            metadata.headCount != null && metadata.headCount in 1..MAX_HEAD_COUNT ->
                metadata.headCount // documented MHA assumption
            else -> return null
        }

        val keyDim = safeDim(metadata.keyLength)
        val valueDim = safeDim(metadata.valueLength)
        val headDim: Long = when {
            keyDim != null -> maxOf(keyDim, valueDim ?: 0L)
            metadata.embeddingLength != null && metadata.headCount != null &&
                metadata.headCount in 1..MAX_HEAD_COUNT ->
                metadata.embeddingLength / metadata.headCount
            else -> return null
        }
        val effectiveHeadDim = maxOf(headDim, valueDim ?: headDim)
        if (effectiveHeadDim < MIN_HEAD_DIM || effectiveHeadDim > MAX_HEAD_DIM) return null

        // Overflow guard for 2 × layers × kvHeads × headDim × bytesPerElem.
        if (layers > Long.MAX_VALUE / (2 * kvHeads * effectiveHeadDim * 2)) return null

        return KvCacheSpec(
            layers = layers.toInt(),
            kvHeads = kvHeads.toInt(),
            headDim = effectiveHeadDim.toInt(),
            // f16 K and f16 V (documented assumption)
            bytesPerElement = 2,
        )
    }

    /** Accepts only sane dim values; anything else is treated as absent. */
    private fun safeDim(dim: Long?): Long? = if (dim != null && dim in MIN_HEAD_DIM..MAX_HEAD_DIM) dim else null
}
