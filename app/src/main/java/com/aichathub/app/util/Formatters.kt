package com.aichathub.app.util

import java.util.Locale

object Formatters {

    fun bytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${units[unit]}"
        else String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun bytesMb(bytes: Long): String =
        String.format(Locale.US, "%.0f MB", bytes / (1024f * 1024f))

    fun bytesGb(bytes: Long): String =
        String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
}