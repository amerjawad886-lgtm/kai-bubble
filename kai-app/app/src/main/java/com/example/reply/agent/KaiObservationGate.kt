package com.example.reply.agent

import android.content.Context
import android.content.Intent
import com.example.reply.ui.KaiAccessibilityService
import com.example.reply.ui.KaiBubbleManager
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Manages observation state, screen refresh, and readiness gating.
 *
 * Clearly separates:
 *   1) observation / refresh  -- [requestFreshScreen]
 *   2) observation gating     -- [ensureStrongGate], [ensureAuthoritative]
 *   3) progress tracking      -- [markActionProgress]
 *
 * Owned by [KaiActionExecutor]; replaces the scattered observation state
 * that previously lived across canonicalRuntimeState, lastGoodScreenState,
 * and lastAcceptedFingerprint with a single coherent model.
 */
class KaiObservationGate(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {

    // ── Types ───────────────────────────────────────────────────────────

    data class RefreshMeta(
        val fingerprint: String = "",
        val changedFromPrevious: Boolean = false,
        val usable: Boolean = false,
        val fallback: Boolean = false,
        val weak: Boolean = false,
        val stale: Boolean = false,
        val reusedLastGood: Boolean = false
    )

    data class GateResult(
        val passed: Boolean,
        val state: KaiScreenState,
        val reason: String
    )

    data class ReadinessResult(
        val passed: Boolean,
        val state: KaiScreenState,
        val reason: String,
        val attempts: Int
    )

    enum class GateTier {
        APP_LAUNCH_SAFE,
        SEMANTIC_ACTION_SAFE
    }

    // ── State ───────────────────────────────────────────────────────────

    /** The current best-known screen state (persists through weak reads). */
    @Volatile
    var canonical: KaiScreenState? = null
        internal set

    @Volatile
    var lastAcceptedFingerprint: String = ""
        internal set

    @Volatile
    var lastAcceptedObservationAt: Long = 0L
        internal set

    var lastMeta: RefreshMeta = RefreshMeta()
        private set

    var consecutiveWeakReads: Int = 0
        private set

    var consecutiveStaleReads: Int = 0
        private set

    var consecutiveNoProgressActions: Int = 0
        private set

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun reset() {
        canonical = null
        lastAcceptedFingerprint = ""
        lastAcceptedObservationAt = 0L
        consecutiveWeakReads = 0
        consecutiveStaleReads = 0
        consecutiveNoProgressActions = 0
        lastMeta = RefreshMeta()
    }

    /**
     * Clears the startup fingerprint baseline so the first cycle's
     * observation is never marked stale against its own startup dump.
     */
    fun clearStartupBaseline() {
        lastAcceptedFingerprint = ""
        lastAcceptedObservationAt = 0L
        consecutiveStaleReads = 0
    }

    fun adopt(state: KaiScreenState) {
        canonical = state
        KaiAgentController.mirrorRuntimeObservation(state)
    }

    fun resolve(): KaiScreenState {
        return canonical ?: KaiAgentController.getLatestScreenState()
    }

    // ── Fingerprinting ──────────────────────────────────────────────────

    fun fingerprintFor(packageName: String, rawDump: String): String {
        return KaiScreenStateParser.fromDump(packageName, rawDump)
            .semanticFingerprint()
            .take(5000)
    }

    fun sameFingerprint(a: String, b: String): Boolean {
        return a.isNotBlank() && b.isNotBlank() && a == b
    }

    fun isExternalPackageChange(before: String, after: String): Boolean {
        if (before.isBlank() || after.isBlank()) return false
        if (before == context.packageName || after == context.packageName) return false
        return before != after
    }

    // ── Dump Validation ─────────────────────────────────────────────────

    fun isOverlayPolluted(raw: String): Boolean {
        val clean = raw.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return false
        val hints = listOf(
            "dynamic island", "custom prompt", "make action", "control panel",
            "agent loop active", "agent planning", "agent executing", "agent observing",
            "monitoring paused before action loop",
            "screen recorder", "recording", "tap to stop", "stop recording"
        )
        return hints.count { clean.contains(it) } >= 2
    }

    fun isUsableDump(raw: String, packageName: String = ""): Boolean {
        val clean = raw.trim()
        if (clean.isBlank()) return false
        if (clean.equals("(no active window)", ignoreCase = true)) return false
        if (clean.equals("(empty dump)", ignoreCase = true)) return false
        if (clean.lines().size < 2) return false
        if (packageName == context.packageName) return false
        if (isOverlayPolluted(clean)) return false
        return true
    }

    fun isExpectedPackageMatch(observed: String, expected: String): Boolean {
        val o = KaiScreenStateParser.normalize(observed)
        val e = KaiScreenStateParser.normalize(expected)
        if (e.isBlank()) return true
        if (o.isBlank()) return false
        return o == e || o.startsWith("$e.") ||
            KaiAppIdentityRegistry.packageMatchesFamily(expected, observed)
    }

    // ── Screen Refresh ──────────────────────────────────────────────────


    private fun canReuseRecentRuntimeObservation(expectedPackage: String): Boolean {
        return KaiObservationRuntime.hasRecentAuthoritative(
            maxAgeMs = 650L,
            expectedPackage = expectedPackage,
            requireSemantic = expectedPackage.isNotBlank()
        )
    }

    private fun canSafelyReuseCanonical(expectedPackage: String): Boolean {
        val current = canonical ?: return false
        if (current.packageName.isBlank()) return false
        if (current.isWeakObservation() || current.isOverlayPolluted()) return false
        if (!current.isMeaningful()) return false
        if (System.currentTimeMillis() - lastAcceptedObservationAt > 2200L) return false
        return expectedPackage.isBlank() || isExpectedPackageMatch(current.packageName, expectedPackage)
    }

    /**
     * Requests a fresh screen observation via the accessibility service.
     *
     * Returns the canonical (last-good) state when the observation is weak,
     * preventing downstream consumers from acting on noisy data.
     */
    suspend fun requestFreshScreen(
        timeoutMs: Long = 2500L,
        expectedPackage: String = ""
    ): KaiScreenState {
        val beforeUpdatedAt = maxOf(
            KaiObservationRuntime.live.updatedAt,
            KaiObservationRuntime.authoritative.updatedAt,
            lastAcceptedObservationAt
        )
        val previousFingerprint = lastAcceptedFingerprint.ifBlank {
            canonical?.let { fingerprintFor(it.packageName, it.rawDump) }.orEmpty()
        }

        val canReuseRecentRuntimeObs = canReuseRecentRuntimeObservation(expectedPackage)

        val obs = if (canReuseRecentRuntimeObs) {
            KaiObservationRuntime.getBestAvailable(expectedPackage = expectedPackage, authoritativeOnly = true)
        } else {
            KaiBubbleManager.beginActionUiSuppression(true)
            try {
                delay(30L)
                val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
                    putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, timeoutMs)
                    if (expectedPackage.isNotBlank()) {
                        putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
                    }
                }
                context.sendBroadcast(intent)
            } finally {
                KaiBubbleManager.endActionUiSuppression(true)
            }

            KaiObservationRuntime.awaitFresh(beforeUpdatedAt, timeoutMs)
                ?: KaiObservationRuntime.getBestAvailable(expectedPackage = expectedPackage)
        }

        val recoveredObs = if (obs.packageName.isBlank() && expectedPackage.isBlank()) {
            KaiObservationRuntime.getBestAvailable(authoritativeOnly = false)
        } else {
            obs
        }

        val state = KaiScreenStateParser.fromDump(recoveredObs.packageName, recoveredObs.screenPreview)
        val fp = fingerprintFor(state.packageName, state.rawDump)
        val changed = fp.isNotBlank() && !sameFingerprint(fp, previousFingerprint)
        val weak = state.packageName.isBlank() ||
            state.rawDump.isBlank() ||
            isOverlayPolluted(state.rawDump) ||
            state.isWeakObservation() ||
            !state.isMeaningful()
        val stale = !changed && recoveredObs.updatedAt <= lastAcceptedObservationAt

        if (!weak && !stale) {
            adopt(state)
            lastAcceptedFingerprint = fp
            lastAcceptedObservationAt = recoveredObs.updatedAt
        }

        consecutiveWeakReads = if (weak) consecutiveWeakReads + 1 else 0
        consecutiveStaleReads = if (stale) consecutiveStaleReads + 1 else 0

        val reusedCanonical = weak && canSafelyReuseCanonical(expectedPackage)
        lastMeta = RefreshMeta(
            fingerprint = fp,
            changedFromPrevious = changed,
            usable = !weak && !stale,
            fallback = !canReuseRecentRuntimeObs && recoveredObs.updatedAt <= beforeUpdatedAt,
            weak = weak,
            stale = stale,
            reusedLastGood = reusedCanonical
        )

        if (weak) {
            onLog("refresh_weak_observation_received")
        }

        return when {
            !weak -> state
            reusedCanonical -> canonical ?: state
            else -> state
        }
    }

    // ── Progress Tracking ───────────────────────────────────────────────

    fun markActionProgress(
        beforePackage: String,
        afterPackage: String,
        beforeFingerprint: String,
        afterFingerprint: String,
        message: String
    ) {
        val changed = !sameFingerprint(beforeFingerprint, afterFingerprint) ||
            isExternalPackageChange(beforePackage, afterPackage)

        if (!changed) {
            consecutiveNoProgressActions += 1
            onLog("$message | no visible progress")
        } else {
            consecutiveNoProgressActions = 0
        }

        if (consecutiveNoProgressActions >= 8) {
            onLog("Too many no-progress actions. Soft-resetting observation state.")
            reset()
        }
    }

    // ── Observation Strength ────────────────────────────────────────────

    /**
     * Unified strength evaluation for both tiers.
     * The APP_LAUNCH_SAFE tier is more lenient (tolerates stale launcher, lower confidence).
     * The SEMANTIC_ACTION_SAFE tier requires stronger evidence but tolerates in-app
     * continuation when the observation clearly changed.
     */
    private fun evaluateStrength(
        state: KaiScreenState,
        meta: RefreshMeta,
        expectedPackage: String,
        allowLauncherSurface: Boolean,
        tier: GateTier
    ): Pair<Boolean, String> {
        if (state.packageName.isBlank()) return false to "missing_package"
        if (!isExpectedPackageMatch(state.packageName, expectedPackage)) {
            return false to "expected_package_mismatch"
        }
        if (state.isOverlayPolluted()) return false to "overlay_polluted"

        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        val isLauncher = state.isLauncher() || family == KaiSurfaceFamily.LAUNCHER_SURFACE

        // FIX: In-app continuation tolerance — allow observations that changed
        // even if they're weak/stale, as long as we're in a coherent in-app surface.
        if (tier == GateTier.SEMANTIC_ACTION_SAFE) {
            val inExpectedApp = expectedPackage.isBlank() ||
                isExpectedPackageMatch(state.packageName, expectedPackage)
            val coherentFamily = family in COHERENT_IN_APP_FAMILIES
            if (inExpectedApp && !isLauncher && coherentFamily &&
                meta.changedFromPrevious && !meta.reusedLastGood
            ) {
                return true to "in_app_continuation_tolerated"
            }
        }

        if (state.isWeakObservation()) return false to "weak_observation"
        if (meta.weak) return false to "weak_refresh_meta"
        if (meta.fallback) return false to "fallback_observation"
        if (meta.reusedLastGood) return false to "reused_last_good_observation"

        // App-launch: tolerate stale on coherent launcher
        if (tier == GateTier.APP_LAUNCH_SAFE && meta.stale && isLauncher) {
            return true to "app_launch_safe_launcher_coherent"
        }

        if (meta.stale) return false to "stale_observation"

        val minConfidence = if (tier == GateTier.APP_LAUNCH_SAFE) 0.28f else 0.42f
        if (state.semanticConfidence < minConfidence) return false to "low_semantic_confidence"

        if (tier == GateTier.APP_LAUNCH_SAFE) {
            if (state.elements.isEmpty() && state.lines.size < 2) {
                return false to "insufficient_app_launch_structure"
            }
        } else {
            if (state.elements.size < 2 && state.lines.size < 3) {
                return false to "insufficient_semantic_structure"
            }
        }

        if (!allowLauncherSurface && isLauncher) {
            return false to "launcher_surface_not_semantic_ready"
        }

        // FIX: Removed SEARCH_SURFACE from rejection list — search is a valid
        // working surface for many flows (YouTube search, contact lookup, etc.)
        if (tier == GateTier.SEMANTIC_ACTION_SAFE && family in setOf(
                KaiSurfaceFamily.UNKNOWN_SURFACE,
                KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE,
                KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE
            )
        ) {
            return false to "wrong_surface_family:${KaiSurfaceModel.familyName(family)}"
        }

        return true to if (tier == GateTier.APP_LAUNCH_SAFE) "app_launch_safe" else "strong"
    }

    // ── Observation Gating ──────────────────────────────────────────────

    /**
     * Ensures a strong observation is available before semantic actions.
     * Retries on recoverable failures (stale, missing package).
     */
    suspend fun ensureStrongGate(
        expectedPackage: String = "",
        timeoutMs: Long = 2600L,
        maxAttempts: Int = 2,
        allowLauncherSurface: Boolean = false,
        tier: GateTier = GateTier.SEMANTIC_ACTION_SAFE,
        staleRetryAttempts: Int = 2,
        missingPackageRetryAttempts: Int = 2
    ): GateResult {
        var lastState = resolve()
        var lastReason = "not_observed"

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val state = requestFreshScreen(timeoutMs, expectedPackage)
            lastState = state

            val (passed, reason) = evaluateStrength(
                state = state,
                meta = lastMeta,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface,
                tier = tier
            )

            if (passed) {
                return GateResult(passed = true, state = state, reason = reason)
            }
            lastReason = reason

            // Retry on recoverable failures
            val canRetry = when {
                reason == "stale_observation" -> attempt < staleRetryAttempts.coerceAtLeast(1)
                reason == "missing_package" -> attempt < missingPackageRetryAttempts.coerceAtLeast(1)
                else -> attempt < maxAttempts - 1
            }
            if (canRetry) {
                delay(if (reason in setOf("stale_observation", "missing_package")) 150L else 220L)
            }
        }

        return GateResult(
            passed = false,
            state = lastState,
            reason = "strong_observation_gate_failed:$lastReason"
        )
    }

    /**
     * Ensures an authoritative observation is ready at startup.
     * Tries the authoritative observation first, falls back to the strong gate.
     */
    suspend fun ensureAuthoritative(
        timeoutMs: Long = 2600L,
        maxAttempts: Int = 3,
        allowLauncherSurface: Boolean = true,
        tier: GateTier = GateTier.APP_LAUNCH_SAFE
    ): ReadinessResult {
        var lastState = resolve()
        var lastReason = "not_observed"

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            // Try authoritative observation first
            val auth = KaiObservationRuntime.authoritative
            if (auth.updatedAt > 0L) {
                val authState = KaiScreenStateParser.fromDump(auth.packageName, auth.screenPreview)
                val authWeak = authState.packageName.isBlank() ||
                    authState.rawDump.isBlank() ||
                    authState.isWeakObservation() ||
                    authState.isOverlayPolluted() ||
                    !authState.isMeaningful()

                if (!authWeak) {
                    adopt(authState)
                    lastAcceptedFingerprint = fingerprintFor(authState.packageName, authState.rawDump)
                    lastAcceptedObservationAt = auth.updatedAt
                    lastMeta = RefreshMeta(
                        fingerprint = lastAcceptedFingerprint,
                        changedFromPrevious = true,
                        usable = true
                    )
                    return ReadinessResult(
                        passed = true,
                        state = authState,
                        reason = "authoritative_observation_ready",
                        attempts = attempt + 1
                    )
                }
            }

            // If authoritative is blank but the live observation is already coherent and package-bearing,
            // allow it as startup readiness instead of failing blind on missing_package.
            val bestLive = KaiObservationRuntime.getBestAvailable(authoritativeOnly = false)
            if (bestLive.updatedAt > 0L) {
                val liveState = KaiScreenStateParser.fromDump(bestLive.packageName, bestLive.screenPreview)
                val liveWeak = liveState.packageName.isBlank() ||
                    liveState.rawDump.isBlank() ||
                    liveState.isWeakObservation() ||
                    liveState.isOverlayPolluted() ||
                    !liveState.isMeaningful()

                if (!liveWeak) {
                    adopt(liveState)
                    lastAcceptedFingerprint = fingerprintFor(liveState.packageName, liveState.rawDump)
                    lastAcceptedObservationAt = bestLive.updatedAt
                    lastMeta = RefreshMeta(
                        fingerprint = lastAcceptedFingerprint,
                        changedFromPrevious = true,
                        usable = true
                    )
                    return ReadinessResult(
                        passed = true,
                        state = liveState,
                        reason = "authoritative_observation_ready_via_live_runtime",
                        attempts = attempt + 1
                    )
                }
            }

            // Fall back to strong gate
            val gate = ensureStrongGate(
                expectedPackage = "",
                timeoutMs = timeoutMs,
                maxAttempts = 1,
                allowLauncherSurface = allowLauncherSurface,
                tier = tier,
                staleRetryAttempts = 1,
                missingPackageRetryAttempts = 1
            )
            lastState = gate.state
            lastReason = gate.reason

            if (gate.passed) {
                return ReadinessResult(
                    passed = true,
                    state = gate.state,
                    reason = "authoritative_observation_ready_via_strong_gate",
                    attempts = attempt + 1
                )
            }

            if (attempt < maxAttempts - 1) delay(180L)
        }

        return ReadinessResult(
            passed = false,
            state = lastState,
            reason = "authoritative_observation_not_ready:$lastReason",
            attempts = maxAttempts.coerceAtLeast(1)
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    fun isContinuationEligible(state: KaiScreenState, expectedPackage: String): Boolean {
        if (state.packageName.isBlank() || state.isOverlayPolluted() || state.isLauncher()) return false

        val expected = KaiScreenStateParser.normalize(expectedPackage)
        val observed = KaiScreenStateParser.normalize(state.packageName)
        if (expected.isNotBlank() && observed.isNotBlank() &&
            observed != expected && !observed.startsWith("$expected.")
        ) {
            return false
        }

        val kind = KaiScreenStateParser.normalize(state.screenKind)
        if (kind in setOf("instagram_camera_overlay", "instagram_search", "notes_search", "search")) {
            return false
        }
        return !state.isCameraOrMediaOverlaySurface() &&
            !state.isInstagramSearchSurface() &&
            !state.isSearchLikeSurface()
    }

    companion object {
        private val COHERENT_IN_APP_FAMILIES = setOf(
            KaiSurfaceFamily.LIST_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.THREAD_SURFACE,
            KaiSurfaceFamily.COMPOSER_SURFACE,
            KaiSurfaceFamily.EDITOR_SURFACE,
            KaiSurfaceFamily.DETAIL_SURFACE,
            KaiSurfaceFamily.SEARCH_SURFACE,
            KaiSurfaceFamily.TABBED_HOME_SURFACE,
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.PLAYER_SURFACE,
            KaiSurfaceFamily.APP_HOME_SURFACE,
            KaiSurfaceFamily.BROWSER_LIKE_SURFACE
        )
    }
}
