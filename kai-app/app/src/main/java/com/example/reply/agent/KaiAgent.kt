package com.example.reply.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

object KaiAgent {

    private const val TAG = "KaiAgent"

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null

    fun start(context: Context) {
        if (running) return
        running = true
        appContext = context.applicationContext
        Log.d(TAG, "KaiAgent START")
        loop()
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "KaiAgent STOP")
    }

    fun manualPulse(context: Context? = appContext, reason: String = "manual") {
        appContext = context?.applicationContext ?: appContext
        Log.d(TAG, "KaiAgent MANUAL PULSE: $reason")
        observe(appContext)
        decide(appContext, reason)
    }

    private fun loop() {
        if (!running) return

        val ctx = appContext
        observe(ctx)
        decide(ctx, "loop")

        handler.postDelayed({ loop() }, 2500)
    }

    private fun observe(context: Context?) {
        Log.d(TAG, "Kai observing...")
    }

    private fun decide(context: Context?, reason: String) {
        Log.d(TAG, "Kai thinking... reason=$reason")
    }
}