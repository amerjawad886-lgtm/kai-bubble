package com.example.reply.ai

enum class KaiTask {
    BRAIN,
    ACTION_PLANNING,
    FAST_COMMAND,
    TTS,
    VISION_STATUS
}

object KaiModelRouter {
    // 🚀 تم التحويل بالكامل لعائلة جيميناي المتطورة لـ Kai OS
    private const val MODEL_DEEP_REASONING = "gemini-1.5-pro"
    private const val MODEL_FLASH_SPEED = "gemini-1.5-flash"

    fun forTask(task: KaiTask): String = when (task) {
        KaiTask.BRAIN -> MODEL_DEEP_REASONING     // التفكير والتحليل العميق
        KaiTask.ACTION_PLANNING -> MODEL_DEEP_REASONING // التخطيط للمهام البرمجية
        KaiTask.FAST_COMMAND -> MODEL_FLASH_SPEED   // الأوامر السريعة اللحظية
        KaiTask.TTS -> MODEL_FLASH_SPEED            // النصوص الصوتية
        KaiTask.VISION_STATUS -> MODEL_FLASH_SPEED  // تشخيص حالة الشاشة
    }
}
