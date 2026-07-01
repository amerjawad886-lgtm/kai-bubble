package com.example.reply.ui

import android.util.Log
import com.example.reply.BuildConfig
import com.example.reply.ai.KaiModelRouter
import com.example.reply.ai.KaiTask
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class GeminiHistoryItem(
    val role: String,
    val text: String
)

object KaiAIClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val MAX_HISTORY_ITEMS = 10
    private const val MAX_HISTORY_TEXT_LEN = 1200
    private const val MAX_USER_TEXT_LEN = 5000

    // Clean Gemini-only client. This client is intentionally isolated from Supabase, legacy OpenAI,
    // and any external shared interceptors or global auth wiring.
    private val geminiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val cleanRequest = chain.request().newBuilder()
                .headers(okhttp3.Headers.headersOf("Content-Type", "application/json"))
                .build()
            chain.proceed(cleanRequest)
        }
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
        KaiTask.VISION_STATUS -> 0.1
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
            KaiTask.VISION_STATUS -> """
                - Summarize screen/vision state briefly and operationally.
                - Prefer compact diagnostics over prose.
                - Do not invent semantic understanding that the runtime has not proven.
                - Keep it short, factual, and execution-oriented.
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

    private fun buildGeminiPayload(userText: String, history: List<GeminiHistoryItem>, task: KaiTask, systemInstruction: String): JSONObject {
        val contents = JSONArray()

        history.takeLast(MAX_HISTORY_ITEMS).forEach { item ->
            val safeText = sanitizeMessageText(item.text, MAX_HISTORY_TEXT_LEN)
            if (safeText.isNotBlank()) {
                contents.put(JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", safeText))))
            }
        }

        val safeUserText = sanitizeMessageText(userText, MAX_USER_TEXT_LEN)
        contents.put(JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", safeUserText))))

        return JSONObject()
            .put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            .put("generationConfig", JSONObject().put("temperature", temperatureFor(task)))
    }

    private fun parseGeminiReply(json: String): String {
        val obj = JSONObject(json)
        val candidates = obj.getJSONArray("candidates")
        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        return parts.getJSONObject(0).getString("text").trim()
    }

    private fun normalizeGeminiApiKey(rawKey: String): String =
        rawKey.replace(Regex("(?i)^Bearer\\s+"), "").trim()

    private fun buildGeminiUrl(modelName: String, endpointPath: String, apiKey: String): HttpUrl {
        return HttpUrl.Builder()
            .scheme("https")
            .host("generativelanguage.googleapis.com")
            .addPathSegments("v1/models/$modelName:$endpointPath")
            .addQueryParameter("key", apiKey)
            .build()
    }

    @Throws(Exception::class)
    fun ask(
        userText: String,
        history: List<GeminiHistoryItem> = emptyList(),
        task: KaiTask = KaiTask.BRAIN
    ): String {
        // 🔥 حل سحري ذكي: يفحص البيئة السحابية للـ Secrets أولاً، وإذا لم يجدها يعود للـ BuildConfig المحدثة
        val key = normalizeGeminiApiKey(
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY
        )
        if (key.isBlank()) {
            return "Gemini API key is missing. Secure it in GitHub Secrets or gradle.properties as GEMINI_API_KEY."
        }

        cancelActiveStream()
        
        val detected = detectLikelyLanguage(userText)
        val sysPrompt = systemPrompt(detected, task)
        val payload = buildGeminiPayload(userText, history, task, sysPrompt).toString()
        val modelName = KaiModelRouter.forTask(task)

        val req = Request.Builder()
            .url(buildGeminiUrl(modelName, "generateContent", key))
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON))
            .build()

        val finalReq = req.newBuilder().headers(okhttp3.Headers.headersOf("Content-Type", "application/json")).build()

        try {
            geminiClient.newCall(finalReq).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) return "Gemini error (${res.code}): $text"
                return parseGeminiReply(text)
            }
        } catch (e: Exception) {
            val causeText = e.cause?.toString().orEmpty().let { if (it.isNotBlank()) " - $it" else "" }
            return "Error: ${e.message ?: "Unknown"}$causeText"
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
        history: List<GeminiHistoryItem> = emptyList(),
        task: KaiTask = KaiTask.BRAIN,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        onFinalText: (String) -> Unit
    ) {
        val key = normalizeGeminiApiKey(
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY
        )
        if (key.isBlank()) {
            onError("Gemini API key is missing.")
            return
        }

        cancelActiveStream()
        val mySeq = streamSeq.incrementAndGet()

        val detected = detectLikelyLanguage(userText)
        val sysPrompt = systemPrompt(detected, task)
        val payload = buildGeminiPayload(userText, history, task, sysPrompt).toString()
        val modelName = KaiModelRouter.forTask(task)

        val req = Request.Builder()
            .url(buildGeminiUrl(modelName, "streamGenerateContent", key))
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON))
            .build()

        val finalReq = req.newBuilder().headers(okhttp3.Headers.headersOf("Content-Type", "application/json")).build()
        val call = geminiClient.newCall(finalReq)
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
                val causeText = e.cause?.toString().orEmpty().let { if (it.isNotBlank()) " - $it" else "" }
                complete {
                    clearIfCurrent()
                    onError("Error: ${e.message ?: "Unknown"}$causeText")
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
                            onError("Gemini error (${res.code}): $text")
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
                        val responseText = source.readUtf8()
                        if (!stillCurrent()) return

                        val cleanJson = responseText.trim()
                        if (cleanJson.startsWith("[")) {
                            val jsonArray = JSONArray(cleanJson)
                            for (i in 0 until jsonArray.length()) {
                                val chunk = jsonArray.getJSONObject(i)
                                val candidate = chunk.getJSONArray("candidates").getJSONObject(0)
                                val textDelta = candidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                                if (textDelta.isNotEmpty()) {
                                    finalText.append(textDelta)
                                    onDelta(textDelta)
                                }
                            }
                        } else if (cleanJson.startsWith("{")) {
                            val textDelta = parseGeminiReply(cleanJson)
                            if (textDelta.isNotEmpty()) {
                                finalText.append(textDelta)
                                onDelta(textDelta)
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
