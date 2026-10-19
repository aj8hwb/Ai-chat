package com.aichathub.app.di

import android.content.Context
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.RemoteCatalogRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.data.local.AiDatabase
import com.aichathub.app.device.DeviceInfoProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository {
        return SettingsRepository(context.applicationContext)
    }

    @Provides
    @Singleton
    fun provideModelRepository(
        @ApplicationContext context: Context,
        database: AiDatabase,
        catalogRepository: CatalogRepository
    ): ModelRepository {
        return ModelRepository(context.applicationContext, database, catalogRepository)
    }

    @Provides
    @Singleton
    fun provideDeviceInfoProvider(@ApplicationContext context: Context): DeviceInfoProvider {
        return DeviceInfoProvider(context.applicationContext)
    }

    @Provides
    @Singleton
    fun provideRemoteCatalogRepository(@ApplicationContext context: Context): RemoteCatalogRepository {
        return RemoteCatalogRepository(context.applicationContext)
    }
}
