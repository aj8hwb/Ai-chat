package com.aichathub.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * App settings, persisted locally. Local-first: all settings stay on device.
 */
class SettingsRepository(private val context: Context) {

    data class Settings(
        val defaultModelId: String? = null,
        val temperature: Float = 0.8f,
        val topK: Int = 40,
        val topP: Float = 0.95f,
        val maxTokens: Int = 512,
        val systemPrompt: String = "You are a helpful, concise local AI assistant.",
        val autoUnload: Boolean = true,
        val batteryConscious: Boolean = false,
        val themeDark: Boolean = true,
        /** SAF tree URI of the user's model folder, if any. */
        val modelsFolderUri: String? = null,
        /** Mirror downloads to the shared Downloads folder for reinstall survival. */
        val storeInSharedDownloads: Boolean = true
    )

    private object Keys {
        val defaultModelId = stringPreferencesKey("default_model_id")
        val temperature = floatPreferencesKey("temperature")
        val topK = intPreferencesKey("top_k")
        val topP = floatPreferencesKey("top_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val autoUnload = booleanPreferencesKey("auto_unload")
        val batteryConscious = booleanPreferencesKey("battery_conscious")
        val themeDark = booleanPreferencesKey("theme_dark")
        val modelsFolderUri = stringPreferencesKey("models_folder_uri")
        val storeInSharedDownloads = booleanPreferencesKey("store_in_shared_downloads")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            defaultModelId = p[Keys.defaultModelId],
            temperature = p[Keys.temperature] ?: 0.8f,
            topK = p[Keys.topK] ?: 40,
            topP = p[Keys.topP] ?: 0.95f,
            maxTokens = p[Keys.maxTokens] ?: 512,
            systemPrompt = p[Keys.systemPrompt] ?: "You are a helpful, concise local AI assistant.",
            autoUnload = p[Keys.autoUnload] ?: true,
            batteryConscious = p[Keys.batteryConscious] ?: false,
            themeDark = p[Keys.themeDark] ?: true,
            modelsFolderUri = p[Keys.modelsFolderUri],
            storeInSharedDownloads = p[Keys.storeInSharedDownloads] ?: true
        )
    }

    suspend fun setDefaultModel(id: String?) {
        context.dataStore.edit { p ->
            if (id == null) p.remove(Keys.defaultModelId) else p[Keys.defaultModelId] = id
        }
    }

    suspend fun setTemperature(v: Float) = context.dataStore.edit { it[Keys.temperature] = v }
    suspend fun setTopK(v: Int) = context.dataStore.edit { it[Keys.topK] = v }
    suspend fun setTopP(v: Float) = context.dataStore.edit { it[Keys.topP] = v }
    suspend fun setMaxTokens(v: Int) = context.dataStore.edit { it[Keys.maxTokens] = v }
    suspend fun setSystemPrompt(v: String) = context.dataStore.edit { it[Keys.systemPrompt] = v }
    suspend fun setAutoUnload(v: Boolean) = context.dataStore.edit { it[Keys.autoUnload] = v }
    suspend fun setBatteryConscious(v: Boolean) = context.dataStore.edit { it[Keys.batteryConscious] = v }
    suspend fun setThemeDark(v: Boolean) = context.dataStore.edit { it[Keys.themeDark] = v }
    suspend fun setModelsFolderUri(uri: String?) = context.dataStore.edit { p ->
        if (uri == null) p.remove(Keys.modelsFolderUri) else p[Keys.modelsFolderUri] = uri
    }
    suspend fun setStoreInSharedDownloads(v: Boolean) = context.dataStore.edit { it[Keys.storeInSharedDownloads] = v }
}