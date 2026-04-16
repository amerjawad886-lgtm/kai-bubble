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
import com.example.reply.agent.KaiLiveObservationRuntime
import com.example.reply.agent.KaiLiveVisionRuntime
import com.example.reply.agent.KaiScreenCaptureBridge

/**
 * Dedicated foreground service for MediaProjection screen capture.
 *
 * This service owns the capture session lifecycle only.
 * It must be started explicitly after the permission result is granted.
 */
class KaiScreenCaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        startCaptureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

                if (resultCode == 0 || projectionData == null) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }

                val ok = KaiScreenCaptureBridge.onPermissionGranted(
                    context = this,
                    resultCode = resultCode,
                    data = projectionData
                )

                if (ok) {
                    KaiLiveVisionRuntime.refreshFromCapture()
                    KaiLiveObservationRuntime.ensureBridge(applicationContext)
                    KaiLiveObservationRuntime.requestImmediateDump()
                } else {
                    stopSelfSafely()
                }
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
        val channelId = CHANNEL_ID

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

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "kai_capture_channel"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val ACTION_START = "com.example.reply.action.KAI_CAPTURE_START"
        private const val ACTION_STOP = "com.example.reply.action.KAI_CAPTURE_STOP"

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            projectionData: Intent
        ): Intent = Intent(context, KaiScreenCaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_DATA, projectionData)
        }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, KaiScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
