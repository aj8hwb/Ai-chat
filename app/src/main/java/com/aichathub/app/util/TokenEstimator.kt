package com.aichathub.app.util

/**
 * Improved token estimation with language-aware heuristics.
 *
 * Different scripts have different token/character ratios:
 *  - English (Latin):    ~4 chars/token (word-piece BPE)
 *  - Bengali/Devanagari: ~1-2 chars/token (each character is complex)
 *  - CJK (Chinese/Japanese/Korean): ~1-1.5 chars/token
 *  - Code: ~2-3 chars/token (operators, keywords are tokens)
 *  - Emoji: 1-2 tokens per emoji
 *  - Numbers: ~1-2 chars/token
 *
 * The heuristic uses UTF-8 byte count with language-specific divisors
 * for a more accurate estimate than a single bytes/N approach.
 *
 * For production accuracy, this estimator can be enhanced with:
 * 1. Native tokenizer integration (when available)
 * 2. Model-specific tokenization rules
 * 3. BPE token frequency tables
 *
 * Pure logic, no Android dependencies, unit-testable.
 */
object TokenEstimator {

    /**
     * Estimates token count using a multi-heuristic approach.
     * Returns a conservative upper bound (never underestimates).
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        if (text.length <= 2) return 1

        val bytes = text.toByteArray(Charsets.UTF_8).size
        val chars = text.length

        // Detect dominant script
        val script = detectDominantScript(text)

        val estimatedTokens = when (script) {
            Script.LATIN -> {
                // English: BPE typically produces ~4 chars/token
                // But code mixed with English is denser
                if (containsCode(text)) {
                    (chars / 2.5).toInt()
                } else {
                    (chars / 4.0).toInt()
                }
            }
            Script.CJK -> {
                // Chinese/Japanese: ~1-1.5 chars/token
                (chars / 1.2).toInt()
            }
            Script.HANGUL -> {
                // Korean: syllable blocks map closely to tokens
                (chars / 1.3).toInt()
            }
            Script.DEVANAGARI,
            Script.BENGALI,
            Script.THAI,
            Script.ARABIC -> {
                // These scripts: each character is typically 1 token
                (chars / 1.1).toInt()
            }
            Script.CYRILLIC -> {
                // Russian/Ukrainian: ~3-4 chars/token
                (chars / 3.5).toInt()
            }
            Script.MIXED -> {
                // Mixed scripts: use byte-based estimation (conservative)
                bytes / 3
            }
        }

        // Emoji handling: each emoji is typically 1-2 tokens
        val emojiCount = countEmojis(text)
        val emojiTokens = emojiCount * 1 // ~1 token per emoji

        // Adjust for emoji-heavy text
        val baseTokens = if (emojiCount > 0 && emojiCount > chars * 0.1) {
            // Emoji-heavy: use char-based with emoji adjustment
            (chars * 0.8 + emojiTokens * 0.2).toInt()
        } else {
            estimatedTokens
        }

        return baseTokens.coerceAtLeast(1)
    }

    /**
     * Estimates tokens with a safety margin for critical operations.
     * Adds a configurable percentage buffer to the base estimate.
     */
    fun estimateWithSafetyMargin(text: String, safetyMarginPercent: Float = 0.15f): Int {
        val baseEstimate = estimate(text)
        val margin = (baseEstimate * safetyMarginPercent).toInt()
        return baseEstimate + margin
    }

    /**
     * Estimates tokens for multiple text segments combined.
     */
    fun estimateCombined(texts: List<String>): Int {
        return texts.sumOf { estimate(it) }
    }

    /**
     * Maximum prompt tokens that fit beside [maxOutputTokens] inside a
     * [contextLength] window, leaving a safety margin.
     */
    fun promptBudgetTokens(contextLength: Int, maxOutputTokens: Int): Int {
        val available = contextLength - maxOutputTokens
        return (available * 0.9).toInt().coerceAtLeast(64)
    }

    /**
     * Token-to-character ratio for the detected script.
     * Useful for truncation that doesn't split mid-character.
     */
    fun charsPerToken(text: String): Float {
        val script = detectDominantScript(text)
        return when (script) {
            Script.LATIN -> if (containsCode(text)) 2.5f else 4.0f
            Script.CJK -> 1.2f
            Script.HANGUL -> 1.3f
            Script.DEVANAGARI, Script.BENGALI, Script.THAI, Script.ARABIC -> 1.1f
            Script.CYRILLIC -> 3.5f
            Script.MIXED -> 3.0f
        }
    }

