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
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Models : Screen("models", "Models", Icons.Filled.SmartToy)
    data object Chat : Screen("chat", "Chat", Icons.Filled.ChatBubbleOutline)
    data object Playground : Screen("playground", "Playground", Icons.Filled.PlayArrow)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

    data object SystemStatus : Screen("system_status", "System Status", Icons.Filled.Speed)
    data object ModelDetails : Screen("model_details/{modelId}", null, Icons.Filled.SmartToy) {
        fun routeFor(modelId: String) = "model_details/$modelId"
        const val ARG = "modelId"
    }
    data object Downloads : Screen("downloads", "Downloads", Icons.Filled.Storage)
    data object MyModels : Screen("my_models", "My Models", Icons.Filled.SmartToy)
    data object ChatSettings : Screen("chat_settings", "Chat Settings", Icons.Filled.Tune)
    data object Performance : Screen("performance", "Performance", Icons.Filled.Speed)
    data object Storage : Screen("storage", "Storage", Icons.Filled.Storage)
    data object Benchmark : Screen("benchmark", "Benchmark", Icons.Filled.Speed)
    data object Compare : Screen("compare", "Compare", Icons.Filled.SwapHoriz)
    data object History : Screen("history", "History", Icons.Filled.History)
    data object Conversation : Screen("conversation/{conversationId}", null, Icons.Filled.ChatBubbleOutline) {
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