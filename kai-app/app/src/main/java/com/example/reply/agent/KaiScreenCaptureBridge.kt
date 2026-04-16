package com.example.reply.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class KaiCapturedFrame(
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val meanLuma: Float,
    val contrast: Float,
    val edgeDensity: Float,
    val frameHash: Long
)

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

        release()
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

    /**
     * يلتقط آخر frame متاح ويستخرج منه pixel-level semantics منخفضة المستوى:
     * mean luma / contrast / edge density / frame hash.
     *
     * هذا ليس OCR ولا semantic UI understanding كامل،
     * لكنه foundation حقيقي للرؤية البصرية المبنية على البيكسل.
     */
    fun acquireLatestFrame(): KaiCapturedFrame? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            analyzeImage(image)
        } catch (_: Exception) {
            null
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
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
            3
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

    private fun analyzeImage(image: Image): KaiCapturedFrame {
        val plane = image.planes.firstOrNull()
            ?: return KaiCapturedFrame(
                width = image.width,
                height = image.height,
                timestampNanos = image.timestamp,
                meanLuma = 0f,
                contrast = 0f,
                edgeDensity = 0f,
                frameHash = 0L
            )

        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = max(plane.pixelStride, 4)

        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)

        val sampleCols = min(48, width)
        val sampleRows = min(48, height)
        val stepX = max(width / sampleCols, 1)
        val stepY = max(height / sampleRows, 1)

        val lumaGrid = Array(sampleRows) { FloatArray(sampleCols) }

        var sum = 0f
        var count = 0
        var hash = 1125899906842597L

        for (row in 0 until sampleRows) {
            val y = min(row * stepY, height - 1)
            for (col in 0 until sampleCols) {
                val x = min(col * stepX, width - 1)
                val index = y * rowStride + x * pixelStride
                if (index + 2 >= buffer.limit()) continue

                val r = buffer.get(index).toInt() and 0xFF
                val g = buffer.get(index + 1).toInt() and 0xFF
                val b = buffer.get(index + 2).toInt() and 0xFF

                val luma = (0.2126f * r) + (0.7152f * g) + (0.0722f * b)
                lumaGrid[row][col] = luma
                sum += luma
                count++

                hash = (hash * 31L) xor ((r shl 16) or (g shl 8) or b).toLong()
            }
        }

        if (count == 0) {
            return KaiCapturedFrame(
                width = width,
                height = height,
                timestampNanos = image.timestamp,
                meanLuma = 0f,
                contrast = 0f,
                edgeDensity = 0f,
                frameHash = 0L
            )
        }

        val mean = sum / count.toFloat()

        var varianceSum = 0f
        var edgeSum = 0f
        var edgeCount = 0

        for (row in 0 until sampleRows) {
            for (col in 0 until sampleCols) {
                val current = lumaGrid[row][col]
                val diff = current - mean
                varianceSum += diff * diff

                if (col + 1 < sampleCols) {
                    edgeSum += abs(current - lumaGrid[row][col + 1])
                    edgeCount++
                }
                if (row + 1 < sampleRows) {
                    edgeSum += abs(current - lumaGrid[row + 1][col])
                    edgeCount++
                }
            }
        }

        val contrast = varianceSum / count.toFloat()
        val edgeDensity = if (edgeCount > 0) {
            (edgeSum / edgeCount.toFloat()) / 255f
        } else {
            0f
        }

        return KaiCapturedFrame(
            width = width,
            height = height,
            timestampNanos = image.timestamp,
            meanLuma = mean,
            contrast = contrast,
            edgeDensity = edgeDensity,
            frameHash = hash
        )
    }
}