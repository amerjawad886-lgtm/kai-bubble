package com.example.reply.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object KaiScreenCaptureBridge {

    const val SCREEN_CAPTURE_REQUEST_CODE = 9917

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var lastKnownWidth: Int = 0

    @Volatile
    private var lastKnownHeight: Int = 0

    @Volatile
    private var lastKnownDensity: Int = 0

    fun createCaptureIntent(activity: Activity): Intent {
        val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun onPermissionGranted(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (data == null) return false

        val appContext = context.applicationContext
        val mgr = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: return false

        mediaProjection?.stop()
        mediaProjection = projection

        createOrRecreateSurface(appContext)
        return true
    }

    fun isReady(): Boolean {
        return mediaProjection != null && imageReader != null
    }

    fun release() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    fun hasRecentFrame(): Boolean {
        val reader = imageReader ?: return false
        return try {
            val image = reader.acquireLatestImage()
            if (image != null) {
                image.close()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun createOrRecreateSurface(context: Context) {
        val projection = mediaProjection ?: return

        val metrics = displayMetrics(context)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)

        lastKnownWidth = width
        lastKnownHeight = height
        lastKnownDensity = density

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        imageReader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection.createVirtualDisplay(
            "KaiLiveCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun displayMetrics(context: Context): DisplayMetrics {
        val out = DisplayMetrics()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            out.widthPixels = bounds.width()
            out.heightPixels = bounds.height()
            out.densityDpi = context.resources.displayMetrics.densityDpi
            out
        } else {
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(out)
            out
        }
    }
}