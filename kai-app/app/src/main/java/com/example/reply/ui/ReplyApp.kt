package com.example.reply.ui

import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// FIX: Removed all dead parameters inherited from the Reply sample template:
//   - displayFeatures      → never passed to any child composable
//   - replyHomeUIState     → never used anywhere in this file or downstream
//   - closeDetailScreen    → default no-op, never wired to anything
//   - navigateToDetail     → default no-op, never wired to anything
//   - toggleSelectedEmail  → default no-op, never wired to anything
// These were compile-time noise that caused confusion about the actual contract.
//
// windowSize is kept because it may be needed by child screens in the future.
// startMode is kept because it is actively passed down to KaiHomeScreen.

@Composable
fun ReplyApp(
    windowSize: WindowSizeClass,
    startMode: String = "",
) {
    val navController = rememberNavController()
    var selectedSessionId by remember { mutableLongStateOf(0L) }

    // FIX: Surface background set to Color.Black to eliminate the white flash
    // that appeared for one frame before KaiHomeScreen painted its own dark gradient.
    Surface(color = Color.Black) {
        KaiNavHost(
            navController = navController,
            modifier = Modifier,
            startMode = startMode,
            selectedSessionId = selectedSessionId,
            onSelectSession = { id ->
                selectedSessionId = id
                navController.popBackStack("kai_home", false)
            }
        )
    }
}

@Composable
private fun KaiNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startMode: String = "",
    selectedSessionId: Long = 0L,
    onSelectSession: (Long) -> Unit
) {
    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = "kai_home"
    ) {
        composable("kai_home") {
            KaiHomeScreen(
                startMode = startMode,
                loadSessionId = if (selectedSessionId == 0L) null else selectedSessionId,
                onEyeTap = { navController.navigate("presence") },
                onOpenHistory = { navController.navigate("history") }
            )
        }

        composable("presence") {
            PresenceScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("history") {
            KaiHistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> onSelectSession(id) }
            )
        }
    }
}
