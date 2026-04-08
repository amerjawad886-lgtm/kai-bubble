package com.example.reply.data.supabase

import android.content.Context
import org.json.JSONObject

class KaiMemoryRepository(context: Context) {
    private val client = SupabaseClientProvider.get(context)

    suspend fun upsertMemory(
        category: String,
        key: String,
        value: String,
        source: String = "app",
        importance: Int = 1
    ): Boolean {
        if (category.isBlank() || key.isBlank() || value.isBlank()) return false

        val body = JSONObject()
            .put("category", category)
            .put("key", key)
            .put("value", value)
            .put("source", source)
            .put("importance", importance.coerceIn(1, 10))

        val upsertOk = client.upsert(
            table = "kai_memory",
            body = body,
            onConflict = "category,key"
        )

        if (upsertOk) return true

        return client.insert(
            table = "kai_memory",
            body = body
        )
    }
}