package com.aichathub.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun `empty text is zero tokens`() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun `short text never reports zero`() {
        assertTrue(TokenEstimator.estimate(".") >= 1)
    }

    @Test
    fun `english estimates roughly bytes over three`() {
        // "hello world" = 11 bytes -> 3 tokens
        assertEquals(3, TokenEstimator.estimate("hello world"))
    }

    @Test
    fun `token-dense script is not undercounted`() {
        // Bengali text: ~3 bytes/char -> ~1 token/char. The estimate must never
        // be LOWER than the conservative bound for a CJK/Bengali string, because
        // underestimating the prompt would overflow the native context.
        val bengali = "আমার সোনার বাংলা আমি তোমায় ভালোবাসি"
        val estimated = TokenEstimator.estimate(bengali)
        val charCount = bengali.length
        assertTrue("estimated=$estimated chars=$charCount", estimated >= charCount / 2)
    }

    @Test
    fun `longer english text scales linearly`() {
        val short = TokenEstimator.estimate("a".repeat(30))
        val long = TokenEstimator.estimate("a".repeat(300))
        assertTrue(long > short)
    }

    @Test
    fun `prompt budget leaves room for output`() {
        // 4096 context, 512 output -> ~3584 available, ~3225 with 90% margin.
        val budget = TokenEstimator.promptBudgetTokens(4096, 512)
        assertTrue(budget > 3000)
        assertTrue(budget < 4096)
    }

    @Test
    fun `prompt budget has a floor`() {
        // Even a tiny context must leave at least 64 tokens for the prompt.
        assertEquals(64, TokenEstimator.promptBudgetTokens(64, 64))
    }
}