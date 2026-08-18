package com.aichathub.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.aichathub.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiChatHubApplication : Application() {

    companion object {
        private const val CRASH_LOG_MAX_LINES = 4000
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installCrashLogger()
    }

    /**
     * Records any uncaught Java exception (e.g. an OutOfMemoryError) to logcat
     * and to a file inside filesDir so the crash can be diagnosed afterwards
     * (pull the file via the debug file explorer or adb). Native SIGSEGV
     * crashes from llama.cpp are NOT reported here — those show up in logcat
     * as `Fatal signal` / `DEBUG`.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = Log.getStackTraceString(throwable)
                Log.e("AiChatHubApp", "CRASH thread=${thread.name} cause=${throwable.javaClass.simpleName}", throwable)
                val mem = runCatching {
                    val info = android.os.Debug.MemoryInfo()
                    android.os.Debug.getMemoryInfo(info)
                    "PSS=${info.totalPss / 1024}MB nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}MB javaHeap=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)}MB"
                }.getOrElse { "memory stats unavailable" }
                Log.e("AiChatHubApp", "CRASH memory: $mem")
                val logFile = File(filesDir, "crash_log.txt")
                val entry = StringBuilder()
                    .append("=== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    .append(" ===\n")
                    .append("memory: ").append(mem).append("\n")
                    .append(thread.name).append(" : ").append(throwable.toString()).append("\n")
                    .append(stack).append("\n")
                val combined = (if (logFile.exists()) logFile.readText() + "\n" else "") + entry
                runCatching {
                    // Bound the file to the last 20 crashes so it can never grow
                    // without limit on a device that crashes repeatedly.
                    val lines = combined.split("\n")
                    val capped = if (lines.size > CRASH_LOG_MAX_LINES) {
                        lines.takeLast(CRASH_LOG_MAX_LINES).joinToString("\n")
                    } else combined
                    logFile.writeText(capped)
                }
            } catch (ignored: Throwable) {
            } finally {
                // Always let the system terminate the process as normal.
                previous?.uncaughtException(thread, throwable)
                    ?: throwable.printStackTrace()
            }
        }
    }

    /**
     * The loaded GGUF model holds several hundred MB of native memory. When
     * the app leaves the foreground the OS may reclaim the process (Low Memory
     * Killer) because of that footprint, which surfaces to the user as an
     * instant "app stopped" after backgrounding. Unload the model as soon as
     * the UI is hidden so the backgrounded process stays small and stable.
     * The model reloads lazily on the next message (it is still READY).
     * Respects the user's "Auto Unload Model" setting.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::container.isInitialized) {
            if (!container.settingsRepository.cachedAutoUnload) return
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    container.inferenceRuntime.unload()
                    Log.i("AiChatHubApp", "MODEL_UNLOADED on background (trimMemory=$level)")
                }
            }
        }
    }
}