package com.example.reply.agent

/**
 * Kai's first-class visual truth model.
 *
 * هذا هو world-state الرسمي للوكيل. كل اتخاذ قرار تنفيذي (execution decision)
 * وكل تحقق من تقدّم العالم (world-state verification) يجب أن يستند إلى هذا النموذج
 * المبني مباشرة على live frame pipeline القادم من MediaProjection.
 *
 * هذا النموذج لا يحتوي على observation dump مسبق أو hybrid cache.
 * هو snapshot فوري لِمَا تراه الشاشة الآن.
 */
data class KaiVisualWorldState(
    // ---- capture / frame availability ----
    val captureReady: Boolean = false,
    val frameAvailable: Boolean = false,
    val frameSequence: Long = 0L,
    val frameTimestampNanos: Long = 0L,
    val updatedAt: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,

    // ---- low-level pixel signals ----
    val meanLuma: Float = 0f,
    val contrast: Float = 0f,
    val edgeDensity: Float = 0f,
    val motionScore: Float = 0f,
    val frameHash: Long = 0L,

    // ---- visual surface semantics (pixel-derived) ----
    val surfaceFamily: KaiVisualSurfaceFamily = KaiVisualSurfaceFamily.UNKNOWN,
    val surfaceLabel: String = "unknown_surface",

    // ---- temporal signals ----
    val contentStable: Boolean = false,
    val stableSinceMs: Long = 0L,
    val justTransitioned: Boolean = false,
    val transitionSignature: Long = 0L,

    // ---- app/screen hints (augmented by accessibility events — not primary) ----
    val packageNameHint: String = "",
    val screenClassHint: String = "",
    val hintUpdatedAt: Long = 0L,

    // ---- visual confidence (quality of the pixel signal, not UI semantics) ----
    val confidence: Float = 0f,
) {
    /** Is there a live pixel signal usable for world-state reasoning at all? */
    fun isCaptureLive(): Boolean = captureReady && frameAvailable

    /** Does the pixel signal meet the minimum quality bar for decision-making? */
    fun isVisuallyUsable(): Boolean =
        isCaptureLive() && confidence >= KaiVisualWorldThresholds.MIN_USABLE_CONFIDENCE

    /** Is the signal strong enough for post-action verification (scene committed)? */
    fun isVisuallyStrong(): Boolean =
        isCaptureLive() &&
            confidence >= KaiVisualWorldThresholds.MIN_STRONG_CONFIDENCE &&
            surfaceFamily.isMeaningful()

    /** Is the current scene visually stable (no in-flight transition)? */
    fun isSceneStable(now: Long = System.currentTimeMillis()): Boolean {
        if (!isVisuallyUsable()) return false
        if (justTransitioned) return false
        val dwell = now - stableSinceMs
        return contentStable && dwell >= KaiVisualWorldThresholds.MIN_STABLE_DWELL_MS
    }

    /** Is the frame fresh enough to be trusted right now? */
    fun isFrameFresh(
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = KaiVisualWorldThresholds.FRESH_FRAME_MAX_AGE_MS,
    ): Boolean = isCaptureLive() && (now - updatedAt) <= maxAgeMs

    /** Package hint is authoritative only if it is recent AND scene looks stable. */
    fun hasTrustedPackageHint(
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = KaiVisualWorldThresholds.HINT_MAX_AGE_MS,
    ): Boolean {
        if (packageNameHint.isBlank()) return false
        if (hintUpdatedAt <= 0L) return false
        return (now - hintUpdatedAt) <= maxAgeMs
    }

    fun describeForDiagnostics(): String =
        "visual[seq=$frameSequence surface=$surfaceLabel " +
            "conf=${"%.2f".format(confidence)} motion=${"%.2f".format(motionScore)} " +
            "stable=$contentStable transitioned=$justTransitioned " +
            "pkgHint=$packageNameHint]"
}

/**
 * Coarse visual surface family inferred from pixel statistics alone.
 *
 * هذه ليست semantic UI recognition، بل تصنيف بصري عام يستخدمه الوكيل
 * كـ signal أولي قبل أن يسأل accessibility tree على الطلب لتحديد أهداف دقيقة.
 */
enum class KaiVisualSurfaceFamily {
    UNKNOWN,
    NO_FRAME,
    NEAR_BLACK,          // lock screen / app closing / screen off
    NEAR_WHITE,          // blank white surface / loading splash
    HIGH_MOTION,         // active transition / animation / scroll
    DENSE_UI,            // busy app with lots of text + controls (list, feed, chat)
    STRUCTURED_UI,       // moderate UI density (settings, dialog, form)
    MEDIA_SURFACE,       // low-edge density, higher contrast (photo/video/gallery)
    LOW_SIGNAL;          // something there, but not enough to classify

    fun isMeaningful(): Boolean =
        this == DENSE_UI || this == STRUCTURED_UI || this == MEDIA_SURFACE

    fun isTransient(): Boolean =
        this == HIGH_MOTION || this == NEAR_BLACK || this == NEAR_WHITE
}

object KaiVisualWorldThresholds {
    const val MIN_USABLE_CONFIDENCE: Float = 0.25f
    const val MIN_STRONG_CONFIDENCE: Float = 0.42f

    const val MIN_STABLE_DWELL_MS: Long = 280L

    const val FRESH_FRAME_MAX_AGE_MS: Long = 650L
    const val HINT_MAX_AGE_MS: Long = 1500L

    const val MOTION_STABLE_MAX: Float = 0.28f
    const val MOTION_HIGH_MIN: Float = 0.70f

    const val EDGE_DENSE_MIN: Float = 0.050f
    const val EDGE_STRUCTURED_MIN: Float = 0.020f
    const val CONTRAST_UI_MIN: Float = 10f
    const val CONTRAST_MEDIA_MIN: Float = 8f

    const val LUMA_NEAR_BLACK_MAX: Float = 18f
    const val LUMA_NEAR_WHITE_MIN: Float = 235f
    const val LOW_SIGNAL_EDGE_MAX: Float = 0.010f
    const val LOW_SIGNAL_CONTRAST_MAX: Float = 6f
}
