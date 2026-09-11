package com.aichathub.app.data

import android.util.Log
import java.net.URL

/**
 * Security validation utilities for model downloads and catalog fetching.
 *
 * Protects against:
 *  - Malicious URLs (SSRF, redirect attacks)
 *  - Path traversal in filenames
 *  - Oversized responses (DoS)
 *  - Malformed GGUF files
 *  - Untrusted content types
 */
object SecurityValidator {

    private const val TAG = "SecurityValidator"
    private const val MAX_FILE_NAME_LENGTH = 255
    private const val MAX_URL_LENGTH = 2048

    /** Maximum response size for catalog/metadata/API responses (1 MB). */
    private const val MAX_CATALOG_RESPONSE_SIZE = 1L * 1024 * 1024

    /** Maximum response size for metadata/API responses (10 MB). */
    private const val MAX_METADATA_RESPONSE_SIZE = 10L * 1024 * 1024

    /** Absolute maximum for model downloads (10 GB). */
    private const val MAX_MODEL_DOWNLOAD_SIZE = 10L * 1024 * 1024 * 1024

    /**
     * Validates a download URL against security rules.
     * @return true if the URL is safe to use
     */
    fun isValidDownloadUrl(urlString: String): Boolean {
        return try {
            if (urlString.length > MAX_URL_LENGTH) {
                Log.w(TAG, "URL too long: ${urlString.length}")
                return false
            }

            val url = URL(urlString)

            // HTTPS only
            if (url.protocol != "https") {
                Log.w(TAG, "Non-HTTPS URL rejected: ${url.protocol}://${url.host}")
                return false
            }

            // No credentials in URL
            if (url.userInfo != null) {
                Log.w(TAG, "URL with credentials rejected")
                return false
            }

            // No fragment (could be used for cache poisoning)
            if (url.ref != null) {
                Log.w(TAG, "URL with fragment rejected")
                return false
            }

            // Valid host
            val host = url.host?.lowercase() ?: return false
            if (host.isBlank() || host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") {
                Log.w(TAG, "Internal URL rejected: $host")
                return false
            }

            // No IP address hosts (prevent SSRF)
            val ipPattern = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
            if (ipPattern.matches(host)) {
                Log.w(TAG, "IP address URL rejected: $host")
                return false
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "URL validation failed: ${e.message}")
            false
        }
    }

    /**
     * Validates a filename for safety.
     * Blocks path traversal, special characters, and overly long names.
     * @return true if the filename is safe
     */
    fun isValidFileName(fileName: String): Boolean {
        if (fileName.length > MAX_FILE_NAME_LENGTH) {
            Log.w(TAG, "Filename too long: ${fileName.length}")
            return false
        }

        // Block path traversal
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            Log.w(TAG, "Path traversal blocked in filename: $fileName")
            return false
        }

        // Block null bytes
        if (fileName.contains('\u0000')) {
            Log.w(TAG, "Null byte blocked in filename: $fileName")
            return false
        }

        // Block dangerous characters
        val dangerousChars = listOf('<', '>', ':', '"', '|', '?', '*', '\r', '\n')
        if (fileName.any { it in dangerousChars }) {
            Log.w(TAG, "Dangerous characters blocked in filename: $fileName")
            return false
        }

        // Must end with .gguf
        if (!fileName.endsWith(".gguf", ignoreCase = true)) {
            Log.w(TAG, "Non-GGUF file rejected: $fileName")
            return false
        }

