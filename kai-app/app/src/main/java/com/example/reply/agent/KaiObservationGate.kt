package com.example.reply.agent

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Thin compatibility shim only.
 *
 * The old Copilot gate used to own canonical/fallback/readiness logic.
 * Kai Live moved those responsibilities into:
 * - KaiLiveObservationRuntime
 * - KaiVisionInterpreter
 * - KaiObservationReadiness
 *
 * Keep a tiny accepted-state cache only for compatibility with legacy call-sites.
 */
class KaiObservationGate(
    @Suppress("UNUSED_PARAMETER")
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
        lastAcceptedFingerprint = state.semanticFingerprint().take(5000)
        lastAcceptedObservationAt = state.updatedAt
        runCatching { KaiAgentController.mirrorRuntimeObservation(state) }
    }

    fun resolve(): KaiScreenState {
        return canonical ?: KaiLiveObservationRuntime.currentScreenState()
    }

    suspend fun requestFreshScreen(
        timeoutMs: Long = 2200L,
        expectedPackage: String = ""
    ): KaiScreenState {
        val afterTime = System.currentTimeMillis()
        KaiLiveObservationRuntime.requestImmediateDump(expectedPackage)

        val obs = KaiLiveObservationRuntime.awaitFreshObservation(
            afterTime = afterTime,
            timeoutMs = timeoutMs,
            expectedPackage = expectedPackage,
            requireStrong = false
        )

        val frame = KaiVisionInterpreter.classify(
            obs = obs,
            expectedPackage = expectedPackage,
            allowLauncherSurface = true
        )

        val fingerprint = frame.screenState.semanticFingerprint().take(5000)
        val changed = fingerprint != lastAcceptedFingerprint
        val stale = !changed && lastAcceptedFingerprint.isNotBlank()

        lastMeta = RefreshMeta(
            fingerprint = fingerprint,
            changedFromPrevious = changed,
            usable = frame.isUsable,
            fallback = false,
            weak = !frame.isStrong,
            stale = stale,
            reusedLastGood = false
        )

        if (frame.isStrong) {
            consecutiveWeakReads = 0
            consecutiveStaleReads = if (stale) consecutiveStaleReads + 1 else 0
            adopt(frame.screenState)
        } else {
            consecutiveWeakReads += 1
            if (stale) consecutiveStaleReads += 1
        }

        return frame.screenState
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

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val state = requestFreshScreen(timeoutMs, expectedPackage)
            lastState = state

            val ready = KaiObservationReadiness.evaluate(
                state = state,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface,
                tier = if (tier == GateTier.APP_LAUNCH_SAFE) {
                    KaiObservationReadiness.Tier.APP_LAUNCH_SAFE
                } else {
                    KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
                }
            )

            if (ready.passed) {
                return GateResult(true, state, ready.reason)
            }

            if (lastMeta.stale) staleSeen += 1
            if (state.packageName.isBlank()) missingSeen += 1

            val shouldRetry = when {
                lastMeta.stale && staleSeen <= staleRetryAttempts -> true
                state.packageName.isBlank() && missingSeen <= missingPackageRetryAttempts -> true
                attempt < maxAttempts - 1 -> true
                else -> false
            }

            if (!shouldRetry) {
                return GateResult(false, state, ready.reason)
            }

            delay(140L)
        }

        val finalReason = KaiObservationReadiness.evaluate(
            state = lastState,
            expectedPackage = expectedPackage,
            allowLauncherSurface = allowLauncherSurface,
            tier = if (tier == GateTier.APP_LAUNCH_SAFE) {
                KaiObservationReadiness.Tier.APP_LAUNCH_SAFE
            } else {
                KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
            }
        ).reason

        return GateResult(false, lastState, finalReason)
    }

    suspend fun ensureAuthoritative(
        timeoutMs: Long = 3200L,
        allowLauncherSurface: Boolean = false,
        tier: GateTier = GateTier.SEMANTIC_ACTION_SAFE,
        maxAttempts: Int = 2
    ): ReadinessResult {
        var lastState = resolve()

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val obs = KaiLiveObservationRuntime.bestObservation(requireStrong = attempt == 0)
            if (obs.updatedAt > 0L) {
                val state = KaiVisionInterpreter.toScreenState(obs)
                lastState = state
                val ready = KaiObservationReadiness.evaluate(
                    state = state,
                    allowLauncherSurface = allowLauncherSurface,
                    tier = if (tier == GateTier.APP_LAUNCH_SAFE) {
                        KaiObservationReadiness.Tier.APP_LAUNCH_SAFE
                    } else {
                        KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
                    }
                )
                if (ready.passed) {
                    adopt(state)
                    lastMeta = RefreshMeta(
                        fingerprint = state.semanticFingerprint().take(5000),
                        changedFromPrevious = true,
                        usable = true,
                        weak = false,
                        stale = false
                    )
                    return ReadinessResult(true, state, ready.reason, attempt + 1)
                }
            }

            val fresh = requestFreshScreen(timeoutMs)
            lastState = fresh
            val readyFresh = KaiObservationReadiness.evaluate(
                state = fresh,
                allowLauncherSurface = allowLauncherSurface,
                tier = if (tier == GateTier.APP_LAUNCH_SAFE) {
                    KaiObservationReadiness.Tier.APP_LAUNCH_SAFE
                } else {
                    KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
                }
            )
            if (readyFresh.passed) {
                adopt(fresh)
                return ReadinessResult(true, fresh, readyFresh.reason, attempt + 1)
            }

            if (attempt < maxAttempts - 1) delay(160L)
        }

        val reason = KaiObservationReadiness.evaluate(
            state = lastState,
            allowLauncherSurface = allowLauncherSurface,
            tier = if (tier == GateTier.APP_LAUNCH_SAFE) {
                KaiObservationReadiness.Tier.APP_LAUNCH_SAFE
            } else {
                KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
            }
        ).reason

        return ReadinessResult(false, lastState, reason, maxAttempts)
    }

    fun markActionProgress(
        beforePackage: String,
        afterPackage: String,
        beforeFingerprint: String,
        afterFingerprint: String,
        reason: String
    ) {
        val externalChange = beforePackage.isNotBlank() &&
            afterPackage.isNotBlank() &&
            beforePackage != afterPackage
        val fingerprintChanged = beforeFingerprint.isNotBlank() &&
            afterFingerprint.isNotBlank() &&
            beforeFingerprint != afterFingerprint

        if (externalChange || fingerprintChanged) {
            consecutiveNoProgressActions = 0
            onLog("action_progress:$reason")
        } else {
            consecutiveNoProgressActions += 1
        }
    }
}
