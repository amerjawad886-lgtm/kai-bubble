package com.example.reply.data.supabase

import android.content.Context
import org.json.JSONObject

class KaiChatRepository(context: Context) {
    private val client = SupabaseClientProvider.get(context)

    suspend fun upsertSession(
        sessionId: String,
        title: String,
        summary: String = ""
    ): Boolean {
        if (sessionId.isBlank()) return false

        val body = JSONObject()
            .put("id", sessionId)
            .put("title", title.ifBlank { "New Chat" })
            .put("summary", summary)

        return client.upsert(
            table = "kai_chat_sessions",
            body = body,
            onConflict = "id"
        )
    }

    suspend fun insertMessage(
        sessionId: String,
        role: String,
        message: String,
        messageType: String = "text"
    ): Boolean {
        if (sessionId.isBlank() || message.isBlank()) return false

        val safeRole = when (role.trim().lowercase()) {
            "user" -> "user"
            "assistant" -> "assistant"
            "system" -> "system"
            else -> "assistant"
        }

        val body = JSONObject()
            .put("session_id", sessionId)
            .put("role", safeRole)
            .put("message", message)
            .put("message_type", messageType)

        return client.insert(
            table = "kai_chat_history",
            body = body
        )
    }
}