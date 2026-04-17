package com.example.reply.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Kai live vision runtime — primary truth owner.
 *
 * مسؤوليات:
 *  1. تشغيل continuous frame loop على KaiScreenCaptureBridge
 *  2. استخراج pixel-level signals (luma/contrast/edge/motion/hash)
 *  3. استنتاج visual surface family من تلك الإشارات
 *  4. حساب temporal signals: content stable, dwell time, just-transitioned
 *  5. نشر KaiVisualWorldState كـ authoritative truth source للوكيل
 *  6. توفير suspend APIs للوكيل: awaitFreshFrame / awaitStableScene /
 *     awaitSceneChange / awaitVisuallyVerifiedEntry
 *
 * هذا الـ runtime لا يعتمد على accessibility dumps، ولا يحتفظ بذاكرة ملاحظة قديمة،
 * ولا يتقاطع مع broadcast pipeline. كل حقيقة ينشرها تخرج من frame حي.
 */
object KaiLiveVisionRuntime {

    private const val FRAME_INTERVAL_MS: Long = 85L
    private const val IDLE_INTERVAL_MS: Long = 240L
    private const val AWAIT_POLL_MS: Long = 35L

    @Volatile
    private var current: KaiVisualWorldState = KaiVisualWorldState()

    @Volatile
    private var previousFrameHash: Long = 0L

    @Volatile
    private var previousMeanLuma: Float = 0f

    @Volatile
    private var previousSurfaceFamily: KaiVisualSurfaceFamily = KaiVisualSurfaceFamily.UNKNOWN

    @Volatile
    private var stableSinceMs: Long = 0L

    @Volatile
    private var frameSequence: Long = 0L

    @Volatile
    private var packageHint: String = ""

    @Volatile
    private var screenClassHint: String = ""

