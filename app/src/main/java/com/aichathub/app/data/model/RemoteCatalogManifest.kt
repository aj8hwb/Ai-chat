package com.aichathub.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Remote model catalog manifest, fetched from the server.
 * Represents the latest set of available models for download.
 *
 * Security: Each manifest is Ed25519-signed. The signature is verified
 * before any model entry is trusted. Individual model files are further
 * protected by SHA-256 checksums in the download pipeline.
 */
@Serializable
data class RemoteCatalogManifest(
    val version: Int,
    @SerialName("updated_at")
    val updatedAt: String,
    val models: List<RemoteModelEntry>,
    /** Ed25519 signature of the manifest JSON (Base64-encoded). */
    @SerialName("signature")
    val signature: String? = null,
    /** Key ID for Ed25519 key rotation (e.g. "prod-v1"). */
    @SerialName("keyId")
    val keyId: String? = null,
    /** Legacy field name — maps to [keyId] for backward compatibility. */
    @SerialName("key_hint")
    val keyHint: String? = null
)

/**
 * A single model entry in the remote catalog.
 */
@Serializable
data class RemoteModelEntry(
    val id: String,
    val name: String,
    val provider: String,
    val category: String,
    @SerialName("file_name")
    val fileName: String,
    @SerialName("file_size_bytes")
    val fileSizeBytes: Long,
    @SerialName("sha256")
    val sha256: String,
    @SerialName("download_url")
    val downloadUrl: String,
    @SerialName("mirror_urls")
    val mirrorUrls: List<String> = emptyList(),
    @SerialName("context_length")
    val contextLength: Int,
    @SerialName("license_name")
    val licenseName: String? = null,
    @SerialName("license_url")
    val licenseUrl: String? = null,
    @SerialName("model_card_url")
    val modelCardUrl: String? = null,
    @SerialName("min_ram")
    val minRam: Long,
    @SerialName("recommended_ram")
    val recommendedRam: Long,
    @SerialName("backend_hints")
    val backendHints: List<String> = emptyList(),
    val deprecated: Boolean = false,
    val changelog: String? = null,
    /** Model architecture (e.g., "llama", "mistral", "gemma", "phi"). */
    @SerialName("architecture")
    val architecture: String? = null,
    /** Quantization type (e.g., "Q4_K_M", "Q5_K_S", "Q8_0"). */
    @SerialName("quantization")
    val quantization: String? = null,
    /** Supported languages (ISO 639-1 codes). */
    @SerialName("languages")
    val languages: List<String> = emptyList(),
    /** Quality score 0-100 (subjective benchmark). */
    @SerialName("quality_score")
    val qualityScore: Int? = null,
    /** Speed score 0-100 (tokens/sec benchmark). */
    @SerialName("speed_score")
    val speedScore: Int? = null,
    /** Whether this model supports GPU acceleration. */
    @SerialName("gpu_support")
    val gpuSupport: Boolean = false,
    /** Minimum Android API level required. */
    @SerialName("min_api")
    val minApi: Int = 26,
    /** Model version for update detection. */
    @SerialName("version")
    val version: Int = 1,
    /** Minimum app version required to use this model. */
    @SerialName("min_app_version")
    val minAppVersion: Int = 1,
    /** ISO 8601 timestamp of when this model version was published. */
    @SerialName("published_at")
    val publishedAt: String? = null
)
