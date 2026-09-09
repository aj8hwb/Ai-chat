package com.aichathub.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichathub.app.chat.RuntimeState
import com.aichathub.app.data.CatalogSyncWorker
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.di.InferenceRuntimeEntryPoint
import com.aichathub.app.privacy.CrashLogRepository
import com.aichathub.app.privacy.PrivacyCenter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AiChatHubApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Initialize PrivacyCenter with the settings repository
        PrivacyCenter.init(settingsRepository)
        settingsRepository.startCaching()
        installCrashLogger()
        scheduleCatalogSync()
    }

    private fun scheduleCatalogSync() {
        try {
            CatalogSyncWorker.enqueue(this)
            Log.d("AiChatHubApp", "Catalog sync work scheduled")
        } catch (e: Exception) {
            Log.w("AiChatHubApp", "Failed to schedule catalog sync", e)
        }
    }

    /**
     * Records any uncaught Java exception to logcat and to the centralized
     * crash log file via [CrashLogRepository].
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
                // Write to centralized crash log
                CrashLogRepository.write(
                    this@AiChatHubApplication,
                    throwable,
                    mem,
                    threadName = thread.name
                )
            } catch (ignored: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
                    ?: throwable.printStackTrace()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Unload the native LLM model when the system is under memory pressure.
        // A loaded GGUF model can consume hundreds of MB to several GB of native
        // memory. If the app is backgrounded or the system is low on memory, the
        // model should be released so the OS can reclaim it — preventing LMK
        // kills, OOM crashes, and ANRs.
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.i("AiChatHubApp", "onTrimMemory level=$level — attempting model unload")
                attemptModelUnload()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI is hidden (app backgrounded). If auto-unload is enabled,
                // release the model proactively before memory pressure escalates.
                if (settingsRepository.cachedAutoUnload) {
                    Log.i("AiChatHubApp", "onTrimMemory UI_HIDDEN — auto-unload enabled, releasing model")
                    attemptModelUnload()
                }
            }
        }
    }

    /**
     * Safely unloads the currently loaded model via the [InferenceRuntime].
     * Only unloads if the runtime is in READY state (not actively generating).
     * Uses a background thread to avoid blocking the main thread during
     * onTrimMemory callbacks.
     */
    private fun attemptModelUnload() {
        try {
            val entryPoint = dagger.hilt.EntryPoints.get(
                this,
                InferenceRuntimeEntryPoint::class.java
            )
            val runtime = entryPoint.inferenceRuntime()
            if (!runtime.isLoaded) return

            val state = runtime.runtimeState
            if (state == RuntimeState.GENERATING || state == RuntimeState.LOADING) {
                Log.i("AiChatHubApp", "Model is $state — skipping unload to avoid interrupting")
                return
            }

            // Perform the unload on a background thread
            Thread({
                try {
                    kotlinx.coroutines.runBlocking {
                        runtime.unload()
                    }
                    Log.i("AiChatHubApp", "Model unloaded by onTrimMemory")
                } catch (e: Exception) {
                    Log.w("AiChatHubApp", "Failed to unload model on trim memory", e)
                }
            }, "model-unload-on-trim").start()
        } catch (e: Exception) {
            Log.w("AiChatHubApp", "Failed to access runtime for model unload", e)
        }
    }
}
