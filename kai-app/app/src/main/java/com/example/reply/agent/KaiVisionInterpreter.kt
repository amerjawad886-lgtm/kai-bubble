package com.example.reply.agent

/**
 * Kai vision interpreter.
 *
 * يقسّم الحكم على world-state إلى طبقتين:
 *
 *  1. Visual readiness — مبنية على [KaiVisualWorldState] القادم من
 *     KaiLiveVisionRuntime. هذه هي الطبقة الأساسية لحكم الوكيل على
 *     هل العالم جاهز للاستمرار / التحقق بعد action.
 *
 *  2. Semantic targeting — تفسير [KaiScreenState] المحوّل من snapshot
 *     مؤقت لـ accessibility node tree (يُطلب على الطلب فقط). تُستخدم
 *     لاختيار عنصر ضغط / حقل إدخال، لا لبناء truth source مستقل.
 *
 * لا يوجد في هذا الملف أي hybrid observation cache أو fallback للـ
 * dumps القديمة. الحقيقة تأتي من frame حي + استعلام accessibility فوري.
 */
object KaiVisionInterpreter {

    // ------------------------------------------------------------------
    // Visual readiness (world-state layer)
    // ------------------------------------------------------------------

    data class VisualReadiness(
        val passed: Boolean,
        val reason: String,
        val confidence: Float,
        val surfaceLabel: String,
        val surfaceFamily: KaiVisualSurfaceFamily,
    )

    fun evaluateVisualReadiness(
        state: KaiVisualWorldState,
        requireStable: Boolean = false,
        requireMeaningfulSurface: Boolean = false,
    ): VisualReadiness {
        val reason = when {
            !state.captureReady -> "capture_not_ready"
            !state.frameAvailable -> "frame_not_available"
            state.surfaceFamily == KaiVisualSurfaceFamily.NEAR_BLACK -> "visual_surface_too_dark"
            state.surfaceFamily == KaiVisualSurfaceFamily.NEAR_WHITE -> "visual_surface_too_bright"
            state.surfaceFamily == KaiVisualSurfaceFamily.LOW_SIGNAL -> "visual_signal_low"
            !state.isVisuallyUsable() -> "visual_signal_too_weak"
            requireStable && !state.contentStable -> "visual_content_unstable"
            requireStable && state.justTransitioned -> "visual_in_transition"
            requireMeaningfulSurface && !state.surfaceFamily.isMeaningful() ->
                "visual_surface_not_meaningful"
            else -> "visual_ready"
        }

        val passed = reason == "visual_ready"

        return VisualReadiness(
            passed = passed,
            reason = reason,
            confidence = state.confidence,
            surfaceLabel = state.surfaceLabel,
            surfaceFamily = state.surfaceFamily,
        )
    }

    // ------------------------------------------------------------------
    // Package-name matching (shared helper — used by world-state awaits and
    // by accessibility snapshot interpretation).
    // ------------------------------------------------------------------

    fun packageMatchesExpected(observed: String, expected: String): Boolean {
        if (expected.isBlank()) return true
        if (observed.isBlank()) return false
        val normalizedObserved = KaiScreenStateParser.normalize(observed)
        val normalizedExpected = KaiScreenStateParser.normalize(expected)
        return normalizedObserved == normalizedExpected ||
            normalizedObserved.startsWith("$normalizedExpected.") ||
            KaiAppIdentityRegistry.packageMatchesFamily(expected, observed)
    }

    // ------------------------------------------------------------------
    // Semantic targeting (accessibility-snapshot layer)
    //
    // These helpers consume a KaiScreenState that was built from an
    // on-demand accessibility snapshot. They do NOT treat the snapshot as
    // world-state; they only use it for UI element targeting.
    // ------------------------------------------------------------------

    data class TargetingReadiness(val passed: Boolean, val reason: String)

