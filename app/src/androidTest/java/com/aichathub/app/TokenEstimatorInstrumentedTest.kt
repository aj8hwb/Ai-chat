package com.aichathub.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aichathub.app.util.TokenEstimator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for TokenEstimator.
 * Verifies token estimation accuracy across different scripts.
 */
@RunWith(AndroidJUnit4::class)
class TokenEstimatorInstrumentedTest {

    @Test
    fun englishText_estimatesCorrectly() {
        val text = "The quick brown fox jumps over the lazy dog"
        val tokens = TokenEstimator.estimate(text)
        // English: ~10-12 tokens for this sentence
        assertTrue("Expected 5-20 tokens for English text, got $tokens", tokens in 5..20)
    }

    @Test
    fun bengaliText_estimatesCorrectly() {
        val text = "আমি বাংলায় কথা বলি। এটি একটি পরীক্ষা।"
        val tokens = TokenEstimator.estimate(text)
        // Bengali: each character is ~1 token
        assertTrue("Expected 15-40 tokens for Bengali text, got $tokens", tokens in 15..40)
    }

    @Test
    fun chineseText_estimatesCorrectly() {
        val text = "这是一个测试句子。中文文本的标记化与英文不同。"
        val tokens = TokenEstimator.estimate(text)
        // Chinese: ~1-1.5 tokens per character
        assertTrue("Expected 15-35 tokens for Chinese text, got $tokens", tokens in 15..35)
    }

    @Test
    fun mixedScriptText_estimatesCorrectly() {
        val text = "Hello 你好Bonjour مرحبا"
        val tokens = TokenEstimator.estimate(text)
        assertTrue("Expected 5-15 tokens for mixed text, got $tokens", tokens in 5..15)
    }

    @Test
    fun emptyText_returnsZero() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun singleCharacter_returnsOne() {
        assertEquals(1, TokenEstimator.estimate("a"))
    }

    @Test
    fun codeText_estimatesCorrectly() {
        val text = "fun main() { println(\"Hello World\") }"
        val tokens = TokenEstimator.estimate(text)
        assertTrue("Expected 5-15 tokens for code, got $tokens", tokens in 5..15)
    }

    @Test
    fun promptBudget_isReasonable() {
        val budget = TokenEstimator.promptBudgetTokens(4096, 512)
        // Should be ~90% of (4096 - 512) = ~3225
        assertTrue("Expected ~3000-3500, got $budget", budget in 3000..3500)
    }
}
