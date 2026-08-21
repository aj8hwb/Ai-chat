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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    )
        .addMigrations(AiDatabase.MIGRATION_1_2, AiDatabase.MIGRATION_2_3)
        .build()

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

    val inferenceRuntime: InferenceRuntime = LlamaCppRuntime(
        context = context.applicationContext,
        onModelMemoryMeasured = { modelId, bytes ->
            // Persist the real measured footprint so the recommendation system
            // can score this model on what actually happens on THIS device.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { settingsRepository.setMeasuredMemory(modelId, bytes) }
            }
        }
    )

    val chatCoordinator: ChatCoordinator = ChatCoordinator(
        runtime = inferenceRuntime,
        conversationDao = conversationDao,
        messageDao = messageDao,
        modelRepository = modelRepository
    )

    val chatBackupManager: com.aichathub.app.data.ChatBackupManager =
        com.aichathub.app.data.ChatBackupManager(context.applicationContext, database)

    init {
        // Mirror persisted settings into synchronous caches used by components
        // that cannot suspend (Application.onTrimMemory etc.).
        settingsRepository.startCaching()
    }
}
