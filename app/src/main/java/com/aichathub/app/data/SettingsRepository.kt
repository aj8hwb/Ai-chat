package com.aichathub.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * App settings, persisted locally. Local-first: all settings stay on device.
 */
class SettingsRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Last-read values of settings that must be consulted SYNCHRONOUSLY from
     * places that cannot suspend (e.g. [android.app.Application.onTrimMemory]).
     * Kept in sync by [startCaching].
     */
    @Volatile
    var cachedAutoUnload: Boolean = true
        private set

    @Volatile
    var cachedBatteryConscious: Boolean = false
        private set

    /** Wi-Fi-only downloads, mirrored for synchronous checks inside the downloader. */
    @Volatile
    var cachedWifiOnlyDownloads: Boolean = false
        private set

    /** Starts mirroring persisted settings into the synchronous caches. */
    fun startCaching() {
        scope.launch {
            settings.collect { s ->
                cachedAutoUnload = s.autoUnload
                cachedBatteryConscious = s.batteryConscious
                cachedWifiOnlyDownloads = s.wifiOnlyDownloads
            }
        }
    }

    data class Settings(
        val defaultModelId: String? = null,
        val temperature: Float = 0.8f,
        val topK: Int = 40,
        val topP: Float = 0.95f,
        val maxTokens: Int = 512,
        val systemPrompt: String = "You are a helpful, concise local AI assistant.",
        val autoUnload: Boolean = true,
        val batteryConscious: Boolean = false,
        /** "system" | "dark" | "light" — how the app theme follows the device. */
        val themeMode: String = "dark",
        /** Use Material You dynamic colors on Android 12+ when dark/light apply. */
        val dynamicColor: Boolean = false,
        /** SAF tree URI of the user's model folder, if any. */
        val modelsFolderUri: String? = null,
        /** Mirror downloads to the shared Downloads folder for reinstall survival. */
        val storeInSharedDownloads: Boolean = true,
        /** Chat thinking depth: INSTANT / DEFAULT / HARD. */
        val thinkingMode: String = "DEFAULT",
        /** Only download model files over Wi-Fi (never mobile data). */
        val wifiOnlyDownloads: Boolean = false,
        /** Number of recent message turns included in the chat prompt (2..20). */
        val historyTurns: Int = 8,
        /** User dismissed the first-run "How it works" card on Home. */
        val helpDismissed: Boolean = false
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
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val modelsFolderUri = stringPreferencesKey("models_folder_uri")
        val storeInSharedDownloads = booleanPreferencesKey("store_in_shared_downloads")
        val thinkingMode = stringPreferencesKey("thinking_mode")
        val wifiOnlyDownloads = booleanPreferencesKey("wifi_only_downloads")
        val historyTurns = intPreferencesKey("history_turns")
        val helpDismissed = booleanPreferencesKey("help_dismissed")
        val measuredMemory = stringPreferencesKey("measured_memory")
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
            themeMode = p[Keys.themeMode] ?: "dark",
            dynamicColor = p[Keys.dynamicColor] ?: false,
            modelsFolderUri = p[Keys.modelsFolderUri],
            storeInSharedDownloads = p[Keys.storeInSharedDownloads] ?: true,
            thinkingMode = p[Keys.thinkingMode] ?: "DEFAULT",
            wifiOnlyDownloads = p[Keys.wifiOnlyDownloads] ?: false,
            historyTurns = (p[Keys.historyTurns] ?: 8).coerceIn(2, 20),
            helpDismissed = p[Keys.helpDismissed] ?: false
        )
    }

    /**
     * Measured runtime memory (App PSS) of each model, keyed by model id. This
     * is REAL memory measured on this device after loading the model, persisted
     * so recommendations can prefer measurements over catalog estimates.
     */
    val measuredMemory: Flow<Map<String, Long>> = context.dataStore.data.map { p ->
        com.aichathub.app.util.MeasuredMemory.decode(p[Keys.measuredMemory])
    }

    suspend fun measuredMemoryOnce(): Map<String, Long> = measuredMemory.first()

    suspend fun setMeasuredMemory(modelId: String, bytes: Long) {
        if (bytes <= 0) return
        context.dataStore.edit { p ->
            val updated = com.aichathub.app.util.MeasuredMemory.decode(p[Keys.measuredMemory]) +
                (modelId to bytes)
            p[Keys.measuredMemory] = com.aichathub.app.util.MeasuredMemory.encode(updated)
        }
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
    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.themeMode] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = v }
    suspend fun setModelsFolderUri(uri: String?) = context.dataStore.edit { p ->
        if (uri == null) p.remove(Keys.modelsFolderUri) else p[Keys.modelsFolderUri] = uri
    }
    suspend fun setStoreInSharedDownloads(v: Boolean) = context.dataStore.edit { it[Keys.storeInSharedDownloads] = v }
    suspend fun setThinkingMode(v: String) = context.dataStore.edit { it[Keys.thinkingMode] = v }
    suspend fun setWifiOnlyDownloads(v: Boolean) = context.dataStore.edit { it[Keys.wifiOnlyDownloads] = v }
    suspend fun setHistoryTurns(v: Int) = context.dataStore.edit { it[Keys.historyTurns] = v.coerceIn(2, 20) }
    suspend fun setHelpDismissed(v: Boolean) = context.dataStore.edit { it[Keys.helpDismissed] = v }
}