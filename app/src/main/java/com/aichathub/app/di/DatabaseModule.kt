package com.aichathub.app.di

import android.content.Context
import androidx.room.Room
import com.aichathub.app.data.local.AiDatabase
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AiDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AiDatabase::class.java,
            "aichathub.db"
        )
            .addMigrations(
                AiDatabase.MIGRATION_1_2,
                AiDatabase.MIGRATION_2_3,
                AiDatabase.MIGRATION_3_4,
                AiDatabase.MIGRATION_4_5
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideConversationDao(database: AiDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: AiDatabase): MessageDao {
        return database.messageDao()
    }
}
