package com.aichathub.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aichathub.app.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically syncs the remote model catalog.
 *
 * Runs once every 24 hours to check for new models. When updates are found,
 * a notification is shown to the user listing the new models.
 */
@HiltWorker
class CatalogSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val catalogRepository: RemoteCatalogRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CatalogSyncWorker"
        private const val WORK_NAME = "catalog_sync"
        private const val NOTIFICATION_CHANNEL_ID = "catalog_sync"
        private const val NOTIFICATION_ID = 2001

        /**
         * Enqueues the periodic catalog sync work. Safe to call multiple times;
         * existing work is not duplicated.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.i(TAG, "Catalog sync work enqueued (every 24h)")
        }

        /**
         * Cancels any pending or running catalog sync work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Catalog sync work cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Catalog sync started")

        val previousManifest = try {
            catalogRepository.getLocalManifest()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read local manifest", e)
            null
        }

        return try {
            val manifest = catalogRepository.fetchManifest()
            val previousVersion = previousManifest?.version ?: 0

            if (manifest.version > previousVersion) {
                Log.i(TAG, "Catalog updated: v$previousVersion -> v${manifest.version}")

                val newModels = if (previousManifest != null) {
                    val previousIds = previousManifest.models.map { it.id }.toSet()
                    manifest.models.filter { it.id !in previousIds }
                } else {
                    emptyList()
                }

                if (newModels.isNotEmpty()) {
                    showNewModelsNotification(newModels.size, manifest.version)
                } else {
                    showVersionUpdateNotification(manifest.version)
                }
            } else {
                Log.d(TAG, "Catalog is up to date (v${manifest.version})")
            }

            Result.success()
        } catch (e: CatalogFetchException) {
            Log.e(TAG, "Catalog fetch failed: ${e.message}", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during catalog sync", e)
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Syncing model catalog")
            .setContentText("Checking for new models...")
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showNewModelsNotification(count: Int, version: Int) {
        createNotificationChannel()
        val manager = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$count new model${if (count > 1) "s" else ""} available")
            .setContentText("Open AiChatHub to browse the latest models (catalog v$version)")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showVersionUpdateNotification(version: Int) {
        createNotificationChannel()
        val manager = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Model catalog updated")
            .setContentText("Catalog v$version — open to see what's new")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Catalog Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications about model catalog updates"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
