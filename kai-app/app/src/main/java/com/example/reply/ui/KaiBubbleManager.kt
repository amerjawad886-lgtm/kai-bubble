package com.example.reply.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

object KaiBubbleManager {
    private const val TAG = "KaiBubbleManager"

    const val OVERLAY_WIDTH_PX = 900
    const val OVERLAY_HEIGHT_PX = 700

    private val main = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var bubbleOwner: BubbleOwner? = null

    // FIX: topOffsetPx default lowered. The old 0.045f * screenHeight produced 80-90px on
    // most devices, pushing the island noticeably away from the top edge.
    // We now compute 1% of screen height (≈ 19px on 1920px screen) with an 8px floor.
    private var topOffsetPx: Int = 8
    private var lastX: Int = 0
    private var lastY: Int = topOffsetPx
    private var inputModeEnabled: Boolean = false

    private var suppressionCounter: Int = 0
    private var strongSuppressionCounter: Int = 0
    private var lastUiApplyAt: Long = 0L
    private var pendingUiApply = false

    fun isShowing(): Boolean = bubbleView != null
    fun getCurrentX(): Int = bubbleParams?.x ?: lastX
    fun getCurrentY(): Int = bubbleParams?.y ?: lastY
    fun isActionUiSuppressed(): Boolean = suppressionCounter > 0 || strongSuppressionCounter > 0
    fun isStronglySuppressed(): Boolean = strongSuppressionCounter > 0