    @Volatile
    private var hintUpdatedAt: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    @Volatile
    private var running: Boolean = false

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Ensure the continuous frame loop is running. Safe to call repeatedly.
     * Called after MediaProjection permission is granted (from the capture service).
     */
    fun ensureRunning() {
        if (running && loopJob?.isActive == true) return
        running = true

        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive && running) {
                val pulled = pullAndPublishOnce()
                val gap = if (pulled) FRAME_INTERVAL_MS else IDLE_INTERVAL_MS
                delay(gap)
            }
        }
    }

    fun stop() {
        running = false
        loopJob?.cancel()
        loopJob = null
    }

    fun reset() {
        current = KaiVisualWorldState()
        previousFrameHash = 0L
        previousMeanLuma = 0f
        previousSurfaceFamily = KaiVisualSurfaceFamily.UNKNOWN
        stableSinceMs = 0L
        frameSequence = 0L
        packageHint = ""
        screenClassHint = ""
        hintUpdatedAt = 0L
    }

    /**
     * Forces a synchronous pull — used right after permission is granted so we
     * publish a first valid state immediately, and also by the capture service
     * to re-seed state after surface recreation.
     */
    fun refreshFromCapture(): KaiVisualWorldState {
        pullAndPublishOnce()
        return current
    }

    // ------------------------------------------------------------------
    // Hints from accessibility events (package / window class)
    //
    // These are *auxiliary* and do not override pixel-derived truth;
    // they are only used to label the world-state for convenience and
    // to help the agent correlate visual transitions with app focus.
    // ------------------------------------------------------------------

    fun onPackageFocusChanged(packageName: String, screenClass: String = "") {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return

        packageHint = normalized
        screenClassHint = screenClass.trim()
        hintUpdatedAt = System.currentTimeMillis()

        current = current.copy(
            packageNameHint = packageHint,
            screenClassHint = screenClassHint,
            hintUpdatedAt = hintUpdatedAt,
        )
    }

    fun clearHints() {
        packageHint = ""
        screenClassHint = ""
        hintUpdatedAt = 0L
        current = current.copy(
            packageNameHint = "",
            screenClassHint = "",
            hintUpdatedAt = 0L,
        )
    }

    // ------------------------------------------------------------------
    // State accessors
    // ------------------------------------------------------------------

    fun getState(): KaiVisualWorldState = current

    fun isCaptureReady(): Boolean = current.isCaptureLive()

    fun hasFreshFrame(maxAgeMs: Long = KaiVisualWorldThresholds.FRESH_FRAME_MAX_AGE_MS): Boolean =
        current.isFrameFresh(maxAgeMs = maxAgeMs)

    // ------------------------------------------------------------------
    // Await APIs (the agent uses these instead of old observation awaits)
    // ------------------------------------------------------------------

    /** Wait for a frame newer than [afterSequence] (or until timeout). */
    suspend fun awaitFreshFrame(
        afterSequence: Long,
        timeoutMs: Long = 1800L,
    ): KaiVisualWorldState {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val state = current
            if (state.frameSequence > afterSequence && state.isCaptureLive()) return state
            delay(AWAIT_POLL_MS)
        }
        return current
    }

    /**
     * Wait for the scene to stabilize (stable signal + dwell time passed).
     * Returns the stabilized state, or the most recent state on timeout.
     */
    suspend fun awaitStableScene(
        timeoutMs: Long = 2400L,
        minDwellMs: Long = KaiVisualWorldThresholds.MIN_STABLE_DWELL_MS,
    ): KaiVisualWorldState {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val now = System.currentTimeMillis()
            val state = current
            if (state.isVisuallyUsable() &&
                !state.justTransitioned &&
                state.contentStable &&
                (now - state.stableSinceMs) >= minDwellMs
            ) {
                return state
            }
            delay(AWAIT_POLL_MS)
        }
        return current
    }

    /**
     * Wait for a visually detected scene change after [afterSequence].
     * A scene change is detected when motion was high at some point after
     * [afterSequence] AND we now have a stable frame from a different surface family
     * or significantly different frame hash.
     */
    suspend fun awaitSceneChange(
        afterSequence: Long,
        baselineHash: Long,
        baselineFamily: KaiVisualSurfaceFamily,
        timeoutMs: Long = 2600L,
    ): KaiVisualWorldState {
        val startedAt = System.currentTimeMillis()
        var observedMotion = false

        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val state = current
            if (state.frameSequence > afterSequence) {
                if (state.motionScore >= KaiVisualWorldThresholds.MOTION_HIGH_MIN ||
                    state.justTransitioned
                ) {
                    observedMotion = true
                }

                val hashChanged =
                    baselineHash != 0L &&
                        java.lang.Long.bitCount(baselineHash xor state.frameHash) >= 8
                val familyChanged = state.surfaceFamily != baselineFamily
                val settled = state.isVisuallyUsable() && state.contentStable

                if ((observedMotion || hashChanged || familyChanged) && settled) {
                    return state
                }
            }
            delay(AWAIT_POLL_MS)
        }
        return current
    }

    /**
     * Visually verify that we entered a new app scene after an open-app action.
     *
     * Strategy:
     *  - Wait for a visually-detected scene change (motion burst + settle)
     *  - Require the post-change scene to be visually meaningful (not near_black/white)
     *  - If an accessibility package hint matches [expectedPackage] within the stable
     *    window, return the state with `packageMatched = true`
     *
     * Returns [KaiVisualEntryOutcome] describing what was observed.
     */
    suspend fun awaitVisuallyVerifiedEntry(
        expectedPackage: String,
        dispatchSequence: Long,
        dispatchHash: Long,
        dispatchFamily: KaiVisualSurfaceFamily,
        timeoutMs: Long = 3200L,
    ): KaiVisualEntryOutcome {
        val changed = awaitSceneChange(
            afterSequence = dispatchSequence,
            baselineHash = dispatchHash,
            baselineFamily = dispatchFamily,
            timeoutMs = timeoutMs,
        )

        val stable = if (changed.isVisuallyUsable() && changed.contentStable) {
            changed
        } else {
            awaitStableScene(timeoutMs = 900L)
        }

        val packageMatched = expectedPackage.isNotBlank() &&
            stable.hasTrustedPackageHint() &&
            packageHintMatches(stable.packageNameHint, expectedPackage)

        val kind = when {
            !stable.isCaptureLive() -> KaiVisualEntryKind.NO_CAPTURE
            stable.surfaceFamily == KaiVisualSurfaceFamily.NEAR_BLACK -> KaiVisualEntryKind.DARK_SURFACE
            stable.surfaceFamily == KaiVisualSurfaceFamily.NEAR_WHITE -> KaiVisualEntryKind.BLANK_SURFACE
            !stable.isVisuallyUsable() -> KaiVisualEntryKind.WEAK_SIGNAL
            stable.surfaceFamily.isMeaningful() && packageMatched -> KaiVisualEntryKind.APP_ENTERED_MATCHED
            stable.surfaceFamily.isMeaningful() -> KaiVisualEntryKind.APP_ENTERED_UNVERIFIED
            else -> KaiVisualEntryKind.UNKNOWN_SURFACE
        }

        return KaiVisualEntryOutcome(
            state = stable,
            kind = kind,
            packageMatched = packageMatched,
        )
    }

    // ------------------------------------------------------------------
    // Internal pipeline: pull a frame, compute signals, publish world state.
    // ------------------------------------------------------------------

    private fun pullAndPublishOnce(): Boolean {
        val ready = KaiScreenCaptureBridge.isReady()
        if (!ready) {
            publishNoFrame(captureReady = false)
            return false
        }

        val frame = KaiScreenCaptureBridge.acquireLatestFrame()
        if (frame == null) {
            publishNoFrame(captureReady = true)
            return false
        }

        frameSequence += 1L
        val now = System.currentTimeMillis()

        val motion = estimateMotion(
            previousHash = previousFrameHash,
            currentHash = frame.frameHash,
            previousLuma = previousMeanLuma,
            currentLuma = frame.meanLuma,
        )

        val family = classifySurfaceFamily(
            meanLuma = frame.meanLuma,
            contrast = frame.contrast,
            edgeDensity = frame.edgeDensity,
            motionScore = motion,
        )

        val confidence = computeConfidence(
            frame = frame,
            motionScore = motion,
            family = family,
        )

        val contentStable = motion < KaiVisualWorldThresholds.MOTION_STABLE_MAX &&
            family != KaiVisualSurfaceFamily.HIGH_MOTION

        val familyChanged = family != previousSurfaceFamily
        val justTransitioned = motion >= KaiVisualWorldThresholds.MOTION_HIGH_MIN || familyChanged

        if (justTransitioned || !contentStable) {
            stableSinceMs = 0L
        } else if (stableSinceMs == 0L) {
            stableSinceMs = now
        }

        val transitionSignature = if (justTransitioned) frame.frameHash else current.transitionSignature

        current = KaiVisualWorldState(
            captureReady = true,
            frameAvailable = true,
            frameSequence = frameSequence,
            frameTimestampNanos = frame.timestampNanos,
            updatedAt = now,
            width = frame.width,
            height = frame.height,
            meanLuma = frame.meanLuma,
            contrast = frame.contrast,
            edgeDensity = frame.edgeDensity,
            motionScore = motion,
            frameHash = frame.frameHash,
            surfaceFamily = family,
            surfaceLabel = surfaceLabelOf(family),
            contentStable = contentStable,
            stableSinceMs = stableSinceMs,
            justTransitioned = justTransitioned,
            transitionSignature = transitionSignature,
            packageNameHint = packageHint,
            screenClassHint = screenClassHint,
            hintUpdatedAt = hintUpdatedAt,
            confidence = confidence,
        )

        previousFrameHash = frame.frameHash
        previousMeanLuma = frame.meanLuma
        previousSurfaceFamily = family

        return true
    }

    private fun publishNoFrame(captureReady: Boolean) {
        current = KaiVisualWorldState(
            captureReady = captureReady,
            frameAvailable = false,
            frameSequence = frameSequence,
            updatedAt = System.currentTimeMillis(),
            surfaceFamily = KaiVisualSurfaceFamily.NO_FRAME,
            surfaceLabel = "no_frame",
            contentStable = false,
            justTransitioned = false,
            packageNameHint = packageHint,
            screenClassHint = screenClassHint,
            hintUpdatedAt = hintUpdatedAt,
            confidence = if (captureReady) 0.12f else 0f,
        )
    }

    // ------------------------------------------------------------------
    // Signal computation
    // ------------------------------------------------------------------

    private fun estimateMotion(
        previousHash: Long,
        currentHash: Long,
        previousLuma: Float,
        currentLuma: Float,
    ): Float {
        if (previousHash == 0L || currentHash == 0L) return 0.5f

        val hashDistance = java.lang.Long.bitCount(previousHash xor currentHash) / 64f
        val lumaDelta = abs(previousLuma - currentLuma) / 255f
        return ((hashDistance * 0.75f) + (lumaDelta * 0.25f)).coerceIn(0f, 1f)
    }

    private fun classifySurfaceFamily(
        meanLuma: Float,
        contrast: Float,
        edgeDensity: Float,
        motionScore: Float,
    ): KaiVisualSurfaceFamily = when {
        contrast < 4f && edgeDensity < 0.01f && meanLuma < KaiVisualWorldThresholds.LUMA_NEAR_BLACK_MAX ->
            KaiVisualSurfaceFamily.NEAR_BLACK
        contrast < 5f && edgeDensity < 0.01f && meanLuma > KaiVisualWorldThresholds.LUMA_NEAR_WHITE_MIN ->
            KaiVisualSurfaceFamily.NEAR_WHITE
        motionScore >= KaiVisualWorldThresholds.MOTION_HIGH_MIN ->
            KaiVisualSurfaceFamily.HIGH_MOTION
        edgeDensity >= KaiVisualWorldThresholds.EDGE_DENSE_MIN &&
            contrast >= KaiVisualWorldThresholds.CONTRAST_UI_MIN * 2f ->
            KaiVisualSurfaceFamily.DENSE_UI
        edgeDensity >= KaiVisualWorldThresholds.EDGE_STRUCTURED_MIN &&
            contrast >= KaiVisualWorldThresholds.CONTRAST_UI_MIN ->
            KaiVisualSurfaceFamily.STRUCTURED_UI
        edgeDensity < KaiVisualWorldThresholds.EDGE_STRUCTURED_MIN &&
            contrast >= KaiVisualWorldThresholds.CONTRAST_MEDIA_MIN ->
            KaiVisualSurfaceFamily.MEDIA_SURFACE
        edgeDensity <= KaiVisualWorldThresholds.LOW_SIGNAL_EDGE_MAX &&
            contrast <= KaiVisualWorldThresholds.LOW_SIGNAL_CONTRAST_MAX ->
            KaiVisualSurfaceFamily.LOW_SIGNAL
        else -> KaiVisualSurfaceFamily.UNKNOWN
    }

    private fun surfaceLabelOf(family: KaiVisualSurfaceFamily): String = when (family) {
        KaiVisualSurfaceFamily.UNKNOWN -> "unknown_surface"
        KaiVisualSurfaceFamily.NO_FRAME -> "no_frame"
        KaiVisualSurfaceFamily.NEAR_BLACK -> "near_black_surface"
        KaiVisualSurfaceFamily.NEAR_WHITE -> "near_white_surface"
        KaiVisualSurfaceFamily.HIGH_MOTION -> "high_motion_surface"
        KaiVisualSurfaceFamily.DENSE_UI -> "dense_ui_surface"
        KaiVisualSurfaceFamily.STRUCTURED_UI -> "structured_ui_surface"
        KaiVisualSurfaceFamily.MEDIA_SURFACE -> "media_like_surface"
        KaiVisualSurfaceFamily.LOW_SIGNAL -> "low_signal_surface"
    }

    private fun computeConfidence(
        frame: KaiCapturedFrame,
        motionScore: Float,
        family: KaiVisualSurfaceFamily,
    ): Float {
        var score = 0.20f // capture ready + frame exists

        score += (frame.edgeDensity.coerceIn(0f, 0.10f) / 0.10f) * 0.35f
        score += (frame.contrast.coerceIn(0f, 40f) / 40f) * 0.25f

        val motionBonus = if (motionScore < KaiVisualWorldThresholds.MOTION_STABLE_MAX) 0.15f
        else if (motionScore < KaiVisualWorldThresholds.MOTION_HIGH_MIN) 0.08f
        else 0.03f
        score += motionBonus

        val familyBonus = when (family) {
            KaiVisualSurfaceFamily.DENSE_UI -> 0.08f
            KaiVisualSurfaceFamily.STRUCTURED_UI -> 0.06f
            KaiVisualSurfaceFamily.MEDIA_SURFACE -> 0.04f
            KaiVisualSurfaceFamily.NEAR_BLACK,
            KaiVisualSurfaceFamily.NEAR_WHITE,
            KaiVisualSurfaceFamily.LOW_SIGNAL -> -0.12f
            KaiVisualSurfaceFamily.HIGH_MOTION -> -0.05f
            else -> 0f
        }
        score += familyBonus

        return score.coerceIn(0f, 1f)
    }

    private fun packageHintMatches(hint: String, expected: String): Boolean {
        if (hint.isBlank() || expected.isBlank()) return false
        if (hint.equals(expected, ignoreCase = true)) return true
        if (hint.startsWith("$expected.", ignoreCase = true)) return true
        return KaiAppIdentityRegistry.packageMatchesFamily(expected, hint)
    }
}

/**
 * Result of a visually-verified app entry attempt.
 *
 * [state] — the world-state we settled on
 * [kind]  — visual interpretation of that state
 * [packageMatched] — accessibility-sourced hint agrees with the expected package
 *                    (auxiliary confirmation; not required for a "meaningful entry")
 */
data class KaiVisualEntryOutcome(
    val state: KaiVisualWorldState,
    val kind: KaiVisualEntryKind,
    val packageMatched: Boolean,
) {
    fun isEntered(): Boolean =
        kind == KaiVisualEntryKind.APP_ENTERED_MATCHED ||
            kind == KaiVisualEntryKind.APP_ENTERED_UNVERIFIED

    fun isStrongEntry(): Boolean = kind == KaiVisualEntryKind.APP_ENTERED_MATCHED
}

enum class KaiVisualEntryKind {
    NO_CAPTURE,
    DARK_SURFACE,
    BLANK_SURFACE,
    WEAK_SIGNAL,
    UNKNOWN_SURFACE,
    APP_ENTERED_UNVERIFIED,
    APP_ENTERED_MATCHED,
}
