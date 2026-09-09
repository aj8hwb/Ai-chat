package com.aichathub.app.chat

import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ChatTemplate
import com.aichathub.app.util.TokenEstimator

/**
 * Advanced token counting and context management engine.
 *
 * Tracks token usage across system prompt, conversation history and pending
 * user input to provide real-time visibility into context window consumption.
 * Also suggests compaction strategies when the context is near capacity.
 */
class TokenContextEngine {

    data class ContextStatus(
        val promptTokens: Int,
        val generationTokens: Int,
        val systemTokens: Int,
        val totalUsed: Int,
        val maxTokens: Int,
        val utilizationPercent: Float,
        val isNearLimit: Boolean,
        val isAtLimit: Boolean
    )

    fun estimateContext(
        userMessage: String,
        systemPrompt: String,
        conversationHistory: List<Pair<String, String>>,
        model: CatalogModel
    ): ContextStatus {
        val maxTokens = model.contextLength
        val systemTokens = estimateSystemTokens(systemPrompt)
        val historyTokens = conversationHistory.sumOf { (role, content) ->
            TokenEstimator.estimate(role) + TokenEstimator.estimate(content)
        }
        val userTokens = TokenEstimator.estimate(userMessage)
        val promptTokens = systemTokens + historyTokens + userTokens
        val totalUsed = promptTokens
        val utilizationPercent = (totalUsed.toFloat() / maxTokens.coerceAtLeast(1)) * 100f
        return ContextStatus(
            promptTokens = promptTokens,
            generationTokens = 0,
            systemTokens = systemTokens,
            totalUsed = totalUsed,
            maxTokens = maxTokens,
            utilizationPercent = utilizationPercent.coerceIn(0f, 100f),
            isNearLimit = utilizationPercent > 80f,
            isAtLimit = utilizationPercent > 95f
        )
    }

    fun estimateFromRendered(
        renderedPrompt: String,
        model: CatalogModel,
        outputTokens: Int = 0
    ): ContextStatus {
        val promptTokens = TokenEstimator.estimate(renderedPrompt)
        val totalUsed = promptTokens + outputTokens
        val maxTokens = model.contextLength
        val utilizationPercent = (totalUsed.toFloat() / maxTokens.coerceAtLeast(1)) * 100f
        return ContextStatus(
            promptTokens = promptTokens,
            generationTokens = outputTokens,
            systemTokens = 0,
            totalUsed = totalUsed,
            maxTokens = maxTokens,
            utilizationPercent = utilizationPercent.coerceIn(0f, 100f),
            isNearLimit = utilizationPercent > 80f,
            isAtLimit = utilizationPercent > 95f
        )
    }

    fun trimHistory(
        history: List<Pair<String, String>>,
        model: CatalogModel,
        maxOutputTokens: Int
    ): List<Pair<String, String>> {
        val promptBudget = TokenEstimator.promptBudgetTokens(model.contextLength, maxOutputTokens)
        val result = history.toMutableList()
        while (result.isNotEmpty() && estimateHistoryTokens(result) > promptBudget) {
            result.removeAt(0)
        }
        return result
    }

    fun estimateHistoryTokens(history: List<Pair<String, String>>): Int =
        history.sumOf { (role, content) ->
            TokenEstimator.estimate(role) + TokenEstimator.estimate(content)
        }

    fun suggestCompaction(
        contextStatus: ContextStatus,
        history: List<Pair<String, String>>
    ): CompactionSuggestion? {
        if (!contextStatus.isNearLimit) return null

        if (contextStatus.isAtLimit && history.size <= 2) {
            return CompactionSuggestion(
                type = CompactionType.START_NEW,
                reason = "Context is at capacity with minimal history. Start a new conversation.",
                estimatedTokensSaved = contextStatus.totalUsed
            )
        }

        if (contextStatus.isAtLimit) {
            val oldTokens = history.dropLast(2).sumOf { (role, content) ->
                TokenEstimator.estimate(role) + TokenEstimator.estimate(content)
            }
            return CompactionSuggestion(
                type = CompactionType.TRIM_OLDEST,
                reason = "Context is almost full. Older messages will be dropped to make room.",
                estimatedTokensSaved = oldTokens
            )
        }

        if (contextStatus.utilizationPercent > 85f) {
            val halfHistoryTokens = estimateHistoryTokens(history.take(history.size / 2))
            return CompactionSuggestion(
                type = CompactionType.SUMMARIZE,
                reason = "Context is getting full. Summarizing older messages can free space.",
                estimatedTokensSaved = halfHistoryTokens
            )
        }

        val oldestTokens = history.firstOrNull()?.let { (role, content) ->
            TokenEstimator.estimate(role) + TokenEstimator.estimate(content)
        } ?: 0
        return CompactionSuggestion(
            type = CompactionType.TRIM_OLDEST,
            reason = "Context usage is high. Dropping the oldest message can help.",
            estimatedTokensSaved = oldestTokens
        )
    }

    fun estimateSystemTokens(systemPrompt: String): Int {
        return TokenEstimator.estimate(systemPrompt)
    }

    fun estimateSystemTokens(systemPrompt: String, template: ChatTemplate): Int {
        val rendered = renderSystemBlock(template, systemPrompt)
        return TokenEstimator.estimate(rendered)
    }

    fun buildSummaryMessages(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        val half = messages.size / 2
        val toSummarize = messages.take(half)
        val toKeep = messages.drop(half)
        val summaryText = toSummarize.joinToString(" ") { (role, content) ->
            "$role said: ${content.take(200)}"
        }
        val summaryEntry = Pair("system", "[Summary of earlier conversation]: $summaryText")
        return listOf(summaryEntry) + toKeep
    }

    private fun renderSystemBlock(template: ChatTemplate, systemPrompt: String): String {
        val sys = systemPrompt.trim()
        if (sys.isEmpty()) return ""
        return when (template) {
            ChatTemplate.CHATML -> buildString {
                appendLine("<|im_start|>system")
                appendLine(sys)
                append("<|im_end|>")
            }
            ChatTemplate.GEMMA -> buildString {
                appendLine("<start_of_turn>system")
                appendLine(sys)
                append("<end_of_turn>")
            }
            ChatTemplate.LLAMA3 -> buildString {
                append("<|start_header_id|>system<|end_header_id|>")
                appendLine()
                appendLine()
                appendLine(sys)
                append("<|eot_id|>")
            }
            ChatTemplate.LLAMA2 -> buildString {
                appendLine("<<SYS>>")
                appendLine(sys)
                appendLine("<</SYS>>")
                appendLine()
            }
            ChatTemplate.GENERIC -> buildString {
                appendLine("System: $sys")
                appendLine()
            }
        }
    }
}

data class CompactionSuggestion(
    val type: CompactionType,
    val reason: String,
    val estimatedTokensSaved: Int
)

enum class CompactionType { TRIM_OLDEST, SUMMARIZE, START_NEW }
