package com.aichathub.app.privacy

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aichathub.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Centralized privacy-management singleton.
 *
 * All operations are **local-first** — nothing leaves the device.
 * The object delegates persistence to [SettingsRepository] and
 * provides convenience methods for data hygiene tasks.
 *
 * Incognito mode enforcement:
 *  When incognito is ON, [isIncognitoMode] returns true and callers
 *  (e.g., ChatCoordinator) must skip Room persistence, auto-title
 *  generation, export, and backup.
 */
object PrivacyCenter {

    private const val TAG = "PrivacyCenter"
    private const val CLIPBOARD_CLEAR_DELAY_MS = 60_000L // 60 s

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var repository: SettingsRepository? = null

    @Volatile
    private var clipboardRunnable: Runnable? = null

    // ------------------------------------------------------------------
    // Reactive state (replaces runBlocking getters)
    // ------------------------------------------------------------------

    private val _appLockEnabled = MutableStateFlow(false)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _incognitoMode = MutableStateFlow(false)
    val incognitoMode: StateFlow<Boolean> = _incognitoMode.asStateFlow()

    private val _clipboardAutoClear = MutableStateFlow(false)
    val clipboardAutoClear: StateFlow<Boolean> = _clipboardAutoClear.asStateFlow()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Must be called once from [android.app.Application.onCreate] (or an
     * equivalent DI entry-point) so that [PrivacyCenter] can reach the
     * [SettingsRepository]. Safe to call multiple times; only the first
     * call takes effect.
     */
    fun init(settingsRepository: SettingsRepository) {
        if (repository != null) return
        repository = settingsRepository
        // Start observing settings changes into reactive state
        scope.launch {
            settingsRepository.settings.collect { s ->
                _appLockEnabled.value = s.appLockEnabled
                _incognitoMode.value = s.incognitoMode
                _clipboardAutoClear.value = s.clipboardAutoClear
            }
        }
        Log.d(TAG, "PrivacyCenter initialised")
    }

    // ------------------------------------------------------------------
    // App Lock
    // ------------------------------------------------------------------

    /**
     * Synchronous check — prefer collecting [appLockEnabled] StateFlow instead.
     */
    fun isAppLockEnabled(): Boolean = _appLockEnabled.value

    suspend fun setAppLockEnabled(enabled: Boolean) {
        repository?.setAppLockEnabled(enabled)
        if (!enabled) BiometricAuthManager.clearAuthentication()
        Log.d(TAG, "App lock ${if (enabled) "enabled" else "disabled"}")
    }

    // ------------------------------------------------------------------
    // Incognito Mode
    // ------------------------------------------------------------------

    /**
     * Synchronous check — prefer collecting [incognitoMode] StateFlow instead.
     * When true, callers MUST NOT persist conversation data to Room.
     */
    fun isIncognitoMode(): Boolean = _incognitoMode.value

    suspend fun setIncognitoMode(enabled: Boolean) {
        repository?.setIncognitoMode(enabled)
        Log.d(TAG, "Incognito mode ${if (enabled) "enabled" else "disabled"}")
    }

    // ------------------------------------------------------------------
    // Clipboard auto-clear
    // ------------------------------------------------------------------

    fun scheduleClipboardClear(context: Context) {
        cancelClipboardClear()
        clipboardRunnable = Runnable {
            clearClipboard(context)
            clipboardRunnable = null
        }
        handler.postDelayed(clipboardRunnable!!, CLIPBOARD_CLEAR_DELAY_MS)
        Log.d(TAG, "Clipboard auto-clear scheduled in ${CLIPBOARD_CLEAR_DELAY_MS}ms")
    }

    fun cancelClipboardClear() {
        clipboardRunnable?.let { handler.removeCallbacks(it) }
        clipboardRunnable = null
    }

