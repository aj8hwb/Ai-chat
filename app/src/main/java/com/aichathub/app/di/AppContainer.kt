package com.aichathub.app.di

import android.content.Context
import androidx.room.Room
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.chat.MediaPipeRuntime
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.local.AiDatabase
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.download.DownloadManager
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.chat.ChatCoordinator
import java.io.File

/**
 * Simple manual service locator. Keeps dependencies explicit and testable
 * without adding a DI framework to the MVP.
 */
class AppContainer(context: Context) {

    val database: AiDatabase = Room.databaseBuilder(
        context.applicationContext,
        AiDatabase::class.java,
        "aichathub.db"
    ).build()

    val conversationDao: ConversationDao = database.conversationDao()
    val messageDao: MessageDao = database.messageDao()

    val modelRepository: ModelRepository = ModelRepository(context.applicationContext, database)

    private val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    val downloadManager: DownloadManager = DownloadManager(downloadsDir)

    val deviceInfoProvider: DeviceInfoProvider = DeviceInfoProvider(context.applicationContext)
    val compatibilityEngine: CompatibilityEngine = CompatibilityEngine()

    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)

    val inferenceRuntime: InferenceRuntime = MediaPipeRuntime(context.applicationContext)

    val chatCoordinator: ChatCoordinator = ChatCoordinator(
        runtime = inferenceRuntime,
        conversationDao = conversationDao,
        messageDao = messageDao,
        modelRepository = modelRepository
    )
}