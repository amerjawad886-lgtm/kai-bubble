package com.example.reply.agent

import com.example.reply.ai.KaiTask
import com.example.reply.ui.OpenAIClient

object KaiVisionBridge {

    suspend fun describeScreenForGoal(
        screenState: KaiScreenState,
        userGoal: String
    ): String {
        val prompt = """
            You are Kai Vision Bridge inside Kai OS.

            This is NOT a real image feed.
            You only have structured text extracted from the current Android screen.

            User goal:
            $userGoal

            Current package:
            ${screenState.packageName.ifBlank { "Unknown" }}

            Visible screen text:
            ${screenState.preview(2400)}

            Write a short practical understanding:
            - what screen/app this probably is
            - what the user is likely trying to do
            - the next safest action direction

            Keep it concise.
        """.trimIndent()

        return OpenAIClient.ask(
            userText = prompt,
            task = KaiTask.BRAIN
        )
    }
}