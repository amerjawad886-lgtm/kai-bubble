package com.example.reply.agent

import android.content.Context

/**
 * Compatibility wrapper during Kai Live migration.
 *
 * Real observation ownership now lives in KaiLiveObservationRuntime.
 * Keep this file tiny so old call-sites do not keep Copilot-era behavior alive.
 */
object KaiObservationRuntime {

    val live: KaiObservation
        get() = KaiLiveObservationRuntime.latestObservation

    val authoritative: KaiObservation
        get() = KaiLiveObservationRuntime.latestStrongObservation

    val isWatching: Boolean
        get() = KaiLiveObservationRuntime.isWatching

    fun currentExpectedPackage(): String = KaiLiveObservationRuntime.currentExpectedPackage()

    fun reset() {
        KaiLiveObservationRuntime.reset()
    }

    fun hardReset(stopWatching: Boolean = true) {
        KaiLiveObservationRuntime.hardReset(stopWatching)
    }

    fun clearRuntimeState(keepWatching: Boolean = true) {
        if (!keepWatching) {
            KaiLiveObservationRuntime.stopWatching()
        }
        KaiLiveObservationRuntime.reset()
        KaiLiveObservationRuntime.requestTransitionReset()
    }

    fun softCleanupAfterRun() {
        KaiLiveObservationRuntime.softCleanupAfterRun()
    }

    fun requestWarmupObservation(
        expectedPackage: String = "",
        burstCount: Int = 4,
        gapMs: Long = 140L,
        skipTransitionReset: Boolean = false
    ) {
        KaiLiveObservationRuntime.requestWarmupObservation(
            expectedPackage = expectedPackage,
            burstCount = burstCount,
            gapMs = gapMs,
            skipTransitionReset = skipTransitionReset
        )
    }

    fun ensureBridge(context: Context) {
        KaiLiveObservationRuntime.ensureBridge(context)
    }

    fun onDumpArrived(
        pkg: String,
        dump: String,
        elements: List<KaiUiElement>,
        screenKind: String,
        confidence: Float
    ) {
        KaiLiveObservationRuntime.onDumpArrived(pkg, dump, elements, screenKind, confidence)
    }

    fun requestImmediateDump(expectedPackage: String = "") {
        KaiLiveObservationRuntime.requestImmediateDump(expectedPackage)
    }

    fun requestTransitionReset() {
        KaiLiveObservationRuntime.requestTransitionReset()
    }

    suspend fun awaitFresh(
        afterTime: Long,
        timeoutMs: Long = 2200L,
        expectedPackage: String = "",
        authoritativeOnly: Boolean = false
    ): KaiObservation {
        return KaiLiveObservationRuntime.awaitFreshObservation(
            afterTime = afterTime,
            timeoutMs = timeoutMs,
            expectedPackage = expectedPackage,
            requireStrong = authoritativeOnly
        )
    }

    fun startWatching(
        immediateDump: Boolean = true,
        expectedPackage: String = ""
    ) {
        KaiLiveObservationRuntime.startWatching(immediateDump, expectedPackage)
    }

    fun stopWatching() {
        KaiLiveObservationRuntime.stopWatching()
    }

    fun hasUsableAuthoritative(expectedPackage: String = ""): Boolean {
        val state = KaiLiveObservationRuntime.currentScreenState(expectedPackage, requireStrong = true)
        return KaiVisionInterpreter.isStrongState(
            state = state,
            expectedPackage = expectedPackage,
            allowLauncherSurface = true
        )
    }

    @JvmOverloads
    fun hasRecentAuthoritative(
        maxAgeMs: Long,
        expectedPackage: String = "",
        requireSemantic: Boolean = false
    ): Boolean {
        val obs = authoritative
        if (obs.updatedAt <= 0L) return false
        if (System.currentTimeMillis() - obs.updatedAt > maxAgeMs) return false

        val state = KaiVisionInterpreter.toScreenState(obs)
        if (!KaiVisionInterpreter.packageMatchesExpected(state.packageName, expectedPackage)) return false
        return !requireSemantic || KaiVisionInterpreter.isUsableState(state)
    }

    @JvmOverloads
    fun hasRecentUsefulObservation(
        maxAgeMs: Long,
        expectedPackage: String = "",
        requireAuthoritative: Boolean = false
    ): Boolean {
        val obs = if (requireAuthoritative) {
            authoritative
        } else {
            KaiLiveObservationRuntime.bestObservation(expectedPackage, requireStrong = false)
        }

        if (obs.updatedAt <= 0L) return false
        if (System.currentTimeMillis() - obs.updatedAt > maxAgeMs) return false

        val state = KaiVisionInterpreter.toScreenState(obs)
        return KaiVisionInterpreter.packageMatchesExpected(state.packageName, expectedPackage) &&
            KaiVisionInterpreter.isUsableState(state)
    }

    @JvmOverloads
    fun getBestAvailable(
        expectedPackage: String = "",
        authoritativeOnly: Boolean = false
    ): KaiObservation {
        return KaiLiveObservationRuntime.bestObservation(
            expectedPackage = expectedPackage,
            requireStrong = authoritativeOnly
        )
    }

    fun currentScreenState(): KaiScreenState {
        return KaiLiveObservationRuntime.currentScreenState()
    }
}
