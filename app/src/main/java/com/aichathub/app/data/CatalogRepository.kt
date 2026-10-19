package com.aichathub.app.data

import android.content.Context
import android.util.Log
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.data.model.RemoteCatalogManifest
import com.aichathub.app.domain.model.CatalogModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified catalog repository that combines local and remote model catalogs.
 *
 * This repository provides a single source of truth for all model catalogs,
 * merging the built-in local catalog with any available remote catalog.
 * Remote catalog models are prioritized when available, with the local
 * catalog serving as a fallback.
 */
class CatalogRepository(
    private val context: Context,
    private val remoteCatalogRepository: RemoteCatalogRepository
) {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    private val _allModels = MutableStateFlow<List<CatalogModel>>(emptyList())
    val allModels: StateFlow<List<CatalogModel>> = _allModels.asStateFlow()

    sealed class CatalogState {
        object Loading : CatalogState()
        data class Ready(val source: CatalogSource) : CatalogState()
        data class Error(val message: String) : CatalogState()
    }

    enum class CatalogSource {
        LOCAL,
        REMOTE,
        MERGED
    }

    init {
        scope.launch {
            loadCatalogs()
        }
    }

    /**
     * Loads and merges catalogs from both local and remote sources.
     */
    private suspend fun loadCatalogs() {
        try {
            _catalogState.value = CatalogState.Loading
            
            val localModels = LocalModelCatalog.models
            var remoteModels: List<CatalogModel> = emptyList()
            var source = CatalogSource.LOCAL

            try {
                val remoteManifest = remoteCatalogRepository.fetchManifest()
                remoteModels = convertRemoteManifestToCatalogModels(remoteManifest)
                if (remoteModels.isNotEmpty()) {
                    source = CatalogSource.REMOTE
                    Log.i(TAG, "Loaded ${remoteModels.size} models from remote catalog")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch remote catalog, using local only", e)
            }

            // Merge catalogs: remote models take precedence for same IDs
            val mergedModels = mergeCatalogs(localModels, remoteModels)
            _allModels.value = mergedModels
            
            if (remoteModels.isNotEmpty()) {
                source = CatalogSource.MERGED
            }
            
            _catalogState.value = CatalogState.Ready(source)
            Log.i(TAG, "Catalog loaded: ${mergedModels.size} total models (source: $source)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load catalogs", e)
            _catalogState.value = CatalogState.Error(e.message ?: "Failed to load catalog")
            _allModels.value = LocalModelCatalog.models
        }
    }

    /**
     * Merges local and remote catalogs, with remote models taking precedence.
     */
    private fun mergeCatalogs(
        localModels: List<CatalogModel>,
        remoteModels: List<CatalogModel>
    ): List<CatalogModel> {
        val remoteIds = remoteModels.map { it.id }.toSet()
        val localOnlyModels = localModels.filter { it.id !in remoteIds }
        
        return remoteModels + localOnlyModels
    }

    /**
     * Converts a remote catalog manifest to a list of CatalogModel objects.
     */
    private fun convertRemoteManifestToCatalogModels(
        manifest: RemoteCatalogManifest
    ): List<CatalogModel> {
        return manifest.models.map { remoteModel ->
            CatalogModel(
                id = remoteModel.id,
                name = remoteModel.name,
                provider = remoteModel.provider,
                description = remoteModel.description,
                parameters = remoteModel.parameters,
                category = remoteModel.category,
                format = remoteModel.format,
                quantization = remoteModel.quantization,
                fileSizeBytes = remoteModel.fileSizeBytes,
                estimatedMemoryBytes = remoteModel.estimatedMemoryBytes,
                contextLength = remoteModel.contextLength,
                license = remoteModel.license,
                licenseType = remoteModel.licenseType,
                officialRepositoryUrl = remoteModel.officialRepositoryUrl,
                downloadUrl = remoteModel.downloadUrl,
                fileName = remoteModel.fileName,
                runtime = remoteModel.runtime,
                sourceNote = remoteModel.sourceNote,
                capabilities = remoteModel.capabilities,
                modelRank = remoteModel.modelRank,
                checksumSha256 = remoteModel.checksumSha256,
                purposeEmoji = remoteModel.purposeEmoji,
                purposeTitle = remoteModel.purposeTitle,
                bestFor = remoteModel.bestFor,
                primaryPurpose = remoteModel.primaryPurpose,
                strengths = remoteModel.strengths,
                limitations = remoteModel.limitations,
                parameterCount = remoteModel.parameterCount,
                chatTemplate = remoteModel.chatTemplate
            )
        }
    }

    /**
     * Refreshes the catalog from remote sources.
     * This is a truly suspendable operation — callers wait for completion.
     */
    suspend fun refreshCatalog() {
        loadCatalogs()
    }

    /**
     * Gets a model by its ID from the merged catalog.
     */
    fun getModelById(id: String): CatalogModel? {
        return _allModels.value.firstOrNull { it.id == id }
    }

    /**
     * Gets all models from the merged catalog.
     */
    fun getAllModels(): List<CatalogModel> {
        return _allModels.value
    }

    /**
     * Gets models filtered by category.
     */
    fun getModelsByCategory(category: String): List<CatalogModel> {
        return _allModels.value.filter { it.category == category }
    }

    /**
     * Gets recommended models based on device compatibility.
     */
    fun getRecommendedModels(): List<CatalogModel> {
        return _allModels.value.filter { it.modelRank <= 5 }
    }
}
