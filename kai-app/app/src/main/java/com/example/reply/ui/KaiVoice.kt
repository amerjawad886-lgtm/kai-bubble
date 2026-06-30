package com.example.reply.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.example.reply.BuildConfig
import com.example.reply.ai.KaiModelRouter
import com.example.reply.ai.KaiTask
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object KaiVoice {

    enum class Tone {
        KAI,
        BUBBLE_READ,
        SYSTEM
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(48, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)

    @Volatile private var isSpeaking: Boolean = false
    @Volatile private var isStopping: Boolean = false
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var lastAudioFile: File? = null
    @Volatile private var lastStopAt: Long = 0L

    fun speakingNow(): Boolean = isSpeaking || isStopping

    fun speechFullyCompleted(): Boolean =
        !isSpeaking && !isStopping && player == null && activeCall == null

    fun recentlyStopped(withinMs: Long = 320L): Boolean =
        System.currentTimeMillis() - lastStopAt <= withinMs

    private fun sanitizeForSpeech(text: String): String =
        text.replace(Regex("""[`*_#>\[\]\(\)"]"""), " ")
            .replace(Regex("""[•●■◆▶︎]+"""), " ")
            .replace("…", ". ")
            .replace("•", " ")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("\n", ". ")
            .replace("\r", " ")
            .replace("\t", " ")
            .replace(Regex("""https?://\S+"""), " ")
            .replace(
                Regex("""\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[A-Za-z]{2,}\b"""),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun removeRepeatedIntro(text: String): String {
        val cleaned = text.trim()
        val badStarts = listOf(
            "كيف يمكنني مساعدتك اليوم",
            "كيف أساعدك اليوم",
            "how can i help you today",
            "how may i assist you today",
            "how can i assist you today",
            "чим я можу допомогти сьогодні"
        )
        val lowered = cleaned.lowercase()
        for (s in badStarts) {
            if (lowered.startsWith(s)) {
                val cutIndex = cleaned.indexOfFirst { it == '.' || it == '!' || it == '?' }
                return cleaned.substring(
                    (cutIndex.takeIf { it >= 0 }?.plus(1)) ?: cleaned.length
                ).trim().ifBlank { cleaned }
            }
        }
        return cleaned
    }

    private fun cleanupVoiceArtifacts(text: String): String =
        text.replace(Regex("""\b([A-Za-z])\s+([A-Za-z])\b"""), "$1$2")
            .replace(Regex("""\s+([,?.!])"""), "$1")
            .replace(Regex("""([,?.!]){2,}""")) { it.value.first().toString() }
            .trim()

    private fun chooseVoice(tone: Tone): String = "sage"

    private fun maxLengthForTone(tone: Tone): Int = when (tone) {
        Tone.KAI -> 420
        Tone.BUBBLE_READ -> 680
        Tone.SYSTEM -> 180
    }

    private fun prepareSpeechText(text: String, tone: Tone): String {
        var clean = sanitizeForSpeech(text)
        clean = removeRepeatedIntro(clean)
        clean = cleanupVoiceArtifacts(clean)
        if (tone == Tone.KAI) {
            clean = clean.replace("!", ".").replace(Regex("""\?{2,}"""), "?")
        }
        val maxLen = maxLengthForTone(tone)
        return if (clean.length > maxLen) clean.take(maxLen).trim() else clean
    }

    private fun cleanupOldAudio(fileToKeep: File?) {
        try {
            val old = lastAudioFile
            if (old != null && old != fileToKeep && old.exists()) old.delete()
        } catch (_: Exception) {
        }
    }

    private fun clearPlaybackState(releasePlayer: Boolean = true) {
        val currentPlayer = player
        player = null
        if (releasePlayer) {
            try { currentPlayer?.stop() } catch (_: Exception) {}
            try { currentPlayer?.release() } catch (_: Exception) {}
        }
        activeCall = null
        isSpeaking = false
        isStopping = false
    }

    fun speak(
        context: Context,
        text: String,
        tone: Tone = Tone.KAI,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        // 🔥 TTS Service: Currently not configured after Gemini migration.
        // To enable TTS, configure a dedicated TTS service (e.g., Google Cloud Text-to-Speech,
        // Azure Cognitive Services, or restore OpenAI with separate OPENAI_API_KEY).
        val spokenText = prepareSpeechText(text, tone)

        if (spokenText.isBlank()) {
            onError?.invoke("empty speech input")
            onDone?.invoke()
            return
        }

        // Graceful fallback: TTS is currently unavailable
        // The app continues to function without audio output
        main.post {
            clearPlaybackState(releasePlayer = false)
            onError?.invoke("TTS service not configured (Gemini migration in progress)")
            onDone?.invoke()
        }
    }

    fun stop() {
        seq.incrementAndGet()
        lastStopAt = System.currentTimeMillis()
        isStopping = true
        try { activeCall?.cancel() } catch (_: Exception) {}
        clearPlaybackState(releasePlayer = true)
    }

    fun resetTransientStateForNewRun() {
        if (speakingNow() || activeCall != null || player != null) {
            stop()
        }
    }
}
