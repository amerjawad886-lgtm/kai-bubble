package com.example.reply.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun KaiAppRoot(startMode: String = "") {
    // ✅ No Compose splash to avoid Double Splash.
    // System Splash (installSplashScreen) already handles the first splash.

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "kai_home"
    ) {
        composable("kai_home") {
            KaiHomeScreen(
                startMode = startMode,
                onEyeTap = { navController.navigate("presence") }
            )
        }

        composable("presence") {
            PresenceScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}