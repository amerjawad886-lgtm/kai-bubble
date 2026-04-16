package com.example.reply.agent

data class KaiVisualState(
    val captureReady: Boolean = false,
    val frameAvailable: Boolean = false,
    val semanticReady: Boolean = false,

    val source: String = "none", // none | mediaprojection
    val packageNameHint: String = "",
    val screenClassHint: String = "",
    val inferredSurface: String = "unknown",

    val width: Int = 0,
    val height: Int = 0,
    val frameTimestampNanos: Long = 0L,
    val updatedAt: Long = 0L,
    val frameSequence: Long = 0L,

    val meanLuma: Float = 0f,
    val contrast: Float = 0f,
    val edgeDensity: Float = 0f,
    val motionScore: Float = 0f,
    val contentStable: Boolean = false,
    val frameHash: Long = 0L,

    // confidence هنا تعني جودة الإشارة البصرية منخفضة المستوى،
    // وليست semantic app understanding كاملة.
    val confidence: Float = 0f
) {
    fun isCaptureReady(): Boolean {
        return captureReady && frameAvailable
    }

    fun isSemanticReady(): Boolean {
        return semanticReady && confidence >= 0.35f
    }

    fun isUsable(): Boolean {
        return isCaptureReady() && confidence >= 0.20f
    }
}