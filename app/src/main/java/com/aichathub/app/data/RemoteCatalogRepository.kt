package com.aichathub.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aichathub.app.data.model.RemoteCatalogManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

private val Context.catalogDataStore by preferencesDataStore(name = "remote_catalog")

/**
 * Fetches and caches the remote model catalog manifest.
 *
 * Security features:
 *  - HTTPS-only transport (cleartext blocked by network_security_config)
 *  - Ed25519 manifest signature verification
 *  - SHA-256 per-model checksum verification (in download pipeline)
 *  - Trusted domain allowlist for download URLs
 *  - Path traversal blocking on file names
 *  - Response size sanity check
 *  - Strict URL and filename validation
 *
 * Uses ETag/If-None-Match for efficient caching and falls back to the local
 * cache on network errors.
 */
class RemoteCatalogRepository(private val context: Context) {

    companion object {
        private const val TAG = "RemoteCatalogRepo"
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/aichathub/catalog/main/manifest.json"
        private const val MAX_CACHE_AGE_HOURS = 24L
        private const val CACHE_FILE_NAME = "catalog_manifest.json"
        /** Maximum allowed manifest response size (1 MB). */
        private const val MAX_MANIFEST_SIZE_BYTES = 1_024_024L
        /** Maximum allowed file name length. */
        private const val MAX_FILE_NAME_LENGTH = 255

        /**
         * Trusted domains from which model downloads are allowed.
         * Add more as mirror infrastructure grows.
         */
        val TRUSTED_DOMAINS = setOf(
            "raw.githubusercontent.com",
            "github.com",
            "huggingface.co",
            "hf.co",
            "cdn-lfs.huggingface.co"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false) // We handle redirects ourselves for security
        .build()

    private object Keys {
        val manifestJson = stringPreferencesKey("manifest_json")
        val etag = stringPreferencesKey("etag")
        val lastFetchAt = longPreferencesKey("last_fetch_at")
        val lastVersion = longPreferencesKey("last_version")
    }

    private val cacheFile: File
        get() = File(context.cacheDir, CACHE_FILE_NAME)

    /**
     * Fetches the latest manifest from the remote server.
     *
     * Uses conditional requests (If-None-Match) to avoid re-downloading
     * unchanged manifests. On 304 Not Modified or network error, returns the
     * locally cached version instead.
     *
     * @return The latest [RemoteCatalogManifest].
     * @throws CatalogFetchException if both network and cache fail.
     */
    suspend fun fetchManifest(): RemoteCatalogManifest = withContext(Dispatchers.IO) {
        // Validate catalog URL is HTTPS
        validateCatalogUrl()

        val prefs = context.catalogDataStore.data.first()
        val cachedEtag = prefs[Keys.etag]
        val cachedJson = prefs[Keys.manifestJson]

        val requestBuilder = Request.Builder()
            .url(CATALOG_URL)
            .header("Accept", "application/json")
            .cacheControl(
                CacheControl.Builder()
                    .maxStale(MAX_CACHE_AGE_HOURS, TimeUnit.HOURS)
                    .build()
            )

        if (!cachedEtag.isNullOrBlank()) {
            requestBuilder.header("If-None-Match", cachedEtag)
        }

        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()

            when (response.code) {
                200 -> {
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        Log.w(TAG, "Empty response body, using cache")
                        return@withContext parseCachedManifest(cachedJson)
                    }

                    // Response size sanity check
                    if (body.length > MAX_MANIFEST_SIZE_BYTES) {
                        Log.w(TAG, "Manifest response too large (${body.length} bytes), using cache")
                        return@withContext parseCachedManifest(cachedJson)
                    }

                    val etagHeader = response.header("ETag")
                    val manifest = parseManifest(body)

                    // Verify Ed25519 signature if present
                    val sigValid = verifyManifestSignature(body, manifest)
                    if (!sigValid && CatalogSignatureVerifier.isSignatureRequired(manifest.version)) {
                        Log.w(TAG, "Manifest signature invalid, using cache")
                        return@withContext parseCachedManifest(cachedJson)
                    }

                    // Validate all model entries before trusting
                    val validatedManifest = validateManifest(manifest)

                    // Persist the new manifest
                    saveManifest(body, etagHeader, validatedManifest.version)

                    Log.i(TAG, "Fetched catalog v${validatedManifest.version} (${validatedManifest.models.size} models)")
                    validatedManifest
                }

                304 -> {
                    Log.d(TAG, "Catalog unchanged (304), using cache")
                    parseCachedManifest(cachedJson)
                }

                in 500..599 -> {
                    Log.w(TAG, "Server error ${response.code}, falling back to cache")
                    parseCachedManifest(cachedJson)
                }

                else -> {
                    Log.w(TAG, "Unexpected HTTP ${response.code}, falling back to cache")
                    parseCachedManifest(cachedJson)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching catalog, falling back to cache", e)
            parseCachedManifest(cachedJson)
        }
    }

    /**
     * Returns the locally cached manifest, or null if no cache exists.
     */
    suspend fun getLocalManifest(): RemoteCatalogManifest? = withContext(Dispatchers.IO) {
        val prefs = context.catalogDataStore.data.first()
        val cachedJson = prefs[Keys.manifestJson]
        if (cachedJson.isNullOrBlank()) null
        else parseCachedManifest(cachedJson)
    }

    /**
     * Checks whether a newer manifest version is available remotely.
     */
    suspend fun isUpdateAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = context.catalogDataStore.data.first()
            val localVersion = prefs[Keys.lastVersion] ?: 0L
            val lastFetch = prefs[Keys.lastFetchAt] ?: 0L

            // Don't check more than once per hour
            val oneHourAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
            if (lastFetch > oneHourAgo && localVersion > 0) {
                return@withContext false
            }

            val manifest = fetchManifest()
            manifest.version > localVersion
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            false
        }
    }

    /**
     * Clears the local cache.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        context.catalogDataStore.edit { it.clear() }
        cacheFile.delete()
        Log.i(TAG, "Catalog cache cleared")
    }

    // ------------------------------------------------------------------
    // Security validation
    // ------------------------------------------------------------------

    /**
     * Ensures the catalog URL uses HTTPS.
     */
    private fun validateCatalogUrl() {
        val uri = URI(CATALOG_URL)
        if (uri.scheme != "https") {
            throw CatalogFetchException("Catalog URL must use HTTPS, got: ${uri.scheme}")
        }
    }

    /**
     * Verifies the Ed25519 signature of the manifest.
     * The manifest JSON contains version, models, signature, and keyId at the
     * top level. The signature is verified over the canonicalized payload
     * (the JSON without the signature and keyId fields).
     */
    private fun verifyManifestSignature(rawJson: String, manifest: RemoteCatalogManifest): Boolean {
        val signature = manifest.signature ?: return false
        val keyId = manifest.keyId ?: manifest.keyHint ?: return false
        // Build an envelope for the verifier: { "payload": <catalog data>, "signature": "...", "keyId": "..." }
        // The payload is the raw JSON with signature and keyId removed.
        return try {
            val envelopeJson = buildEnvelopeJson(rawJson, signature, keyId)
            CatalogSignatureVerifier.verifyEnvelope(envelopeJson, context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build verification envelope", e)
            false
        }
    }

    /**
     * Builds an envelope JSON from the raw manifest by extracting the payload
     * (everything except signature and keyId) and wrapping it with the
     * signature and keyId fields.
     */
    private fun buildEnvelopeJson(rawJson: String, signature: String, keyId: String): String {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(rawJson) as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalArgumentException("Invalid manifest JSON")
        // Remove signature and keyId from the payload
        val payload = kotlinx.serialization.json.JsonObject(obj.filterKeys { it != "signature" && it != "keyId" })
        // Build the envelope
        val envelope = kotlinx.serialization.json.JsonObject(mapOf(
            "payload" to payload,
            "signature" to kotlinx.serialization.json.JsonPrimitive(signature),
            "keyId" to kotlinx.serialization.json.JsonPrimitive(keyId)
        ))
        return envelope.toString()
    }

    /**
     * Validates all model entries in the manifest.
     * Filters out invalid entries and logs warnings.
     */
    private fun validateManifest(manifest: RemoteCatalogManifest): RemoteCatalogManifest {
        val validModels = manifest.models.filter { entry ->
            val issues = mutableListOf<String>()

            // Validate file name (no path traversal)
            if (entry.fileName.contains("..") || entry.fileName.contains("/") || entry.fileName.contains("\\")) {
                issues.add("Path traversal in fileName: ${entry.fileName}")
            }
            if (entry.fileName.length > MAX_FILE_NAME_LENGTH) {
                issues.add("fileName too long: ${entry.fileName.length}")
            }
            if (!entry.fileName.endsWith(".gguf", ignoreCase = true)) {
                issues.add("Not a GGUF file: ${entry.fileName}")
            }

            // Validate download URL
            if (!isValidDownloadUrl(entry.downloadUrl)) {
                issues.add("Untrusted download URL domain: ${entry.downloadUrl}")
            }
            if (!entry.downloadUrl.startsWith("https://")) {
                issues.add("Download URL not HTTPS: ${entry.downloadUrl}")
            }

            // Validate mirror URLs
            entry.mirrorUrls.forEach { mirror ->
                if (!isValidDownloadUrl(mirror)) {
                    issues.add("Untrusted mirror URL domain: $mirror")
                }
                if (!mirror.startsWith("https://")) {
                    issues.add("Mirror URL not HTTPS: $mirror")
                }
            }

            // Validate SHA-256 format (64 hex chars)
            if (entry.sha256.length != 64 || !entry.sha256.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                issues.add("Invalid SHA-256 format: ${entry.sha256}")
            }

            // Validate file size sanity
            if (entry.fileSizeBytes <= 0) {
                issues.add("Invalid file size: ${entry.fileSizeBytes}")
            }
            if (entry.fileSizeBytes > 50L * 1024 * 1024 * 1024) {
                issues.add("File size suspiciously large: ${entry.fileSizeBytes}")
            }

            // Validate context length
            if (entry.contextLength <= 0) {
                issues.add("Invalid context length: ${entry.contextLength}")
            }

            if (issues.isNotEmpty()) {
                Log.w(TAG, "Model ${entry.id} validation issues: $issues")
            }

            issues.isEmpty() // Keep only valid entries
        }

        if (validModels.size < manifest.models.size) {
            Log.w(TAG, "Filtered ${manifest.models.size - validModels.size} invalid model entries")
        }

        return manifest.copy(models = validModels)
    }

    /**
     * Checks if a URL's host is in the trusted domain list.
     */
    private fun isValidDownloadUrl(urlString: String): Boolean {
        return try {
            val host = URL(urlString).host?.lowercase() ?: return false
            TRUSTED_DOMAINS.any { trusted ->
                host == trusted || host.endsWith(".$trusted")
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Cache management
    // ------------------------------------------------------------------

    private fun parseCachedManifest(cachedJson: String?): RemoteCatalogManifest {
        if (cachedJson.isNullOrBlank()) {
            throw CatalogFetchException("No cached catalog available")
        }
        val manifest = parseManifest(cachedJson)
        // Re-verify cached manifest signature for defense-in-depth
        // (especially important on compromised/rooted devices)
        val sigValid = verifyManifestSignature(cachedJson, manifest)
        if (!sigValid && CatalogSignatureVerifier.isSignatureRequired(manifest.version)) {
            Log.w(TAG, "Cached manifest signature verification failed")
            throw CatalogFetchException("Cached catalog signature invalid")
        }
        return manifest
    }

    private fun parseManifest(jsonString: String): RemoteCatalogManifest {
        return try {
            json.decodeFromString<RemoteCatalogManifest>(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse catalog JSON", e)
            throw CatalogFetchException("Invalid catalog format: ${e.message}", e)
        }
    }

    private suspend fun saveManifest(
        jsonString: String,
        etag: String?,
        version: Int
    ) {
        context.catalogDataStore.edit { prefs ->
            prefs[Keys.manifestJson] = jsonString
            if (etag != null) {
                prefs[Keys.etag] = etag
            }
            prefs[Keys.lastFetchAt] = System.currentTimeMillis()
            prefs[Keys.lastVersion] = version.toLong()
        }

        // Persist rollback protection state
        CatalogSignatureVerifier.persistHighestVersion(context, version)

        // Also write to disk cache for rapid startup reads
        try {
            cacheFile.writeText(jsonString)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write disk cache", e)
        }
    }
}

/**
 * Exception thrown when the remote catalog cannot be fetched or parsed.
 */
class CatalogFetchException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
