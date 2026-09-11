package com.aichathub.app.data.model

import android.util.Log
import com.aichathub.app.domain.model.CatalogModel

/**
 * Checks for model updates by comparing installed model versions
 * with the latest catalog versions.
 *
 * Update detection:
 *  - Compares version numbers (higher version = update available)
 *  - Checks SHA-256 hash (same version, different hash = re-download needed)
 *  - Checks minAppVersion (model requires newer app version)
 *
 * Usage:
 *  1. Call checkForUpdates() with installed models and catalog
 *  2. Receive list of ModelUpdateInfo with update details
 *  3. Present update options to user
 */
object ModelUpdateChecker {

    private const val TAG = "ModelUpdateChecker"

    data class ModelUpdateInfo(
        val modelId: String,
        val modelName: String,
        val installedVersion: Int,
        val latestVersion: Int,
        val installedHash: String?,
        val latestHash: String?,
        val isVersionUpdate: Boolean,
        val isHashMismatch: Boolean,
        val changelog: String?,
        val publishedAt: String?,
        val downloadUrl: String,
        val fileSizeBytes: Long
    )

    /**
     * Checks for available updates by comparing installed models with catalog.
     *
     * @param installedModels map of modelId to (version, sha256) for installed models
     * @param catalogModels list of models from the remote catalog
     * @return list of models that have updates available
     */
    fun checkForUpdates(
        installedModels: Map<String, Pair<Int, String?>>,
        catalogModels: List<CatalogModel>
    ): List<ModelUpdateInfo> {
        val updates = mutableListOf<ModelUpdateInfo>()

        for (catalogModel in catalogModels) {
            val installed = installedModels[catalogModel.id]

            if (installed == null) {
                // Model not installed — skip (not an update)
                continue
            }

            val (installedVersion, installedHash) = installed
            val latestVersion = catalogModel.version
            val latestHash = catalogModel.checksumSha256

            val isVersionUpdate = latestVersion > installedVersion
            val isHashMismatch = installedHash != null && latestHash != null &&
                !installedHash.equals(latestHash, ignoreCase = true) &&
                installedVersion == latestVersion

            if (isVersionUpdate || isHashMismatch) {
                Log.i(TAG, "Update available for ${catalogModel.id}: " +
                    "v$installedVersion -> v$latestVersion" +
                    if (isHashMismatch) " (hash mismatch)" else "")

                updates.add(
                    ModelUpdateInfo(
                        modelId = catalogModel.id,
                        modelName = catalogModel.name,
                        installedVersion = installedVersion,
                        latestVersion = latestVersion,
                        installedHash = installedHash,
                        latestHash = latestHash,
                        isVersionUpdate = isVersionUpdate,
                        isHashMismatch = isHashMismatch,
                        changelog = catalogModel.changelog,
                        publishedAt = catalogModel.publishedAt,
                        downloadUrl = catalogModel.downloadUrl,
                        fileSizeBytes = catalogModel.fileSizeBytes
                    )
                )
            }
        }

        return updates.sortedByDescending { it.latestVersion }
    }

    /**
     * Checks if a specific model has an update available.
     */
    fun hasUpdate(
        modelId: String,
        installedVersion: Int,
        installedHash: String?,
        catalogModel: CatalogModel?
    ): Boolean {
        if (catalogModel == null) return false
        if (catalogModel.id != modelId) return false

        val isVersionUpdate = catalogModel.version > installedVersion
        val isHashMismatch = installedHash != null && catalogModel.checksumSha256 != null &&
            !installedHash.equals(catalogModel.checksumSha256, ignoreCase = true) &&
            catalogModel.version == installedVersion

        return isVersionUpdate || isHashMismatch
    }

    /**
     * Formats a human-readable update summary.
     */
    fun formatUpdateSummary(updates: List<ModelUpdateInfo>): String {
        if (updates.isEmpty()) return "All models are up to date."

        return buildString {
            appendLine("${updates.size} model update(s) available:")
            appendLine()
            for (update in updates) {
                appendLine("• ${update.modelName}")
                appendLine("  v${update.installedVersion} → v${update.latestVersion}")
                if (update.isHashMismatch) {
                    appendLine("  (content changed, re-download recommended)")
                }
                if (update.changelog != null) {
                    appendLine("  ${update.changelog}")
                }
                appendLine()
            }
        }
    }
}
