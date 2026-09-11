package com.aichathub.app.download

import android.util.Log
import com.aichathub.app.domain.model.CatalogModel
import java.io.File
import java.security.MessageDigest

/**
 * Handles checksum verification for downloaded model files.
 *
 * Responsibilities:
 *  - SHA-256 checksum computation
 *  - Streaming verification (honors cancel/pause)
 *  - GGUF header validation
 *
 * All verification is interruptible — the shouldAbort callback is
 * checked between chunks so large files can be cancelled promptly.
 */
class DownloadVerifier {

    companion object {
        private const val TAG = "DownloadVerifier"
        private const val CHUNK_SIZE = 64 * 1024 // 64 KB
    }

    /**
     * Verifies the SHA-256 checksum of a downloaded file.
     *
     * @param file the file to verify
     * @param expected expected SHA-256 hash (hex string)
     * @param shouldAbort callback to check if verification should be cancelled
     * @return true if checksum matches, false otherwise
     */
    fun verifyChecksum(
        file: File,
        expected: String?,
        shouldAbort: () -> Boolean = { false }
    ): Boolean {
        if (expected == null) return true
        if (!file.exists()) return false

        val actual = sha256Hex(file, shouldAbort)
        if (actual.isEmpty()) return false

        Log.i(TAG, "Checksum: expected=$expected actual=$actual")
        return actual.equals(expected, ignoreCase = true)
    }

    /**
     * Computes SHA-256 hash of a file in streaming fashion.
     * Returns empty string on abort or error.
     */
    fun sha256Hex(file: File, shouldAbort: () -> Boolean = { false }): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK_SIZE)
            while (true) {
                if (shouldAbort()) return ""
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { String.format("%02x", it) }
    } catch (e: Exception) {
        Log.w(TAG, "Checksum computation failed", e)
        ""
    }

    /**
     * Validates that a file has a valid GGUF header.
     * Delegates to SecurityValidator for the actual check.
     */
    fun isValidGgufHeader(file: File): Boolean {
        return com.aichathub.app.data.SecurityValidator.isValidGgufHeader(file)
    }

    /**
     * Performs full verification of a downloaded model file:
     *  1. File exists and is non-empty
     *  2. GGUF header is valid
     *  3. SHA-256 checksum matches
     *
     * @return VerificationResult with details
     */
    fun verifyDownload(
        file: File,
        model: CatalogModel,
        shouldAbort: () -> Boolean = { false }
    ): VerificationResult {
        if (!file.exists() || file.length() == 0L) {
            return VerificationResult.FAILED("File does not exist or is empty")
        }

        if (shouldAbort()) return VerificationResult.ABORTED

        if (!isValidGgufHeader(file)) {
            return VerificationResult.FAILED("Invalid GGUF header")
        }

        if (shouldAbort()) return VerificationResult.ABORTED

        if (!verifyChecksum(file, model.checksumSha256, shouldAbort)) {
            return VerificationResult.FAILED("Checksum mismatch")
        }

        return VerificationResult.SUCCESS
    }

    sealed class VerificationResult {
        data object SUCCESS : VerificationResult()
        data class FAILED(val reason: String) : VerificationResult()
        data object ABORTED : VerificationResult()
    }
}
