package com.aichathub.app.util

/**
 * Pure (de)serialization for the measured-memory map persisted in DataStore
 * as a single string: `"modelId:bytes;modelId:bytes"`.
 *
 * Model ids never contain `:`, so the last index splits the key and value
 * unambiguously. No Android dependencies — unit-testable.
 */
object MeasuredMemory {

    fun encode(map: Map<String, Long>): String =
        map.entries.joinToString(";") { "${it.key}:${it.value}" }

    fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, Long>()
        raw.split(";").forEach { part ->
            if (part.isBlank()) return@forEach
            val idx = part.lastIndexOf(':')
            if (idx <= 0) return@forEach
            val id = part.substring(0, idx)
            val bytes = part.substring(idx + 1).toLongOrNull() ?: return@forEach
            if (bytes > 0) result[id] = bytes
        }
        return result
    }
}
