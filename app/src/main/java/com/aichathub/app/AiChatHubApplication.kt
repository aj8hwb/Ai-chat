package com.aichathub.app

import android.app.Application
import com.aichathub.app.di.AppContainer

class AiChatHubApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}