package com.aichathub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.aichathub.app.di.AppContainer
import com.aichathub.app.ui.AiChatHubApp
import com.aichathub.app.ui.theme.AiChatHubTheme
import com.aichathub.app.ui.theme.NearBlack

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AiChatHubApplication).container
        enableEdgeToEdge()
        setContent {
            AiChatHubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NearBlack
                ) {
                    AiChatHubApp()
                }
            }
        }
    }
}