package com.aichathub.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.aichathub.app.data.CatalogSyncWorker
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.di.ChatCoordinatorEntryPoint
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
        PrivacyCenter.init(settingsRepository)
        settingsRepository.startCaching()
        installCrashLogger()
        scheduleCatalogSync()
        initializeLifecycleObserver()
    }

    private fun initializeLifecycleObserver() {
        try {
            val entryPoint = dagger.hilt.EntryPoints.get(
                this,
                ChatCoordinatorEntryPoint::class.java
            )
            val coordinator = entryPoint.chatCoordinator()

            ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> coordinator.handleBackgroundTransition(inBackground = true)
                        Lifecycle.Event.ON_START -> coordinator.handleBackgroundTransition(inBackground = false)
                        else -> {}
                    }
                }
            })

            Log.d("AiChatHubApp", "Lifecycle observer initialized via ChatCoordinator")
        } catch (e: Exception) {
            Log.w("AiChatHubApp", "Failed to initialize lifecycle observer", e)
        }
    }

    private fun scheduleCatalogSync() {
        try {
            CatalogSyncWorker.enqueue(this)
            Log.d("AiChatHubApp", "Catalog sync work scheduled")
        } catch (e: Exception) {
            Log.w("AiChatHubApp", "Failed to schedule catalog sync", e)
        }
    }

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
        try {
            val entryPoint = dagger.hilt.EntryPoints.get(
                this,
                ChatCoordinatorEntryPoint::class.java
            )
            entryPoint.chatCoordinator().handleMemoryPressure(level)
        } catch (e: Exception) {
            Log.w("AiChatHubApp", "Failed to handle memory pressure", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // ChatCoordinator no longer owns a CoroutineScope — it uses the
        // application-scoped scope provided by Hilt. Process death is safe
        // by design: the OS cancels all coroutines, model files on disk
        // remain valid, and persistent state is recovered on next launch.
    }
}
