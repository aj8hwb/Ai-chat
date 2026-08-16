package com.aichathub.app.data

import android.content.Context
import com.aichathub.app.data.local.AiDatabase
import com.aichathub.app.data.local.InstalledModelEntity
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages installed models: filesystem layout + persisted state.
 * Single source of truth for model lifecycle state across all screens.
 */
class ModelRepository(
    context: Context,
    private val database: AiDatabase
) {
    private val modelsDir = File(context.filesDir, "models")

    data class ModelState(
        val modelId: String,
        val filePath: String?,
        val fileSizeBytes: Long,
        val state: ModelLifecycleState,
        val installedAt: Long
    )

    init {
        modelsDir.mkdirs()
    }

    val installedModels: Flow<List<ModelState>> =
        database.installedModelDao().observeAll().map { list ->
            list.map { it.toState() }
        }

    suspend fun installedModelsOnce(): List<ModelState> =
        database.installedModelDao().getAll().map { it.toState() }

    suspend fun stateFor(modelId: String): ModelState? =
        database.installedModelDao().byId(modelId)?.toState()

    suspend fun isInstalled(modelId: String): Boolean =
        database.installedModelDao().byId(modelId) != null

    suspend fun modelFile(model: CatalogModel): File =
        File(modelsDir, model.fileName)

    suspend fun setState(modelId: String, state: ModelLifecycleState) = withContext(Dispatchers.IO) {
        val existing = database.installedModelDao().byId(modelId)
        val entity = existing ?: InstalledModelEntity(
            modelId = modelId,
            installedAt = System.currentTimeMillis(),
            filePath = "",
            fileSizeBytes = 0,
            state = state.name
        )
        database.installedModelDao().upsert(
            entity.copy(state = state.name)
        )
    }

    /**
     * Marks a model as fully installed (verified) after a completed download or
     * a successful import. The model is immediately "ready" — selectable in the
     * Chat model selector and loadable on demand.
     */
    suspend fun markInstalled(modelId: String, file: File, sizeBytes: Long) = withContext(Dispatchers.IO) {
        val existing = database.installedModelDao().byId(modelId)
        val entity = existing ?: InstalledModelEntity(
            modelId = modelId,
            installedAt = System.currentTimeMillis(),
            filePath = "",
            fileSizeBytes = sizeBytes,
            state = ModelLifecycleState.READY.name
        )
        database.installedModelDao().upsert(
            entity.copy(
                filePath = file.absolutePath,
                fileSizeBytes = sizeBytes,
                state = ModelLifecycleState.READY.name,
                installedAt = if (existing == null) System.currentTimeMillis() else existing.installedAt
            )
        )
    }

    /**
     * Registers a model that was discovered/imported from an existing file on
     * the device (shared folder, SAF picker, or reinstall recovery).
     */
    suspend fun registerImported(modelId: String, filePath: String, sizeBytes: Long) = withContext(Dispatchers.IO) {
        val existing = database.installedModelDao().byId(modelId)
        database.installedModelDao().upsert(
            InstalledModelEntity(
                modelId = modelId,
                installedAt = existing?.installedAt ?: System.currentTimeMillis(),
                filePath = filePath,
                fileSizeBytes = sizeBytes,
                state = ModelLifecycleState.READY.name,
                lastUsedAt = existing?.lastUsedAt ?: 0L
            )
        )
    }

    /**
     * Startup reconciliation: the persisted registry must agree with the real
     * filesystem. Installed rows whose file vanished are removed; legacy
     * INSTALLED rows are promoted to READY; download-in-progress rows are kept.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val rows = database.installedModelDao().getAll()
        for (row in rows) {
            val st = runCatching { ModelLifecycleState.valueOf(row.state) }
                .getOrDefault(ModelLifecycleState.NOT_INSTALLED)
            val hasPath = row.filePath.isNotBlank()
            when (st) {
                ModelLifecycleState.INSTALLED, ModelLifecycleState.LOADING,
                ModelLifecycleState.RUNNING, ModelLifecycleState.UNLOADING -> {
                    if (!hasPath || !File(row.filePath).isFile) {
                        // File is gone → the model is no longer usable.
                        database.installedModelDao().delete(row.modelId)
                    } else {
                        // Legitimately installed → READY.
                        database.installedModelDao().upsert(row.copy(state = ModelLifecycleState.READY.name))
                    }
                }
                ModelLifecycleState.READY -> {
                    if (!hasPath || !File(row.filePath).isFile) {
                        database.installedModelDao().delete(row.modelId)
                    }
                }
                else -> {
                    // DOWNLOADING / VERIFYING / NOT_INSTALLED rows are left alone:
                    // download state is reconciled from the .part files on disk.
                }
            }
        }
    }

    suspend fun remove(modelId: String, deleteFile: Boolean = true) = withContext(Dispatchers.IO) {
        val existing = database.installedModelDao().byId(modelId)
        if (existing != null && deleteFile && existing.filePath.isNotBlank()) {
            runCatching { File(existing.filePath).delete() }
        }
        database.installedModelDao().delete(modelId)
    }

    private fun InstalledModelEntity.toState() = ModelState(
        modelId = modelId,
        filePath = filePath.ifBlank { null },
        fileSizeBytes = fileSizeBytes,
        state = runCatching { ModelLifecycleState.valueOf(state) }.getOrDefault(ModelLifecycleState.INSTALLED),
        installedAt = installedAt
    )
}