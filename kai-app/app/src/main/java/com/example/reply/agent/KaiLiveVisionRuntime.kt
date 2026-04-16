package com.example.reply.agent

/**
 * Capture readiness telemetry — NOT semantic visual understanding.
 *
 * This object reports whether the MediaProjection pipeline has a live surface and
 * a recent frame available. It does not decode, classify, or understand screen
 * content. The "confidence" values here reflect capture pipeline health only.
 *
 * Execution truth (what is on screen, which app is active, what UI is present)
 * is owned exclusively by KaiLiveObservationRuntime via accessibility events and
 * KaiVisionInterpreter. Do not use this object to make step-success decisions.
 */
object KaiLiveVisionRuntime {

    @Volatile
    private var current: KaiVisualState = KaiVisualState()

    /** Updates the capture pipeline readiness state. Does not perform visual analysis. */
    fun refreshFromCapture(currentPackageHint: String = "", currentScreenClassHint: String = "") {
        val ready = KaiScreenCaptureBridge.isReady()
        val hasFrame = if (ready) KaiScreenCaptureBridge.hasRecentFrame() else false

        current = KaiVisualState(
            captureReady = ready,
            frameAvailable = hasFrame,
            source = if (ready) "mediaprojection" else "none",
            packageNameHint = currentPackageHint,
            screenClassHint = currentScreenClassHint,
            // Confidence here means capture-pipeline health, not semantic screen confidence.
            confidence = when {
                ready && hasFrame -> 0.55f
                ready -> 0.25f
                else -> 0f
            },
            updatedAt = System.currentTimeMillis()
        )
    }

    fun getState(): KaiVisualState = current

    /** True only when the capture pipeline has a surface and a recent frame — not a semantic check. */
    fun isCaptureReady(): Boolean = current.isUsable()

    /** @deprecated Use isCaptureReady() — name was misleading about semantic capability. */
    fun isReady(): Boolean = isCaptureReady()

    fun reset() {
        current = KaiVisualState()
    }
}