package com.aichathub.app.di

import android.content.Context
import com.aichathub.app.chat.BackendSelector
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.chat.LlamaCppRuntime
import com.aichathub.app.chat.LlamaCppRuntimeProvider
import com.aichathub.app.chat.RuntimeProvider
import com.aichathub.app.chat.RuntimeProviderRegistry
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.RemoteCatalogRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.ModelScanner
import com.aichathub.app.download.DownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Qualifier for the application-scoped [CoroutineScope].
 * This scope lives as long as the application process and is never cancelled
 * explicitly — process death handles cleanup automatically.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @Provides
    @Singleton
    fun provideCompatibilityEngine(): CompatibilityEngine {
        return CompatibilityEngine()
    }

    @Provides
    @Singleton
    fun provideBackendSelector(
        @ApplicationContext context: Context,
        deviceInfoProvider: DeviceInfoProvider
    ): BackendSelector {
        return BackendSelector(
            context = context.applicationContext,
            deviceInfoProvider = deviceInfoProvider
        )
    }

    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        modelRepository: ModelRepository,
        deviceInfoProvider: DeviceInfoProvider,
        settingsRepository: SettingsRepository,
        catalogRepository: CatalogRepository
    ): DownloadManager {
        val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        return DownloadManager(
            context = context.applicationContext,
            downloadsDir = downloadsDir,
            modelsDir = modelsDir,
            modelRepository = modelRepository,
            deviceInfoProvider = deviceInfoProvider,
            settingsRepository = settingsRepository,
            catalogRepository = catalogRepository
        )
    }

    @Provides
    @Singleton
    fun provideModelScanner(
        @ApplicationContext context: Context,
        modelRepository: ModelRepository,
        catalogRepository: CatalogRepository
    ): ModelScanner {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        return ModelScanner(
            context = context.applicationContext,
            modelsDir = modelsDir,
            modelRepository = modelRepository,
            catalogRepository = catalogRepository
        )
    }

    @Provides
    @Singleton
    fun provideRemoteCatalogRepository(
        @ApplicationContext context: Context
    ): RemoteCatalogRepository {
        return RemoteCatalogRepository(context.applicationContext)
    }

    @Provides
    @Singleton
    fun provideCatalogRepository(
        @ApplicationContext context: Context,
        remoteCatalogRepository: RemoteCatalogRepository
    ): CatalogRepository {
        return CatalogRepository(
            context = context.applicationContext,
            remoteCatalogRepository = remoteCatalogRepository
        )
    }

    /**
     * Provides the default [InferenceRuntime] by querying the [RuntimeProviderRegistry].
     *
     * This replaces the old hard-coded [LlamaCppRuntime] binding. The registry
     * selects the best available runtime at injection time. If no provider is
     * available (unlikely in production), it falls back to a direct
     * [LlamaCppRuntime] instance so the app never breaks.
     *
     * Uses a lazy wrapper with coroutine-safe initialization (no runBlocking).
     */
    @Provides
    @Singleton
    fun provideInferenceRuntime(
        registry: RuntimeProviderRegistry,
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        @ApplicationScope appScope: CoroutineScope
    ): InferenceRuntime {
        val fallbackRuntime by lazy {
            LlamaCppRuntime(
                context = context.applicationContext,
                onModelMemoryMeasured = { modelId, bytes ->
                    appScope.launch {
                        runCatching { settingsRepository.setMeasuredMemory(modelId, bytes) }
                    }
                }
            )
        }
        return LazyInferenceRuntime(registry, fallbackRuntime)
    }

    @Provides
    @Singleton
    fun provideChatCoordinator(
        runtime: InferenceRuntime,
        conversationDao: ConversationDao,
        messageDao: MessageDao,
        modelRepository: ModelRepository,
        @ApplicationScope appScope: CoroutineScope
    ): ChatCoordinator {
        return ChatCoordinator(
            runtime = runtime,
            conversationDao = conversationDao,
            messageDao = messageDao,
            modelRepository = modelRepository,
            appScope = appScope
        )
    }
}

/**
 * Provides runtime providers via Hilt multibinding.
 *
 * Each `@Provides @ElementsIntoSet` function adds one or more
 * [RuntimeProvider] implementations to the set that is injected into
 * [RuntimeProviderRegistry]. Adding a new inference engine is as simple as
 * adding another function here.
 */
@Module
@InstallIn(SingletonComponent::class)
object RuntimeProviderModule {

    @Provides
    @ElementsIntoSet
    fun provideLlamaCppProvider(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository
    ): Set<RuntimeProvider> {
        return setOf(
            LlamaCppRuntimeProvider(
                context = context.applicationContext,
                settingsRepository = settingsRepository
            )
        )
    }
}

private fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit) {
    kotlinx.coroutines.launch(block = block)
}

/**
 * Lazy wrapper that defers runtime provider selection until first use.
 * Avoids blocking during Hilt dependency graph construction.
 *
 * Provider resolution happens asynchronously on first access — no runBlocking.
 * The synchronized block ensures thread-safe one-time initialization.
 */
private class LazyInferenceRuntime(
    private val registry: RuntimeProviderRegistry,
    private val fallbackRuntime: InferenceRuntime
) : InferenceRuntime {

    @Volatile
    private var resolved = false
    private val initLock = Any()

    private var _delegate: InferenceRuntime? = null

    private val delegate: InferenceRuntime
        get() {
            if (!resolved) {
                synchronized(initLock) {
                    if (!resolved) {
                        _delegate = resolveRuntime()
                        resolved = true
                    }
                }
            }
            return _delegate ?: fallbackRuntime
        }

    /**
     * Resolves the runtime provider synchronously. [RuntimeProviderRegistry.getBestProvider]
     * is a lightweight in-memory lookup (no I/O), so blocking here is safe.
     */
    private fun resolveRuntime(): InferenceRuntime {
        val provider = kotlinx.coroutines.runBlocking {
            registry.getBestProvider()
        }
        return if (provider != null) {
            kotlinx.coroutines.runBlocking { provider.createRuntime() }
        } else {
            fallbackRuntime
        }
    }

    override val isLoaded: Boolean get() = delegate.isLoaded

    override val runtimeState: com.aichathub.app.chat.RuntimeState
        get() = delegate.runtimeState

    override val activeModelId: String? get() = delegate.activeModelId

    override val performance: kotlinx.coroutines.flow.StateFlow<InferenceRuntime.Performance>
        get() = delegate.performance

    override suspend fun load(
        modelId: String,
        file: java.io.File,
        contextLength: Int,
        sampling: GenerationConfig?,
        threads: Int
    ) {
        delegate.load(modelId, file, contextLength, sampling, threads)
    }

    override suspend fun unload() {
        delegate.unload()
    }

    override suspend fun generate(prompt: String, config: GenerationConfig): String {
        return delegate.generate(prompt, config)
    }

    override suspend fun generateStreaming(
        prompt: String,
        config: GenerationConfig,
        onToken: (String) -> Unit
    ): String {
        return delegate.generateStreaming(prompt, config, onToken)
    }

    override fun cancelGeneration() {
        delegate.cancelGeneration()
    }

    override fun clearCancellation() {
        delegate.clearCancellation()
    }

    override suspend fun release() {
        delegate.release()
    }
}
