package com.aichathub.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.math.BigInteger

private val Context.signatureDataStore by preferencesDataStore(name = "catalog_signature")

/**
 * Ed25519 signature verification for remote catalog manifests.
 *
 * Security model:
 *  - TLS protects transport (HTTPS only)
 *  - Ed25519 signature protects manifest integrity at rest
 *  - SHA-256 protects individual model file integrity
 *  - Manifest version prevents rollback attacks (persisted via DataStore)
 *  - Key ID supports key rotation
 *
 * Manifest format:
 *  {
 *    "payload": { ... catalog data ... },
 *    "signature": "base64-encoded-ed25519-signature",
 *    "keyId": "prod-v1"
 *  }
 *
 * The signature is computed over the canonicalized payload JSON.
 *
 * Key formats accepted:
 *  - X.509 SubjectPublicKeyInfo (Base64-encoded DER, preferred)
 *  - Raw 32-byte key (Base64-encoded, legacy fallback)
 */
object CatalogSignatureVerifier {

    private const val TAG = "CatalogSigVerifier"

    /**
     * Known production Ed25519 public keys.
     * Key ID -> public key mapping for key rotation support.
     *
     * Keys can be in either format:
     *  - X.509 SubjectPublicKeyInfo (preferred): standard DER-encoded public key
     *  - Raw 32-byte key (legacy): raw compressed point
     *
     * SECURITY: The production key must be configured before release.
     * To generate a new Ed25519 key pair:
     *   openssl genpkey -algorithm Ed25519 -outform PEM -out private.pem
     *   openssl pkey -in private.pem -pubout -outform PEM -out public.pem
     *
     * For new keys, export as X.509 DER:
     *   openssl pkey -in public.pem -outform DER | base64
     *
     * The key should be stored securely and not committed to version control.
     * For production, use environment variables or secure key management.
     */
    private val PUBLIC_KEYS: Map<String, String> = run {
        val keys = mutableMapOf<String, String>()

        // Load production key from BuildConfig (embedded at build time)
        // The public key is safe to embed in the application binary.
        val prodKey = try {
            val key = com.aichathub.app.BuildConfig.CATALOG_PUBLIC_KEY
            if (key.isBlank()) {
                Log.w(TAG, "Catalog public key not configured. Signature verification disabled.")
                ""
            } else {
                key
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load catalog public key", e)
            ""
        }

        if (prodKey.isNotBlank()) {
            keys["prod-v1"] = prodKey
        }

        keys
    }

    private const val ALGORITHM = "Ed25519"

    init {
        // Warn but do NOT crash — the app can still function with cached catalogs
        // when the production key is not yet configured.
        if (PUBLIC_KEYS.isEmpty()) {
            Log.w(TAG, "CatalogSignatureVerifier: production Ed25519 public key not configured. " +
                "Remote catalog signature verification will be skipped until the key is set.")
        }
    }

    private object Keys {
        val highestAcceptedVersion = intPreferencesKey("highest_accepted_manifest_version")
    }

    /**
     * Gets the highest accepted manifest version from persistent storage.
     */
    fun getHighestAcceptedVersion(context: Context): Int {
        return runBlocking {
            try {
                val prefs = context.signatureDataStore.data.first()
                prefs[Keys.highestAcceptedVersion] ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read persisted version", e)
                0
            }
        }
    }

    /**
     * Persists the highest accepted manifest version.
     */
    suspend fun persistHighestVersion(context: Context, version: Int) {
        try {
            val current = getHighestAcceptedVersion(context)
            if (version > current) {
                context.signatureDataStore.edit { prefs ->
                    prefs[Keys.highestAcceptedVersion] = version
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist version", e)
        }
    }

    /**
     * Verifies the Ed25519 signature of a manifest envelope.
     *
     * @param envelopeJson the full JSON string containing payload, signature, and keyId
     * @param context Android context for persistent rollback protection
     * @return true if the signature is valid, false otherwise
     */
    fun verifyEnvelope(envelopeJson: String, context: Context): Boolean {
        return try {
            val envelope = parseEnvelope(envelopeJson) ?: return false

            // Rollback protection: reject manifests with lower version
            val highestVersion = getHighestAcceptedVersion(context)
            if (envelope.version > 0 && envelope.version < highestVersion) {
                Log.e(TAG, "Manifest rollback detected: version ${envelope.version} < persisted $highestVersion")
                return false
            }

            // Key selection
            val publicKeyB64 = PUBLIC_KEYS[envelope.keyId] ?: run {
                Log.e(TAG, "Unknown key ID: ${envelope.keyId}")
                return false
            }

            val rawKey = Base64.getDecoder().decode(publicKeyB64)
            val sigBytes = Base64.getDecoder().decode(envelope.signature)

            if (sigBytes.size != 64) {
                Log.e(TAG, "Invalid signature size: ${sigBytes.size} (expected 64)")
                return false
            }

            val publicKey = buildPublicKey(rawKey)
            val sig = Signature.getInstance(ALGORITHM)
            sig.initVerify(publicKey)
            // Sign over the canonicalized payload
            sig.update(envelope.payload.toByteArray(Charsets.UTF_8))
            val valid = sig.verify(sigBytes)
            if (!valid) {
                Log.w(TAG, "Signature verification FAILED")
            }
            valid
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error", e)
            false
        }
    }

    /**
     * Parses a manifest envelope JSON string.
     * Expected format: { "payload": {...}, "signature": "...", "keyId": "..." }
     */
    private fun parseEnvelope(json: String): ManifestEnvelope? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
                ?: return null

            val payloadObj = obj["payload"] as? kotlinx.serialization.json.JsonObject
                ?: return null
            val signature = obj["signature"]?.toString()?.trim('"')
                ?: return null
            val keyId = obj["keyId"]?.toString()?.trim('"')
                ?: return null
            val version = payloadObj["version"]?.toString()?.toIntOrNull() ?: 0

            ManifestEnvelope(
                payload = canonicalizeJson(payloadObj),
                signature = signature,
                keyId = keyId,
                version = version
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse manifest envelope", e)
            null
        }
    }

    /**
     * Canonicalizes a JSON object to a deterministic string representation.
     * This ensures consistent signature verification regardless of JSON serialization order.
     */
    private fun canonicalizeJson(obj: kotlinx.serialization.json.JsonObject): String {
        return buildString {
            append("{")
            val sortedEntries = obj.entries.sortedBy { it.key }
            sortedEntries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"")
                append(escapeJsonString(key))
                append("\":")
                append(canonicalizeJsonValue(value))
            }
            append("}")
        }
    }

