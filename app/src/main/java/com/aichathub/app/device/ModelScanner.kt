package com.aichathub.app.device

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.model.LocalModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Discovers GGUF model files that already exist on the device and imports
 * them into the app. Sources:
 *  - app-private models dir (models/),
 *  - the shared Downloads folder (Download/AiChatHub/Models) via MediaStore —
 *    this survives an app reinstall,
 *  - any user-selected folder via Storage Access Framework (SAF).
 *
 * Only files that match a known catalog model exactly (by file name) are
 * importable; unmatched GGUF files are reported but skipped.
 */
class ModelScanner(
    private val context: Context,
    private val modelsDir: File,
    private val modelRepository: ModelRepository
) {

    private val tag = "ModelScanner"

    sealed interface Source {
        data class Local(val file: File) : Source
        data class SharedDownloads(val id: Long) : Source
        data class Tree(val uri: Uri) : Source
    }

    data class DiscoveredFile(
        val fileName: String,
        val source: Source,
        val sizeBytes: Long,
        val matchedModel: com.aichathub.app.domain.model.CatalogModel?
    )

    sealed interface ImportResult {
        data class Imported(val modelId: String, val filePath: String) : ImportResult
        data class AlreadyInstalled(val modelId: String) : ImportResult
        data class NoMatch(val fileName: String) : ImportResult
        data class Failed(val fileName: String, val message: String) : ImportResult
    }

    /** Scans the app-private models dir for GGUF files. */
    suspend fun scanLocalDir(): List<DiscoveredFile> = withContext(Dispatchers.IO) {
        if (!modelsDir.isDirectory) return@withContext emptyList()
        modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", true) }
            ?.mapNotNull { f ->
                discovered(f.name, f.length()) { _ -> Source.Local(f) }
            }
            ?.toList() ?: emptyList()
    }

    /** Scans the shared Downloads folder (Download/AiChatHub/Models). */
    suspend fun scanSharedDownloads(): List<DiscoveredFile> = withContext(Dispatchers.IO) {
        // MediaStore.Downloads only exists on API 29+; on older devices the
        // class reference throws NoClassDefFoundError, so skip cleanly.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return@withContext emptyList()
        }
        val found = mutableListOf<DiscoveredFile>()
        runCatching {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("Download/AiChatHub/Models/%")
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx)
                    val size = c.getLong(sizeIdx)
                    val id = c.getLong(idIdx)
                    discovered(name, size) { file -> Source.SharedDownloads(id) }?.let { found += it }
                }
            }
        }.onFailure { Log.w(tag, "MediaStore scan failed", it) }
        found
    }

    /** Scans a user-selected SAF folder (recursively, shallow). */
    suspend fun scanTree(treeUri: Uri): List<DiscoveredFile> = withContext(Dispatchers.IO) {
        val found = mutableListOf<DiscoveredFile>()
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) return@withContext found
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 5) return
            dir.listFiles().forEach { f ->
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else {
                    val name = f.name ?: return@forEach
                    discovered(name, f.length()) { file -> Source.Tree(f.uri) }?.let { found += it }
                }
            }
        }
        walk(root, 0)
        found
    }

    private fun discovered(
        fileName: String,
        sizeBytes: Long,
        makeSource: (String) -> Source
    ): DiscoveredFile? {
        if (!fileName.endsWith(".gguf", ignoreCase = true)) return null
        val model = LocalModelCatalog.models.firstOrNull { it.fileName == fileName }
        return DiscoveredFile(
            fileName = fileName,
            source = makeSource(fileName),
            sizeBytes = sizeBytes,
            matchedModel = model
        )
    }

    /**
     * Imports a discovered file into the app: copies it into the private
     * models dir (when the source is not already there) and registers it as
     * READY so it becomes selectable in Chat.
     */
    suspend fun import(file: DiscoveredFile): ImportResult = withContext(Dispatchers.IO) {
        val model = file.matchedModel
        if (model == null) {
            return@withContext ImportResult.NoMatch(file.fileName)
        }
        val existing = modelRepository.stateFor(model.id)
        if (existing?.state == com.aichathub.app.domain.model.ModelLifecycleState.READY &&
            existing.filePath != null && File(existing.filePath).isFile
        ) {
            return@withContext ImportResult.AlreadyInstalled(model.id)
        }

        val target = File(modelsDir, file.fileName)
        val copied = when (val src = file.source) {
            is Source.Local -> {
                // Already in the private dir; make sure it is not a partial file.
                if (src.file.absoluteFile == target.absoluteFile) true else copy(src.file, target)
            }
            is Source.SharedDownloads -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                    return@withContext ImportResult.Failed(file.fileName, "Shared Downloads import needs Android 10+.")
                }
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, src.id)
                copyUri(uri, target)
            }
            is Source.Tree -> copyUri(src.uri, target)
        }

        if (!copied || !target.isFile || target.length() == 0L) {
            runCatching { target.delete() }
            return@withContext ImportResult.Failed(file.fileName, "Could not copy the file.")
        }

        // Integrity check: an imported file must match the catalog checksum,
        // otherwise a corrupted or tampered model would be marked READY.
        val expected = model.checksumSha256
        if (expected != null && !expected.equals(sha256Hex(target), ignoreCase = true)) {
            runCatching { target.delete() }
            Log.w(tag, "MODEL_IMPORT_REJECTED ${model.id} — checksum mismatch")
            return@withContext ImportResult.Failed(
                file.fileName,
                "Checksum mismatch — this file is not the genuine ${model.name}. Download it from the Model Store instead."
            )
        }

        modelRepository.registerImported(model.id, target.absolutePath, target.length())
        Log.i(tag, "MODEL_IMPORTED ${model.id} -> ${target.absolutePath}")
        ImportResult.Imported(model.id, target.absolutePath)
    }

    private fun copy(source: File, target: File): Boolean = runCatching {
        if (target.exists()) target.delete()
        source.copyTo(target)
        true
    }.getOrDefault(false)

    private fun copyUri(uri: Uri, target: File): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            if (target.exists()) target.delete()
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: return false
        true
    }.getOrDefault(false)

    private fun sha256Hex(file: File): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { String.format("%02x", it) }
    } catch (e: Exception) {
        Log.w(tag, "Checksum computation failed", e)
        ""
    }
}
