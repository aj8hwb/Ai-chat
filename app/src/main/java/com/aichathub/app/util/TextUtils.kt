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
            if (code in 0xD800..0xDBFF && i + 1 < chars.size && chars[i + 1].code in 0xDC00..0xDFFF) {
                // A surrogate pair (e.g. an emoji) is one grapheme unit worth
                // ~2 tokens; consume it only if it fits the budget entirely,
                // otherwise back off so the pair is never half-kept.
                if (tokens + 2 > maxTokens) break
                tokens += 2
                i += 2
            } else {
                // Rough token estimate: ~2 bytes per token, minimum 1.
                tokens += 1
                i++
            }
        }
        return text.substring(0, i.coerceAtMost(text.length))
    }
}
