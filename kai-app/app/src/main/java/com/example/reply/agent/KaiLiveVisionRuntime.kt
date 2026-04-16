package com.example.reply.agent

import kotlin.math.abs

/**
 * Live visual runtime foundation.
 *
 * هذا الملف لم يعد مجرد readiness flag بسيط، بل صار يملك
 * pixel/frame-level semantics منخفضة المستوى مبنية على MediaProjection.
 *
 * مهم:
 * - هذا ليس OCR
 * - وليس فهم UI دلالي كامل بعد
 * - لكنه foundation بصري حقيقي قائم على frame acquisition + pixel analysis
 *
 * في الدفعة الثانية سنربط هذا المسار مباشرة مع execution/agent truth model.
 */
object KaiLiveVisionRuntime {

    @Volatile
    private var current: KaiVisualState = KaiVisualState()

    @Volatile
    private var previousFrameHash: Long = 0L

    @Volatile
    private var previousMeanLuma: Float = 0f

    @Volatile
    private var frameSequence: Long = 0L

    fun refreshFromCapture(
        currentPackageHint: String = "",
        currentScreenClassHint: String = ""
    ) {
        val ready = KaiScreenCaptureBridge.isReady()
        val frame = if (ready) KaiScreenCaptureBridge.acquireLatestFrame() else null

        if (!ready || frame == null) {
            current = KaiVisualState(
                captureReady = ready,
                frameAvailable = false,
                semanticReady = false,
                source = if (ready) "mediaprojection" else "none",
                packageNameHint = currentPackageHint,
                screenClassHint = currentScreenClassHint,
                inferredSurface = "no_frame",
                confidence = if (ready) 0.15f else 0f,
                updatedAt = System.currentTimeMillis()
            )
            return
        }

        frameSequence += 1L

        val motionScore = estimateMotionScore(
            previousHash = previousFrameHash,
            currentHash = frame.frameHash,
            previousLuma = previousMeanLuma,
            currentLuma = frame.meanLuma
        )

        val inferredSurface = inferSurface(
            meanLuma = frame.meanLuma,
            contrast = frame.contrast,
            edgeDensity = frame.edgeDensity,
            motionScore = motionScore
        )

        val confidence = computeConfidence(
            frame = frame,
            motionScore = motionScore
        )

        val semanticReady = confidence >= 0.35f &&
            frame.edgeDensity >= 0.015f &&
            frame.contrast >= 12f

        val contentStable = motionScore < 0.30f

        current = KaiVisualState(
            captureReady = true,
            frameAvailable = true,
            semanticReady = semanticReady,
            source = "mediaprojection",
            packageNameHint = currentPackageHint,
            screenClassHint = currentScreenClassHint,
            inferredSurface = inferredSurface,
            width = frame.width,
            height = frame.height,
            frameTimestampNanos = frame.timestampNanos,
            updatedAt = System.currentTimeMillis(),
            frameSequence = frameSequence,
            meanLuma = frame.meanLuma,
            contrast = frame.contrast,
            edgeDensity = frame.edgeDensity,
            motionScore = motionScore,
            contentStable = contentStable,
            frameHash = frame.frameHash,
            confidence = confidence
        )

        previousFrameHash = frame.frameHash
        previousMeanLuma = frame.meanLuma
    }

    fun getState(): KaiVisualState = current

    fun isCaptureReady(): Boolean = current.isCaptureReady()

    fun isSemanticReady(): Boolean = current.isSemanticReady()

    /**
     * تفضيل بسيط: هل عندنا live pixel signal طازج؟
     * هذا سيُستخدم لاحقًا في الدفعة الثانية لربط الوكيل بالرؤية الجديدة.
     */
    fun hasFreshVisualSignal(maxAgeMs: Long = 500L): Boolean {
        val now = System.currentTimeMillis()
        return current.isUsable() && (now - current.updatedAt) <= maxAgeMs
    }

    fun reset() {
        current = KaiVisualState()
        previousFrameHash = 0L
        previousMeanLuma = 0f
        frameSequence = 0L
    }

    private fun estimateMotionScore(
        previousHash: Long,
        currentHash: Long,
        previousLuma: Float,
        currentLuma: Float
    ): Float {
        if (previousHash == 0L || currentHash == 0L) {
            return 0.5f
        }

        val hashDistance = java.lang.Long.bitCount(previousHash xor currentHash) / 64f
        val lumaDelta = abs(previousLuma - currentLuma) / 255f
        return ((hashDistance * 0.75f) + (lumaDelta * 0.25f)).coerceIn(0f, 1f)
    }

    private fun inferSurface(
        meanLuma: Float,
        contrast: Float,
        edgeDensity: Float,
        motionScore: Float
    ): String {
        return when {
            contrast < 4f && edgeDensity < 0.01f && meanLuma < 18f -> "near_black_surface"
            contrast < 5f && edgeDensity < 0.01f && meanLuma > 235f -> "near_white_surface"
            motionScore > 0.75f -> "high_motion_surface"
            edgeDensity >= 0.05f && contrast >= 20f -> "dense_ui_surface"
            edgeDensity in 0.02f..0.05f && contrast >= 10f -> "structured_ui_surface"
            edgeDensity < 0.015f && contrast >= 8f -> "media_like_surface"
            else -> "unknown_surface"
        }
    }

    private fun computeConfidence(
        frame: KaiCapturedFrame,
        motionScore: Float
    ): Float {
        var score = 0f

        score += 0.20f // capture ready + frame exists

        score += (frame.edgeDensity.coerceIn(0f, 0.10f) / 0.10f) * 0.35f
        score += (frame.contrast.coerceIn(0f, 40f) / 40f) * 0.30f

        val motionBonus = if (motionScore < 0.65f) 0.15f else 0.05f
        score += motionBonus

        return score.coerceIn(0f, 1f)
    }
}