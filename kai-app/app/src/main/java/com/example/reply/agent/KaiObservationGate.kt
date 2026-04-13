
package com.example.reply.agent

import android.content.Context
import com.example.reply.ui.KaiBubbleManager
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
        canonical = state
        runCatching { KaiAgentController.mirrorRuntimeObservation(state) }
    }

    fun resolve(): KaiScreenState {
        return canonical ?: runCatching { KaiAgentController.getLatestScreenState() }
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

    suspend fun requestFreshScreen(
        timeoutMs: Long = 2200L,
        expectedPackage: String = ""
    ): KaiScreenState {
        val after = System.currentTimeMillis()
        KaiObservationRuntime.requestImmediateDump(expectedPackage)
        val obs = KaiObservationRuntime.awaitFresh(
            afterTime = after,
            timeoutMs = timeoutMs,
            expectedPackage = expectedPackage,
            authoritativeOnly = false
        )
        val state = KaiScreenStateParser.fromDump(
            packageName = obs.packageName,
            dump = obs.screenPreview,
            elements = obs.elements,
            screenKindHint = obs.screenKind,
            semanticConfidence = obs.semanticConfidence
        )
        val fingerprint = fingerprintFor(state.packageName, state.rawDump)
        val usable = isUsableState(state)
        val changed = !sameFingerprint(fingerprint, lastAcceptedFingerprint)

        lastMeta = RefreshMeta(
            fingerprint = fingerprint,
            changedFromPrevious = changed,
            usable = usable,
            fallback = false,
            weak = state.isWeakObservation(),
            stale = !changed && lastAcceptedFingerprint.isNotBlank(),
            reusedLastGood = false
        )

        if (state.isWeakObservation() || !usable) {
            consecutiveWeakReads += 1
            if (lastMeta.stale) consecutiveStaleReads += 1
            val fallback = canonical
            if (fallback != null && isContinuationEligible(fallback, expectedPackage)) {
                lastMeta = lastMeta.copy(
                    fallback = true,
                    reusedLastGood = true,
                    usable = true,
                    stale = false
                )
                return fallback
            }
            return state
        }

        consecutiveWeakReads = 0
        consecutiveStaleReads = if (lastMeta.stale) consecutiveStaleReads + 1 else 0
        canonical = state
        lastAcceptedFingerprint = fingerprint
        lastAcceptedObservationAt = obs.updatedAt
        runCatching { KaiAgentController.mirrorRuntimeObservation(state) }
        return state
    }

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
        var lastReason = "no_observation"

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val state = requestFreshScreen(timeoutMs = timeoutMs, expectedPackage = expectedPackage)
            lastState = state

            val packageMissing = state.packageName.isBlank()
            val overlay = state.isOverlayPolluted()
            val launcher = state.isLauncher()
            val weak = state.isWeakObservation()
            val packageMismatch = !isExpectedPackageMatch(state.packageName, expectedPackage)
            val stale = !lastMeta.changedFromPrevious && lastAcceptedFingerprint.isNotBlank()

            val passed = when (tier) {
                GateTier.APP_LAUNCH_SAFE -> {
                    !overlay &&
                        !packageMissing &&
                        (allowLauncherSurface || !launcher || expectedPackage.isNotBlank())
                }
                GateTier.SEMANTIC_ACTION_SAFE -> {
                    !overlay &&
                        !packageMissing &&
                        !weak &&
                        !packageMismatch &&
                        (allowLauncherSurface || !launcher)
                }
            }

            if (passed) {
                return GateResult(true, state, "strong_observation_ready")
            }

            lastReason = when {
                overlay -> "overlay_pollution"
                packageMissing -> "missing_package"
                packageMismatch -> "wrong_package"
                stale -> "stale_observation"
                weak -> "weak_observation"
                launcher && !allowLauncherSurface -> "launcher_surface"
                else -> "observation_not_strong"
            }

            val shouldRetry =
                (lastReason == "stale_observation" && attempt < staleRetryAttempts) ||
                    (lastReason == "missing_package" && attempt < missingPackageRetryAttempts) ||
                    attempt < maxAttempts - 1

            if (shouldRetry) delay(140L)
        }

        return GateResult(false, lastState, lastReason)
    }

    suspend fun ensureAuthoritative(
        timeoutMs: Long = 3200L,
        allowLauncherSurface: Boolean = false,
        tier: GateTier = GateTier.SEMANTIC_ACTION_SAFE,
        maxAttempts: Int = 3
    ): ReadinessResult {
        var lastState = resolve()
        var lastReason = "not_ready"

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val auth = KaiObservationRuntime.getBestAvailable(authoritativeOnly = true)
            if (auth.updatedAt > 0L) {
                val state = KaiScreenStateParser.fromDump(
                    packageName = auth.packageName,
                    dump = auth.screenPreview,
                    elements = auth.elements,
                    screenKindHint = auth.screenKind,
                    semanticConfidence = auth.semanticConfidence
                )
                lastState = state

                val authReady = when (tier) {
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

                if (authReady) {
                    adopt(state)
                    lastAcceptedFingerprint = fingerprintFor(state.packageName, state.rawDump)
                    lastAcceptedObservationAt = auth.updatedAt
                    lastMeta = RefreshMeta(
                        fingerprint = lastAcceptedFingerprint,
                        changedFromPrevious = true,
                        usable = true
                    )
                    return ReadinessResult(true, state, "authoritative_observation_ready", attempt + 1)
                }

                lastReason = when {
                    state.packageName.isBlank() -> "missing_package"
                    state.isOverlayPolluted() -> "overlay_pollution"
                    state.isWeakObservation() -> "weak_observation"
                    state.isLauncher() && !allowLauncherSurface -> "launcher_surface"
                    else -> "authoritative_not_strong"
                }
            }

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
                    true,
                    gate.state,
                    "authoritative_observation_ready_via_strong_gate",
                    attempt + 1
                )
            }

            delay(150L)
        }

        val fallback = KaiObservationRuntime.getBestAvailable(authoritativeOnly = false)
        if (fallback.updatedAt > 0L && fallback.packageName.isNotBlank()) {
            val state = KaiScreenStateParser.fromDump(
                packageName = fallback.packageName,
                dump = fallback.screenPreview,
                elements = fallback.elements,
                screenKindHint = fallback.screenKind,
                semanticConfidence = fallback.semanticConfidence
            )
            if (!state.isOverlayPolluted()) {
                adopt(state)
                lastAcceptedFingerprint = fingerprintFor(state.packageName, state.rawDump)
                lastAcceptedObservationAt = fallback.updatedAt
                lastMeta = RefreshMeta(
                    fingerprint = lastAcceptedFingerprint,
                    changedFromPrevious = true,
                    usable = true,
                    fallback = true
                )
                return ReadinessResult(
                    true,
                    state,
                    "authoritative_observation_ready_via_lenient_fallback",
                    maxAttempts.coerceAtLeast(1) + 1
                )
            }
        }

        return ReadinessResult(
            false,
            lastState,
            "authoritative_observation_not_ready:$lastReason",
            maxAttempts.coerceAtLeast(1)
        )
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
        if (expectedPackage.isNotBlank() && !isExpectedPackageMatch(state.packageName, expectedPackage)) {
            return false
        }
        return !state.isSearchLikeSurface() &&
            !state.isCameraOrMediaOverlaySurface() &&
            !state.isSheetOrDialogSurface()
    }

    private fun isUsableState(state: KaiScreenState): Boolean {
        if (!isUsableDump(state.rawDump, state.packageName)) return false
        if (state.packageName.isBlank()) return false
        if (state.isOverlayPolluted()) return false
        return true
    }
}