    fun getScreenWidth(context: Context): Int {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            wm.currentWindowMetrics.bounds.width()
        } catch (_: Exception) {
            1080
        }
    }

    fun getScreenHeight(context: Context): Int {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            wm.currentWindowMetrics.bounds.height()
        } catch (_: Exception) {
            1920
        }
    }

    fun show(context: Context, onReady: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        main.post {
            if (bubbleView != null) {
                try {
                    bubbleView?.visibility = View.VISIBLE
                    applyActionUiState()
                    if (!inputModeEnabled) {
                        bubbleView?.clearFocus()
                    }
                } catch (_: Exception) {
                }
                onReady?.invoke()
                return@post
            }

            try {
                val owner = BubbleOwner().apply {
                    performRestore(null)
                    handleOnCreate()
                    handleOnStart()
                    handleOnResume()
                }
                bubbleOwner = owner

                val compose = ComposeView(appContext).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    isSoundEffectsEnabled = false
                    try {
                        rootView?.isSoundEffectsEnabled = false
                    } catch (_: Exception) {
                    }
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeViewModelStoreOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setContent {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            KaiBubbleUI(
                                context = appContext,
                                onClose = { hide(appContext) }
                            )
                        }
                    }
                }

                val params = createDefaultLayoutParams(appContext)
                bubbleParams = params
                wm.addView(compose, params)
                bubbleView = compose
                applyActionUiState()
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Bubble show failed: ${e.message}", e)
                removeBubbleView(wm)
                destroyOwner()
            }
        }
    }

    fun hide(context: Context) {
        val appContext = context.applicationContext
        val wm = windowManager ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        main.post {
            removeBubbleView(wm)
            destroyOwner()
            inputModeEnabled = false
            suppressionCounter = 0
            strongSuppressionCounter = 0
            lastX = 0
            lastY = topOffsetPx
        }
    }

    fun softResetUiState() {
        main.post {
            suppressionCounter = 0
            strongSuppressionCounter = 0

            val wm = windowManager
            val view = bubbleView
            val params = bubbleParams

            if (wm != null && view != null && params != null) {
                try {
                    inputModeEnabled = false
                    params.flags =
                        params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    view.clearFocus()
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "softResetUiState update failed: ${e.message}", e)
                }
            }

            applyActionUiState()
        }
    }

    fun setInputModeEnabled(enabled: Boolean) {
        inputModeEnabled = enabled
        val wm = windowManager ?: return
        val view = bubbleView ?: return
        val params = bubbleParams ?: return

        main.post {
            try {
                if (enabled) {
                    params.flags =
                        params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    params.softInputMode =
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    view.isFocusable = true
                    view.isFocusableInTouchMode = true
                    view.requestFocus()
                } else {
                    params.flags =
                        params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    params.softInputMode =
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    view.clearFocus()
                }
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "setInputModeEnabled failed: ${e.message}", e)
            }
        }
    }

    fun onPromptComposerStateChanged(isOpen: Boolean) {
        main.post {
            try {
                setInputModeEnabled(isOpen)
            } catch (e: Exception) {
                Log.e(TAG, "onPromptComposerStateChanged failed: ${e.message}", e)
            }
        }
    }

    fun openMainApp(context: Context) {
        try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openMainApp failed: ${e.message}", e)
        }
    }

    fun onHomeAction(context: Context) {
        try {
            openMainApp(context)
        } catch (e: Exception) {
            Log.e(TAG, "onHomeAction failed: ${e.message}", e)
        }
    }

    // FIX: x parameter was accepted but then hardcoded to 0 internally — removed the dead param.
    fun updatePosition(y: Int) {
        val wm = windowManager ?: return
        val view = bubbleView ?: return
        val params = bubbleParams ?: return

        main.post {
            try {
                params.x = 0
                params.y = clampY(y)
                lastX = 0
                lastY = params.y
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "updatePosition failed: ${e.message}", e)
            }
        }
    }

    // FIX: clamp range expanded to topOffsetPx..topOffsetPx+300 so the island has
    // more room to be positioned without being clamped unexpectedly.
    fun clampY(y: Int): Int = y.coerceIn(topOffsetPx, topOffsetPx + 300)

    fun snapToTopCenter() {
        updatePosition(y = topOffsetPx)
    }

    fun beginActionUiSuppression(strong: Boolean = false) {
        main.post {
            if (strong) {
                strongSuppressionCounter += 1
            } else {
                suppressionCounter += 1
            }
            applyActionUiState()
        }
    }

    fun endActionUiSuppression(strong: Boolean = false) {
        main.post {
            if (strong) {
                strongSuppressionCounter = (strongSuppressionCounter - 1).coerceAtLeast(0)
            } else {
                suppressionCounter = (suppressionCounter - 1).coerceAtLeast(0)
            }
            applyActionUiState()
        }
    }

    fun releaseAllSuppression() {
        main.post {
            suppressionCounter = 0
            strongSuppressionCounter = 0
            applyActionUiState(force = true)
        }
    }

    fun prepareForObservationPulse() {
        main.post {
            strongSuppressionCounter = 1
            applyActionUiState(force = true)
            main.postDelayed({
                strongSuppressionCounter = 0
                applyActionUiState(force = true)
            }, 180L)
        }
    }

    private fun applyActionUiState(force: Boolean = false) {
        val view = bubbleView ?: return
        val now = System.currentTimeMillis()
        if (!force && pendingUiApply) return
        if (!force && now - lastUiApplyAt < 90L) {
            pendingUiApply = true
            main.postDelayed({
                pendingUiApply = false
                applyActionUiState(force = true)
            }, 96L)
            return
        }
        lastUiApplyAt = now
        try {
            val alpha = when {
                strongSuppressionCounter > 0 -> 0.08f
                suppressionCounter > 0 -> 0.45f
                else -> 1f
            }
            // Keep the view visible to avoid flicker / re-attach feeling.
            // We only fade aggressively during strong suppression.
            view.visibility = View.VISIBLE
            view.alpha = alpha
        } catch (e: Exception) {
            Log.e(TAG, "applyActionUiState failed: ${e.message}", e)
        }
    }

    fun temporarilyHideForAction(
        context: Context,
        hideMs: Long = 500L,
        action: () -> Unit
    ) {
        main.post {
            beginActionUiSuppression()
            main.postDelayed({
                try {
                    action()
                } catch (e: Exception) {
                    Log.e(TAG, "temporarilyHideForAction action failed: ${e.message}", e)
                }
            }, 40L)
            main.postDelayed({
                endActionUiSuppression()
            }, hideMs.coerceAtLeast(120L))
        }
    }

    private fun createDefaultLayoutParams(context: Context): WindowManager.LayoutParams {
        // FIX: Use 1% of screen height instead of 4.5% to place the island near the top edge.
        // Old value (0.045f) produced ~86px on a 1920px screen, visibly pushing it down.
        // Floor is 8px to avoid clipping into the status bar notch area.
        topOffsetPx = (getScreenHeight(context) * 0.01f).toInt().coerceAtLeast(8)
        lastY = topOffsetPx

        return WindowManager.LayoutParams(
            OVERLAY_WIDTH_PX,
            OVERLAY_HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = topOffsetPx
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun removeBubbleView(wm: WindowManager) {
        bubbleView?.let { view ->
            try {
                wm.removeViewImmediate(view)
            } catch (_: Exception) {
                try {
                    wm.removeView(view)
                } catch (_: Exception) {
                }
            }
        }
        bubbleView = null
        bubbleParams = null
        windowManager = null
    }

    private fun destroyOwner() {
        try {
            bubbleOwner?.destroy()
        } catch (_: Exception) {
        }
        bubbleOwner = null
    }

    private class BubbleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val viewModelStoreInternal = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val viewModelStore: ViewModelStore
            get() = viewModelStoreInternal

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun performRestore(bundle: Bundle?) {
            savedStateController.performRestore(bundle)
        }

        fun handleOnCreate() {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun handleOnStart() {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        fun handleOnResume() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStoreInternal.clear()
        }
    }
}
