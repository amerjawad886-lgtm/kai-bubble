package com.example.reply.data.supabase

import android.content.Context
import org.json.JSONObject

class KaiAgentStateRepository(context: Context) {
    private val client = SupabaseClientProvider.get(context)

    suspend fun upsertState(
        id: String = "main_agent",
        state: String,
        lastObservation: String = "",
        lastDecision: String = "",
        lastAction: String = "",
        notes: String = ""
    ): Boolean {
        val body = JSONObject()
            .put("id", id)
            .put("state", state)
            .put("last_observation", lastObservation)
            .put("last_decision", lastDecision)
            .put("last_action", lastAction)
            .put("notes", notes)

        return client.upsert(
            table = "kai_agent_state",
            body = body,
            onConflict = "id"
        )
    }
}