package com.aichathub.app.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class DownloadVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val verifier = DownloadVerifier()

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { String.format("%02x", it) }
    }

    // ── verifySha256 ────────────────────────────────────────────────

    @Test
    fun `verifySha256 returns true for correct hash`() = runTest {
        val content = "Hello, World!".toByteArray()
        val file = tempFolder.newFile("test.txt").apply { writeBytes(content) }
        val expectedHash = sha256Hex(content)

        assertTrue(verifier.verifySha256(file, expectedHash))
    }

    @Test
    fun `verifySha256 returns true for correct hash case-insensitive`() = runTest {
        val content = "Test content".toByteArray()
        val file = tempFolder.newFile("test2.txt").apply { writeBytes(content) }
        val hash = sha256Hex(content)

        assertTrue(verifier.verifySha256(file, hash.uppercase()))
        assertTrue(verifier.verifySha256(file, hash.lowercase()))
        assertTrue(verifier.verifySha256(file, hash))
    }

    @Test
    fun `verifySha256 returns false for incorrect hash`() = runTest {
        val content = "Hello, World!".toByteArray()
        val file = tempFolder.newFile("test3.txt").apply { writeBytes(content) }
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"

        assertFalse(verifier.verifySha256(file, wrongHash))
    }

    @Test
    fun `verifySha256 returns false for non-existent file`() = runTest {
        val file = tempFolder.root.resolve("nonexistent.txt")

        assertFalse(verifier.verifySha256(file, "abc123"))
    }

    @Test
    fun `verifySha256 returns false for empty expected hash`() = runTest {
        val content = "data".toByteArray()
        val file = tempFolder.newFile("test4.txt").apply { writeBytes(content) }

        assertFalse(verifier.verifySha256(file, ""))
    }

    // ── verifyFileIntegrity ──────────────────────────────────────────

    @Test
    fun `verifyFileIntegrity returns true for correct size`() {
        val content = ByteArray(1024) { it.toByte() }
        val file = tempFolder.newFile("size_test.bin").apply { writeBytes(content) }

        assertTrue(verifier.verifyFileIntegrity(file, 1024L))
    }

    @Test
    fun `verifyFileIntegrity returns false for wrong size`() {
        val content = ByteArray(1024) { it.toByte() }
        val file = tempFolder.newFile("size_test2.bin").apply { writeBytes(content) }

        assertFalse(verifier.verifyFileIntegrity(file, 2048L))
    }

    @Test
    fun `verifyFileIntegrity returns false for non-existent file`() {
        val file = tempFolder.root.resolve("does_not_exist.bin")

        assertFalse(verifier.verifyFileIntegrity(file, 100L))
    }

    @Test
    fun `verifyFileIntegrity returns true for empty file with zero size`() {
        val file = tempFolder.newFile("empty.bin")

        assertTrue(verifier.verifyFileIntegrity(file, 0L))
    }

    @Test
    fun `verifyFileIntegrity returns false for non-empty file with zero expected size`() {
        val file = tempFolder.newFile("not_empty.bin").apply { writeBytes("x".toByteArray()) }

        assertFalse(verifier.verifyFileIntegrity(file, 0L))
    }

    // ── computeSha256 ───────────────────────────────────────────────

    @Test
    fun `computeSha256 produces correct hash for known content`() = runTest {
        val content = "The quick brown fox jumps over the lazy dog".toByteArray()
        val file = tempFolder.newFile("known.txt").apply { writeBytes(content) }
        val expected = sha256Hex(content)

        val actual = verifier.computeSha256(file)

        assertEquals(expected, actual)
    }

    @Test
    fun `computeSha256 produces correct hash for empty file`() = runTest {
        val file = tempFolder.newFile("empty_hash.txt")

        val actual = verifier.computeSha256(file)

        assertEquals(sha256Hex(ByteArray(0)), actual)
    }

    @Test
    fun `computeSha256 produces different hashes for different content`() = runTest {
        val file1 = tempFolder.newFile("a.txt").apply { writeBytes("alpha".toByteArray()) }
        val file2 = tempFolder.newFile("b.txt").apply { writeBytes("beta".toByteArray()) }

        val hash1 = verifier.computeSha256(file1)
        val hash2 = verifier.computeSha256(file2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `computeSha256 handles large file correctly`() = runTest {
        // 1MB of random-ish data
        val content = ByteArray(1024 * 1024) { (it * 37).toByte() }
        val file = tempFolder.newFile("large.bin").apply { writeBytes(content) }
        val expected = sha256Hex(content)

        val actual = verifier.computeSha256(file)

        assertEquals(expected, actual)
    }

    @Test
    fun `computeSha256 returns empty string for non-existent file`() = runTest {
        val file = tempFolder.root.resolve("ghost.txt")

        val actual = verifier.computeSha256(file)

        assertEquals("", actual)
    }
}
