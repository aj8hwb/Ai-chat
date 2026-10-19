package com.aichathub.app.di

import com.aichathub.app.chat.ChatCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatCoordinatorEntryPoint {
    fun chatCoordinator(): ChatCoordinator
}
