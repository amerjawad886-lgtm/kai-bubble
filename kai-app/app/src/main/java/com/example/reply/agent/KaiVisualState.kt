package com.example.reply.agent

data class KaiVisualState(
    val captureReady: Boolean = false,
    val frameAvailable: Boolean = false,
    val source: String = "none", // none | accessibility | mediaprojection
    val packageNameHint: String = "",
    val screenClassHint: String = "",
    val confidence: Float = 0f,
    val updatedAt: Long = 0L
) {
    fun isUsable(): Boolean {
        return captureReady && frameAvailable && confidence >= 0.25f
    }
}