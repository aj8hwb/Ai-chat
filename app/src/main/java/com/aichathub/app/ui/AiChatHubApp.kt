package com.aichathub.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.navigation.bottomDestinations
import com.aichathub.app.ui.screens.BenchmarkScreen
import com.aichathub.app.ui.screens.ChatScreen
import com.aichathub.app.ui.screens.ChatSettingsScreen
import com.aichathub.app.ui.screens.CompareScreen
import com.aichathub.app.ui.screens.DownloadsScreen
import com.aichathub.app.ui.screens.HistoryScreen
import com.aichathub.app.ui.screens.HomeScreen
import com.aichathub.app.ui.screens.ModelDetailsScreen
import com.aichathub.app.ui.screens.ModelsScreen
import com.aichathub.app.ui.screens.MyModelsScreen
import com.aichathub.app.ui.screens.PerformanceScreen
import com.aichathub.app.ui.screens.PlaygroundScreen
import com.aichathub.app.ui.screens.SettingsScreen
import com.aichathub.app.ui.screens.StorageScreen
import com.aichathub.app.ui.screens.SystemStatusScreen
import com.aichathub.app.ui.theme.NearBlack
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.TextMuted

@Composable
fun AiChatHubApp() {
    val navController = rememberNavController()

    Scaffold(
        containerColor = NearBlack,
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            if (bottomDestinations.any { it.route == currentRoute }) {
                NavigationBar(
                    containerColor = Color(0xFF14141E),
                    tonalElevation = 0.dp
                ) {
                    bottomDestinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon!!, contentDescription = destination.label)
                            },
                            label = { Text(destination.label!!, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Color(0x228B5CF6)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(onNavigate = navController::navigate)
        }
        composable(Screen.Models.route) {
            ModelsScreen(onNavigate = navController::navigate)
        }
        composable(Screen.Chat.route) {
            ChatScreen(onNavigate = navController::navigate)
        }
        composable(Screen.Playground.route) {
            PlaygroundScreen()
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onNavigate = navController::navigate)
        }
        composable(Screen.SystemStatus.route) {
            SystemStatusScreen()
        }
        composable(
            route = Screen.ModelDetails.route,
            arguments = listOf(
                androidx.navigation.navArgument(Screen.ModelDetails.ARG) {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) { entry ->
            val modelId = entry.arguments?.getString(Screen.ModelDetails.ARG) ?: return@composable
            ModelDetailsScreen(
                modelId = modelId,
                onBack = { navController.popBackStack() },
                onOpenChat = {
                    navController.popBackStack()
                    navController.navigate(Screen.Chat.route)
                }
            )
        }
        composable(Screen.Downloads.route) {
            DownloadsScreen()
        }
        composable(Screen.MyModels.route) {
            MyModelsScreen(onNavigate = navController::navigate)
        }
        composable(Screen.ChatSettings.route) {
            ChatSettingsScreen()
        }
        composable(Screen.Performance.route) {
            PerformanceScreen()
        }
        composable(Screen.Storage.route) {
            StorageScreen(onNavigate = navController::navigate)
        }
        composable(Screen.Benchmark.route) {
            BenchmarkScreen()
        }
        composable(Screen.Compare.route) {
            CompareScreen()
        }
        composable(Screen.History.route) {
            HistoryScreen(onNavigate = navController::navigate)
        }
        composable(
            route = Screen.Conversation.route,
            arguments = listOf(
                androidx.navigation.navArgument(Screen.Conversation.ARG) {
                    type = androidx.navigation.NavType.LongType
                }
            )
        ) { entry ->
            val conversationId = entry.arguments?.getLong(Screen.Conversation.ARG)
            ChatScreen(
                onNavigate = navController::navigate,
                conversationId = conversationId
            )
        }
    }
}