    private fun clearClipboard(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            Log.d(TAG, "Clipboard cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear clipboard", e)
        }
    }

    // ------------------------------------------------------------------
    // Data purge
    // ------------------------------------------------------------------

    suspend fun clearAllLocalData(context: Context) {
        clearCrashLogs(context)
        clearTempFiles(context)
        clearDownloadMetadata(context)
        Log.d(TAG, "All local privacy-relevant data cleared")
    }

    /**
     * Clears download metadata and partial downloads.
     */
    private suspend fun clearDownloadMetadata(context: Context) {
        try {
            val downloadsDir = java.io.File(context.filesDir, "downloads")
            if (downloadsDir.exists()) {
                val deleted = downloadsDir.listFiles()?.sumOf { file ->
                    if (file.delete()) 1L else 0L
                } ?: 0L
                Log.d(TAG, "Download metadata cleared ($deleted files)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear download metadata", e)
        }
    }

    /**
     * Clears crash logs from BOTH filesDir and cacheDir.
     * Uses CrashLogRepository when available, falls back to file scan.
     */
    fun clearCrashLogs(context: Context) {
        try {
            // Clear via CrashLogRepository if initialized
            CrashLogRepository.clear(context)
            Log.d(TAG, "Crash logs cleared via CrashLogRepository")
        } catch (e: Exception) {
            Log.w(TAG, "CrashLogRepository clear failed, falling back to file scan", e)
            // Fallback: scan filesDir
            val logFile = File(context.filesDir, "crash_log.txt")
            if (logFile.exists()) logFile.delete()
            // Scan cacheDir
            val cacheDir = context.cacheDir
            cacheDir.listFiles { file ->
                file.name.startsWith("crash_") || file.name.endsWith(".log")
            }?.forEach { it.delete() }
            Log.d(TAG, "Crash logs cleared via file scan fallback")
        }
    }

    fun clearTempFiles(context: Context) {
        try {
            val cacheDir = context.cacheDir
            val deleted = cacheDir.listFiles()?.sumOf { file ->
                if (file.delete()) 1L else 0L
            } ?: 0L
            Log.d(TAG, "Temp files cleared ($deleted files)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear temp files", e)
        }
    }

    // ------------------------------------------------------------------
    // Aggregate settings snapshot
    // ------------------------------------------------------------------

    suspend fun getPrivacySettings(): PrivacySettings {
        val s = repository?.settings?.first()
        return PrivacySettings(
            appLockEnabled = s?.appLockEnabled ?: false,
            incognitoMode = s?.incognitoMode ?: false,
            clipboardAutoClear = s?.clipboardAutoClear ?: false,
            crashLogPurge = s?.crashLogPurge ?: false
        )
    }

    // ------------------------------------------------------------------
    // Model file availability (not cryptographic integrity)
    // ------------------------------------------------------------------

    /**
     * Checks model file availability by verifying file existence and size.
     * For full SHA-256 verification, use DownloadVerifier directly.
     */
    suspend fun verifyModelAvailability(context: Context): Boolean {
        return try {
            val modelsDir = java.io.File(context.filesDir, "models")
            if (!modelsDir.exists()) return true
            modelsDir.listFiles()?.all { file ->
                file.exists() && file.length() > 0
            } ?: true
        } catch (e: Exception) {
            Log.w(TAG, "Model availability check failed", e)
            false
        }
    }

    @Deprecated("Renamed to verifyModelAvailability", replaceWith = ReplaceWith("verifyModelAvailability(context)"))
    suspend fun verifyModelIntegrity(context: Context): Boolean = verifyModelAvailability(context)

    // ------------------------------------------------------------------
    // Privacy dashboard data
    // ------------------------------------------------------------------

    /**
     * Returns a comprehensive privacy status snapshot for the dashboard.
     */
    fun getPrivacyDashboard(): PrivacyDashboard {
        return PrivacyDashboard(
            aiProcessing = "Local device only",
            cloudAi = "Disabled",
            networkRequests = "Model catalog sync + Model downloads",
            conversationBackup = if (_incognitoMode.value) "Disabled (incognito)" else "Manual encrypted export only",
            crashLogs = "Local only",
            telemetry = "Off",
            incognito = if (_incognitoMode.value) "On" else "Off",
            appLock = if (_appLockEnabled.value) "On" else "Off",
            clipboardAccess = if (_clipboardAutoClear.value) "Auto-clear (60s)" else "On"
        )
    }
}

data class PrivacySettings(
    val appLockEnabled: Boolean = false,
    val incognitoMode: Boolean = false,
    val clipboardAutoClear: Boolean = false,
    val crashLogPurge: Boolean = false
)

data class PrivacyDashboard(
    val aiProcessing: String = "Local device only",
    val cloudAi: String = "Disabled",
    val networkRequests: String = "Model catalog sync + Model downloads",
    val conversationBackup: String = "Manual encrypted export only",
    val crashLogs: String = "Local only",
    val telemetry: String = "Off",
    val incognito: String = "Off",
    val appLock: String = "Off",
    val clipboardAccess: String = "On"
)
