package com.aichathub.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val label: String? = null,
    val icon: ImageVector? = null
) {
    data object Home : Screen("home", "Home", Icons.filled.Home)
    data object Models : Screen("models", "Models", Icons.filled.SmartToy)
    data object Chat : Screen("chat", "Chat", Icons.filled.ChatBubbleOutline)
    data object Playground : Screen("playground", "Playground", Icons.filled.PlayArrow)
    data object Settings : Screen("settings", "Settings", Icons.filled.Settings)

    data object SystemStatus : Screen("system_status", "System Status", Icons.filled.Speed)
    data object ModelDetails : Screen("model_details/{modelId}", null, Icons.filled.SmartToy) {
        fun routeFor(modelId: String) = "model_details/$modelId"
        const val ARG = "modelId"
    }
    data object Downloads : Screen("downloads", "Downloads", Icons.filled.Storage)
    data object MyModels : Screen("my_models", "My Models", Icons.filled.SmartToy)
    data object ChatSettings : Screen("chat_settings", "Chat Settings", Icons.filled.Tune)
    data object Performance : Screen("performance", "Performance", Icons.filled.Speed)
    data object Storage : Screen("storage", "Storage", Icons.filled.Storage)
    data object Benchmark : Screen("benchmark", "Benchmark", Icons.filled.Speed)
    data object Compare : Screen("compare", "Compare", Icons.filled.SwapHoriz)
    data object History : Screen("history", "History", Icons.filled.History)
    data object Conversation : Screen("conversation/{conversationId}", null, Icons.filled.ChatBubbleOutline) {
        fun routeFor(id: Long) = "conversation/$id"
        const val ARG = "conversationId"
    }
}

/** Primary bottom-navigation destinations. */
val bottomDestinations = listOf(
    Screen.Home,
    Screen.Models,
    Screen.Chat,
    Screen.Playground,
    Screen.Settings
)