        return true
    }

    /**
     * Validates response size for catalog/metadata/API responses.
     * @return true if the response size is acceptable
     */
    fun isCatalogResponseSizeAcceptable(contentLength: Long?): Boolean {
        if (contentLength != null) {
            if (contentLength > MAX_CATALOG_RESPONSE_SIZE) {
                Log.w(TAG, "Catalog response too large: $contentLength (max: $MAX_CATALOG_RESPONSE_SIZE)")
                return false
            }
        }
        return true
    }

    /**
     * Validates response size for metadata/API responses.
     * @return true if the response size is acceptable
     */
    fun isMetadataResponseSizeAcceptable(contentLength: Long?): Boolean {
        if (contentLength != null) {
            if (contentLength > MAX_METADATA_RESPONSE_SIZE) {
                Log.w(TAG, "Metadata response too large: $contentLength (max: $MAX_METADATA_RESPONSE_SIZE)")
                return false
            }
        }
        return true
    }

    /**
     * Validates response size for model downloads.
     * Uses the catalog-declared expected size for validation.
     *
     * @param contentLength the Content-Length from the server response
     * @param expectedSize the expected model size from the catalog (0 to skip size check)
     * @return true if the response size is acceptable
     */
    fun isModelDownloadSizeAcceptable(contentLength: Long?, expectedSize: Long): Boolean {
        if (contentLength != null) {
            // Absolute max check
            if (contentLength > MAX_MODEL_DOWNLOAD_SIZE) {
                Log.w(TAG, "Model download exceeds absolute max: $contentLength")
                return false
            }
            // If we have an expected size from catalog, validate against it
            if (expectedSize > 0) {
                // Allow 5% tolerance for server reporting differences
                val tolerance = expectedSize * 0.05
                if (contentLength > expectedSize + tolerance) {
                    Log.w(TAG, "Model download too large: $contentLength vs expected $expectedSize (tolerance: $tolerance)")
                    return false
                }
                if (contentLength < expectedSize - tolerance) {
                    Log.w(TAG, "Model download too small: $contentLength vs expected $expectedSize")
                    return false
                }
            }
        }
        return true
    }

    /**
     * Validates Content-Type for model downloads.
     * Accepts octet-stream (binary) and GGUF-specific types.
     * @return true if the content type is acceptable
     */
    fun isAcceptableContentType(contentType: String?): Boolean {
        if (contentType == null) return true // Some servers don't send Content-Type
        val normalized = contentType.lowercase().trim()
        return when {
            normalized.contains("application/octet-stream") -> true
            normalized.contains("application/gguf") -> true
            normalized.contains("application/x-gguf") -> true
            normalized.contains("application/binary") -> true
            normalized.contains("application/json") -> true // For catalog responses
            normalized.contains("text/plain") -> true // Some servers use this
            normalized.contains("*/*") -> true // Wildcard
            else -> {
                Log.w(TAG, "Unexpected content type: $contentType")
                false // Reject unknown types
            }
        }
    }

    /**
     * Validates a SHA-256 hash format.
     * @return true if the hash is valid hex and correct length
     */
    fun isValidSha256(hash: String): Boolean {
        if (hash.length != 64) return false
        return hash.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Validates a redirect URL against security rules.
     * Ensures redirects don't bypass trusted domain checks.
     *
     * @param originalUrl the original request URL
     * @param redirectUrl the URL from the Location header
     * @param trustedDomains set of trusted domain names
     * @return true if the redirect URL is safe to follow
     */
    fun isValidRedirect(
        originalUrl: String,
        redirectUrl: String,
        trustedDomains: Set<String>
    ): Boolean {
        return try {
            if (redirectUrl.length > MAX_URL_LENGTH) {
                Log.w(TAG, "Redirect URL too long: ${redirectUrl.length}")
                return false
            }

            val url = java.net.URL(redirectUrl)

            // HTTPS only
            if (url.protocol != "https") {
                Log.w(TAG, "Non-HTTPS redirect rejected: ${url.protocol}://${url.host}")
                return false
            }

            // Valid host
            val host = url.host?.lowercase() ?: return false
            if (host.isBlank() || host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") {
                Log.w(TAG, "Internal redirect URL rejected: $host")
                return false
            }

            // No IP address hosts
            val ipPattern = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
            if (ipPattern.matches(host)) {
                Log.w(TAG, "IP address redirect URL rejected: $host")
                return false
            }

            // Must be in trusted domains
            val isTrusted = trustedDomains.any { trusted ->
                host == trusted || host.endsWith(".$trusted")
            }
            if (!isTrusted) {
                Log.w(TAG, "Redirect to untrusted domain rejected: $host")
                return false
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Redirect URL validation failed: ${e.message}")
            false
        }
    }

    /**
     * Checks if a GGUF file header is valid.
     * Reads the first bytes and validates the GGUF structure:
     *  - Magic number (0x47, 0x47, 0x55, 0x46)
     *  - Version number (1-3 supported)
     *  - Tensor count (must be > 0)
     *  - Metadata count (must be >= 0)
     *
     * @return true if the file appears to be a valid GGUF
     */
    fun isValidGgufHeader(filePath: java.io.File): Boolean {
        return try {
            if (!filePath.exists() || filePath.length() < 16) return false

            java.io.RandomAccessFile(filePath, "r").use { raf ->
                // Read magic (4 bytes)
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (magic[0] != 0x47.toByte() || magic[1] != 0x47.toByte() ||
                    magic[2] != 0x55.toByte() || magic[3] != 0x46.toByte()) {
                    Log.w(TAG, "Invalid GGUF magic bytes")
                    return false
                }

                // Read version (4 bytes, little-endian)
                val versionBytes = ByteArray(4)
                raf.readFully(versionBytes)
                val version = (versionBytes[0].toInt() and 0xFF) or
                    ((versionBytes[1].toInt() and 0xFF) shl 8) or
                    ((versionBytes[2].toInt() and 0xFF) shl 16) or
                    ((versionBytes[3].toInt() and 0xFF) shl 24)

                if (version < 1 || version > 3) {
                    Log.w(TAG, "Unsupported GGUF version: $version")
                    return false
                }

                // Read tensor count (8 bytes, little-endian)
                val tensorCountBytes = ByteArray(8)
                raf.readFully(tensorCountBytes)
                val tensorCount = tensorCountBytes[0].toLong() and 0xFF or
                    ((tensorCountBytes[1].toLong() and 0xFF) shl 8) or
                    ((tensorCountBytes[2].toLong() and 0xFF) shl 16) or
                    ((tensorCountBytes[3].toLong() and 0xFF) shl 24) or
                    ((tensorCountBytes[4].toLong() and 0xFF) shl 32) or
                    ((tensorCountBytes[5].toLong() and 0xFF) shl 40) or
                    ((tensorCountBytes[6].toLong() and 0xFF) shl 48) or
                    ((tensorCountBytes[7].toLong() and 0xFF) shl 56)

                if (tensorCount <= 0) {
                    Log.w(TAG, "GGUF has no tensors: $tensorCount")
                    return false
                }

                // Read metadata count (8 bytes, little-endian)
                val metadataCountBytes = ByteArray(8)
                raf.readFully(metadataCountBytes)
                val metadataCount = metadataCountBytes[0].toLong() and 0xFF or
                    ((metadataCountBytes[1].toLong() and 0xFF) shl 8) or
                    ((metadataCountBytes[2].toLong() and 0xFF) shl 16) or
                    ((metadataCountBytes[3].toLong() and 0xFF) shl 24) or
                    ((metadataCountBytes[4].toLong() and 0xFF) shl 32) or
                    ((metadataCountBytes[5].toLong() and 0xFF) shl 40) or
                    ((metadataCountBytes[6].toLong() and 0xFF) shl 48) or
                    ((metadataCountBytes[7].toLong() and 0xFF) shl 56)

                if (metadataCount < 0) {
                    Log.w(TAG, "GGUF has invalid metadata count: $metadataCount")
                    return false
                }

                Log.d(TAG, "GGUF validation passed: version=$version, tensors=$tensorCount, metadata=$metadataCount")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "GGUF header check failed: ${e.message}")
            false
        }
    }
}
