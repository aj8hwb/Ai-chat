package com.aichathub.app.util

/**
 * Shared text utilities used across the app.
 */
object TextUtils {

    /**
     * Creates a short conversation title from a user prompt.
     * Truncates at 42 characters with an ellipsis if needed.
     */
    fun titleFromPrompt(prompt: String): String {
        val clean = prompt.trim().replace("\n", " ")
        return if (clean.length > 42) clean.take(42) + "\u2026" else clean
    }

    /**
     * Takes the first [maxTokens] estimated tokens worth of [text] without
     * splitting a UTF-16 surrogate pair (emoji safety).
     */
    fun takeNoSplit(text: String, maxTokens: Int): String {
        if (text.isEmpty()) return text
        val chars = text.toCharArray()
        var tokens = 0
        var i = 0
        while (i < chars.size && tokens < maxTokens) {
            val code = chars[i].code
            // Rough token estimate: 1 token per ~4 bytes
            val bytes = when {
                code <= 0x7F -> 1
                code <= 0x7FF -> 2
                code in 0xD800..0xDFFF -> 4 // surrogate pair
                code <= 0xFFFF -> 3
                else -> 4
            }
            tokens += (bytes / 4).coerceAtLeast(1)
            // Skip surrogate pair
            if (code in 0xD800..0xDBFF && i + 1 < chars.size && chars[i + 1].code in 0xDC00..0xDFFF) {
                i += 2
            } else {
                i++
            }
        }
        return text.substring(0, i.coerceAtMost(text.length))
    }
}
