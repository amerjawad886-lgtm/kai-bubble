package com.example.reply.agent

import android.content.Context
import android.content.Intent
import com.example.reply.ui.KaiAccessibilityService
import com.example.reply.ui.KaiBubbleManager
import kotlinx.coroutines.delay

class KaiActionExecutor(
    internal val context: Context,
    internal val onLog: (String) -> Unit = {}
) {
    data class ScreenRefreshMeta(
        val fingerprint: String = "",
        val changedFromPrevious: Boolean = false,
        val usable: Boolean = false,
        val fallback: Boolean = false,
        val weak: Boolean = false,
        val stale: Boolean = false,
        val reusedLastGood: Boolean = false
    )

    data class ObservationGateResult(val passed: Boolean, val state: KaiScreenState, val reason: String)
    data class ObservationReadinessResult(val passed: Boolean, val state: KaiScreenState, val reason: String, val attempts: Int)

    enum class ObservationGateTier { APP_LAUNCH_SAFE, SEMANTIC_ACTION_SAFE }

    private var canonicalRuntimeState: KaiScreenState? = null
    private var lastAcceptedFingerprint: String = ""
    private var lastAcceptedObservationAt: Long = 0L
    private var consecutiveWeakReads: Int = 0
    private var consecutiveStaleReads: Int = 0
    private var consecutiveNoProgressActions: Int = 0
    private var lastRefreshMeta: ScreenRefreshMeta = ScreenRefreshMeta()
    private var lastRecoveryContextKey: String = ""
    private var repeatedRecoveryContextCount: Int = 0

    fun resetRuntimeState(clearLastGoodScreen: Boolean = true) {
        if (clearLastGoodScreen) {
            canonicalRuntimeState = null
            lastAcceptedFingerprint = ""
            lastAcceptedObservationAt = 0L
        }
        softResetObservationState()
    }

    internal fun softResetObservationState() {
        consecutiveWeakReads = 0
        consecutiveStaleReads = 0
        consecutiveNoProgressActions = 0
        lastRecoveryContextKey = ""
        repeatedRecoveryContextCount = 0
        lastRefreshMeta = ScreenRefreshMeta()
    }

    fun clearStartupFingerprintBaseline() {
        lastAcceptedFingerprint = ""
        lastAcceptedObservationAt = 0L
        consecutiveStaleReads = 0
    }

    internal fun adoptCanonicalRuntimeState(state: KaiScreenState) {
        if (state.packageName.isBlank()) return
        canonicalRuntimeState = state
        lastAcceptedFingerprint = state.semanticFingerprint().take(5000)
        lastAcceptedObservationAt = state.updatedAt
        runCatching { KaiAgentController.mirrorRuntimeObservation(state) }
    }

    fun getCanonicalRuntimeState(): KaiScreenState? = canonicalRuntimeState
    internal fun resolveCanonicalRuntimeState(): KaiScreenState = canonicalRuntimeState ?: KaiLiveObservationRuntime.currentScreenState()
    fun getLastRefreshMeta(): ScreenRefreshMeta = lastRefreshMeta
    fun getConsecutiveWeakReads(): Int = consecutiveWeakReads
    fun getConsecutiveStaleReads(): Int = consecutiveStaleReads
    fun bestRuntimeObservation(): KaiObservation = KaiLiveObservationRuntime.bestObservation(requireStrong = false)

    suspend fun resetObservationTransitionStateForRun() {
        sendKaiCmdSuppressed(KaiAccessibilityService.CMD_RESET_TRANSITION_STATE, preDelayMs = 20L, postDelayMs = 20L)
    }

    suspend fun requestFreshScreen(timeoutMs: Long = 2200L, expectedPackage: String = ""): KaiScreenState {
        val afterTime = System.currentTimeMillis()
        KaiLiveObservationRuntime.requestImmediateDump(expectedPackage)
        val obs = KaiLiveObservationRuntime.awaitFreshObservation(afterTime, timeoutMs, expectedPackage, requireStrong = false)
        val frame = KaiVisionInterpreter.classify(obs = obs, expectedPackage = expectedPackage, allowLauncherSurface = true)
        val fingerprint = frame.screenState.semanticFingerprint().take(5000)
        val changed = fingerprint != lastAcceptedFingerprint
        val stale = !changed && lastAcceptedFingerprint.isNotBlank()

        lastRefreshMeta = ScreenRefreshMeta(
            fingerprint = fingerprint,
            changedFromPrevious = changed,
            usable = frame.isUsable,
            fallback = false,
            weak = !frame.isUsable,
            stale = stale,
            reusedLastGood = false
        )

        if (frame.isUsable) {
            consecutiveWeakReads = 0
            consecutiveStaleReads = if (stale) consecutiveStaleReads + 1 else 0
            adoptCanonicalRuntimeState(frame.screenState)
        } else {
            consecutiveWeakReads += 1
            if (stale) consecutiveStaleReads += 1
        }
        return frame.screenState
    }

    suspend fun ensureStrongObservationGate(
        expectedPackage: String = "",
        timeoutMs: Long = 2600L,
        maxAttempts: Int = 2,
        allowLauncherSurface: Boolean = false,
        tier: ObservationGateTier = ObservationGateTier.SEMANTIC_ACTION_SAFE,
        staleRetryAttempts: Int = 2,
        missingPackageRetryAttempts: Int = 2
    ): ObservationGateResult {
        var lastState = resolveCanonicalRuntimeState()
        var staleSeen = 0
        var missingSeen = 0

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val state = requestFreshScreen(timeoutMs, expectedPackage)
            lastState = state
            val ready = KaiObservationReadiness.evaluate(
                state = state,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface,
                tier = if (tier == ObservationGateTier.APP_LAUNCH_SAFE) KaiObservationReadiness.Tier.APP_LAUNCH_SAFE else KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
            )
            if (ready.passed) return ObservationGateResult(true, state, ready.reason)

            if (lastRefreshMeta.stale) staleSeen += 1
            if (state.packageName.isBlank()) missingSeen += 1
            val shouldRetry = when {
                lastRefreshMeta.stale && staleSeen <= staleRetryAttempts -> true
                state.packageName.isBlank() && missingSeen <= missingPackageRetryAttempts -> true
                attempt < maxAttempts - 1 -> true
                else -> false
            }
            if (!shouldRetry) return ObservationGateResult(false, state, ready.reason)
            delay(140L)
        }
        return ObservationGateResult(false, lastState, "observation_not_ready")
    }

    suspend fun ensureAuthoritativeObservationReady(
        timeoutMs: Long = 3200L,
        allowLauncherSurface: Boolean = false,
        tier: ObservationGateTier = ObservationGateTier.SEMANTIC_ACTION_SAFE,
        maxAttempts: Int = 3
    ): ObservationReadinessResult {
        var lastState = resolveCanonicalRuntimeState()
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val obs = KaiLiveObservationRuntime.bestObservation(requireStrong = attempt == 0)
            if (obs.updatedAt > 0L) {
                val state = KaiVisionInterpreter.toScreenState(obs)
                lastState = state
                val ready = KaiObservationReadiness.evaluate(
                    state = state,
                    allowLauncherSurface = allowLauncherSurface,
                    tier = if (tier == ObservationGateTier.APP_LAUNCH_SAFE) KaiObservationReadiness.Tier.APP_LAUNCH_SAFE else KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
                )
                if (ready.passed) {
                    adoptCanonicalRuntimeState(state)
                    return ObservationReadinessResult(true, state, ready.reason, attempt + 1)
                }
            }
            val fresh = requestFreshScreen(timeoutMs)
            lastState = fresh
            val readyFresh = KaiObservationReadiness.evaluate(
                state = fresh,
                allowLauncherSurface = allowLauncherSurface,
                tier = if (tier == ObservationGateTier.APP_LAUNCH_SAFE) KaiObservationReadiness.Tier.APP_LAUNCH_SAFE else KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
            )
            if (readyFresh.passed) {
                adoptCanonicalRuntimeState(fresh)
                return ObservationReadinessResult(true, fresh, readyFresh.reason, attempt + 1)
            }
            if (attempt < maxAttempts - 1) delay(160L)
        }
        return ObservationReadinessResult(false, lastState, "observation_not_ready", maxAttempts)
    }

    suspend fun attemptRecoveryForStep(step: KaiActionStep, state: KaiScreenState): KaiActionExecutionResult {
        val key = "${step.cmd}|${step.selectorRole}|${step.selectorText}|${state.packageName}|${state.surfaceFamily()}"
        repeatedRecoveryContextCount = if (key == lastRecoveryContextKey) repeatedRecoveryContextCount + 1 else 1
        lastRecoveryContextKey = key
        if (repeatedRecoveryContextCount > 1) return KaiActionExecutionResult(false, "replan_required:recovery_repeat_blocked", state)

        val needsBack = state.isOverlayPolluted() || state.isCameraOrMediaOverlaySurface() || state.isSheetOrDialogSurface() || state.isSearchLikeSurface()
        return if (needsBack) {
            sendKaiCmdSuppressed(KaiAccessibilityService.CMD_BACK, expectedPackage = step.expectedPackage.ifBlank { state.packageName }, preDelayMs = 70L, postDelayMs = 100L)
            KaiActionExecutionResult(true, "recovery_back_once", requestFreshScreen(2400L, step.expectedPackage.ifBlank { state.packageName }))
        } else {
            KaiActionExecutionResult(false, "replan_required:no_recovery_needed", state)
        }
    }

    suspend fun executeStep(step: KaiActionStep): KaiActionExecutionResult = executeStep(step, resolveCanonicalRuntimeState())

    suspend fun executeStep(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult = when (step.normalizedCommand()) {
        "open_app" -> executeOpenApp(step, currentState)
        "click_text" -> simpleCommand(KaiAccessibilityService.CMD_CLICK_TEXT, step.semanticPayload(), step, currentState, "click_text_sent")
        "long_press_text" -> simpleCommand(KaiAccessibilityService.CMD_LONG_PRESS_TEXT, step.semanticPayload(), step, currentState, "long_press_text_sent", holdMs = step.holdMs)
        "click_best_match", "open_best_list_item" -> simpleCommand(KaiAccessibilityService.CMD_CLICK_TEXT, step.selectorText.ifBlank { step.text }, step, currentState, "click_best_match_sent")
        "focus_best_input" -> KaiActionExecutionResult(true, "focus_best_input_noop", currentState)
        "input_into_best_field", "input_text", "type_text" -> simpleCommand(KaiAccessibilityService.CMD_INPUT_TEXT, step.semanticPayload(), step, currentState, "input_sent")
        "press_primary_action" -> simpleCommand(KaiAccessibilityService.CMD_CLICK_TEXT, step.text.ifBlank { "send" }, step, currentState, "primary_action_sent")
        "scroll" -> scrollCommand(step, currentState)
        "back" -> navCommand(KaiAccessibilityService.CMD_BACK, step, currentState)
        "home" -> navCommand(KaiAccessibilityService.CMD_HOME, step, currentState)
        "recents" -> navCommand(KaiAccessibilityService.CMD_RECENTS, step, currentState)
        "read_screen", "verify_state", "wait_for_text" -> executeVerify(step, currentState)
        "tap_xy" -> pointCommand(KaiAccessibilityService.CMD_TAP_XY, step, currentState, "tap_sent")
        "long_press_xy" -> pointCommand(KaiAccessibilityService.CMD_LONG_PRESS_XY, step, currentState, "long_press_sent")
        "swipe_xy" -> pointCommand(KaiAccessibilityService.CMD_SWIPE_XY, step, currentState, "swipe_sent")
        "wait" -> executeWait(step, currentState)
        else -> KaiActionExecutionResult(false, "unsupported_command:${step.normalizedCommand()}", currentState)
    }

    internal fun expectedStateSatisfied(step: KaiActionStep, state: KaiScreenState): Boolean = KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, state)

    private fun markActionProgress(beforePackage: String, afterPackage: String, beforeFingerprint: String, afterFingerprint: String, reason: String) {
        val externalChange = beforePackage.isNotBlank() && afterPackage.isNotBlank() && beforePackage != afterPackage
        val fingerprintChanged = beforeFingerprint.isNotBlank() && afterFingerprint.isNotBlank() && beforeFingerprint != afterFingerprint
        if (externalChange || fingerprintChanged) {
            consecutiveNoProgressActions = 0
            onLog("action_progress:$reason")
        } else {
            consecutiveNoProgressActions += 1
        }
    }

    private suspend fun executeOpenApp(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val rawTarget = step.semanticPayload()
        val targetHint = KaiAppIdentityRegistry.resolveAppKey(rawTarget).ifBlank { KaiScreenStateParser.inferAppHint(rawTarget) }
        val targetPackage = step.expectedPackage.ifBlank { KaiAppIdentityRegistry.resolvePrimaryPackage(rawTarget) }

        if (rawTarget.isBlank() && targetHint.isBlank() && targetPackage.isBlank()) {
            return KaiActionExecutionResult(false, "open_app_missing_target", currentState, openAppOutcome = KaiOpenAppOutcome.OPEN_FAILED)
        }

        if (targetPackage.isNotBlank() && currentState.matchesExpectedPackage(targetPackage) && !currentState.isLauncher() && KaiVisionInterpreter.isUsableState(currentState)) {
            return KaiActionExecutionResult(true, "open_app_already_in_target", currentState, openAppOutcome = KaiOpenAppOutcome.TARGET_READY)
        }

        sendKaiCmdSuppressed(KaiAccessibilityService.CMD_OPEN_APP, text = targetHint.ifBlank { rawTarget }, expectedPackage = targetPackage, preDelayMs = 40L, postDelayMs = 220L)

        val beforeFingerprint = currentState.semanticFingerprint()
        var sawTransition = false

        repeat(3) {
            val after = requestFreshScreen(timeoutMs = 1400L, expectedPackage = targetPackage)
            val targetMatched = when {
                targetPackage.isNotBlank() -> after.matchesExpectedPackage(targetPackage)
                targetHint.isNotBlank() -> after.likelyMatchesAppHint(targetHint)
                else -> false
            }
            val usable = KaiVisionInterpreter.isUsableState(after)
            val progress = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) || beforeFingerprint != after.semanticFingerprint()

            if (targetMatched && usable && !after.isLauncher()) {
                softResetObservationState()
                markActionProgress(currentState.packageName, after.packageName, beforeFingerprint, after.semanticFingerprint(), "open_app")
                return KaiActionExecutionResult(true, "open_app_target_visible_usable", after, openAppOutcome = KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP)
            }

            if (progress && after.packageName.isNotBlank() && !after.isLauncher()) sawTransition = true
            delay(120L)
        }

        val stabilizedObs = KaiLiveObservationRuntime.awaitPostOpenStabilization(targetPackage, timeoutMs = 2600L)
        val stabilized = KaiVisionInterpreter.toScreenState(stabilizedObs)
        val stabilizedMatch = when {
            targetPackage.isNotBlank() -> stabilized.matchesExpectedPackage(targetPackage)
            targetHint.isNotBlank() -> stabilized.likelyMatchesAppHint(targetHint)
            else -> false
        }
        val stabilizedUsable = KaiVisionInterpreter.isUsableState(stabilized)

        if (stabilizedMatch && stabilizedUsable && !stabilized.isLauncher()) {
            softResetObservationState()
            markActionProgress(currentState.packageName, stabilized.packageName, beforeFingerprint, stabilized.semanticFingerprint(), "open_app_stabilized")
            return KaiActionExecutionResult(
                true,
                if (stabilized.isKaiLiveStrong(targetPackage)) "open_app_target_ready" else "open_app_target_visible_usable",
                stabilized,
                openAppOutcome = if (stabilized.isKaiLiveStrong(targetPackage)) KaiOpenAppOutcome.TARGET_READY else KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
            )
        }

        if (targetPackage.isNotBlank() && stabilized.packageName.isNotBlank() && !stabilized.isLauncher() && !stabilized.matchesExpectedPackage(targetPackage)) {
            return KaiActionExecutionResult(false, "open_app_wrong_package_after_launch", stabilized, openAppOutcome = KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED)
        }

        return KaiActionExecutionResult(
            false,
            if (sawTransition) "open_app_transition_unconfirmed" else "open_app_not_confirmed",
            stabilized,
            openAppOutcome = when {
                stabilized.packageName.isBlank() || stabilized.isLauncher() -> KaiOpenAppOutcome.OPEN_FAILED
                stabilizedMatch -> KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
                else -> KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS
            }
        )
    }

    private suspend fun simpleCommand(cmd: String, text: String, step: KaiActionStep, currentState: KaiScreenState, msg: String, holdMs: Long = 450L): KaiActionExecutionResult {
        if (text.isBlank() && cmd in setOf(KaiAccessibilityService.CMD_CLICK_TEXT, KaiAccessibilityService.CMD_LONG_PRESS_TEXT, KaiAccessibilityService.CMD_INPUT_TEXT)) {
            return KaiActionExecutionResult(false, "missing_text", currentState)
        }
        sendKaiCmdSuppressed(cmd, text = text, holdMs = holdMs, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }, preDelayMs = 30L, postDelayMs = 110L)
        val after = requestFreshScreen(timeoutMs = step.timeoutMs, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName })
        markActionProgress(currentState.packageName, after.packageName, currentState.semanticFingerprint(), after.semanticFingerprint(), msg)
        return KaiActionExecutionResult(true, msg, after)
    }

    private suspend fun scrollCommand(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        sendKaiCmdSuppressed(KaiAccessibilityService.CMD_SCROLL, dir = step.dir.ifBlank { "down" }, times = step.times.coerceAtLeast(1), expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }, preDelayMs = 30L, postDelayMs = 120L)
        val after = requestFreshScreen(timeoutMs = step.timeoutMs, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName })
        markActionProgress(currentState.packageName, after.packageName, currentState.semanticFingerprint(), after.semanticFingerprint(), "scroll")
        return KaiActionExecutionResult(true, "scroll_sent", after)
    }

    private suspend fun navCommand(cmd: String, step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        sendKaiCmdSuppressed(cmd, expectedPackage = step.expectedPackage, preDelayMs = 20L, postDelayMs = 120L)
        val after = requestFreshScreen(timeoutMs = step.timeoutMs, expectedPackage = step.expectedPackage)
        markActionProgress(currentState.packageName, after.packageName, currentState.semanticFingerprint(), after.semanticFingerprint(), cmd)
        return KaiActionExecutionResult(true, "system_nav_sent", after)
    }

    private suspend fun executeVerify(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val after = requestFreshScreen(timeoutMs = step.timeoutMs, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName })
        val success = if (step.expectedTexts.isNotEmpty() || step.expectedScreenKind.isNotBlank() || step.expectedPackage.isNotBlank()) expectedStateSatisfied(step, after) else KaiVisionInterpreter.isUsableState(after)
        return KaiActionExecutionResult(success, if (success) "verify_success" else "verify_not_ready", after)
    }

    private suspend fun pointCommand(cmd: String, step: KaiActionStep, currentState: KaiScreenState, msg: String): KaiActionExecutionResult {
        sendKaiCmdSuppressed(cmd, x = step.x, y = step.y, endX = step.endX, endY = step.endY, holdMs = step.holdMs, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }, preDelayMs = 20L, postDelayMs = 80L)
        val after = requestFreshScreen(timeoutMs = step.timeoutMs, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName })
        return KaiActionExecutionResult(true, msg, after)
    }

    private suspend fun executeWait(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        delay(step.waitMs.coerceAtLeast(80L))
        val after = requestFreshScreen(timeoutMs = step.timeoutMs, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName })
        return KaiActionExecutionResult(true, "wait_complete", after)
    }

    private suspend fun sendKaiCmdSuppressed(
        cmd: String,
        text: String = "",
        dir: String = "",
        times: Int = 1,
        x: Float? = null,
        y: Float? = null,
        endX: Float? = null,
        endY: Float? = null,
        holdMs: Long = 450L,
        expectedPackage: String = "",
        preDelayMs: Long = 25L,
        postDelayMs: Long = 90L
    ) {
        KaiBubbleManager.beginActionUiSuppression(false)
        try {
            if (preDelayMs > 0) delay(preDelayMs)
            context.sendBroadcast(
                Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(KaiAccessibilityService.EXTRA_CMD, cmd)
                    if (text.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_TEXT, text)
                    if (dir.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_DIR, dir)
                    putExtra(KaiAccessibilityService.EXTRA_TIMES, times)
                    if (x != null) putExtra(KaiAccessibilityService.EXTRA_X, x)
                    if (y != null) putExtra(KaiAccessibilityService.EXTRA_Y, y)
                    if (endX != null) putExtra(KaiAccessibilityService.EXTRA_END_X, endX)
                    if (endY != null) putExtra(KaiAccessibilityService.EXTRA_END_Y, endY)
                    putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, holdMs)
                    if (expectedPackage.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
                }
            )
            if (postDelayMs > 0) delay(postDelayMs)
        } finally {
            KaiBubbleManager.endActionUiSuppression(false)
        }
    }
}
