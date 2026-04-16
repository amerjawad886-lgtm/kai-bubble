package com.example.reply.agent

object KaiLiveVisionRuntime {

    @Volatile
    private var current: KaiVisualState = KaiVisualState()

    fun refreshFromCapture(currentPackageHint: String = "", currentScreenClassHint: String = "") {
        val ready = KaiScreenCaptureBridge.isReady()
        val hasFrame = if (ready) KaiScreenCaptureBridge.hasRecentFrame() else false

        current = KaiVisualState(
            captureReady = ready,
            frameAvailable = hasFrame,
            source = if (ready) "mediaprojection" else "none",
            packageNameHint = currentPackageHint,
            screenClassHint = currentScreenClassHint,
            confidence = when {
                ready && hasFrame -> 0.55f
                ready -> 0.25f
                else -> 0f
            },
            updatedAt = System.currentTimeMillis()
        )
    }

    fun getState(): KaiVisualState = current

    fun isReady(): Boolean = current.isUsable()

    fun reset() {
        current = KaiVisualState()
    }
}