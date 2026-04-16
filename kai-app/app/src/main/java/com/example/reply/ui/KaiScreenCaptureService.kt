package com.example.reply.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.reply.agent.KaiLiveVisionRuntime
import com.example.reply.agent.KaiScreenCaptureBridge

/**
 * Dedicated foreground service for MediaProjection screen capture.
 *
 * Android requires a foreground service with type "mediaProjection" to be
 * running before MediaProjection.createVirtualDisplay() is called.
 * This service owns that lifecycle. It is started explicitly from the
 * live-vision entry path (eye button) and stopped when capture ends.
 */
class KaiScreenCaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        startCaptureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        if (resultCode != 0 && projectionData != null) {
            val ok = KaiScreenCaptureBridge.onPermissionGranted(
                context = this,
                resultCode = resultCode,
                data = projectionData
            )
            if (ok) {
                KaiLiveVisionRuntime.refreshFromCapture()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        KaiScreenCaptureBridge.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCaptureForeground() {
        val channelId = "kai_capture_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Kai Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "Active while Kai live vision is capturing the screen"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Kai live vision")
                .setContentText("Screen capture active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .build()

        startForeground(2, notification)
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            projectionData: Intent
        ): Intent = Intent(context, KaiScreenCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_DATA, projectionData)
        }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, KaiScreenCaptureService::class.java)
    }
}
