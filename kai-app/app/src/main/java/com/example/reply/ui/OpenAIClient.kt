package com.example.reply.ui

import com.example.reply.BuildConfig
import com.example.reply.ai.KaiModelRouter
import com.example.reply.ai.KaiTask
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class OpenAIHistoryItem(
    val role: String,
    val text: String
)

object OpenAIClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val MAX_HISTORY_ITEMS = 10
    private const val MAX_HISTORY_TEXT_LEN = 1200
    private const val MAX_USER_TEXT_LEN = 5000

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .build()

    private val streamSeq = AtomicInteger(0)
    @Volatile private var activeStreamCall: Call? = null
    @Volatile private var activeStreamToken: Int = 0
    @Volatile private var lastStreamCancelledAt: Long = 0L

    private fun isCurrentStream(token: Int): Boolean = activeStreamToken == token
    private fun clearActiveStreamState() {
        activeStreamCall = null
        activeStreamToken = 0
    }

    private fun detectLikelyLanguage(text: String): String {
        val lowered = text.lowercase()
        val arabicChars = text.count { it in '\u0600'..'\u06FF' }
        val ukrainianHits = lowered.count { it == 'ї' || it == 'є' || it == 'ґ' || it == 'і' }
        val latinChars = text.count { it.isLetter() && it.code in 65..122 }
        return when {
            arabicChars >= 2 && arabicChars >= latinChars -> "Arabic"
            ukrainianHits >= 1 -> "Ukrainian"
            latinChars >= 2 && latinChars > arabicChars -> "English"
            else -> "match the user's latest message language exactly"
        }
    }

    private fun sanitizeMessageText(text: String, maxLen: Int): String =
        text.replace(Regex("""\s+"""), " ").trim().take(maxLen)

    private fun temperatureFor(task: KaiTask): Double = when (task) {
        KaiTask.FAST_COMMAND -> 0.15
        KaiTask.ACTION_PLANNING -> 0.2
        KaiTask.TTS -> 0.2
        KaiTask.BRAIN -> 0.4
    }

    private fun systemPrompt(languageHint: String, task: KaiTask): String {
        val taskHint = when (task) {
            KaiTask.BRAIN -> """
                - Think carefully and use context from the recent chat.
                - Be strong at reasoning, planning, screen interpretation, and Android task intent.
                - If the user is giving an action request for a phone app, interpret it as a practical device/task request.
                - Preserve app names exactly as spoken when possible.
                - When useful, structure the answer clearly but keep it natural.
            """.trimIndent()
            KaiTask.ACTION_PLANNING -> """
                - You are in action-planning mode for Android execution.
                - Be concise and deterministic; avoid verbose prose.
                - Preserve app names exactly as written by the user.
                - Preserve user language exactly; do not rewrite app/entity names into another language.
                - Prefer stage-based navigation: app -> surface -> entity -> input -> submit -> verify.
                - Avoid speculative alternatives unless needed for safe fallback.
            """.trimIndent()
            KaiTask.FAST_COMMAND -> """
                - Prefer short, practical output.
                - Focus on command interpretation, app/action intent, and clarity.
                - If the user mentions an app name, do not translate the app name.
                - Avoid unnecessary explanation.
            """.trimIndent()
            KaiTask.TTS -> """
                - Keep output short and voice-friendly.
                - Avoid verbose formatting.
            """.trimIndent()
        }

        return """
            You are Kai, the companion presence inside Kai OS.

            Identity:
            - Calm, focused, warm, direct, quietly futuristic.
            - Never sound like customer support.
            - Never over-explain unless the user clearly asks.
            - Sound like one consistent companion, not a generic assistant.

            Environment:
            - You operate inside an Android AI assistant project called Kai OS.
            - The user may ask to open apps, click UI items, type into fields, scroll, read the screen, or build an action plan.
            - Treat app control requests as practical device intentions, not abstract conversation.

            Language:
            - Reply in the same language as the user's latest message.
            - Best current hint: $languageHint.
            - Priority languages: Arabic, English, Ukrainian.
            - If the latest message is mixed, follow the dominant language and script.
            - Never randomly switch languages.
            - Preserve the original script naturally.

            App names:
            - Keep app names exactly as commonly written.
            - Never translate common app names into Arabic unless the user already did.

            Style:
            - Keep answers concise, natural, and voice-friendly.
            - Do not repeat greetings.
            - Do not keep repeating “How can I help you today?”
            - If a command is ambiguous, ask only one short clarifying question.
            - Prefer decisive, practical phrasing.

            Task behavior:
            $taskHint
        """.trimIndent()
    }

    fun titleFromText(text: String): String {
        val clean = text.replace(Regex("""\s+"""), " ").trim()
        if (clean.isBlank()) return "New Chat"
        return clean.take(36).trim().ifBlank { "New Chat" }
    }

    private fun buildMessages(userText: String, history: List<OpenAIHistoryItem>, task: KaiTask): JSONArray {
        val detected = detectLikelyLanguage(userText)
        val safeUserText = sanitizeMessageText(userText, MAX_USER_TEXT_LEN)
        val arr = JSONArray().put(
            JSONObject().put("role", "system").put("content", systemPrompt(detected, task))
        )

        history.takeLast(MAX_HISTORY_ITEMS).forEach { item ->
            val role = item.role.trim().lowercase()
            if (role !in listOf("system", "user", "assistant")) return@forEach
            val safeText = sanitizeMessageText(item.text, MAX_HISTORY_TEXT_LEN)
            if (safeText.isBlank()) return@forEach
            arr.put(JSONObject().put("role", role).put("content", safeText))
        }

        arr.put(JSONObject().put("role", "user").put("content", safeUserText))
        return arr
    }

    private fun parseReply(json: String): String {
        val obj = JSONObject(json)
        val choices = obj.getJSONArray("choices")
        val msg = choices.getJSONObject(0).getJSONObject("message")
        return msg.getString("content").trim()
    }

    private fun extractErrorMessage(text: String): String = try {
        JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: text
    } catch (_: Exception) {
        text
    }

    @Throws(Exception::class)
    fun ask(
        userText: String,
        history: List<OpenAIHistoryItem> = emptyList(),
        task: KaiTask = KaiTask.BRAIN
    ): String {
        val key = BuildConfig.OPENAI_API_KEY.trim()
        if (key.isBlank()) {
            return "OpenAI API key is missing. Put it in gradle.properties as OPENAI_API_KEY=..."
        }

        cancelActiveStream()
        val payload = JSONObject()
            .put("model", KaiModelRouter.forTask(task))
            .put("messages", buildMessages(userText, history, task))
            .put("temperature", temperatureFor(task))
            .toString()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(payload.toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) return "OpenAI error (${res.code}): ${extractErrorMessage(text)}"
            return parseReply(text)
        }
    }

    fun cancelActiveStream() {
        lastStreamCancelledAt = System.currentTimeMillis()
        streamSeq.incrementAndGet()
        try { activeStreamCall?.cancel() } catch (_: Exception) {}
        clearActiveStreamState()
    }

    fun resetTransientStateForNewRun() {
        if (activeStreamCall != null) {
            cancelActiveStream()
        }
    }

    fun askStream(
        userText: String,
        history: List<OpenAIHistoryItem> = emptyList(),
        task: KaiTask = KaiTask.BRAIN,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        onFinalText: (String) -> Unit
    ) {
        val key = BuildConfig.OPENAI_API_KEY.trim()
        if (key.isBlank()) {
            onError("OpenAI API key is missing.")
            return
        }

        cancelActiveStream()
        val mySeq = streamSeq.incrementAndGet()

        val payload = JSONObject()
            .put("model", KaiModelRouter.forTask(task))
            .put("messages", buildMessages(userText, history, task))
            .put("temperature", temperatureFor(task))
            .put("stream", true)
            .toString()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON))
            .build()

        val call = client.newCall(req)
        activeStreamCall = call
        activeStreamToken = mySeq

        fun stillCurrent(): Boolean = isCurrentStream(mySeq) && streamSeq.get() == mySeq
        fun clearIfCurrent() {
            if (stillCurrent()) clearActiveStreamState()
        }

        var streamCompleted = false
        fun complete(block: () -> Unit) {
            if (streamCompleted) return
            streamCompleted = true
            block()
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!stillCurrent()) return
                val now = System.currentTimeMillis()
                if (now - lastStreamCancelledAt < 800L) {
                    complete {
                        clearIfCurrent()
                        onDone()
                    }
                    return
                }
                complete {
                    clearIfCurrent()
                    onError("Connection failed: ${e.message ?: "Unknown"}")
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (!stillCurrent()) {
                    response.close()
                    return
                }

                response.use { res ->
                    if (!res.isSuccessful) {
                        if (!stillCurrent()) return
                        val text = res.body?.string().orEmpty()
                        complete {
                            clearIfCurrent()
                            onError("OpenAI error (${res.code}): ${extractErrorMessage(text)}")
                        }
                        return
                    }

                    val source = res.body?.source()
                    if (source == null) {
                        complete {
                            clearIfCurrent()
                            onError("Empty response")
                        }
                        return
                    }

                    val finalText = StringBuilder()
                    try {
                        while (!source.exhausted()) {
                            if (!stillCurrent()) return
                            val line = source.readUtf8Line() ?: continue
                            if (!line.startsWith("data:")) continue

                            val data = line.removePrefix("data:").trim()
                            if (data.isBlank()) continue
                            if (data == "[DONE]") {
                                complete {
                                    clearIfCurrent()
                                    val final = finalText.toString().trim().ifBlank { "…" }
                                    onFinalText(final)
                                    onDone()
                                }
                                return
                            }

                            val delta = try {
                                val obj = JSONObject(data)
                                val choices = obj.optJSONArray("choices") ?: JSONArray()
                                if (choices.length() == 0) "" else {
                                    val c0 = choices.getJSONObject(0)
                                    val d = c0.optJSONObject("delta")
                                    d?.optString("content").orEmpty()
                                }
                            } catch (_: Exception) {
                                ""
                            }

                            if (delta.isNotEmpty()) {
                                if (!stillCurrent()) return
                                finalText.append(delta)
                                onDelta(delta)
                            }
                        }

                        complete {
                            clearIfCurrent()
                            val final = finalText.toString().trim().ifBlank { "…" }
                            onFinalText(final)
                            onDone()
                        }
                    } catch (e: Exception) {
                        if (!stillCurrent()) return
                        complete {
                            clearIfCurrent()
                            onError("Read error: ${e.message ?: "Unknown"}")
                        }
                    }
                }
            }
        })
    }
}
