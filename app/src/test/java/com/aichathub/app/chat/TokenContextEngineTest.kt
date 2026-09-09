package com.aichathub.app.chat

import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ChatTemplate
import com.aichathub.app.domain.model.ModelFormat
import com.aichathub.app.util.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TokenContextEngineTest {

    private lateinit var engine: TokenContextEngine

    private val smallModel = CatalogModel(
        id = "small",
        name = "Small Model",
        provider = "test",
        description = "",
        parameters = "1B",
        category = "chat",
        format = ModelFormat.GGUF,
        quantization = "Q4_K_M",
        fileSizeBytes = 500_000_000L,
        estimatedMemoryBytes = 800_000_000L,
        contextLength = 2048,
        license = "MIT",
        licenseType = "MIT",
        officialRepositoryUrl = "",
        downloadUrl = "",
        fileName = "small.gguf",
        runtime = "llama",
        chatTemplate = ChatTemplate.CHATML
    )

    private val largeModel = CatalogModel(
        id = "large",
        name = "Large Model",
        provider = "test",
        description = "",
        parameters = "70B",
        category = "chat",
        format = ModelFormat.GGUF,
        quantization = "Q4_K_M",
        fileSizeBytes = 40_000_000_000L,
        estimatedMemoryBytes = 42_000_000_000L,
        contextLength = 131072,
        license = "MIT",
        licenseType = "MIT",
        officialRepositoryUrl = "",
        downloadUrl = "",
        fileName = "large.gguf",
        runtime = "llama",
        chatTemplate = ChatTemplate.LLAMA3
    )

    @Before
    fun setUp() {
        engine = TokenContextEngine()
    }

    // ── estimateContext ──────────────────────────────────────────────

    @Test
    fun `estimateContext with empty history returns minimal token count`() {
        val status = engine.estimateContext(
            userMessage = "Hello",
            systemPrompt = "You are helpful",
            conversationHistory = emptyList(),
            model = smallModel
        )

        assertTrue(status.promptTokens > 0)
        assertEquals(0, status.generationTokens)
        assertEquals(2048, status.maxTokens)
        assertFalse(status.isNearLimit)
        assertFalse(status.isAtLimit)
    }

    @Test
    fun `estimateContext includes system prompt tokens`() {
        val withoutSystem = engine.estimateContext(
            userMessage = "Hi",
            systemPrompt = "",
            conversationHistory = emptyList(),
            model = smallModel
        )
        val withSystem = engine.estimateContext(
            userMessage = "Hi",
            systemPrompt = "You are a helpful assistant who always responds concisely.",
            conversationHistory = emptyList(),
            model = smallModel
        )

        assertTrue(withSystem.systemTokens > withoutSystem.systemTokens)
        assertTrue(withSystem.promptTokens > withoutSystem.promptTokens)
    }

    @Test
    fun `estimateContext with long history adds history tokens`() {
        val shortHistory = listOf(
            "user" to "Hello",
            "assistant" to "Hi there!"
        )
        val longHistory = (1..20).flatMap { listOf(
            "user" to "Message $it from user with some extra content to make it longer",
            "assistant" to "Response $it from assistant with additional details and explanation"
        ) }

        val shortStatus = engine.estimateContext("Hi", "sys", shortHistory, smallModel)
        val longStatus = engine.estimateContext("Hi", "sys", longHistory, smallModel)

        assertTrue(longStatus.promptTokens > shortStatus.promptTokens)
    }

    @Test
    fun `estimateContext utilization percent is clamped to 100`() {
        val status = engine.estimateContext(
            userMessage = "x".repeat(100_000),
            systemPrompt = "y".repeat(100_000),
            conversationHistory = emptyList(),
            model = smallModel
        )

        assertTrue(status.utilizationPercent <= 100f)
    }

    @Test
    fun `estimateContext large context model has higher maxTokens`() {
        val smallStatus = engine.estimateContext("Hi", "sys", emptyList(), smallModel)
        val largeStatus = engine.estimateContext("Hi", "sys", emptyList(), largeModel)

        assertEquals(2048, smallStatus.maxTokens)
        assertEquals(131072, largeStatus.maxTokens)
    }

    // ── near-limit and at-limit detection ────────────────────────────

    @Test
    fun `nearLimit is true when utilization exceeds 80 percent`() {
        // Use a tiny context to fill it up quickly
        val tinyModel = smallModel.copy(contextLength = 100)
        val status = engine.estimateContext(
            userMessage = "x".repeat(500),
            systemPrompt = "y".repeat(500),
            conversationHistory = (1..10).map { "user" to "z".repeat(100) },
            model = tinyModel
        )

        assertTrue(status.isNearLimit)
    }

    @Test
    fun `atLimit is true when utilization exceeds 95 percent`() {
        val tinyModel = smallModel.copy(contextLength = 50)
        val status = engine.estimateContext(
            userMessage = "x".repeat(500),
            systemPrompt = "y".repeat(500),
            conversationHistory = (1..20).map { "user" to "z".repeat(200) },
            model = tinyModel
        )

        assertTrue(status.isAtLimit)
    }

    @Test
    fun `nearLimit is false for low utilization`() {
        val status = engine.estimateContext(
            userMessage = "Hi",
            systemPrompt = "sys",
            conversationHistory = emptyList(),
            model = largeModel
        )

        assertFalse(status.isNearLimit)
        assertFalse(status.isAtLimit)
    }

    // ── trimHistory ──────────────────────────────────────────────────

    @Test
    fun `trimHistory returns empty list when all history exceeds budget`() {
        val tinyModel = smallModel.copy(contextLength = 100)
        val history = (1..10).map { "user" to "z".repeat(200) }

        val trimmed = engine.trimHistory(history, tinyModel, maxOutputTokens = 20)

        assertEquals(0, trimmed.size)
    }

    @Test
    fun `trimHistory keeps recent messages when possible`() {
        val history = (1..10).map { "user" to "Message $it" }

        val trimmed = engine.trimHistory(history, smallModel, maxOutputTokens = 512)

        assertTrue(trimmed.size <= history.size)
        // Most recent messages should be kept
        if (trimmed.isNotEmpty()) {
            assertEquals(history.last(), trimmed.last())
        }
    }

    @Test
    fun `trimHistory returns all history when it fits`() {
        val history = listOf("user" to "Hi", "assistant" to "Hello!")

        val trimmed = engine.trimHistory(history, smallModel, maxOutputTokens = 512)

        assertEquals(2, trimmed.size)
    }

    // ── suggestCompaction ────────────────────────────────────────────

    @Test
    fun `suggestCompaction returns null when not near limit`() {
        val status = TokenContextEngine.ContextStatus(
            promptTokens = 100,
            generationTokens = 0,
            systemTokens = 10,
            totalUsed = 100,
            maxTokens = 4096,
            utilizationPercent = 2.4f,
            isNearLimit = false,
            isAtLimit = false
        )
        val history = listOf("user" to "Hi")

        val suggestion = engine.suggestCompaction(status, history)

        assertNull(suggestion)
    }

    @Test
    fun `suggestCompaction suggests TRIM_OLDEST when near limit but not at limit`() {
        val status = TokenContextEngine.ContextStatus(
            promptTokens = 3500,
            generationTokens = 0,
            systemTokens = 100,
            totalUsed = 3500,
            maxTokens = 4096,
            utilizationPercent = 85.4f,
            isNearLimit = true,
            isAtLimit = false
        )
        val history = listOf(
            "user" to "Old message",
            "assistant" to "Old response",
            "user" to "New message",
            "assistant" to "New response"
        )

        val suggestion = engine.suggestCompaction(status, history)

        assertNotNull(suggestion)
        assertEquals(CompactionType.TRIM_OLDEST, suggestion!!.type)
        assertTrue(suggestion.estimatedTokensSaved > 0)
    }

    @Test
    fun `suggestCompaction suggests SUMMARIZE at high utilization`() {
        val status = TokenContextEngine.ContextStatus(
            promptTokens = 3700,
            generationTokens = 0,
            systemTokens = 100,
            totalUsed = 3700,
            maxTokens = 4096,
            utilizationPercent = 90.3f,
            isNearLimit = true,
            isAtLimit = false
        )
        val history = (1..10).map { "user" to "Message $it with content" }

        val suggestion = engine.suggestCompaction(status, history)

        assertNotNull(suggestion)
        assertEquals(CompactionType.SUMMARIZE, suggestion!!.type)
    }

    @Test
    fun `suggestCompaction suggests START_NEW when at limit with minimal history`() {
        val status = TokenContextEngine.ContextStatus(
            promptTokens = 4000,
            generationTokens = 0,
            systemTokens = 500,
            totalUsed = 4000,
            maxTokens = 4096,
            utilizationPercent = 97.7f,
            isNearLimit = true,
            isAtLimit = true
        )
        val history = listOf("user" to "Hi")

        val suggestion = engine.suggestCompaction(status, history)

        assertNotNull(suggestion)
        assertEquals(CompactionType.START_NEW, suggestion!!.type)
        assertEquals(
            "Context is at capacity with minimal history. Start a new conversation.",
            suggestion.reason
        )
    }

    @Test
    fun `suggestCompaction suggests TRIM_OLDEST when at limit with many messages`() {
        val status = TokenContextEngine.ContextStatus(
            promptTokens = 4000,
            generationTokens = 0,
            systemTokens = 500,
            totalUsed = 4000,
            maxTokens = 4096,
            utilizationPercent = 97.7f,
            isNearLimit = true,
            isAtLimit = true
        )
        val history = (1..10).map { "user" to "Message $it" }

        val suggestion = engine.suggestCompaction(status, history)

        assertNotNull(suggestion)
        assertEquals(CompactionType.TRIM_OLDEST, suggestion!!.type)
        assertTrue(suggestion.estimatedTokensSaved > 0)
    }

    // ── estimateSystemTokens ─────────────────────────────────────────

    @Test
    fun `estimateSystemTokens with empty prompt returns zero`() {
        assertEquals(0, engine.estimateSystemTokens(""))
    }

    @Test
    fun `estimateSystemTokens returns positive for non-empty prompt`() {
        assertTrue(engine.estimateSystemTokens("You are a helpful assistant") > 0)
    }

    @Test
    fun `estimateSystemTokens with template returns more tokens than raw`() {
        val prompt = "You are helpful"
        val raw = engine.estimateSystemTokens(prompt)
        val withTemplate = engine.estimateSystemTokens(prompt, ChatTemplate.CHATML)

        assertTrue(withTemplate >= raw)
    }

    // ── estimateHistoryTokens ────────────────────────────────────────

    @Test
    fun `estimateHistoryTokens returns zero for empty history`() {
        assertEquals(0, engine.estimateHistoryTokens(emptyList()))
    }

    @Test
    fun `estimateHistoryTokens sums token counts`() {
        val history = listOf("user" to "Hello", "assistant" to "Hi there!")

        val tokens = engine.estimateHistoryTokens(history)

        assertTrue(tokens > 0)
        // Should be roughly the sum of individual estimates
        val expected = TokenEstimator.estimate("user") + TokenEstimator.estimate("Hello") +
            TokenEstimator.estimate("assistant") + TokenEstimator.estimate("Hi there!")
        assertEquals(expected, tokens)
    }
}