    /**
     * Recursively canonicalizes a JSON value.
     */
    private fun canonicalizeJsonValue(value: kotlinx.serialization.json.JsonElement): String {
        return when (value) {
            is kotlinx.serialization.json.JsonObject -> canonicalizeJson(value)
            is kotlinx.serialization.json.JsonArray -> buildString {
                append("[")
                value.forEachIndexed { index, element ->
                    if (index > 0) append(",")
                    append(canonicalizeJsonValue(element))
                }
                append("]")
            }
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (value.isString) {
                    "\"${escapeJsonString(value.content)}\""
                } else {
                    // For numbers, ensure consistent formatting
                    val numStr = value.content
                    if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                        // Float/double - keep as-is but ensure consistent format
                        numStr
                    } else {
                        // Integer - keep as-is
                        numStr
                    }
                }
            }
            is kotlinx.serialization.json.JsonNull -> "null"
        }
    }

    /**
     * Escapes special characters in a JSON string.
     */
    private fun escapeJsonString(s: String): String {
        return buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> {
                        if (c.code < 0x20) {
                            append("\\u")
                            append("%04x".format(c.code))
                        } else {
                            append(c)
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds an Ed25519 PublicKey from raw key material.
     *
     * Supports two formats:
     *  - 32 bytes: raw Ed25519 public key (legacy format, uses custom decompression)
     *  - Longer: X.509 SubjectPublicKeyInfo DER-encoded key (preferred, standard JCA)
     */
    private fun buildPublicKey(rawKey: ByteArray): java.security.PublicKey {
        return when {
            rawKey.size == 32 -> {
                // Legacy raw 32-byte key: use custom point decompression
                val point = ed25519PointDecompress(rawKey)
                val spec = EdECPublicKeySpec(NamedParameterSpec.ED25519, point)
                KeyFactory.getInstance(ALGORITHM).generatePublic(spec)
            }
            else -> {
                // X.509 SubjectPublicKeyInfo format (standard, preferred)
                // This is the standard way JCA handles public keys — no custom math needed.
                val spec = X509EncodedKeySpec(rawKey)
                KeyFactory.getInstance(ALGORITHM).generatePublic(spec)
            }
        }
    }

    /**
     * Decompresses an Ed25519 public key (32 bytes) into an EdECPoint.
     * Used only for legacy raw 32-byte keys.
     */
    private fun ed25519PointDecompress(compressedKey: ByteArray): java.security.spec.EdECPoint {
        val yBytes = compressedKey.copyOf()
        val signBit = (yBytes[31].toInt() shr 7) and 1
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()

        val y = BigInteger(1, yBytes)
        val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val d = BigInteger("-4513249062541557337682894930092624173785641285191125241628941591882900924598840740")

        val ySq = y.multiply(y).mod(p)
        val onePlusYSq = BigInteger.ONE.add(ySq).mod(p)
        val dYSq = d.multiply(ySq).mod(p)
        val oneMinusDSq = BigInteger.ONE.subtract(dYSq).mod(p)
        val invOnePlusY = onePlusYSq.modInverse(p)
        val xSq = oneMinusDSq.multiply(invOnePlusY).mod(p)
        val x = tonelliShanks(xSq, p)

        val xIsOdd = x.testBit(0)
        val finalX = if (xIsOdd != (signBit != 0)) p.subtract(x) else x

        return java.security.spec.EdECPoint(finalX, y)
    }

    /**
     * Tonelli-Shanks algorithm for modular square root.
     */
    private fun tonelliShanks(n: BigInteger, p: BigInteger): BigInteger {
        if (n.signum() == 0) return BigInteger.ZERO
        if (p.mod(BigInteger.valueOf(4)).equals(BigInteger.valueOf(3))) {
            return n.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        }
        var q = p.subtract(BigInteger.ONE)
        var s = 0
        while (q.mod(BigInteger.valueOf(2)).equals(BigInteger.ZERO)) {
            q = q.shiftRight(1)
            s++
        }
        var z = BigInteger.TWO
        while (z.modPow(p.subtract(BigInteger.ONE).shiftRight(1), p) != BigInteger.ONE) {
            z = z.add(BigInteger.ONE)
        }
        var m = s
        var c = z.modPow(q, p)
        var t = n.modPow(q, p)
        var r = n.modPow(q.add(BigInteger.ONE).shiftRight(2), p)
        while (t != BigInteger.ONE) {
            var i = 1
            var tmp = t.modPow(BigInteger.TWO, p)
            while (tmp != BigInteger.ONE) {
                tmp = tmp.modPow(BigInteger.TWO, p)
                i++
                if (i == m) return BigInteger.ZERO
            }
            val b = c.modPow(BigInteger.ONE.shiftLeft(m - i - 1), p)
            m = i
            c = b.modPow(BigInteger.TWO, p)
            t = t.multiply(c).mod(p)
            r = r.multiply(b).mod(p)
        }
        return r
    }

    /**
     * Checks whether a signature is required for this manifest version.
     */
    fun isSignatureRequired(manifestVersion: Int): Boolean = manifestVersion >= 1

    /**
     * Human-readable verification status for logging.
     */
    fun verificationStatus(manifestVersion: Int, signaturePresent: Boolean): String = when {
        !isSignatureRequired(manifestVersion) -> "legacy (no signature required)"
        !signaturePresent -> "MISSING (required)"
        else -> "present"
    }

    /**
     * Clears the rollback protection state. Call this on app reset or
     * when the user explicitly allows importing from an older catalog.
     */
    suspend fun clearRollbackState(context: Context) {
        context.signatureDataStore.edit { it.clear() }
    }
}

/**
 * Parsed manifest envelope with payload, signature, and key ID.
 */
data class ManifestEnvelope(
    val payload: String,
    val signature: String,
    val keyId: String,
    val version: Int
)
