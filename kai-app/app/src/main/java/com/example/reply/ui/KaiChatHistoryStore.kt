package com.example.reply.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class KaiHistoryMessage(
    val role: String,
    val text: String
)

data class KaiHistorySession(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messages: List<KaiHistoryMessage>
)

object KaiChatHistoryStore {
    private const val PREFS = "kai_history_store"
    private const val KEY_SESSIONS = "sessions"

    private const val MAX_SESSIONS = 40
    private const val MAX_MESSAGES_PER_SESSION = 120
    private const val MAX_TEXT_LEN = 1200

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listSessions(context: Context): List<KaiHistorySession> {
        val raw = prefs(context).getString(KEY_SESSIONS, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(fromJson(obj))
                }
            }
                .map { sanitizeSession(it) }
                .sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun latestSession(context: Context): KaiHistorySession? =
        listSessions(context).firstOrNull()

    fun loadSession(context: Context, id: Long): KaiHistorySession? =
        listSessions(context).firstOrNull { it.id == id }

    fun saveSession(
        context: Context,
        id: Long,
        title: String,
        messages: List<KaiHistoryMessage>
    ) {
        if (messages.isEmpty()) return

        val sessions = listSessions(context).toMutableList()
        val newSession = sanitizeSession(
            KaiHistorySession(
                id = id,
                title = title.ifBlank { "New Chat" },
                updatedAt = System.currentTimeMillis(),
                messages = messages
            )
        )

        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) {
            sessions[index] = newSession
        } else {
            sessions.add(0, newSession)
        }

        persist(context, sessions)
    }

    fun renameSession(
        context: Context,
        id: Long,
        newTitle: String
    ) {
        val title = newTitle.trim().ifBlank { return }
        val sessions = listSessions(context).toMutableList()
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return

        val old = sessions[index]
        sessions[index] = old.copy(
            title = title.take(60),
            updatedAt = System.currentTimeMillis()
        )
        persist(context, sessions)
    }

    fun deleteSession(context: Context, id: Long) {
        persist(context, listSessions(context).filterNot { it.id == id })
    }

    fun deleteAll(context: Context) {
        prefs(context).edit().putString(KEY_SESSIONS, "[]").apply()
    }

    fun compactAll(context: Context) {
        val compacted = listSessions(context).map { sanitizeSession(it) }
        persist(context, compacted)
    }

    private fun persist(context: Context, sessions: List<KaiHistorySession>) {
        val arr = JSONArray()
        sessions
            .sortedByDescending { it.updatedAt }
            .take(MAX_SESSIONS)
            .map { sanitizeSession(it) }
            .forEach { arr.put(toJson(it)) }

        prefs(context).edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    private fun sanitizeSession(session: KaiHistorySession): KaiHistorySession {
        val cleanedMessages = session.messages
            .mapNotNull { sanitizeMessage(it) }
            .let { removeConsecutiveDuplicates(it) }
            .let { trimSystemNoise(it) }
            .takeLast(MAX_MESSAGES_PER_SESSION)

        return session.copy(
            title = session.title.trim().ifBlank { "New Chat" }.take(60),
            messages = cleanedMessages
        )
    }

    private fun sanitizeMessage(message: KaiHistoryMessage): KaiHistoryMessage? {
        val role = message.role.trim().lowercase().ifBlank { "system" }
        val text = message.text
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_TEXT_LEN)

        if (text.isBlank()) return null

        return KaiHistoryMessage(
            role = role,
            text = text
        )
    }

    private fun removeConsecutiveDuplicates(messages: List<KaiHistoryMessage>): List<KaiHistoryMessage> {
        if (messages.isEmpty()) return emptyList()

        val out = mutableListOf<KaiHistoryMessage>()
        for (msg in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == msg.role && last.text == msg.text) {
                continue
            }
            out += msg
        }
        return out
    }

    private fun trimSystemNoise(messages: List<KaiHistoryMessage>): List<KaiHistoryMessage> {
        val out = mutableListOf<KaiHistoryMessage>()
        var loopStartCount = 0

        messages.forEach { msg ->
            val t = msg.text.lowercase()

            val noisy =
                msg.role == "system" && (
                    t == "agent loop starting…" ||
                        t == "agent loop starting..." ||
                        t.startsWith("waiting ") ||
                        t.startsWith("refreshing screen understanding") ||
                        t.startsWith("screen observation appears stale") ||
                        t.startsWith("using weak stale screen dump")
                    )

            if (t == "agent loop starting…" || t == "agent loop starting...") {
                loopStartCount++
                if (loopStartCount > 4) return@forEach
            }

            if (!noisy) out += msg
        }

        return out
    }

    private fun toJson(session: KaiHistorySession): JSONObject {
        val messages = JSONArray()
        session.messages.forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("text", message.text)
            )
        }

        return JSONObject()
            .put("id", session.id)
            .put("title", session.title)
            .put("updatedAt", session.updatedAt)
            .put("messages", messages)
    }

    private fun fromJson(obj: JSONObject): KaiHistorySession {
        val arr = obj.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (i in 0 until arr.length()) {
                val message = arr.optJSONObject(i) ?: continue
                add(
                    KaiHistoryMessage(
                        role = message.optString("role"),
                        text = message.optString("text")
                    )
                )
            }
        }

        return KaiHistorySession(
            id = obj.optLong("id"),
            title = obj.optString("title", "New Chat"),
            updatedAt = obj.optLong("updatedAt"),
            messages = messages
        )
    }
}