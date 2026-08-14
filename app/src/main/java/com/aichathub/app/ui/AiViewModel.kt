package com.aichathub.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aichathub.app.AiChatHubApplication
import com.aichathub.app.di.AppContainer

/**
 * Base ViewModel that exposes the shared [AppContainer].
 * Works with the default `viewModel()` factory because it is an
 * [AndroidViewModel] whose constructor takes an [Application].
 */
abstract class AiViewModel(application: Application) : AndroidViewModel(application) {

    protected val container: AppContainer
        get() = (getApplication() as AiChatHubApplication).container
}