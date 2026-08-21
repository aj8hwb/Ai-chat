package com.aichathub.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aichathub.app.data.local.AiDatabase
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Full chat backup & restore.
 *
 * A backup is a single JSON document containing every conversation and message
 * plus a small metadata header (schema version, exported-at, count). The
 * document is portable: it can be opened in any editor, survives app
 * reinstalls, and can be re-imported on this device or any other.
 *
 * Backup format:
 * {
 *   "app": "aichathub",
 *   "schema": 1,
 *   "exportedAt": 1690000000000,
 *   "conversations": [
 *     {
 *       "title": "...",
 *       "modelId": "...",
 *       "createdAt": 1690000000000,
 *       "updatedAt": 1690000000000,
 *       "systemPrompt": "..." | null,
 *       "messages": [
 *         { "role": "user", "content": "...", "createdAt": 1690000000000, "modelId": "..." }
 *       ]
 *     }
 *   ]
 * }
 *
 * Import is additive: imported conversations are inserted as NEW conversations
 * (existing chats are never overwritten). Message timestamps are preserved.
 */
class ChatBackupManager(
    private val context: Context,
    private val database: AiDatabase
) {

    sealed interface BackupResult {
        data class Success(val summary: String) : BackupResult
        data class Failure(val message: String) : BackupResult
    }

    /** Exports the whole chat history into [uri]. */
    suspend fun export(uri: Uri): BackupResult {
        val convDao = database.conversationDao()
        val msgDao = database.messageDao()
        return try {
            val conversations = convDao.getAll()
            val root = JSONObject().apply {
                put("app", "aichathub")
                put("schema", SCHEMA)
                put("exportedAt", System.currentTimeMillis())
                put("conversationCount", conversations.size)
            }
            val convArr = JSONArray()
            conversations.forEach { conv ->
                val messages = msgDao.forConversation(conv.id)
                val msgArr = JSONArray()
                messages.forEach { m ->
                    msgArr.put(
                        JSONObject().apply {
                            put("role", m.role)
                            put("content", m.content)
                            put("createdAt", m.createdAt)
                            if (m.modelId != null) put("modelId", m.modelId)
                        }
                    )
                }
                convArr.put(
                    JSONObject().apply {
                        put("title", conv.title)
                        put("modelId", conv.modelId)
                        put("createdAt", conv.createdAt)
                        put("updatedAt", conv.updatedAt)
                        if (conv.systemPrompt != null) put("systemPrompt", conv.systemPrompt)
                        put("messages", msgArr)
                    }
                )
            }
            root.put("conversations", convArr)

            context.contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                out.write(root.toString(2).toByteArray())
            } ?: return BackupResult.Failure("Could not open the destination file")

            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            BackupResult.Success("Backup saved at $stamp — ${conversations.size} conversations")
        } catch (e: Exception) {
            BackupResult.Failure("Export failed: ${e.message}")
        }
    }

    /** Imports conversations from [uri] (additive, never overwrites). */
    suspend fun import(uri: Uri): BackupResult {
        val convDao = database.conversationDao()
        val msgDao = database.messageDao()
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { it: InputStream ->
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return BackupResult.Failure("Could not open the backup file")

            val root = JSONObject(content)
            if (root.optString("app") != "aichathub") {
                return BackupResult.Failure("This does not look like an AiChatHub backup file")
            }
            val schema = root.optInt("schema", 0)
            if (schema > SCHEMA) {
                return BackupResult.Failure("Backup was made by a newer app version (schema $schema)")
            }
            val convArr = root.optJSONArray("conversations") ?: JSONArray()
            var imported = 0
            var messages = 0
            for (i in 0 until convArr.length()) {
                val convJson = convArr.getJSONObject(i)
                val newId = convDao.insert(
                    ConversationEntity(
                        title = convJson.optString("title", "Imported chat"),
                        modelId = convJson.optString("modelId", ""),
                        createdAt = convJson.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = convJson.optLong("updatedAt", System.currentTimeMillis()),
                        systemPrompt = if (convJson.has("systemPrompt") && !convJson.isNull("systemPrompt"))
                            convJson.optString("systemPrompt") else null
                    )
                )
                val msgArr = convJson.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgArr.length()) {
                    val m = msgArr.getJSONObject(j)
                    msgDao.insert(
                        MessageEntity(
                            conversationId = newId,
                            role = m.optString("role", "assistant"),
                            content = m.optString("content", ""),
                            createdAt = m.optLong("createdAt", System.currentTimeMillis()),
                            modelId = if (m.has("modelId") && !m.isNull("modelId")) m.optString("modelId") else null
                        )
                    )
                    messages++
                }
                imported++
            }
            BackupResult.Success("Imported $imported conversations ($messages messages)")
        } catch (e: Exception) {
            BackupResult.Failure("Import failed: ${e.message}")
        }
    }

    /** Human-readable file name suggestion for a backup document. */
    fun suggestedFileName(): String =
        "AiChatHub-Backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"

    private companion object {
        const val SCHEMA = 1
    }
}