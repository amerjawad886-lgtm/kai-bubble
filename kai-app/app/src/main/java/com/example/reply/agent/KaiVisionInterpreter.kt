package com.example.reply.agent

object KaiVisionInterpreter {

    data class LiveFrame(
        val observation: KaiObservation,
        val screenState: KaiScreenState,
        val isStrong: Boolean,
        val isUsable: Boolean,
        val expectedPackageMatched: Boolean,
        val reason: String
    )

    data class ReadinessResult(val passed: Boolean, val reason: String)

    data class VisualReadinessResult(
        val passed: Boolean,
        val reason: String,
        val confidence: Float,
        val inferredSurface: String
    )

    fun evaluateReadiness(
        state: KaiScreenState,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false,
        requireStrong: Boolean = true
    ): ReadinessResult = when {
        state.packageName.isBlank() -> ReadinessResult(false, "missing_package")
        state.isOverlayPolluted() -> ReadinessResult(false, "overlay_pollution")
        !packageMatchesExpected(state.packageName, expectedPackage) ->
            ReadinessResult(false, "wrong_package")
        !allowLauncherSurface && state.isLauncher() ->
            ReadinessResult(false, "launcher_surface")
        requireStrong && !isStrongState(state, expectedPackage, allowLauncherSurface) ->
            ReadinessResult(
                false,
                if (state.isWeakObservation()) "weak_observation" else "observation_not_ready"
            )
        !isUsableState(state) -> ReadinessResult(false, "observation_not_usable")
        else -> ReadinessResult(true, "observation_ready")
    }

    /**
     * low-level visual readiness مبني على البيكسل.
     * لا يثبت app package ولا semantic UI intent، لكنه يثبت أن عندنا
     * live visual signal usable من MediaProjection.
     */
    fun evaluateVisualReadiness(
        state: KaiVisualState,
        requireStableContent: Boolean = false
    ): VisualReadinessResult {
        val reason = when {
            !state.captureReady -> "capture_not_ready"
            !state.frameAvailable -> "frame_not_available"
            state.confidence < 0.20f -> "visual_signal_too_weak"
            requireStableContent && !state.contentStable -> "visual_content_unstable"
            state.inferredSurface == "near_black_surface" -> "visual_surface_too_dark"
            state.inferredSurface == "near_white_surface" -> "visual_surface_too_bright"
            else -> "visual_ready"
        }

        val passed = when {
            !state.captureReady -> false
            !state.frameAvailable -> false
            state.confidence < 0.20f -> false
            requireStableContent && !state.contentStable -> false
            else -> true
        }

        return VisualReadinessResult(
            passed = passed,
            reason = reason,
            confidence = state.confidence,
            inferredSurface = state.inferredSurface
        )
    }

    fun toScreenState(obs: KaiObservation): KaiScreenState {
        return KaiScreenStateParser.fromDump(
            packageName = obs.packageName,
            dump = obs.screenPreview,
            elements = obs.elements,
            screenKindHint = obs.screenKind,
            semanticConfidence = obs.semanticConfidence
        )
    }

    fun packageMatchesExpected(observed: String, expected: String): Boolean {
        if (expected.isBlank()) return true
        if (observed.isBlank()) return false
        val normalizedObserved = KaiScreenStateParser.normalize(observed)
        val normalizedExpected = KaiScreenStateParser.normalize(expected)
        return normalizedObserved == normalizedExpected ||
            normalizedObserved.startsWith("$normalizedExpected.") ||
            KaiAppIdentityRegistry.packageMatchesFamily(expected, observed)
    }

    fun isUsableState(state: KaiScreenState): Boolean {
        if (state.packageName.isBlank()) return false
        if (state.rawDump.isBlank()) return false
        if (state.isOverlayPolluted()) return false
        if (!state.isMeaningful()) return false
        return true
    }

    fun isStrongState(
        state: KaiScreenState,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false
    ): Boolean {
        if (!isUsableState(state)) return false
        if (!packageMatchesExpected(state.packageName, expectedPackage)) return false
        if (state.isWeakObservation()) return false
        if (!allowLauncherSurface && state.isLauncher()) return false
        return true
    }

    fun classify(
        obs: KaiObservation,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false
    ): LiveFrame {
        val state = toScreenState(obs)
        val usable = isUsableState(state)
        val matched = packageMatchesExpected(state.packageName, expectedPackage)
        val strong = isStrongState(state, expectedPackage, allowLauncherSurface)

        val reason = when {
            state.packageName.isBlank() -> "missing_package"
            state.isOverlayPolluted() -> "overlay_pollution"
            !matched -> "wrong_package"
            !usable -> "observation_not_usable"
            state.isWeakObservation() -> "weak_observation"
            !allowLauncherSurface && state.isLauncher() -> "launcher_surface"
            strong -> "strong_observation"
            else -> "observation_not_ready"
        }

        return LiveFrame(
            observation = obs,
            screenState = state,
            isStrong = strong,
            isUsable = usable,
            expectedPackageMatched = matched,
            reason = reason
        )
    }
}