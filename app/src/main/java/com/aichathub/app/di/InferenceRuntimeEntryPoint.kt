package com.aichathub.app.di

import com.aichathub.app.chat.InferenceRuntime
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface InferenceRuntimeEntryPoint {
    fun inferenceRuntime(): InferenceRuntime
}
