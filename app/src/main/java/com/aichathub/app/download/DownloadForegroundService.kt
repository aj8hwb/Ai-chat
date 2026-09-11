package com.aichathub.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aichathub.app.MainActivity
import com.aichathub.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject
    lateinit var downloadManager: DownloadManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        observeDownloads()
        return START_STICKY
    }

    /**
     * Android 15+ (API 35) enforces a 6-hour runtime limit for dataSync
     * foreground services within each 24-hour period. When the limit is
     * reached, the system calls this method. We must stop the service
     * promptly to avoid ANR or force-stop.
     *
     * Large model downloads (8-20 GB) may exceed this limit. The download
     * state is persisted via DownloadManager's .part files, so the download
     * can be resumed after the service is restarted.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "FGS timeout reached (startId=$startId, type=$fgsType). Stopping service.")
        // Notify the user that the download was paused due to system timeout
        showTimeoutNotification()
        // Stop the service — downloads are resumable via .part files
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Android 14+ (API 34) callback for foreground service timeout.
     * Called when the dataSync FGS runtime limit is exceeded.
     */
    override fun onTimeout(startId: Int) {
        if (Build.VERSION.SDK_INT >= 35) {
            // Delegate to the 3-parameter version on API 35+
            onTimeout(startId, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            super.onTimeout(startId)
        }
    }

    private fun showTimeoutNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Download paused by system")
            .setContentText("Large downloads are limited to 6 hours. Your download will resume when you open the app.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        nm.notify(TIMEOUT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeDownloads() {
        progressJob?.cancel()
        progressJob = scope.launch {
            downloadManager.downloads.collect { list ->
                val active = list.firstOrNull {
                    it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.VERIFYING
                }
                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, buildNotification(active))
                }
            }
        }
    }

    private fun buildNotification(info: DownloadInfo?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notification_download_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)

        if (info == null) {
            builder.setContentText(getString(R.string.notification_download_paused))
        } else {
            builder.setContentText(info.modelName)
            when (info.status) {
                DownloadStatus.VERIFYING -> {
                    builder.setContentText(getString(R.string.notification_download_verifying))
                    builder.addAction(
                        0,
                        getString(R.string.notification_action_cancel),
                        DownloadActionReceiver.actionIntent(this, DownloadActionReceiver.ACTION_CANCEL, info.modelId)
                    )
                }
                DownloadStatus.PAUSED -> {
                    builder.setContentText(getString(R.string.notification_download_paused))
                    builder.addAction(
                        0,
                        getString(R.string.notification_action_resume),
                        DownloadActionReceiver.actionIntent(this, DownloadActionReceiver.ACTION_RESUME, info.modelId)
                    )
                    builder.addAction(
                        0,
                        getString(R.string.notification_action_cancel),
                        DownloadActionReceiver.actionIntent(this, DownloadActionReceiver.ACTION_CANCEL, info.modelId)
                    )
                }
                else -> {
                    builder.setProgress(100, info.progress, false)
                    builder.setContentText(
                        "${info.progress}% . ${formatBytes(info.downloadedBytes)} / ${formatBytes(info.totalBytes)}" +
                            (if (info.speedBytesPerSec > 0) " . ${formatBytes(info.speedBytesPerSec)}/s" else "")
                    )
                    builder.addAction(
                        0,
                        getString(R.string.notification_action_pause),
                        DownloadActionReceiver.actionIntent(this, DownloadActionReceiver.ACTION_PAUSE, info.modelId)
                    )
                    builder.addAction(
                        0,
                        getString(R.string.notification_action_cancel),
                        DownloadActionReceiver.actionIntent(this, DownloadActionReceiver.ACTION_CANCEL, info.modelId)
                    )
                }
            }
        }
        return builder.build()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DownloadFGS"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val TIMEOUT_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("DownloadForegroundService", "Foreground start blocked", e)
            }
        }
    }
}
