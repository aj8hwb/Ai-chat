package com.aichathub.app.data

import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.data.local.MessageDao
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationExportManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    suspend fun exportMarkdown(conversationId: Long): String {
        val conversation = conversationDao.byId(conversationId)
            ?: return "Conversation not found."
        val messages = messageDao.forConversation(conversationId)

        return buildString {
            appendLine("# ${conversation.title.ifBlank { "Untitled Conversation" }}")
            appendLine()
            appendLine("**Model:** ${conversation.modelId}")
            appendLine("**Created:** ${formatTimestamp(conversation.createdAt)}")
            appendLine("**Updated:** ${formatTimestamp(conversation.updatedAt)}")
            if (!conversation.systemPrompt.isNullOrBlank()) {
                appendLine()
                appendLine("**System prompt:**")
                appendLine("> ${conversation.systemPrompt}")
            }
            appendLine()
            appendLine("---")
            appendLine()

            messages.forEach { msg ->
                val role = when (msg.role) {
                    "user" -> "**You**"
                    "assistant" -> "**Assistant**"
                    else -> "**${msg.role.replaceFirstChar { it.uppercase() }}**"
                }
                appendLine("$role · ${formatTimestamp(msg.createdAt)}")
                appendLine()
                appendLine(msg.content)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    suspend fun exportJson(conversationId: Long): String {
        val conversation = conversationDao.byId(conversationId)
            ?: return JSONObject().put("error", "Conversation not found.").toString(2)
        val messages = messageDao.forConversation(conversationId)

        val json = JSONObject().apply {
            put("title", conversation.title.ifBlank { "Untitled Conversation" })
            put("modelId", conversation.modelId)
            put("createdAt", formatTimestamp(conversation.createdAt))
            put("updatedAt", formatTimestamp(conversation.updatedAt))
            put("systemPrompt", conversation.systemPrompt ?: JSONObject.NULL)

            val messagesArray = JSONArray()
            messages.forEach { msg ->
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                    put("timestamp", formatTimestamp(msg.createdAt))
                })
            }
            put("messages", messagesArray)
        }

        return json.toString(2)
    }

    suspend fun exportPlainText(conversationId: Long): String {
        val conversation = conversationDao.byId(conversationId)
            ?: return "Conversation not found."
        val messages = messageDao.forConversation(conversationId)

        return buildString {
            appendLine(conversation.title.ifBlank { "Untitled Conversation" })
            appendLine("Model: ${conversation.modelId}")
            appendLine("Created: ${formatTimestamp(conversation.createdAt)}")
            appendLine("Updated: ${formatTimestamp(conversation.updatedAt)}")
            if (!conversation.systemPrompt.isNullOrBlank()) {
                appendLine()
                appendLine("System prompt:")
                appendLine(conversation.systemPrompt)
            }
            appendLine()
            appendLine("=".repeat(40))
            appendLine()

            messages.forEach { msg ->
                val role = when (msg.role) {
                    "user" -> "You"
                    "assistant" -> "Assistant"
                    else -> msg.role.replaceFirstChar { it.uppercase() }
                }
                appendLine("[$role] ${formatTimestamp(msg.createdAt)}")
                appendLine(msg.content)
                appendLine()
                appendLine("-".repeat(30))
                appendLine()
            }
        }
    }

    private fun formatTimestamp(millis: Long): String =
        timestampFormat.format(Date(millis))
}
