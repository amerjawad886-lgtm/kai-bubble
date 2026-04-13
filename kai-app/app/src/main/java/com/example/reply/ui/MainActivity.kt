package com.example.reply.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.reply.agent.KaiObservationRuntime
import com.example.reply.ui.theme.ContrastAwareReplyTheme

class MainActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private var modeState by mutableStateOf("")

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (!granted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        modeState = extractMode(intent)

                ensureRuntimeBridge()

        applyFullScreenBars()

        setContent {
            val view = LocalView.current
            val windowSize = calculateWindowSizeClass(this)

            SideEffect {
                applyFullScreenBars(view)
                disableViewClickSounds(view)
            }

            ContrastAwareReplyTheme {
                ReplyApp(
                    windowSize = windowSize,
                    startMode = modeState
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        modeState = extractMode(intent)

        ensureRuntimeBridge()
        applyFullScreenBars()
    }

    override fun onStart() {
        super.onStart()
        ensureRuntimeBridge()
    }

    override fun onResume() {
        super.onResume()
        ensureRuntimeBridge()
        applyFullScreenBars()
    }


    private fun ensureRuntimeBridge() {
        KaiObservationRuntime.ensureBridge(applicationContext)
        if (modeState.isNotBlank() || KaiBubbleManager.isShowing()) {
            KaiObservationRuntime.requestImmediateDump()
        }
    }

    private fun extractMode(intent: Intent?): String {
        return intent?.getStringExtra("kai_mode")?.trim().orEmpty()
    }

    private fun applyFullScreenBars(viewOverride: View? = null) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false

        val v = viewOverride ?: window.decorView

        WindowInsetsControllerCompat(window, v).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        disableViewClickSounds(v)
    }

    private fun disableViewClickSounds(view: View?) {
        try {
            if (view == null) return
            view.isSoundEffectsEnabled = false
            view.rootView?.isSoundEffectsEnabled = false
            window.decorView.isSoundEffectsEnabled = false
            window.decorView.rootView?.isSoundEffectsEnabled = false
            disableRecursively(window.decorView)
        } catch (_: Exception) {
        }
    }

    private fun disableRecursively(view: View?) {
        if (view == null) return
        try {
            view.isSoundEffectsEnabled = false
        } catch (_: Exception) {
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableRecursively(view.getChildAt(i))
            }
        }
    }
}