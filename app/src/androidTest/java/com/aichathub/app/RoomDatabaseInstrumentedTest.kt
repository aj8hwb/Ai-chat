package com.aichathub.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aichathub.app.data.local.AiDatabase
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Room database operations.
 * Tests conversation and message CRUD with the new summary column.
 */
@RunWith(AndroidJUnit4::class)
class RoomDatabaseInstrumentedTest {

    private lateinit var db: AiDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertConversation_andRetrieve() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.conversationDao().insert(
            ConversationEntity(
                title = "Test Chat",
                modelId = "test-model",
                createdAt = now,
                updatedAt = now
            )
        )
        assertTrue("Conversation ID should be positive", id > 0)

        val retrieved = db.conversationDao().byId(id)
        assertNotNull("Should retrieve conversation", retrieved)
        assertEquals("Test Chat", retrieved?.title)
        assertEquals("test-model", retrieved?.modelId)
    }

    @Test
    fun setSummary_andRetrieve() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.conversationDao().insert(
            ConversationEntity(
                title = "Summary Test",
                modelId = "test-model",
                createdAt = now,
                updatedAt = now
            )
        )

        val summary = "User discussed Python programming. Decided to use Flask for web development."
        db.conversationDao().setSummary(id, summary)

        val retrieved = db.conversationDao().byId(id)
        assertEquals(summary, retrieved?.summary)
    }

    @Test
    fun insertMessage_andRetrieve() = runBlocking {
        val now = System.currentTimeMillis()
        val convId = db.conversationDao().insert(
            ConversationEntity(
                title = "Message Test",
                modelId = "test-model",
                createdAt = now,
                updatedAt = now
            )
        )

        db.messageDao().insert(
            MessageEntity(
                conversationId = convId,
                role = "user",
                content = "Hello, how are you?",
                createdAt = now,
                modelId = "test-model"
            )
        )

        db.messageDao().insert(
            MessageEntity(
                conversationId = convId,
                role = "assistant",
                content = "I'm doing well, thanks for asking!",
                createdAt = now + 1000,
                modelId = "test-model"
            )
        )

        val messages = db.messageDao().forConversation(convId)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
    }

    @Test
    fun deleteConversation_cascadesMessages() = runBlocking {
        val now = System.currentTimeMillis()
        val convId = db.conversationDao().insert(
            ConversationEntity(
                title = "Cascade Test",
                modelId = "test-model",
                createdAt = now,
                updatedAt = now
            )
        )

        db.messageDao().insert(
            MessageEntity(
                conversationId = convId,
                role = "user",
                content = "Test message",
                createdAt = now,
                modelId = "test-model"
            )
        )

        db.conversationDao().delete(convId)

        val messages = db.messageDao().forConversation(convId)
        assertTrue("Messages should be deleted with conversation", messages.isEmpty())
    }
}
