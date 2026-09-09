package com.aichathub.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
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
    val icon: ImageVector? = null,
    val deepLinkPattern: String? = null
) {
    /** Route used when NAVIGATING to this screen (may differ from [route] when
     *  the destination carries optional arguments). */
    open val navRoute: String
        get() = route

    data object Onboarding : Screen("onboarding", "Onboarding")
    data object Home : Screen("home", "Home", Icons.Filled.Home, deepLinkPattern = "aichathub://home")
    data object Models : Screen("models", "Models", Icons.Filled.SmartToy, deepLinkPattern = "aichathub://models")
    data object Chat : Screen("chat?modelId={modelId}", "Chat", Icons.Filled.ChatBubbleOutline, deepLinkPattern = "aichathub://chat") {
        const val ARG = "modelId"
        const val BASE_ROUTE = "chat"
        override val navRoute: String get() = BASE_ROUTE
        fun routeFor(modelId: String) = "$BASE_ROUTE?modelId=$modelId"
        fun deepLink(modelId: String) = "aichathub://chat?modelId=$modelId"
    }
    data object Playground : Screen("playground", "Playground", Icons.Filled.PlayArrow, deepLinkPattern = "aichathub://playground")
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, deepLinkPattern = "aichathub://settings")

    data object SystemStatus : Screen("system_status", "System Status", Icons.Filled.Speed, deepLinkPattern = "aichathub://system-status")
    data object ModelDetails : Screen("model_details/{modelId}", null, Icons.Filled.SmartToy, deepLinkPattern = "aichathub://model-details") {
        fun routeFor(modelId: String) = "model_details/$modelId"
        fun deepLink(modelId: String) = "aichathub://model-details/$modelId"
        const val ARG = "modelId"
    }
    data object Downloads : Screen("downloads", "Downloads", Icons.Filled.Storage, deepLinkPattern = "aichathub://downloads")
    data object MyModels : Screen("my_models", "My Models", Icons.Filled.SmartToy, deepLinkPattern = "aichathub://my-models")
    data object ChatSettings : Screen("chat_settings", "Chat Settings", Icons.Filled.Tune, deepLinkPattern = "aichathub://chat-settings")
    data object Performance : Screen("performance", "Performance", Icons.Filled.Speed, deepLinkPattern = "aichathub://performance")
    data object Storage : Screen("storage", "Storage", Icons.Filled.Storage, deepLinkPattern = "aichathub://storage")
    data object Benchmark : Screen("benchmark", "Benchmark", Icons.Filled.Speed, deepLinkPattern = "aichathub://benchmark")
    data object Compare : Screen("compare", "Compare", Icons.Filled.SwapHoriz, deepLinkPattern = "aichathub://compare")
    data object History : Screen("history", "History", Icons.Filled.History, deepLinkPattern = "aichathub://history")
    data object About : Screen("about", "About", Icons.Filled.Info, deepLinkPattern = "aichathub://about")
    data object PrivacyCenter : Screen("privacy_center", "Privacy Center", Icons.Filled.Info, deepLinkPattern = "aichathub://privacy-center")
    data object Conversation : Screen("conversation/{conversationId}", null, Icons.Filled.ChatBubbleOutline) {
        fun routeFor(id: Long) = "conversation/$id"
        fun deepLink(id: Long) = "aichathub://conversation/$id"
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