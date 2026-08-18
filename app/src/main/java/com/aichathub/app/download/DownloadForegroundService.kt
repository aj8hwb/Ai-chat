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
import androidx.core.app.NotificationCompat
import com.aichathub.app.AiChatHubApplication
import com.aichathub.app.MainActivity
import com.aichathub.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps model downloads alive when the app leaves the
 * foreground or is swiped away. The heavy lifting stays in [DownloadManager];
 * this service only:
 *  1. promotes the process to foreground so the OS never kills it mid-download,
 *  2. shows a live progress notification,
 *  3. stops itself once no download is active.
 *
 * Downloads also survive a full app restart — resumable `.part` files are
 * re-scanned and re-paused on startup (see [DownloadManager.init]).
 */
class DownloadForegroundService : Service() {

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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun downloadManager(): DownloadManager? =
        (application as? AiChatHubApplication)?.container?.downloadManager

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
        val manager = downloadManager() ?: run { stopSelf(); return }
        progressJob = scope.launch {
            manager.downloads.collect { list ->
                val active = list.firstOrNull {
                    it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.VERIFYING
                }
                if (active == null) {
                    // Nothing left to do — drop the foreground state and die.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, buildNotification(active))
                }
            }
        }
    }

    private fun buildNotification(info: com.aichathub.app.download.DownloadInfo?): Notification {
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
                DownloadStatus.VERIFYING -> builder.setContentText(
                    getString(R.string.notification_download_verifying)
                )
                DownloadStatus.PAUSED -> builder.setContentText(
                    getString(R.string.notification_download_paused)
                )
                else -> {
                    builder.setProgress(100, info.progress, false)
                    builder.setContentText(
                        "${info.progress}% · ${formatBytes(info.downloadedBytes)} / ${formatBytes(info.totalBytes)}" +
                            (if (info.speedBytesPerSec > 0) " · ${formatBytes(info.speedBytesPerSec)}/s" else "")
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
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 1001

        /** Kick the service into life for an active download. */
        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // On API 31+ starting a foreground service from the background is
                // restricted (ForegroundServiceStartNotAllowedException). The
                // download continues in the app process regardless; the service
                // simply can't promote to foreground until the next opportunity.
                android.util.Log.w("DownloadForegroundService", "Foreground start blocked", e)
            }
        }
    }
}