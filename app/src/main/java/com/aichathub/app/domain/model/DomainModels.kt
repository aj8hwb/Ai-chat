package com.aichathub.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Compatibility level of a model for the user's device.
 * UI-facing, kept simple.
 */
enum class CompatibilityLevel(val label: String, val rank: Int) {
    EXCELLENT("Excellent", 5),
    RECOMMENDED("Recommended", 4),
    USABLE("Good", 3),
    HEAVY("Heavy", 2),
    NOT_RECOMMENDED("Not Recommended", 1);

    companion object {
        fun fromRank(rank: Int): CompatibilityLevel =
            entries.firstOrNull { it.rank == rank } ?: NOT_RECOMMENDED
    }
}

/** Lifecycle state of an installed model on the device. */
enum class ModelLifecycleState {
    NOT_INSTALLED,
    DOWNLOADING,
    DOWNLOADED,
    VERIFYING,
    INSTALLED,
    LOADING,
    READY,
    RUNNING,
    UNLOADING,
    ERROR
}

enum class ModelFormat(val extension: String) {
    TASK(".task"),
    LITERTLM(".litertlm"),
    TFLITE(".tflite"),
    GGUF(".gguf")
}

/**
 * A model as defined in the built-in catalog.
 * This is the static, product-level metadata used for discovery,
 * compatibility analysis and download.
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
    /** SHA-256 hex digest of the exact artifact to download (checksum verification). */
    val checksumSha256: String? = null,
    /** Emoji used for the model's primary purpose identity (e.g. "⚡", "💻", "💬", "🧪"). */
    val purposeEmoji: String = "",
    /** Short purpose label shown on cards (e.g. "Ultra Lightweight · Basic Chat"). */
    val purposeTitle: String = "",
    /** "Best for" summary, e.g. "Simple Q&A · Rewriting · Light text". */
    val bestFor: String = "",
    /** Longer human description of the model's primary purpose. */
    val primaryPurpose: String = "",
    /** Documented strengths (capabilities verified from the model card). */
    val strengths: List<String> = emptyList(),
    /** Documented limitations to display honestly. */
    val limitations: List<String> = emptyList(),
    /** Number of trainable parameters (e.g. 135_000_000). */
    val parameterCount: Long = 0
)

/** A snapshot of the device's current capabilities. */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val storageTotalBytes: Long,
    val storageAvailableBytes: Long,
    val cpuCores: Int,
    val abi: String,
    val androidVersion: Int,
    val isLowRamDevice: Boolean = totalRamBytes <= (4L * 1024 * 1024 * 1024)
) {
    val availableRamGb: Float get() = availableRamBytes / (1024f * 1024f * 1024f)
    val totalRamGb: Float get() = totalRamBytes / (1024f * 1024f * 1024f)
}

/** The safe amount of memory the AI runtime may use. */
data class AiMemoryBudget(
    val availableBytes: Long,
    val reservedBytes: Long,
    val runtimeOverheadBytes: Long,
    val safetyReserveBytes: Long,
    val modelMemoryBytes: Long
) {
    val usableBytes: Long
        get() = (availableBytes - runtimeOverheadBytes - safetyReserveBytes)
            .coerceAtLeast(0)

    val usableMb: Long get() = usableBytes / (1024 * 1024)
    val usableGb: Float get() = usableBytes / (1024f * 1024f * 1024f)

    val modelMemoryMb: Long get() = modelMemoryBytes / (1024 * 1024)
    val modelMemoryGb: Float get() = modelMemoryBytes / (1024f * 1024f * 1024f)

    val availableGb: Float get() = availableBytes / (1024f * 1024f * 1024f)
}

/** A recommendation result for a single model. */
data class Recommendation(
    val model: CatalogModel,
    val level: CompatibilityLevel,
    val score: Int,
    val reason: String,
    val quantizationNote: String? = null
)

/** A live performance snapshot from the inference runtime. */
data class PerformanceSnapshot(
    val tokensPerSecond: Float = 0f,
    val modelMemoryBytes: Long = 0,
    val contextTokensUsed: Int = 0,
    val contextTokensMax: Int = 0,
    val running: Boolean = false
)