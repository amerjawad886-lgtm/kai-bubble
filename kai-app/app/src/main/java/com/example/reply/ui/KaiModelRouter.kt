package com.example.reply.ai

enum class KaiTask {
    BRAIN,
    ACTION_PLANNING,
    FAST_COMMAND,
    TTS,
    VISION_STATUS
}

object KaiModelRouter {
    private const val MODEL_BRAIN = "gpt-4o"
    private const val MODEL_FAST = "gpt-4o-mini"
    private const val MODEL_TTS = "gpt-4o-audio-preview"

    fun forTask(task: KaiTask): String = when (task) {
        KaiTask.BRAIN -> MODEL_BRAIN
        KaiTask.ACTION_PLANNING -> MODEL_BRAIN
        KaiTask.FAST_COMMAND -> MODEL_FAST
        KaiTask.TTS -> MODEL_TTS
        KaiTask.VISION_STATUS -> MODEL_FAST
    }
}
