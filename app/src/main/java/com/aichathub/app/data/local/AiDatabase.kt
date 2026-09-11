package com.aichathub.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "installed_models")
data class InstalledModelEntity(
    @PrimaryKey val modelId: String,
    val installedAt: Long,
    val filePath: String,
    val fileSizeBytes: Long,
    val state: String,
    val lastUsedAt: Long = 0L
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Optional per-conversation system prompt override; null/blank = use the global one. */
    val systemPrompt: String? = null,
    /**
     * LLM-generated summary of older messages in this conversation.
     * When set, the summary is injected into the context instead of the
     * full message history, enabling effective conversation compaction.
     * Only populated when the context window fills up and SUMMARIZE
     * compaction is triggered.
     */
    @ColumnInfo(defaultValue = "NULL")
    val summary: String? = null
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["conversationId", "createdAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val modelId: String? = null
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface InstalledModelDao {
    @Query("SELECT * FROM installed_models")
    fun observeAll(): Flow<List<InstalledModelEntity>>

    @Query("SELECT * FROM installed_models")
    suspend fun getAll(): List<InstalledModelEntity>

    @Query("SELECT * FROM installed_models WHERE modelId = :modelId")
    suspend fun byId(modelId: String): InstalledModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstalledModelEntity)

    @Query("DELETE FROM installed_models WHERE modelId = :modelId")
    suspend fun delete(modelId: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET systemPrompt = :systemPrompt WHERE id = :id")
    suspend fun setSystemPrompt(id: Long, systemPrompt: String?)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE conversations SET summary = :summary WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String?)

    @Query("SELECT summary FROM conversations WHERE id = :id")
    suspend fun getSummary(id: Long): String?

    /**
     * Removes "ghost" conversations that were created but never received a
     * message (e.g. a failed send). A chat is only worth keeping once the user
     * actually exchanged a message in it.
     */
    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT DISTINCT conversationId FROM messages)")
    suspend fun pruneEmpty()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    /**
     * Returns the most recent [limit] messages for a conversation, ordered
     * chronologically. This avoids loading the full conversation history into
     * memory when only recent context is needed for prompt building.
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForConversation(conversationId: Long, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Int
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(
    entities = [
        InstalledModelEntity::class,
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AiDatabase : RoomDatabase() {
    abstract fun installedModelDao(): InstalledModelDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        /**
         * v1 → v2: index the `messages.conversationId` column.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
            }
        }

        /**
         * v2 → v3: add the per-conversation system prompt override column.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `systemPrompt` TEXT DEFAULT NULL")
            }
        }

        /**
         * v3 → v4: add the summary column for conversation compaction.
         * When the context window fills up, older messages are summarized
         * by the LLM and stored here to enable effective context management.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `summary` TEXT DEFAULT NULL")
            }
        }

        /**
         * v4 → v5: add composite index on (conversationId, createdAt) for
         * efficient message queries that filter by conversation and sort by time.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `messages` (`conversationId`, `createdAt`)")
            }
        }
    }
}