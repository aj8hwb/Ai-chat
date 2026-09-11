package com.aichathub.app.di

import com.aichathub.app.data.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsRepositoryEntryPoint {
    fun settingsRepository(): SettingsRepository
}