    fun evaluateTargetingReadiness(
        snapshot: KaiScreenState,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false,
        requireStrong: Boolean = true,
    ): TargetingReadiness = when {
        snapshot.packageName.isBlank() -> TargetingReadiness(false, "missing_package")
        snapshot.isOverlayPolluted() -> TargetingReadiness(false, "overlay_pollution")
        !packageMatchesExpected(snapshot.packageName, expectedPackage) ->
            TargetingReadiness(false, "wrong_package")
        !allowLauncherSurface && snapshot.isLauncher() ->
            TargetingReadiness(false, "launcher_surface")
        requireStrong && !isSnapshotStrong(snapshot, expectedPackage, allowLauncherSurface) ->
            TargetingReadiness(
                false,
                if (snapshot.isWeakObservation()) "weak_snapshot" else "snapshot_not_ready",
            )
        !isSnapshotUsable(snapshot) -> TargetingReadiness(false, "snapshot_not_usable")
        else -> TargetingReadiness(true, "snapshot_ready")
    }

    fun isSnapshotUsable(snapshot: KaiScreenState): Boolean {
        if (snapshot.packageName.isBlank()) return false
        if (snapshot.rawDump.isBlank() && snapshot.elements.isEmpty()) return false
        if (snapshot.isOverlayPolluted()) return false
        if (!snapshot.isMeaningful()) return false
        return true
    }

    fun isSnapshotStrong(
        snapshot: KaiScreenState,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false,
    ): Boolean {
        if (!isSnapshotUsable(snapshot)) return false
        if (!packageMatchesExpected(snapshot.packageName, expectedPackage)) return false
        if (snapshot.isWeakObservation()) return false
        if (!allowLauncherSurface && snapshot.isLauncher()) return false
        return true
    }

    // Transition aliases kept for callers still on snapshot-semantics targeting.
    // They forward to the snapshot-oriented methods above and will be removed
    // once KaiAgentController / KaiExecutionDecisionAuthority / KaiScreenState
    // migrate to the world-state + on-demand snapshot APIs.
    fun isUsableState(state: KaiScreenState): Boolean = isSnapshotUsable(state)

    fun isStrongState(
        state: KaiScreenState,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false,
    ): Boolean = isSnapshotStrong(state, expectedPackage, allowLauncherSurface)

    // ------------------------------------------------------------------
    // Legacy bridge (to be removed when KaiLiveObservationRuntime is deleted).
    //
    // These three helpers exist ONLY to let the old observation runtime
    // compile during the migration. They must not be called from new code.
    // ------------------------------------------------------------------

    @Deprecated("Use accessibility snapshot API + evaluateTargetingReadiness")
    fun toScreenState(obs: KaiObservation): KaiScreenState =
        KaiScreenStateParser.fromDump(
            packageName = obs.packageName,
            dump = obs.screenPreview,
            elements = obs.elements,
            screenKindHint = obs.screenKind,
            semanticConfidence = obs.semanticConfidence,
        )

    @Deprecated("Use evaluateTargetingReadiness on a fresh snapshot")
    fun evaluateReadiness(
        state: KaiScreenState,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false,
        requireStrong: Boolean = true,
    ) = evaluateTargetingReadiness(state, expectedPackage, allowLauncherSurface, requireStrong)

    @Deprecated("Use live visual world-state + snapshot targeting instead")
    fun classify(
        obs: KaiObservation,
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = false,
    ): LegacyClassification {
        val state = toScreenState(obs)
        val usable = isSnapshotUsable(state)
        val matched = packageMatchesExpected(state.packageName, expectedPackage)
        val strong = isSnapshotStrong(state, expectedPackage, allowLauncherSurface)

        val reason = when {
            state.packageName.isBlank() -> "missing_package"
            state.isOverlayPolluted() -> "overlay_pollution"
            !matched -> "wrong_package"
            !usable -> "snapshot_not_usable"
            state.isWeakObservation() -> "weak_snapshot"
            !allowLauncherSurface && state.isLauncher() -> "launcher_surface"
            strong -> "strong_snapshot"
            else -> "snapshot_not_ready"
        }

        return LegacyClassification(
            observation = obs,
            screenState = state,
            isStrong = strong,
            isUsable = usable,
            expectedPackageMatched = matched,
            reason = reason,
        )
    }

    @Deprecated("Transitional shape — will be removed with KaiLiveObservationRuntime")
    data class LegacyClassification(
        val observation: KaiObservation,
        val screenState: KaiScreenState,
        val isStrong: Boolean,
        val isUsable: Boolean,
        val expectedPackageMatched: Boolean,
        val reason: String,
    )
}
