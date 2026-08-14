package com.aichathub.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.aichathub.app.AiChatHubApplication
import com.aichathub.app.di.AppContainer

/**
 * Resolves the shared [AppContainer] from the CreationExtras of any
 * ViewModel. All ViewModels in the app use a no-arg constructor and call
 * `applicationContainer()` to reach dependencies; the default Android
 * ViewModel factory supplies the Application key this relies on.
 */
fun CreationExtras.applicationContainer(): AppContainer {
    val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
        ?: throw IllegalStateException("Expected Application in CreationExtras")
    return (application as AiChatHubApplication).container
}