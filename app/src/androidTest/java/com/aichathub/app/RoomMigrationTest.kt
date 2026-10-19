package com.aichathub.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aichathub.app.data.local.AiDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Tests that verify every Room database migration preserves data correctly.
 *
 * Each migration test:
 *  1. Creates a database at the old version with test data
 *  2. Runs the migration
 *  3. Verifies the data survived AND new columns/features work
 *
 * This catches the scenario where a user upgrades from an old version and
 * loses their conversations or messages.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    private val TEST_DB_NAME = "migration-test-db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AiDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        // Create v1 database with test data
        var db = helper.createDatabase(TEST_DB_NAME, 1).apply {
            // v1 schema: installed_models, conversations, messages
            execSQL("""
                INSERT INTO conversations (title, modelId, createdAt, updatedAt)
                VALUES ('Old Chat', 'model-1', 1000, 2000)
            """.trimIndent())
            execSQL("""
                INSERT INTO messages (conversationId, role, content, createdAt, modelId)
                VALUES (1, 'user', 'Hello from v1', 1500, 'model-1')
            """.trimIndent())
        }
        db.close()

        // Migrate to v2 (adds index on messages.conversationId)
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 2, true, AiDatabase.MIGRATION_1_2
        )

        // Verify data survived
        val cursor = db.query("SELECT * FROM conversations WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Old Chat", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("model-1", cursor.getString(cursor.getColumnIndexOrThrow("modelId")))
        cursor.close()

        val msgCursor = db.query("SELECT * FROM messages WHERE conversationId = 1")
        assertTrue(msgCursor.moveToFirst())
        assertEquals("Hello from v1", msgCursor.getString(msgCursor.getColumnIndexOrThrow("content")))
        msgCursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3() {
        // Create v2 database
        var db = helper.createDatabase(TEST_DB_NAME, 2).apply {
            execSQL("""
                INSERT INTO conversations (title, modelId, createdAt, updatedAt)
                VALUES ('V2 Chat', 'model-2', 1000, 2000)
            """.trimIndent())
        }
        db.close()

        // Migrate to v3 (adds systemPrompt column)
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 3, true, AiDatabase.MIGRATION_2_3
        )

        // Verify data survived and new column exists with default null
        val cursor = db.query("SELECT * FROM conversations WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("V2 Chat", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        // systemPrompt should default to null for existing rows
        val sysPromptIdx = cursor.getColumnIndexOrThrow("systemPrompt")
        assertTrue(cursor.isNull(sysPromptIdx) || cursor.getString(sysPromptIdx) == "NULL")
        cursor.close()

        // Verify we can set the new column
        db.execSQL("UPDATE conversations SET systemPrompt = 'Custom prompt' WHERE id = 1")
        val cursor2 = db.query("SELECT systemPrompt FROM conversations WHERE id = 1")
        assertTrue(cursor2.moveToFirst())
        assertEquals("Custom prompt", cursor2.getString(0))
        cursor2.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4() {
        // Create v3 database
        var db = helper.createDatabase(TEST_DB_NAME, 3).apply {
            execSQL("""
                INSERT INTO conversations (title, modelId, createdAt, updatedAt, systemPrompt)
                VALUES ('V3 Chat', 'model-3', 1000, 2000, 'Be helpful')
            """.trimIndent())
            execSQL("""
                INSERT INTO messages (conversationId, role, content, createdAt, modelId)
                VALUES (1, 'user', 'Test message', 1500, 'model-3')
            """.trimIndent())
        }
        db.close()

        // Migrate to v4 (adds summary column)
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 4, true, AiDatabase.MIGRATION_3_4
        )

        // Verify all data survived
        val convCursor = db.query("SELECT * FROM conversations WHERE id = 1")
        assertTrue(convCursor.moveToFirst())
        assertEquals("V3 Chat", convCursor.getString(convCursor.getColumnIndexOrThrow("title")))
        assertEquals("Be helpful", convCursor.getString(convCursor.getColumnIndexOrThrow("systemPrompt")))
        // summary should default to null
        val summaryIdx = convCursor.getColumnIndexOrThrow("summary")
        assertTrue(convCursor.isNull(summaryIdx) || convCursor.getString(summaryIdx) == "NULL")
        convCursor.close()

        // Verify messages survived
        val msgCursor = db.query("SELECT * FROM messages WHERE conversationId = 1")
        assertTrue(msgCursor.moveToFirst())
        assertEquals("Test message", msgCursor.getString(msgCursor.getColumnIndexOrThrow("content")))
        msgCursor.close()

        // Verify we can set summary
        db.execSQL("UPDATE conversations SET summary = 'A summary' WHERE id = 1")
        val sumCursor = db.query("SELECT summary FROM conversations WHERE id = 1")
        assertTrue(sumCursor.moveToFirst())
        assertEquals("A summary", sumCursor.getString(0))
        sumCursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5() {
        // Create v4 database with full data
        var db = helper.createDatabase(TEST_DB_NAME, 4).apply {
            execSQL("""
                INSERT INTO conversations (title, modelId, createdAt, updatedAt, systemPrompt, summary)
                VALUES ('V4 Chat', 'model-4', 1000, 2000, 'Be concise', 'Discussed AI')
            """.trimIndent())
            execSQL("""
                INSERT INTO messages (conversationId, role, content, createdAt, modelId)
                VALUES (1, 'user', 'First message', 1500, 'model-4')
            """.trimIndent())
            execSQL("""
                INSERT INTO messages (conversationId, role, content, createdAt, modelId)
                VALUES (1, 'assistant', 'Response', 2000, 'model-4')
            """.trimIndent())
        }
        db.close()

        // Migrate to v5 (adds composite index on conversationId+createdAt)
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 5, true, AiDatabase.MIGRATION_4_5
        )

        // Verify conversation data survived
        val convCursor = db.query("SELECT * FROM conversations WHERE id = 1")
        assertTrue(convCursor.moveToFirst())
        assertEquals("V4 Chat", convCursor.getString(convCursor.getColumnIndexOrThrow("title")))
        assertEquals("model-4", convCursor.getString(convCursor.getColumnIndexOrThrow("modelId")))
        assertEquals("Be concise", convCursor.getString(convCursor.getColumnIndexOrThrow("systemPrompt")))
        assertEquals("Discussed AI", convCursor.getString(convCursor.getColumnIndexOrThrow("summary")))
        convCursor.close()

        // Verify messages survived
        val msgCursor = db.query("SELECT * FROM messages WHERE conversationId = 1 ORDER BY createdAt ASC")
        assertEquals(2, msgCursor.count)
        msgCursor.moveToFirst()
        assertEquals("First message", msgCursor.getString(msgCursor.getColumnIndexOrThrow("content")))
        msgCursor.moveToNext()
        assertEquals("Response", msgCursor.getString(msgCursor.getColumnIndexOrThrow("content")))
        msgCursor.close()

        // Verify the new composite index exists
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_messages_conversationId_createdAt'"
        )
        assertTrue("Composite index should exist after migration", indexCursor.moveToFirst())
        indexCursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun fullMigrationPath_1To5() {
        // Start from v1 and migrate all the way to v5
        var db = helper.createDatabase(TEST_DB_NAME, 1).apply {
            execSQL("""
                INSERT INTO conversations (title, modelId, createdAt, updatedAt)
                VALUES ('Full Path Chat', 'model-full', 1000, 2000)
            """.trimIndent())
            execSQL("""
                INSERT INTO messages (conversationId, role, content, createdAt, modelId)
                VALUES (1, 'user', 'Start of journey', 1500, 'model-full')
            """.trimIndent())
        }
        db.close()

        // Migrate 1→2
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 2, true, AiDatabase.MIGRATION_1_2
        )
        db.close()

        // Migrate 2→3
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 3, true, AiDatabase.MIGRATION_2_3
        )
        db.close()

        // Migrate 3→4
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 4, true, AiDatabase.MIGRATION_3_4
        )
        db.close()

        // Migrate 4→5
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 5, true, AiDatabase.MIGRATION_4_5
        )

        // Verify everything survived the full migration path
        val convCursor = db.query("SELECT * FROM conversations WHERE id = 1")
        assertTrue(convCursor.moveToFirst())
        assertEquals("Full Path Chat", convCursor.getString(convCursor.getColumnIndexOrThrow("title")))
        assertEquals("model-full", convCursor.getString(convCursor.getColumnIndexOrThrow("modelId")))
        convCursor.close()

        val msgCursor = db.query("SELECT * FROM messages WHERE conversationId = 1")
        assertTrue(msgCursor.moveToFirst())
        assertEquals("Start of journey", msgCursor.getString(msgCursor.getColumnIndexOrThrow("content")))
        msgCursor.close()

        db.close()
    }
}
