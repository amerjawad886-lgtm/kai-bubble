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

    @Volatile
    private var isSpeaking: Boolean = false

    @Volatile
    private var isStopping: Boolean = false

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var activeCall: Call? = null

    private val seq = AtomicInteger(0)
    private var lastAudioFile: File? = null

    fun speakingNow(): Boolean = isSpeaking || isStopping

    fun speechFullyCompleted(): Boolean = !isSpeaking && !isStopping && player == null && activeCall == null

    private fun sanitizeForSpeech(text: String): String {
        return text
            .replace(Regex("""[`*_#>\[\]\(\)"]"""), " ")
            .replace(Regex("""[•●■◆▶︎]+"""), " ")
            .replace("…", ". ")
            .replace("•", " ")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("\n", ". ")
            .replace("\r", " ")
            .replace("\t", " ")
            .replace(Regex("""https?://\S+"""), " ")
            .replace(Regex("""\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[A-Za-z]{2,}\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

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
                return cleaned
                    .substring((cutIndex.takeIf { it >= 0 }?.plus(1)) ?: cleaned.length)
                    .trim()
                    .ifBlank { cleaned }
            }
        }

        return cleaned
    }

    private fun cleanupVoiceArtifacts(text: String): String {
        return text
            .replace(Regex("""\b([A-Za-z])\s+([A-Za-z])\b"""), "$1$2")
            .replace(Regex("""\s+([,?.!])"""), "$1")
            .replace(Regex("""([,?.!]){2,}""")) { match ->
                match.value.first().toString()
            }
            .trim()
    }

    private fun chooseVoice(tone: Tone): String {
        return when (tone) {
            Tone.KAI -> "sage"
            Tone.BUBBLE_READ -> "sage"
            Tone.SYSTEM -> "sage"
        }
    }

    private fun maxLengthForTone(tone: Tone): Int {
        return when (tone) {
            Tone.KAI -> 420
            Tone.BUBBLE_READ -> 680
            Tone.SYSTEM -> 180
        }
    }

    private fun prepareSpeechText(text: String, tone: Tone): String {
        var clean = sanitizeForSpeech(text)
        clean = removeRepeatedIntro(clean)
        clean = cleanupVoiceArtifacts(clean)

        if (tone == Tone.KAI) {
            clean = clean
                .replace("!", ".")
                .replace(Regex("""\?{2,}"""), "?")
        }

        val maxLen = maxLengthForTone(tone)
        if (clean.length > maxLen) {
            clean = clean.take(maxLen).trim()
        }

        return clean
    }

    private fun cleanupOldAudio(fileToKeep: File?) {
        try {
            if (lastAudioFile != null && lastAudioFile != fileToKeep && lastAudioFile?.exists() == true) {
                lastAudioFile?.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun speak(
        context: Context,
        text: String,
        tone: Tone = Tone.KAI,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val key = BuildConfig.OPENAI_API_KEY.trim()
        val spokenText = prepareSpeechText(text, tone)

        if (key.isBlank()) {
            onError?.invoke("missing api key")
            onDone?.invoke()
            return
        }

        if (spokenText.isBlank()) {
            onError?.invoke("empty speech input")
            onDone?.invoke()
            return
        }

        stop()

        val mySeq = seq.incrementAndGet()
        val voice = chooseVoice(tone)

        val payload = JSONObject()
            .put("model", KaiModelRouter.forTask(KaiTask.TTS))
            .put("voice", voice)
            .put("format", "mp3")
            .put("input", spokenText)
            .toString()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON))
            .build()

        val call = client.newCall(request)
        activeCall = call

        var sessionFinalized = false
        fun markFinal(action: () -> Unit) {
            if (sessionFinalized) return
            sessionFinalized = true
            action()
        }

        fun stillCurrent(): Boolean = seq.get() == mySeq

        fun finishCurrent(
            error: String? = null,
            invokeDone: Boolean = true
        ) {
            if (!stillCurrent()) return
            markFinal {
                isSpeaking = false
                activeCall = null
                if (error != null) onError?.invoke(error)
                if (invokeDone) onDone?.invoke()
            }
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!stillCurrent()) return
                main.post {
                    finishCurrent(e.message ?: "network failure")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!stillCurrent()) {
                    response.close()
                    return
                }

                response.use { res ->
                    if (!res.isSuccessful) {
                        main.post {
                            finishCurrent("http ${res.code}")
                        }
                        return
                    }

                    val bytes = res.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        main.post {
                            finishCurrent("empty audio")
                        }
                        return
                    }

                    val file = File(context.cacheDir, "kai_voice_$mySeq.mp3")
                    try {
                        FileOutputStream(file).use { it.write(bytes) }
                    } catch (e: Exception) {
                        main.post {
                            finishCurrent("file write error: ${e.message ?: "unknown"}")
                        }
                        return
                    }

                    main.post {
                        if (!stillCurrent()) {
                            try {
                                file.delete()
                            } catch (_: Exception) {
                            }
                            return@post
                        }

                        cleanupOldAudio(file)
                        lastAudioFile = file

                        val oldPlayer = player
                        player = null
                        try {
                            oldPlayer?.release()
                        } catch (_: Exception) {
                        }

                        val newPlayer = MediaPlayer()
                        player = newPlayer

                        try {
                            newPlayer.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )

                            newPlayer.setDataSource(file.absolutePath)

                            newPlayer.setOnPreparedListener { mp ->
                                if (!stillCurrent() || player !== mp) {
                                    try {
                                        mp.release()
                                    } catch (_: Exception) {
                                    }
                                    return@setOnPreparedListener
                                }

                                isStopping = false
                                isSpeaking = true
                                onStart?.invoke()

                                try {
                                    mp.start()
                                } catch (e: Exception) {
                                    if (player === mp) player = null
                                    try {
                                        mp.release()
                                    } catch (_: Exception) {
                                    }
                                    finishCurrent(e.message ?: "start playback error")
                                }
                            }

                            newPlayer.setOnCompletionListener { mp ->
                                if (!stillCurrent() || player !== mp) {
                                    try {
                                        mp.release()
                                    } catch (_: Exception) {
                                    }
                                    return@setOnCompletionListener
                                }

                                isSpeaking = false
                                isStopping = false

                                if (player === mp) player = null
                                try {
                                    mp.release()
                                } catch (_: Exception) {
                                }
                                finishCurrent(invokeDone = true)
                            }

                            newPlayer.setOnErrorListener { mp, _, _ ->
                                if (!stillCurrent() || player !== mp) {
                                    try {
                                        mp.release()
                                    } catch (_: Exception) {
                                    }
                                    return@setOnErrorListener true
                                }

                                isSpeaking = false
                                isStopping = false

                                if (player === mp) player = null
                                try {
                                    mp.release()
                                } catch (_: Exception) {
                                }
                                finishCurrent("MediaPlayer error")
                                true
                            }

                            newPlayer.prepareAsync()
                        } catch (e: Exception) {
                            if (player === newPlayer) player = null
                            try {
                                newPlayer.release()
                            } catch (_: Exception) {
                            }
                            finishCurrent(e.message ?: "player setup error")
                        }
                    }
                }
            }
        })
    }

    fun stop() {
        seq.incrementAndGet()

        isStopping = true

        try {
            activeCall?.cancel()
        } catch (_: Exception) {
        }
        activeCall = null

        val currentPlayer = player
        player = null

        try {
            currentPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            currentPlayer?.release()
        } catch (_: Exception) {
        }

        isSpeaking = false
        isStopping = false
    }

    fun resetTransientStateForNewRun() {
        // Idempotent runtime reset: safe to call multiple times before a run.
        stop()
    }
}