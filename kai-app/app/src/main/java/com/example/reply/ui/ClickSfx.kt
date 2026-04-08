package com.example.reply.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.reply.R

object ClickSfx {

    private var pool: SoundPool? = null
    private var soundId: Int = 0
    private var loaded = false
    private var playQueued = false
    private var lastPlayAt = 0L

    fun preload(context: Context) {
        if (pool != null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        soundId = pool?.load(context.applicationContext, R.raw.click_soft, 1) ?: 0

        pool?.setOnLoadCompleteListener { _, id, status ->
            if (id == soundId && status == 0) {
                loaded = true
                if (playQueued) {
                    playQueued = false
                    pool?.play(soundId, 0.42f, 0.42f, 1, 0, 1f)
                }
            }
        }
    }

    fun play(context: Context, volume: Float = 0.42f) {
        val now = System.currentTimeMillis()
        if (now - lastPlayAt < 45) return
        lastPlayAt = now

        if (pool == null) preload(context)

        if (!loaded) {
            playQueued = true
            return
        }

        val v = volume.coerceIn(0.08f, 0.65f)
        pool?.play(soundId, v, v, 1, 0, 1f)
    }

    fun release() {
        try {
            pool?.release()
        } catch (_: Exception) {
        }
        pool = null
        soundId = 0
        loaded = false
        playQueued = false
    }
}