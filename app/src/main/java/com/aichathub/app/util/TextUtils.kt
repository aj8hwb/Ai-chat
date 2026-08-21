package com.aichathub.app.util

/**
 * String helpers shared across the app.
 */
object TextUtils {

    /**
     * Like [String.take] but never splits a UTF-16 surrogate pair, so emoji and
     * other astral-plane characters survive hard truncation intact.
     *
     * If the cut would land on a high surrogate (the first half of a pair), the
     * returned string ends one character earlier so neither half of the pair is
     * kept — an orphaned surrogate renders as a broken "?" glyph.
     */
    fun takeNoSplit(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) {
            end--
        }
        return value.substring(0, end)
    }
}