package com.example.reply.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun KaiAppRoot(startMode: String = "") {
    val navController = rememberNavController()
    var selectedSessionId by remember { mutableLongStateOf(0L) }

    Surface(color = Color.Black) {
        NavHost(navController = navController, startDestination = "kai_home") {
            composable("kai_home") {
                KaiHomeScreen(
                    startMode = startMode,
                    loadSessionId = if (selectedSessionId == 0L) null else selectedSessionId,
                    onEyeTap = { navController.navigate("presence") },
                    onOpenHistory = { navController.navigate("history") }
                )
            }
            composable("presence") {
                PresenceScreen(onBack = { navController.popBackStack() })
            }
            composable("history") {
                KaiHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSession = { id ->
                        selectedSessionId = id
                        navController.popBackStack("kai_home", false)
                    }
                )
            }
        }
    }
}