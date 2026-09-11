package com.aichathub.app.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles model file installation after download and verification.
 *
 * Responsibilities:
 *  - Move verified file to models directory
 *  - Update model repository
 *  - Mirror to shared Downloads folder (optional)
 *  - Handle installation failures
 *
 * Installation is idempotent — calling install twice for the same model
 * will not corrupt the existing file.
 */
class DownloadInstaller(
    private val context: Context,
    private val modelsDir: File,
    private val modelRepository: ModelRepository,
    private val settingsRepository: com.aichathub.app.data.SettingsRepository? = null
) {

    companion object {
        private const val TAG = "DownloadInstaller"
    }

    init {
        modelsDir.mkdirs()
    }

    /**
     * Installs a verified model file.
     *
     * @param model the model metadata
     * @param verifiedFile the verified downloaded file
     * @return true if installation succeeded
     */
    suspend fun install(
        model: CatalogModel,
        verifiedFile: File
    ): Boolean {
        val finalFile = File(modelsDir, model.fileName)
        val installingFile = File(modelsDir, "${model.fileName}.installing")

        withContext(Dispatchers.IO) {
            // Step 1: Move verified file to a temporary .installing location
            if (installingFile.exists()) installingFile.delete()
            val movedToTemp = verifiedFile.renameTo(installingFile)
            if (!movedToTemp) {
                verifiedFile.copyTo(installingFile, overwrite = true)
                verifiedFile.delete()
            }

            // Step 2: Atomic rename from .installing to final location
            // The old model remains usable until this point
            val renamed = installingFile.renameTo(finalFile)
            if (!renamed) {
                // Fallback: copy then delete temp
                installingFile.copyTo(finalFile, overwrite = true)
                installingFile.delete()
            }
        }

        return if (finalFile.exists() && finalFile.length() > 0) {
            modelRepository.markInstalled(model.id, finalFile, finalFile.length())
            modelRepository.setState(model.id, ModelLifecycleState.READY)
            Log.i(TAG, "MODEL_REGISTERED ${model.id} -> READY (${finalFile.absolutePath})")
            // Mirror to shared Downloads folder so the file survives app reinstall.
            // Best-effort: failures are logged and never break the install.
            copyToSharedDownloads(model, finalFile)
            true
        } else {
            Log.e(TAG, "MODEL_INSTALL_FAILED ${model.id} - file not created")
            // Clean up any leftover .installing file
            runCatching { installingFile.delete() }
            false
        }
    }

    /**
     * Mirrors the installed model into the shared Downloads folder
     * (Download/AiChatHub/Models) via MediaStore.
     *
     * The copy is visible in the system file manager and survives app
     * reinstall, so the file can be recovered with a rescan + re-import.
     * Best-effort: failures are logged and never break the install.
     */
    private suspend fun copyToSharedDownloads(model: CatalogModel, file: File) {
        val enabled = settingsRepository?.let { repo ->
            runCatching { repo.settings.first().storeInSharedDownloads }.getOrDefault(true)
        } ?: true
        if (!enabled) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, model.fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/AiChatHub/Models")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "MODEL_SHARED_COPY ${model.id} -> Download/AiChatHub/Models/${model.fileName}")
        } catch (e: Throwable) {
            Log.w(TAG, "Shared Downloads copy failed for ${model.id}", e)
        }
    }

    /**
     * Gets the models directory.
     */
    fun getModelsDir(): File = modelsDir
}
