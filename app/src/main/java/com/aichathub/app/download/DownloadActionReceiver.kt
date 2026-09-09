package com.aichathub.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aichathub.app.di.DownloadManagerEntryPoint
import dagger.hilt.android.EntryPointAccessors

class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DownloadManagerEntryPoint::class.java
        )
        val manager = entryPoint.downloadManager()
        when (intent.action) {
            ACTION_PAUSE -> {
                Log.i("DownloadActionReceiver", "pause $modelId")
                manager.pause(modelId)
            }
            ACTION_RESUME -> {
                Log.i("DownloadActionReceiver", "resume $modelId")
                manager.resume(modelId)
            }
            ACTION_CANCEL -> {
                Log.i("DownloadActionReceiver", "cancel $modelId")
                manager.cancel(modelId)
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.aichathub.app.download.action.PAUSE"
        const val ACTION_RESUME = "com.aichathub.app.download.action.RESUME"
        const val ACTION_CANCEL = "com.aichathub.app.download.action.CANCEL"
        const val EXTRA_MODEL_ID = "model_id"

        private const val REQUEST_CODE = 0x1000
        private var requestCodeSeed = 0

        fun actionIntent(context: Context, action: String, modelId: String): android.app.PendingIntent {
            val intent = Intent(context, DownloadActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            val requestCode = REQUEST_CODE + requestCodeSeed++
            return android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
