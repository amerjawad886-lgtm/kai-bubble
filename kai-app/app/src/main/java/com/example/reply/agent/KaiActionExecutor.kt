package com.example.reply.agent

import android.content.Context
import android.content.Intent
import com.example.reply.ui.KaiAccessibilityService
import com.example.reply.ui.KaiBubbleManager
import com.example.reply.ui.KaiCommandParser
import kotlinx.coroutines.delay
import java.util.Locale

class KaiActionExecutor(
    internal val context: Context,
    internal val onLog: (String) -> Unit = {}
) {
    internal val gate = KaiObservationGate(context, onLog)

    data class ScreenRefreshMeta(
        val fingerprint: String,
        val changedFromPrevious: Boolean,
        val usable: Boolean,
        val fallback: Boolean,
        val weak: Boolean,
        val stale: Boolean,
        val reusedLastGood: Boolean
    )

    data class ObservationGateResult(
        val passed: Boolean,
        val state: KaiScreenState,
        val reason: String
    )

    data class ObservationReadinessResult(
        val passed: Boolean,
        val state: KaiScreenState,
        val reason: String,
        val attempts: Int
    )

    enum class ObservationGateTier {
        APP_LAUNCH_SAFE,
        SEMANTIC_ACTION_SAFE
    }

    internal var canonicalRuntimeState: KaiScreenState?
        get() = gate.canonical
        set(value) {
            gate.canonical = value
        }

    internal var lastGoodScreenState: KaiScreenState?
        get() = gate.canonical
        set(value) {
            gate.canonical = value
        }

    internal var lastAcceptedFingerprint: String
        get() = gate.lastAcceptedFingerprint
        set(value) {
            gate.lastAcceptedFingerprint = value
        }

    internal var lastAcceptedObservationAt: Long
        get() = gate.lastAcceptedObservationAt
        set(value) {
            gate.lastAcceptedObservationAt = value
        }

    internal var consecutiveWeakReads: Int
        get() = gate.consecutiveWeakReads
        set(_) {}

    internal var consecutiveStaleReads: Int
        get() = gate.consecutiveStaleReads
        set(_) {}

    internal var consecutiveNoProgressActions: Int
        get() = gate.consecutiveNoProgressActions
        set(_) {}

    internal var lastRefreshMeta: ScreenRefreshMeta
        get() = gate.lastMeta.let {
            ScreenRefreshMeta(
                fingerprint = it.fingerprint,
                changedFromPrevious = it.changedFromPrevious,
                usable = it.usable,
                fallback = it.fallback,
                weak = it.weak,
                stale = it.stale,
                reusedLastGood = it.reusedLastGood
            )
        }
        set(_) {}

    private var lastRecoveryContextKey: String = ""
    private var repeatedRecoveryContextCount: Int = 0

    fun resetRuntimeState(clearLastGoodScreen: Boolean = true) {
        if (clearLastGoodScreen) {
            gate.reset()
        } else {
            val preservedCanonical = gate.canonical
            val preservedFingerprint = gate.lastAcceptedFingerprint
            val preservedAcceptedAt = gate.lastAcceptedObservationAt
            gate.reset()
            gate.canonical = preservedCanonical
            gate.lastAcceptedFingerprint = preservedFingerprint
            gate.lastAcceptedObservationAt = preservedAcceptedAt
        }
        lastRecoveryContextKey = ""
        repeatedRecoveryContextCount = 0
    }

    internal fun softResetObservationState() {
        val preservedCanonical = gate.canonical
        val preservedFingerprint = gate.lastAcceptedFingerprint
        val preservedAcceptedAt = gate.lastAcceptedObservationAt
        gate.reset()
        gate.canonical = preservedCanonical
        gate.lastAcceptedFingerprint = preservedFingerprint
        gate.lastAcceptedObservationAt = preservedAcceptedAt
        lastRecoveryContextKey = ""
        repeatedRecoveryContextCount = 0
    }

    fun clearStartupFingerprintBaseline() {
        gate.clearStartupBaseline()
    }

    internal fun adoptCanonicalRuntimeState(state: KaiScreenState) {
        gate.adopt(state)
    }

    fun getCanonicalRuntimeState(): KaiScreenState? = gate.canonical

    internal fun resolveCanonicalRuntimeState(): KaiScreenState = gate.resolve()

    fun getLastRefreshMeta(): ScreenRefreshMeta = lastRefreshMeta

    suspend fun resetObservationTransitionStateForRun() {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_RESET_TRANSITION_STATE,
            preDelayMs = 20L,
            postDelayMs = 20L
        )
    }

    suspend fun requestFreshScreen(
        timeoutMs: Long = 2200L,
        expectedPackage: String = ""
    ): KaiScreenState {
        return gate.requestFreshScreen(timeoutMs = timeoutMs, expectedPackage = expectedPackage)
    }

    suspend fun ensureStrongObservationGate(
        expectedPackage: String = "",
        timeoutMs: Long = 2600L,
        maxAttempts: Int = 2,
        allowLauncherSurface: Boolean = false,
        tier: ObservationGateTier = ObservationGateTier.SEMANTIC_ACTION_SAFE
    ): ObservationGateResult {
        val result = gate.ensureStrongGate(
            expectedPackage = expectedPackage,
            timeoutMs = timeoutMs,
            maxAttempts = maxAttempts,
            allowLauncherSurface = allowLauncherSurface,
            tier = if (tier == ObservationGateTier.APP_LAUNCH_SAFE) {
                KaiObservationGate.GateTier.APP_LAUNCH_SAFE
            } else {
                KaiObservationGate.GateTier.SEMANTIC_ACTION_SAFE
            }
        )
        return ObservationGateResult(result.passed, result.state, result.reason)
    }

    suspend fun ensureAuthoritativeObservationReady(
        timeoutMs: Long = 3200L,
        allowLauncherSurface: Boolean = false,
        tier: ObservationGateTier = ObservationGateTier.SEMANTIC_ACTION_SAFE,
        maxAttempts: Int = 3
    ): ObservationReadinessResult {
        val result = gate.ensureAuthoritative(
            timeoutMs = timeoutMs,
            allowLauncherSurface = allowLauncherSurface,
            tier = if (tier == ObservationGateTier.APP_LAUNCH_SAFE) {
                KaiObservationGate.GateTier.APP_LAUNCH_SAFE
            } else {
                KaiObservationGate.GateTier.SEMANTIC_ACTION_SAFE
            },
            maxAttempts = maxAttempts
        )
        return ObservationReadinessResult(result.passed, result.state, result.reason, result.attempts)
    }

    suspend fun attemptRecoveryForStep(
        step: KaiActionStep,
        state: KaiScreenState
    ): KaiActionExecutionResult {
        val key = "${step.cmd}|${step.selectorRole}|${step.selectorText}|${state.packageName}|${state.surfaceFamily()}"
        repeatedRecoveryContextCount = if (key == lastRecoveryContextKey) {
            repeatedRecoveryContextCount + 1
        } else {
            1
        }
        lastRecoveryContextKey = key

        if (repeatedRecoveryContextCount > 1) {
            return KaiActionExecutionResult(
                success = false,
                message = "replan_required:recovery_repeat_blocked",
                screenState = state
            )
        }

        val backRecovery = when {
            state.isOverlayPolluted() -> true
            state.isCameraOrMediaOverlaySurface() -> true
            state.isSheetOrDialogSurface() -> true
            else -> false
        }

        return if (backRecovery) {
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_BACK,
                preDelayMs = 70L,
                postDelayMs = 100L
            )
            KaiActionExecutionResult(
                success = true,
                message = "recovery_back_once",
                screenState = requestFreshScreen(
                    timeoutMs = 2400L,
                    expectedPackage = step.expectedPackage.ifBlank { state.packageName }
                )
            )
        } else {
            KaiActionExecutionResult(
                success = false,
                message = "replan_required:no_recovery_needed",
                screenState = state
            )
        }
    }

    suspend fun executeStep(step: KaiActionStep): KaiActionExecutionResult {
        val current = resolveCanonicalRuntimeState()
        return executeStep(step, current)
    }

    suspend fun executeStep(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val cmd = step.cmd.trim().lowercase(Locale.ROOT)
        val expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }

        val policyGateCommands = setOf(
            "click_best_match",
            "focus_best_input",
            "input_into_best_field",
            "press_primary_action",
            "open_best_list_item",
            "verify_state"
        )

        if (cmd in policyGateCommands) {
            val gateResult = ensureStrongObservationGate(
                expectedPackage = expectedPackage,
                timeoutMs = 2200L,
                maxAttempts = 2,
                allowLauncherSurface = false,
                tier = ObservationGateTier.SEMANTIC_ACTION_SAFE
            )
            if (!gateResult.passed) {
                return KaiActionExecutionResult(
                    success = false,
                    message = gateResult.reason,
                    screenState = gateResult.state
                )
            }
        }

        return when (cmd) {
            "open_app" -> executeOpenApp(step, currentState)
            "click_text" -> executeClickText(step, currentState)
            "long_press_text" -> executeLongPressText(step, currentState)
            "click_best_match", "open_best_list_item" -> executeClickBestMatch(step, currentState)
            "focus_best_input" -> executeFocusBestInput(step, currentState)
            "input_into_best_field" -> executeInputIntoBestField(step, currentState)
            "input_text", "type_text" -> executeInputText(step, currentState)
            "press_primary_action" -> executePressPrimaryAction(step, currentState)
            "scroll" -> executeScroll(step, currentState)
            "back" -> executeSystemNav(KaiAccessibilityService.CMD_BACK, step, currentState)
            "home" -> executeSystemNav(KaiAccessibilityService.CMD_HOME, step, currentState)
            "recents" -> executeSystemNav(KaiAccessibilityService.CMD_RECENTS, step, currentState)
            "read_screen", "verify_state", "wait_for_text" -> executeVerify(step, currentState)
            "tap_xy" -> executeTap(step, currentState)
            "long_press_xy" -> executeLongPress(step, currentState)
            "swipe_xy" -> executeSwipe(step, currentState)
            "wait" -> executeWait(step, currentState)
            else -> KaiActionExecutionResult(
                success = false,
                message = "unsupported_command:$cmd",
                screenState = currentState
            )
        }
    }

    private suspend fun executeOpenApp(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val target = step.text.ifBlank { step.selectorText }.trim()
        val resolved = KaiCommandParser.resolveAppAlias(target).ifBlank { target }
        val inferredKey = KaiScreenStateParser.inferAppHint(target)
        val expectedPackage = step.expectedPackage.ifBlank {
            KaiAppIdentityRegistry.packageCandidatesForKey(inferredKey).firstOrNull().orEmpty()
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_OPEN_APP,
            text = resolved,
            expectedPackage = expectedPackage,
            preDelayMs = 80L
        )

        var after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 3200L),
            expectedPackage = expectedPackage
        )

        if (after.packageName.isBlank() && expectedPackage.isNotBlank()) {
            after = requestFreshScreen(1600L, expectedPackage)
        }

        val arrived = after.packageName.isNotBlank() && (
            expectedPackage.isBlank() ||
                KaiAppIdentityRegistry.packageMatchesFamily(expectedPackage, after.packageName) ||
                after.matchesExpectedPackage(expectedPackage)
            )

        return KaiActionExecutionResult(
            success = arrived,
            message = if (arrived) "open_app_arrived" else "open_app_failed",
            screenState = after
        )
    }

    private suspend fun executeClickText(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val target = step.text.ifBlank { step.selectorText }.trim()
        if (target.isBlank()) {
            return KaiActionExecutionResult(false, "click_text_missing_target", currentState)
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
            text = target,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 60L,
            postDelayMs = 80L
        )

        var after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2600L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        if (!KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) &&
            !KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after)
        ) {
            val candidate = findTapCandidate(currentState, target, step)
            if (candidate != null) {
                onLog("click_text fallback attempt (click_text + gesture) for '$target'")
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_TAP_XY,
                    x = candidate.first,
                    y = candidate.second,
                    expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
                    preDelayMs = 70L,
                    postDelayMs = 120L
                )
                after = requestFreshScreen(
                    timeoutMs = 2200L,
                    expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
                )
            } else {
                onLog("click_text gesture fallback skipped: no safe coordinates")
            }
        }

        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after)

        return KaiActionExecutionResult(
            success = success,
            message = if (success) "click_text_ok" else "click_text_failed",
            screenState = after
        )
    }

    private suspend fun executeLongPressText(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val target = step.text.ifBlank { step.selectorText }.trim()
        if (target.isBlank()) {
            return KaiActionExecutionResult(false, "long_press_text_missing_target", currentState)
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_LONG_PRESS_TEXT,
            text = target,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            holdMs = step.holdMs,
            preDelayMs = 60L,
            postDelayMs = 120L
        )

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1400L, 2800L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after)

        return KaiActionExecutionResult(
            success = success,
            message = if (success) "long_press_text_ok" else "long_press_text_failed",
            screenState = after
        )
    }

    private suspend fun executeClickBestMatch(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val candidate = findTapCandidate(
            state = currentState,
            fallbackText = step.selectorText.ifBlank { step.text },
            step = step
        )
        if (candidate == null) {
            return KaiActionExecutionResult(false, "click_best_match_no_candidate", currentState)
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = candidate.first,
            y = candidate.second,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 60L,
            postDelayMs = 100L
        )

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1400L, 2600L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after)

        return KaiActionExecutionResult(
            success = success,
            message = if (success) "click_best_match_ok" else "click_best_match_failed",
            screenState = after
        )
    }

    private suspend fun executeFocusBestInput(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val candidate = findBestInputCandidate(currentState, step)
        if (candidate == null) {
            return KaiActionExecutionResult(false, "focus_best_input_no_candidate", currentState)
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = candidate.first,
            y = candidate.second,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 70L,
            postDelayMs = 100L
        )

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after)

        return KaiActionExecutionResult(
            success = success,
            message = if (success) "focus_best_input_ok" else "focus_best_input_failed",
            screenState = after
        )
    }

    private suspend fun executeInputIntoBestField(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val focused = executeFocusBestInput(step, currentState)
        if (!focused.success) return focused
        return executeInputText(step, focused.screenState)
    }

    private suspend fun executeInputText(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val text = step.text.trim()
        if (text.isBlank()) {
            return KaiActionExecutionResult(false, "input_text_missing_text", currentState)
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_INPUT_TEXT,
            text = text,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 70L,
            postDelayMs = 140L
        )

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1500L, 3200L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after) ||
            after.containsText(text)

        return KaiActionExecutionResult(
            success = success,
            message = if (success) "input_text_ok" else "input_text_failed",
            screenState = after
        )
    }

    private suspend fun executePressPrimaryAction(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val candidate = findPrimaryActionCandidate(currentState, step)
        if (candidate == null) {
            return KaiActionExecutionResult(false, "press_primary_action_no_candidate", currentState)
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = candidate.first,
            y = candidate.second,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 60L,
            postDelayMs = 120L
        )

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1500L, 2800L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after)

        return KaiActionExecutionResult(
            success = success,
            message = if (success) "press_primary_action_ok" else "press_primary_action_failed",
            screenState = after
        )
    }

    private suspend fun executeScroll(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_SCROLL,
            dir = step.dir.ifBlank { "down" },
            times = step.times.coerceIn(1, 10),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 50L,
            postDelayMs = 180L
        )

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(
            success = success,
            message = if (success) "scroll_ok" else "scroll_no_progress",
            screenState = after
        )
    }

    private suspend fun executeSystemNav(
        cmd: String,
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(cmd = cmd, preDelayMs = 50L, postDelayMs = 120L)
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2200L),
            expectedPackage = step.expectedPackage
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(
            success = success,
            message = if (success) "${cmd}_ok" else "${cmd}_no_progress",
            screenState = after
        )
    }

    private suspend fun executeVerify(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1000L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after) ||
            KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(
            success = success,
            message = if (success) "verify_state_ok" else "verify_state_failed",
            screenState = after
        )
    }

    private suspend fun executeTap(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = step.x,
            y = step.y,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 100L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1000L, 2200L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(
            success = success,
            message = if (success) "tap_xy_ok" else "tap_xy_no_progress",
            screenState = after
        )
    }

    private suspend fun executeLongPress(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_LONG_PRESS_XY,
            x = step.x,
            y = step.y,
            holdMs = step.holdMs,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 120L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(
            success = success,
            message = if (success) "long_press_xy_ok" else "long_press_xy_no_progress",
            screenState = after
        )
    }

    private suspend fun executeSwipe(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_SWIPE_XY,
            x = step.x,
            y = step.y,
            endX = step.endX,
            endY = step.endY,
            holdMs = step.holdMs,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 150L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(
            success = success,
            message = if (success) "swipe_xy_ok" else "swipe_xy_no_progress",
            screenState = after
        )
    }

    private suspend fun executeWait(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        delay(step.waitMs.coerceIn(80L, 12000L))
        val after = requestFreshScreen(
            timeoutMs = 1400L,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, after)
        return KaiActionExecutionResult(
            success = true,
            message = if (success) "wait_observed_progress" else "wait_complete",
            screenState = after
        )
    }

    private fun findTapCandidate(
        state: KaiScreenState,
        fallbackText: String,
        step: KaiActionStep
    ): Pair<Float, Float>? {
        val queryTexts = buildList {
            val primary = step.selectorText.ifBlank { fallbackText }.trim()
            if (primary.isNotBlank()) add(primary)
            if (step.text.isNotBlank() && step.text != primary) add(step.text.trim())
            if (step.selectorHint.isNotBlank()) add(step.selectorHint.trim())
        }.distinct()

        val pool = buildList {
            addAll(state.elements)
            addAll(state.likelyNavigationTargets)
            addAll(state.likelyPrimaryActions)
            addAll(state.likelyInputFields)
        }.distinctBy {
            "${it.text}|${it.contentDescription}|${it.hint}|${it.viewId}|${it.bounds}|${it.roleGuess}"
        }

        val best = pool
            .mapNotNull { element ->
                val score = scoreElementForStep(element, queryTexts, step)
                if (score <= 0) null else element to score
            }
            .maxByOrNull { it.second }
            ?.first

        return best?.bounds?.let { KaiGestureUtils.safeTapFromBounds(it) }
    }

    private fun findBestInputCandidate(
        state: KaiScreenState,
        step: KaiActionStep
    ): Pair<Float, Float>? {
        val pool = buildList {
            addAll(state.likelyInputFields)
            addAll(state.elements.filter { it.editable })
        }.distinctBy {
            "${it.text}|${it.contentDescription}|${it.hint}|${it.viewId}|${it.bounds}|${it.roleGuess}"
        }

        val queryTexts = listOf(
            step.selectorHint.trim(),
            step.selectorText.trim(),
            step.text.trim()
        ).filter { it.isNotBlank() }

        val best = pool
            .mapNotNull { element ->
                val score = scoreElementForInput(element, queryTexts)
                if (score <= 0) null else element to score
            }
            .maxByOrNull { it.second }
            ?.first

        return best?.bounds?.let { KaiGestureUtils.safeTapFromBounds(it) }
    }

    private fun findPrimaryActionCandidate(
        state: KaiScreenState,
        step: KaiActionStep
    ): Pair<Float, Float>? {
        val pool = buildList {
            addAll(state.likelyPrimaryActions)
            addAll(state.elements.filter { it.clickable })
        }.distinctBy {
            "${it.text}|${it.contentDescription}|${it.hint}|${it.viewId}|${it.bounds}|${it.roleGuess}"
        }

        val queryTexts = listOf(
            step.selectorText.trim(),
            step.text.trim(),
            "send",
            "ارسال",
            "إرسال",
            "save",
            "done",
            "تم"
        ).filter { it.isNotBlank() }

        val best = pool
            .mapNotNull { element ->
                val score = scoreElementForPrimaryAction(element, queryTexts)
                if (score <= 0) null else element to score
            }
            .maxByOrNull { it.second }
            ?.first

        return best?.bounds?.let { KaiGestureUtils.safeTapFromBounds(it) }
    }

    private fun scoreElementForStep(
        element: KaiUiElement,
        queries: List<String>,
        step: KaiActionStep
    ): Int {
        val joined = semanticJoined(element)
        var score = 0

        queries.forEach { query ->
            val normQuery = KaiScreenStateParser.normalize(query)
            if (normQuery.isBlank()) return@forEach
            if (joined == normQuery) score += 90
            if (joined.contains(normQuery)) score += 50
            if (KaiScreenStateParser.isLooseTextMatch(joined, normQuery)) score += 24
        }

        val role = step.selectorRole.trim().lowercase(Locale.ROOT)
        if (role.isNotBlank()) {
            val r = element.roleGuess.trim().lowercase(Locale.ROOT)
            if (r == role) score += 26
            if (r.contains(role) || role.contains(r)) score += 12
        }

        if (element.clickable) score += 10
        if (element.bounds.isNotBlank()) score += 6
        return score
    }

    private fun scoreElementForInput(
        element: KaiUiElement,
        queries: List<String>
    ): Int {
        val joined = semanticJoined(element)
        var score = 0
        if (element.editable) score += 60
        if (element.roleGuess.contains("input", true) || element.roleGuess.contains("composer", true)) score += 25

        queries.forEach { query ->
            val normQuery = KaiScreenStateParser.normalize(query)
            if (normQuery.isBlank()) return@forEach
            if (joined == normQuery) score += 50
            if (joined.contains(normQuery)) score += 32
            if (KaiScreenStateParser.isLooseTextMatch(joined, normQuery)) score += 18
        }

        if (element.bounds.isNotBlank()) score += 6
        return score
    }

    private fun scoreElementForPrimaryAction(
        element: KaiUiElement,
        queries: List<String>
    ): Int {
        val joined = semanticJoined(element)
        var score = 0
        if (element.clickable) score += 18
        if (element.roleGuess.contains("send", true) || element.roleGuess.contains("primary", true)) score += 32

        queries.forEach { query ->
            val normQuery = KaiScreenStateParser.normalize(query)
            if (normQuery.isBlank()) return@forEach
            if (joined == normQuery) score += 50
            if (joined.contains(normQuery)) score += 30
            if (KaiScreenStateParser.isLooseTextMatch(joined, normQuery)) score += 16
        }

        if (element.bounds.isNotBlank()) score += 6
        return score
    }

    private fun semanticJoined(element: KaiUiElement): String {
        return KaiScreenStateParser.normalize(
            listOf(
                element.text,
                element.contentDescription,
                element.hint,
                element.viewId,
                element.roleGuess
            ).joinToString(" ")
        )
    }

    private suspend fun sendKaiCmdSuppressed(
        cmd: String,
        text: String = "",
        expectedPackage: String = "",
        dir: String = "",
        times: Int = 1,
        x: Float? = null,
        y: Float? = null,
        endX: Float? = null,
        endY: Float? = null,
        holdMs: Long? = null,
        timeoutMs: Long? = null,
        preDelayMs: Long = 60L,
        postDelayMs: Long = 0L,
        strongObservationMode: Boolean = false
    ) {
        KaiBubbleManager.beginActionUiSuppression(strongObservationMode)
        try {
            if (preDelayMs > 0L) delay(preDelayMs)
            sendKaiCmd(
                cmd = cmd,
                text = text,
                expectedPackage = expectedPackage,
                dir = dir,
                times = times,
                x = x,
                y = y,
                endX = endX,
                endY = endY,
                holdMs = holdMs,
                timeoutMs = timeoutMs
            )
            if (postDelayMs > 0L) delay(postDelayMs)
        } finally {
            KaiBubbleManager.endActionUiSuppression(strongObservationMode)
        }
    }

    private fun sendKaiCmd(
        cmd: String,
        text: String = "",
        expectedPackage: String = "",
        dir: String = "",
        times: Int = 1,
        x: Float? = null,
        y: Float? = null,
        endX: Float? = null,
        endY: Float? = null,
        holdMs: Long? = null,
        timeoutMs: Long? = null
    ) {
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, cmd)
            putExtra(KaiAccessibilityService.EXTRA_TEXT, text)
            if (expectedPackage.isNotBlank()) {
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
            }
            putExtra(KaiAccessibilityService.EXTRA_TIMES, times.coerceIn(1, 10))
            if (dir.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_DIR, dir)
            if (x != null) putExtra(KaiAccessibilityService.EXTRA_X, x)
            if (y != null) putExtra(KaiAccessibilityService.EXTRA_Y, y)
            if (endX != null) putExtra(KaiAccessibilityService.EXTRA_END_X, endX)
            if (endY != null) putExtra(KaiAccessibilityService.EXTRA_END_Y, endY)
            if (holdMs != null) putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, holdMs)
            if (timeoutMs != null) putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, timeoutMs)
        }
        context.sendBroadcast(intent)
    }
}
