package com.example.reply.agent

import android.content.Context
import kotlinx.coroutines.delay
import java.util.Locale

class KaiObservationGate(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {

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

    fun reset() {
        canonical = null
        lastAcceptedFingerprint = ""
        lastAcceptedObservationAt = 0L
        consecutiveWeakReads = 0
        consecutiveStaleReads = 0
        consecutiveNoProgressActions = 0
        lastMeta = RefreshMeta()
    }

    fun clearStartupBaseline() {
        lastAcceptedFingerprint = ""
        lastAcceptedObservationAt = 0L
        consecutiveStaleReads = 0
    }

    fun adopt(state: KaiScreenState) {
        if (state.packageName.isBlank()) return
        canonical = state
        lastAcceptedFingerprint = fingerprintFor(state.packageName, state.rawDump)
        lastAcceptedObservationAt = state.updatedAt
        runCatching { KaiAgentController.mirrorRuntimeObservation(state) }
    }

    fun resolve(): KaiScreenState {
        val local = canonical
        if (local != null) return local
        return runCatching { KaiAgentController.getLatestScreenState() }
            .getOrElse { KaiScreenStateParser.fromDump("", "") }
    }

    fun weakReadCount(): Int = consecutiveWeakReads
    fun staleReadCount(): Int = consecutiveStaleReads

    fun fingerprintFor(packageName: String, rawDump: String): String {
        return KaiScreenStateParser.fromDump(packageName, rawDump).semanticFingerprint().take(5000)
    }

    fun sameFingerprint(a: String, b: String): Boolean {
        return a.isNotBlank() && b.isNotBlank() && a == b
    }

    fun isExternalPackageChange(before: String, after: String): Boolean {
        if (before.isBlank() || after.isBlank()) return false
        if (before == context.packageName || after == context.packageName) return false
        return !KaiAppIdentityRegistry.packageMatchesFamily(before, after) &&
            KaiScreenStateParser.normalize(before) != KaiScreenStateParser.normalize(after)
    }

    fun isOverlayPolluted(raw: String): Boolean {
        val clean = raw.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return false
        val hints = listOf(
            "dynamic island", "custom prompt", "make action", "control panel",
            "agent loop active", "agent planning", "agent executing", "agent observing",
            "monitoring paused before action loop", "screen recorder", "recording"
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
        return o == e || o.startsWith("$e.") || KaiAppIdentityRegistry.packageMatchesFamily(expected, observed)
    }

    private fun isUsableState(state: KaiScreenState): Boolean {
        if (!isUsableDump(state.rawDump, state.packageName)) return false
        if (state.packageName.isBlank()) return false
        if (state.isOverlayPolluted()) return false
        return true
    }

    private fun buildState(obs: KaiObservation): KaiScreenState {
        return KaiScreenStateParser.fromDump(
            packageName = obs.packageName,
            dump = obs.screenPreview,
            elements = obs.elements,
            screenKindHint = obs.screenKind,
            semanticConfidence = obs.semanticConfidence
        )
    }

    private fun classifyFailureReason(
        state: KaiScreenState,
        expectedPackage: String,
        allowLauncherSurface: Boolean,
        tier: GateTier,
        stale: Boolean
    ): String {
        val packageMismatch = expectedPackage.isNotBlank() &&
            !isExpectedPackageMatch(state.packageName, expectedPackage)

        return when {
            state.isOverlayPolluted() -> "overlay_pollution"
            state.packageName.isBlank() -> "missing_package"
            packageMismatch -> "wrong_package"
            stale -> "stale_observation"
            tier == GateTier.SEMANTIC_ACTION_SAFE && state.isWeakObservation() -> "weak_observation"
            state.isLauncher() && !allowLauncherSurface -> "launcher_surface"
            else -> "observation_not_ready"
        }
    }

    suspend fun requestFreshScreen(
        timeoutMs: Long = 2200L,
        expectedPackage: String = ""
    ): KaiScreenState {
        val afterTime = System.currentTimeMillis()
        KaiObservationRuntime.requestImmediateDump(expectedPackage)
        val obs = KaiObservationRuntime.awaitFresh(
            afterTime = afterTime,
            timeoutMs = timeoutMs,
            expectedPackage = expectedPackage,
            authoritativeOnly = false
        )
        val state = buildState(obs)
        val fingerprint = fingerprintFor(state.packageName, state.rawDump)
        val usable = isUsableState(state)
        val weak = state.isWeakObservation()
        val changed = !sameFingerprint(fingerprint, lastAcceptedFingerprint)
        val stale = !changed && lastAcceptedFingerprint.isNotBlank()

        lastMeta = RefreshMeta(
            fingerprint = fingerprint,
            changedFromPrevious = changed,
            usable = usable,
            fallback = false,
            weak = weak,
            stale = stale,
            reusedLastGood = false
        )

        if (!usable || weak) {
            consecutiveWeakReads += 1
            if (stale) consecutiveStaleReads += 1
            return state
        }

        consecutiveWeakReads = 0
        consecutiveStaleReads = if (stale) consecutiveStaleReads + 1 else 0
        adopt(state)
        return state
    }

    suspend fun ensureStrongGate(
        expectedPackage: String = "",
        timeoutMs: Long = 2600L,
        maxAttempts: Int = 1,
        allowLauncherSurface: Boolean = false,
        tier: GateTier = GateTier.SEMANTIC_ACTION_SAFE,
        staleRetryAttempts: Int = 1,
        missingPackageRetryAttempts: Int = 1
    ): GateResult {
        var lastState = resolve()
        var staleSeen = 0
        var missingSeen = 0
        val attempts = maxAttempts.coerceAtLeast(1)

        repeat(attempts) { attempt ->
            val state = requestFreshScreen(timeoutMs = timeoutMs, expectedPackage = expectedPackage)
            lastState = state
            val meta = lastMeta
            val packageMismatch = expectedPackage.isNotBlank() &&
                !isExpectedPackageMatch(state.packageName, expectedPackage)
            val passed = when (tier) {
                GateTier.APP_LAUNCH_SAFE -> {
                    !state.isOverlayPolluted() &&
                        state.packageName.isNotBlank() &&
                        !packageMismatch &&
                        (allowLauncherSurface || !state.isLauncher())
                }
                GateTier.SEMANTIC_ACTION_SAFE -> {
                    !state.isOverlayPolluted() &&
                        state.packageName.isNotBlank() &&
                        !state.isWeakObservation() &&
                        !packageMismatch &&
                        (allowLauncherSurface || !state.isLauncher())
                }
            }

            if (passed) {
                return GateResult(true, state, "observation_ready")
            }

            if (meta.stale) staleSeen += 1
            if (state.packageName.isBlank()) missingSeen += 1

            val shouldRetry = when {
                meta.stale && staleSeen <= staleRetryAttempts -> true
                state.packageName.isBlank() && missingSeen <= missingPackageRetryAttempts -> true
                attempt < attempts - 1 -> true
                else -> false
            }

            if (!shouldRetry) {
                val reason = classifyFailureReason(
                    state = state,
                    expectedPackage = expectedPackage,
                    allowLauncherSurface = allowLauncherSurface,
                    tier = tier,
                    stale = meta.stale
                )
                return GateResult(false, state, reason)
            }

            delay(140L)
        }

        val reason = classifyFailureReason(
            state = lastState,
            expectedPackage = expectedPackage,
            allowLauncherSurface = allowLauncherSurface,
            tier = tier,
            stale = lastMeta.stale
        )
        return GateResult(false, lastState, reason)
    }

    suspend fun ensureAuthoritative(
        timeoutMs: Long = 3200L,
        allowLauncherSurface: Boolean = false,
        tier: GateTier = GateTier.SEMANTIC_ACTION_SAFE,
        maxAttempts: Int = 2
    ): ReadinessResult {
        var lastState = resolve()
        val attempts = maxAttempts.coerceAtLeast(1)

        repeat(attempts) { attempt ->
            val best = KaiObservationRuntime.getBestAvailable(authoritativeOnly = attempt == 0)
            if (best.updatedAt > 0L) {
                val state = buildState(best)
                lastState = state
                val ready = when (tier) {
                    GateTier.APP_LAUNCH_SAFE -> {
                        state.packageName.isNotBlank() &&
                            !state.isOverlayPolluted() &&
                            (allowLauncherSurface || !state.isLauncher())
                    }
                    GateTier.SEMANTIC_ACTION_SAFE -> {
                        state.packageName.isNotBlank() &&
                            !state.isOverlayPolluted() &&
                            !state.isWeakObservation() &&
                            (allowLauncherSurface || !state.isLauncher())
                    }
                }
                if (ready) {
                    adopt(state)
                    lastMeta = RefreshMeta(
                        fingerprint = fingerprintFor(state.packageName, state.rawDump),
                        changedFromPrevious = true,
                        usable = true,
                        fallback = false,
                        weak = false,
                        stale = false,
                        reusedLastGood = false
                    )
                    return ReadinessResult(true, state, "observation_ready", attempt + 1)
                }
            }

            val fresh = requestFreshScreen(timeoutMs = timeoutMs, expectedPackage = "")
            lastState = fresh
            val freshReady = when (tier) {
                GateTier.APP_LAUNCH_SAFE -> {
                    fresh.packageName.isNotBlank() &&
                        !fresh.isOverlayPolluted() &&
                        (allowLauncherSurface || !fresh.isLauncher())
                }
                GateTier.SEMANTIC_ACTION_SAFE -> {
                    fresh.packageName.isNotBlank() &&
                        !fresh.isOverlayPolluted() &&
                        !fresh.isWeakObservation() &&
                        (allowLauncherSurface || !fresh.isLauncher())
                }
            }
            if (freshReady) {
                adopt(fresh)
                return ReadinessResult(true, fresh, "observation_ready_via_fresh", attempt + 1)
            }

            if (attempt < attempts - 1) delay(160L)
        }

        val reason = classifyFailureReason(
            state = lastState,
            expectedPackage = "",
            allowLauncherSurface = allowLauncherSurface,
            tier = tier,
            stale = lastMeta.stale
        )
        return ReadinessResult(false, lastState, reason, attempts)
    }

    fun markActionProgress(
        beforePackage: String,
        afterPackage: String,
        beforeFingerprint: String,
        afterFingerprint: String,
        reason: String
    ) {
        val externalChange = isExternalPackageChange(beforePackage, afterPackage)
        val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
        if (externalChange || fingerprintChanged) {
            consecutiveNoProgressActions = 0
            onLog("action_progress:$reason")
        } else {
            consecutiveNoProgressActions += 1
        }
    }

    fun isContinuationEligible(state: KaiScreenState, expectedPackage: String): Boolean {
        if (state.packageName.isBlank()) return false
        if (state.isOverlayPolluted()) return false
        if (expectedPackage.isNotBlank() && !isExpectedPackageMatch(state.packageName, expectedPackage)) return false
        return !state.isSearchLikeSurface() &&
            !state.isCameraOrMediaOverlaySurface() &&
            !state.isSheetOrDialogSurface() &&
            !state.isWeakObservation()
    }
}
