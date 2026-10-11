package com.dreamgirl.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dreamgirl.app.ui.chat.ChatScreen
import com.dreamgirl.app.ui.memory.MemoryScreen
import com.dreamgirl.app.ui.persona.PersonaScreen
import com.dreamgirl.app.ui.report.ReportScreen
import com.dreamgirl.app.ui.sessions.SessionListScreen
import com.dreamgirl.app.ui.settings.SettingsScreen
import com.dreamgirl.app.ui.theme.LocalWxPalette

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("sessions", "聊天", Icons.AutoMirrored.Filled.Chat),
    Destination("memory", "记忆", Icons.Filled.MenuBook),
    Destination("report", "今天", Icons.Filled.Insights),
    Destination("settings", "设置", Icons.Filled.Settings),
)

/**
 * 首页是**会话列表**（微信那一屏），一行 = 和她的一天。
 * 点进去才是聊天详情；聊天详情里不显示底部标签栏，跟微信一致。
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val inChat = current?.route?.startsWith("chat") == true
    val inPersona = current?.route == "persona" || current?.route == "prompt"
    val wx = LocalWxPalette.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (!inChat && !inPersona) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEach { dest ->
                    val selected = current?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label, fontSize = 11.sp) },
                        // 微信的选中态只有"绿色图标+绿字"，没有背景胶囊
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = wx.green,
                            selectedTextColor = wx.green,
                            unselectedIconColor = wx.subText,
                            unselectedTextColor = wx.subText,
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "sessions",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable("sessions") {
                SessionListScreen(
                    onOpenDate = { date -> navController.navigate("chat/${date}") },
                )
            }
            composable(
                route = "chat/{date}",
                arguments = listOf(navArgument("date") { type = NavType.StringType }),
            ) { entry ->
                ChatScreen(
                    initialDate = entry.arguments?.getString("date"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("memory") { MemoryScreen() }
            composable("persona") {
                PersonaScreen(onBack = { navController.popBackStack() })
            }
            composable("report") { ReportScreen() }
            composable("settings") {
                SettingsScreen(
                    onOpenPersona = { navController.navigate("persona") },
                    onOpenPrompt = { navController.navigate("prompt") },
                )
            }
            composable("prompt") {
                com.dreamgirl.app.ui.settings.PromptViewerScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