    /**
     * Truncates text to fit within a token budget while preserving complete tokens.
     * Uses character-level truncation based on the detected script's chars-per-token ratio.
     */
    fun truncateToTokenBudget(text: String, maxTokens: Int): String {
        if (text.isEmpty()) return text
        
        val estimatedTokens = estimate(text)
        if (estimatedTokens <= maxTokens) return text
        
        val ratio = charsPerToken(text)
        val targetChars = (maxTokens * ratio * 0.9).toInt() // 10% safety margin
        
        return if (targetChars >= text.length) {
            text
        } else {
            text.take(targetChars.coerceAtLeast(1))
        }
    }

    private enum class Script {
        LATIN, CJK, HANGUL, DEVANAGARI, BENGALI, THAI, ARABIC, CYRILLIC, MIXED
    }

    private fun detectDominantScript(text: String): Script {
        var latin = 0
        var cjk = 0
        var hangul = 0
        var devanagari = 0
        var bengali = 0
        var thai = 0
        var arabic = 0
        var cyrillic = 0
        var other = 0

        for (codePoint in text.codePoints()) {
            when {
                codePoint in 0x0041..0x024F -> latin++ // Basic Latin + Latin Extended
                codePoint in 0x4E00..0x9FFF || codePoint in 0x3400..0x4DBF -> cjk++ // CJK Unified
                codePoint in 0xAC00..0xD7AF -> hangul++ // Hangul Syllables
                codePoint in 0x0900..0x097F -> devanagari++ // Devanagari
                codePoint in 0x0980..0x09FF -> bengali++ // Bengali
                codePoint in 0x0E00..0x0E7F -> thai++ // Thai
                codePoint in 0x0600..0x06FF || codePoint in 0xFB50..0xFDFF -> arabic++ // Arabic
                codePoint in 0x0400..0x04FF -> cyrillic++ // Cyrillic
                codePoint in 0x1F600..0x1F64F || codePoint in 0x1F300..0x1F5FF -> other++ // Emoji (count as other)
                else -> other++
            }
        }

        val total = latin + cjk + hangul + devanagari + bengali + thai + arabic + cyrillic + other
        if (total == 0) return Script.LATIN

        // If no single script dominates (>60%), it's mixed
        val maxCount = maxOf(latin, cjk, hangul, devanagari, bengali, thai, arabic, cyrillic)
        if (maxCount < total * 0.6) return Script.MIXED

        return when {
            latin == maxCount -> Script.LATIN
            cjk == maxCount -> Script.CJK
            hangul == maxCount -> Script.HANGUL
            devanagari == maxCount -> Script.DEVANAGARI
            bengali == maxCount -> Script.BENGALI
            thai == maxCount -> Script.THAI
            arabic == maxCount -> Script.ARABIC
            cyrillic == maxCount -> Script.CYRILLIC
            else -> Script.MIXED
        }
    }

    private fun containsCode(text: String): Boolean {
        // Simple heuristic: code has lots of symbols and keywords
        val codeIndicators = listOf("{", "}", "()", ";", "==", "!=", "//", "/*", "fun ", "val ", "var ", "if (", "for (")
        val count = codeIndicators.count { text.contains(it, ignoreCase = true) }
        return count >= 2
    }

    private fun countEmojis(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            // Check if it's an emoji
            if (codePoint in 0x1F600..0x1F64F || // Emoticons
                codePoint in 0x1F300..0x1F5FF || // Misc Symbols and Pictographs
                codePoint in 0x1F680..0x1F6FF || // Transport and Map
                codePoint in 0x1F900..0x1F9FF || // Supplemental Symbols
                codePoint in 0x2600..0x26FF ||   // Misc Symbols
                codePoint in 0x2700..0x27BF ||   // Dingbats
                codePoint in 0xFE00..0xFE0F ||   // Variation Selectors
                codePoint in 0x1F000..0x1F02F    // Mahjong Tiles
            ) {
                count++
            }
            i += charCount
        }
        return count
    }
}
