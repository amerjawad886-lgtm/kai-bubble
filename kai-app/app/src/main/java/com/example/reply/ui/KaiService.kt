package com.example.reply.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.reply.agent.KaiObservationRuntime

class KaiService : Service() {

    override fun onCreate() {
        super.onCreate()
        startKaiForeground()

        // Keep only the bridge available here. Observation ownership stays in the runtime/coordinator.
        KaiObservationRuntime.ensureBridge(applicationContext)

        if (Settings.canDrawOverlays(this)) {
            KaiBubbleManager.show(this)
            KaiObservationRuntime.requestImmediateDump()
        }
    }

    private fun startKaiForeground() {
        val channelId = "kai_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Kai Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "Kai overlay and runtime service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Kai is alive")
                .setContentText("Dynamic Island is running")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KaiObservationRuntime.ensureBridge(applicationContext)

        if (Settings.canDrawOverlays(this)) {
            if (!KaiBubbleManager.isShowing()) {
                KaiBubbleManager.show(this)
            }
            KaiObservationRuntime.requestImmediateDump()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            KaiBubbleManager.hide(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
