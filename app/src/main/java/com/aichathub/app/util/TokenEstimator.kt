package com.aichathub.app.util

/**
 * Conservative prompt-size estimation.
 *
 * llama.cpp's native context is measured in TOKENS, not characters. Bounding a
 * prompt by characters is unsafe: in token-dense scripts (Bengali, Chinese,
 * Japanese, Korean) one character is ~1 token, while the old 4-char-per-token
 * assumption fits English only. Overestimating is safe (we simply keep a little
 * more history headroom), underestimating crashes the native process.
 *
 * Heuristic: UTF-8 bytes / 3.
 *  - English:  ~1 byte/char  -> ~3 chars/token  (real ~4, so conservative).
 *  - Bengali:  ~3 bytes/char -> ~1 token/char   (matches real tokenizers).
 *  - CJK:      ~3 bytes/char -> ~1 token/char   (matches real tokenizers).
 *  - Code:     ~1-2 bytes/char -> 2-3 chars/token (conservative).
 *
 * Pure logic, no Android dependencies, unit-testable.
 */
object TokenEstimator {

    private const val BYTES_PER_TOKEN = 3

    /** Returns a conservative upper-bound estimate of the token count. */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val byBytes = bytes / BYTES_PER_TOKEN
        // Never let punctuation-only / empty-ish strings report zero tokens.
        return (byBytes).coerceAtLeast(1)
    }

    /**
     * Maximum prompt tokens that fit beside [maxOutputTokens] inside a
     * [contextLength] window, leaving a small safety margin.
     */
    fun promptBudgetTokens(contextLength: Int, maxOutputTokens: Int): Int {
        val available = contextLength - maxOutputTokens
        return (available * 0.9).toInt().coerceAtLeast(64)
    }
}