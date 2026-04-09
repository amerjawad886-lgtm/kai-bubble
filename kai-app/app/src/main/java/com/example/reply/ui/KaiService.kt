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
import com.example.reply.agent.KaiAgentController
import com.example.reply.agent.KaiObservationRuntime

class KaiService : Service() {

    override fun onCreate() {
        super.onCreate()
        startKaiForeground()

        // Keep the observation bridge ready at service startup.
        KaiAgentController.ensureRuntimeObservationBridge(applicationContext)

        // If monitoring was already intended to be active, keep observation runtime alive.
        if (KaiAgentController.getSnapshot().isRunning) {
            KaiObservationRuntime.startWatching(immediateDump = true)
        }

        if (Settings.canDrawOverlays(this)) {
            KaiBubbleManager.show(this)
        }
    }

    private fun startKaiForeground() {
        val channelId = "kai_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Kai Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Kai is alive")
                .setContentText("Dynamic Island is running")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()

        startForeground(1, notification)
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