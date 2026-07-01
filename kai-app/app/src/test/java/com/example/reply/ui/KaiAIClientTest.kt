package com.example.reply.ui

import com.example.reply.ai.KaiTask
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class KaiAIClientTest {
    @Test
    fun buildGeminiPayloadUsesSnakeCaseSystemInstructionField() {
        val method: Method = KaiAIClient::class.java.getDeclaredMethod(
            "buildGeminiPayload",
            String::class.java,
            List::class.java,
            KaiTask::class.java,
            String::class.java
        )
        method.isAccessible = true

        val payload = method.invoke(
            KaiAIClient,
            "Hello world",
            emptyList<GeminiHistoryItem>(),
            KaiTask.BRAIN,
            "System prompt"
        ) as JSONObject

        println("PAYLOAD_JSON=${payload.toString()}")
        assertTrue("Payload should include system_instruction", payload.has("system_instruction"))
        assertFalse("Payload should not include systemInstruction", payload.has("systemInstruction"))
    }
}
