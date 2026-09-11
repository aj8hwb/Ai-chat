package com.aichathub.app.domain.model

import kotlinx.serialization.Serializable

/**
 * A model as defined in the built-in catalog.
 * Static, product-level metadata for discovery, compatibility analysis and download.
 */
@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val provider: String,
    val description: String,
    val parameters: String,
    val category: String,
    val format: ModelFormat,
    val quantization: String,
    val fileSizeBytes: Long,
    val estimatedMemoryBytes: Long,
    val contextLength: Int,
    val license: String,
    val licenseType: String,
    val officialRepositoryUrl: String,
    val downloadUrl: String,
    val fileName: String,
    val runtime: String,
    val sourceNote: String? = null,
    val capabilities: List<String> = emptyList(),
    val modelRank: Int = 0,
    val checksumSha256: String? = null,
    val purposeEmoji: String = "",
    val purposeTitle: String = "",
    val bestFor: String = "",
    val primaryPurpose: String = "",
    val strengths: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val parameterCount: Long = 0,
    val chatTemplate: ChatTemplate = ChatTemplate.GENERIC,
    /** Model version for update detection (semantic versioning or build number). */
    val version: Int = 1,
    /** Minimum app version required to use this model. */
    val minAppVersion: Int = 1,
    /** ISO 8601 timestamp of when this model version was published. */
    val publishedAt: String? = null,
    /** Changelog for this version (displayed to users). */
    val changelog: String? = null
)
