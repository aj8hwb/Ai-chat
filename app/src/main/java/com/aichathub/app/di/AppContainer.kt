package com.aichathub.app.di

import android.content.Context
import androidx.room.Room
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.chat.LlamaCppRuntime
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.data.local.AiDatabase
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.ModelScanner
import com.aichathub.app.download.DownloadManager
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
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    val deviceInfoProvider: DeviceInfoProvider = DeviceInfoProvider(context.applicationContext)
    val compatibilityEngine: CompatibilityEngine = CompatibilityEngine()

    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)

    val downloadManager: DownloadManager = DownloadManager(
        context = context.applicationContext,
        downloadsDir = downloadsDir,
        modelsDir = modelsDir,
        modelRepository = modelRepository,
        deviceInfoProvider = deviceInfoProvider,
        settingsRepository = settingsRepository
    )

    val modelScanner: ModelScanner = ModelScanner(
        context = context.applicationContext,
        modelsDir = modelsDir,
        modelRepository = modelRepository
    )

    val inferenceRuntime: InferenceRuntime = LlamaCppRuntime(context.applicationContext)

    val chatCoordinator: ChatCoordinator = ChatCoordinator(
        runtime = inferenceRuntime,
        conversationDao = conversationDao,
        messageDao = messageDao,
        modelRepository = modelRepository
    )
}
