package com.example.reply.agent

import android.content.Context
import android.content.Intent
import com.example.reply.ui.KaiAccessibilityService
import com.example.reply.ui.KaiBubbleManager
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
        set(value) { gate.canonical = value }

    internal var lastGoodScreenState: KaiScreenState?
        get() = gate.canonical
        set(value) { gate.canonical = value }

    internal var lastAcceptedFingerprint: String
        get() = gate.lastAcceptedFingerprint
        set(value) { gate.lastAcceptedFingerprint = value }

    internal var lastAcceptedObservationAt: Long
        get() = gate.lastAcceptedObservationAt
        set(value) { gate.lastAcceptedObservationAt = value }

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
    fun getConsecutiveWeakReads(): Int = gate.consecutiveWeakReads
    fun getConsecutiveStaleReads(): Int = gate.consecutiveStaleReads
    fun bestRuntimeObservation(): KaiObservation = KaiObservationRuntime.getBestAvailable()

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
        tier: ObservationGateTier = ObservationGateTier.SEMANTIC_ACTION_SAFE,
        staleRetryAttempts: Int = 2,
        missingPackageRetryAttempts: Int = 2
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
            },
            staleRetryAttempts = staleRetryAttempts,
            missingPackageRetryAttempts = missingPackageRetryAttempts
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

        val needsBack = state.isOverlayPolluted() ||
            state.isCameraOrMediaOverlaySurface() ||
            state.isSheetOrDialogSurface() ||
            state.isSearchLikeSurface()

        return if (needsBack) {
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_BACK,
                expectedPackage = step.expectedPackage.ifBlank { state.packageName },
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
        return when (step.normalizedCommand()) {
            "open_app" -> executeOpenApp(step, currentState)
            "click_text" -> executeClickText(step, currentState)
            "long_press_text" -> executeLongPressText(step, currentState)
            "click_best_match", "open_best_list_item" -> executeClickBestMatch(step, currentState)
            "focus_best_input" -> executeFocusBestInput(step, currentState)
            "input_into_best_field", "input_text", "type_text" -> executeInput(step, currentState)
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
                message = "unsupported_command:${step.normalizedCommand()}",
                screenState = currentState
            )
        }
    }

    internal fun expectedStateSatisfied(step: KaiActionStep, state: KaiScreenState): Boolean {
        return KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, state)
    }

    private suspend fun executeOpenApp(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val rawTarget = step.semanticPayload()
        val targetHint = KaiAppIdentityRegistry.resolveAppKey(rawTarget).ifBlank {
            KaiScreenStateParser.inferAppHint(rawTarget)
        }
        val targetPackage = step.expectedPackage.ifBlank {
            KaiAppIdentityRegistry.resolvePrimaryPackage(rawTarget)
        }

        if (rawTarget.isBlank() && targetHint.isBlank() && targetPackage.isBlank()) {
            return KaiActionExecutionResult(
                success = false,
                message = "open_app_missing_target",
                screenState = currentState,
                openAppOutcome = KaiOpenAppOutcome.OPEN_FAILED
            )
        }

        if (targetPackage.isNotBlank() && currentState.matchesExpectedPackage(targetPackage) && !currentState.isLauncher()) {
            val outcome = if (currentState.isWeakObservation()) {
                KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
            } else {
                KaiOpenAppOutcome.TARGET_READY
            }
            return KaiActionExecutionResult(
                success = true,
                message = "open_app_already_in_target",
                screenState = currentState,
                openAppOutcome = outcome
            )
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_OPEN_APP,
            text = targetHint.ifBlank { rawTarget },
            expectedPackage = targetPackage,
            preDelayMs = 40L,
            postDelayMs = 220L
        )

        val beforeFingerprint = currentState.semanticFingerprint()
        var bestIntermediate: KaiScreenState? = null

        repeat(5) { attempt ->
            val after = requestFreshScreen(
                timeoutMs = 1800L + (attempt * 350L),
                expectedPackage = if (targetPackage.isNotBlank()) targetPackage else ""
            )

            val targetMatched = when {
                targetPackage.isNotBlank() -> after.matchesExpectedPackage(targetPackage)
                targetHint.isNotBlank() -> after.likelyMatchesAppHint(targetHint)
                else -> false
            }

            val progress = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
                beforeFingerprint != after.semanticFingerprint()

            if (targetMatched && !after.isLauncher()) {
                gate.markActionProgress(
                    currentState.packageName,
                    after.packageName,
                    beforeFingerprint,
                    after.semanticFingerprint(),
                    "open_app"
                )
                val outcome = if (after.isWeakObservation()) {
                    KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
                } else {
                    KaiOpenAppOutcome.TARGET_READY
                }
                return KaiActionExecutionResult(
                    success = true,
                    message = if (outcome == KaiOpenAppOutcome.TARGET_READY) "open_app_target_ready" else "open_app_target_weak",
                    screenState = after,
                    openAppOutcome = outcome
                )
            }

            if (progress && after.packageName.isNotBlank() && !after.isLauncher()) {
                bestIntermediate = after
            }

            if (targetPackage.isNotBlank() && after.packageName.isNotBlank() && !after.matchesExpectedPackage(targetPackage) && !after.isLauncher()) {
                return KaiActionExecutionResult(
                    success = false,
                    message = "open_app_wrong_package_after_launch",
                    screenState = after,
                    openAppOutcome = KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED
                )
            }

            delay(120L)
        }

        if (bestIntermediate != null) {
            return KaiActionExecutionResult(
                success = true,
                message = "open_app_transition_progress_only",
                screenState = bestIntermediate,
                openAppOutcome = KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS
            )
        }

        val finalState = requestFreshScreen(timeoutMs = 1800L, expectedPackage = if (targetPackage.isNotBlank()) targetPackage else "")
        val finalMatch = when {
            targetPackage.isNotBlank() -> finalState.matchesExpectedPackage(targetPackage)
            targetHint.isNotBlank() -> finalState.likelyMatchesAppHint(targetHint)
            else -> false
        }
        if (finalMatch && !finalState.isLauncher()) {
            val outcome = if (finalState.isWeakObservation()) {
                KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
            } else {
                KaiOpenAppOutcome.TARGET_READY
            }
            return KaiActionExecutionResult(
                success = true,
                message = "open_app_late_match",
                screenState = finalState,
                openAppOutcome = outcome
            )
        }

        if (targetPackage.isNotBlank() && finalState.packageName.isNotBlank() && !finalState.isLauncher() && !finalState.matchesExpectedPackage(targetPackage)) {
            return KaiActionExecutionResult(
                success = false,
                message = "open_app_final_wrong_package",
                screenState = finalState,
                openAppOutcome = KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED
            )
        }

        return KaiActionExecutionResult(
            success = false,
            message = "open_app_not_confirmed_yet",
            screenState = finalState,
            openAppOutcome = if (finalState.packageName.isBlank() || finalState.isLauncher()) {
                KaiOpenAppOutcome.OPEN_FAILED
            } else {
                KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS
            }
        )
    }

    private suspend fun executeClickText(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val text = step.semanticPayload()
        if (text.isBlank()) return KaiActionExecutionResult(false, "click_text_missing_text", currentState)
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
            text = text,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 160L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2600L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) || after.containsText(text)
        return KaiActionExecutionResult(success, if (success) "click_text_ok" else "click_text_no_progress", after)
    }

    private suspend fun executeLongPressText(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val text = step.semanticPayload()
        if (text.isBlank()) return KaiActionExecutionResult(false, "long_press_text_missing_text", currentState)
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_LONG_PRESS_TEXT,
            text = text,
            holdMs = step.holdMs,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 180L
        )
        val after = requestFreshScreen(2200L, step.expectedPackage.ifBlank { currentState.packageName })
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(success, if (success) "long_press_text_ok" else "long_press_text_no_progress", after)
    }

    private suspend fun executeClickBestMatch(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val query = step.selectorText.ifBlank { step.text }.trim()
        val element = selectSemanticElement(currentState, step)
        val issued = when {
            element != null -> tapSemanticElement(element)
            query.isNotBlank() -> {
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                    text = query,
                    expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
                    preDelayMs = 50L,
                    postDelayMs = 150L
                )
                true
            }
            else -> false
        }

        if (!issued) {
            return KaiActionExecutionResult(false, "ambiguous_click_target", currentState)
        }

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1400L, 2800L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) || expectedStateSatisfied(step, after)
        return KaiActionExecutionResult(success, if (success) "click_best_match_ok" else "click_best_match_no_progress", after)
    }

    private suspend fun executeFocusBestInput(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val field = currentState.findBestInputField(step.selectorHint.ifBlank { step.selectorText.ifBlank { step.text } })
        val issued = when {
            field != null -> tapSemanticElement(field)
            step.selectorText.isNotBlank() -> {
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                    text = step.selectorText,
                    expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
                    preDelayMs = 40L,
                    postDelayMs = 140L
                )
                true
            }
            else -> false
        }

        if (!issued) {
            return KaiActionExecutionResult(false, "focus_input_not_found", currentState)
        }

        val after = requestFreshScreen(2200L, step.expectedPackage.ifBlank { currentState.packageName })
        val success = after.findBestInputField(step.selectorHint.ifBlank { step.selectorText.ifBlank { step.text } }) != null ||
            KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(success, if (success) "focus_best_input_ok" else "focus_best_input_no_progress", after)
    }

    private suspend fun executeInput(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val text = step.semanticPayload()
        if (text.isBlank()) return KaiActionExecutionResult(false, "input_text_missing_text", currentState)

        val focused = currentState.findBestInputField(step.selectorHint)
        if (focused != null) {
            tapSemanticElement(focused)
            delay(90L)
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TYPE,
            text = text,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 160L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1400L, 2800L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = after.containsText(text) || after.editableTextSignature().contains(KaiScreenStateParser.normalize(text))
        return KaiActionExecutionResult(success, if (success) "input_text_ok" else "input_text_not_verified", after)
    }

    private suspend fun executePressPrimaryAction(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val send = currentState.findSendAction()
        val issued = when {
            send != null -> tapSemanticElement(send)
            step.selectorText.isNotBlank() -> {
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                    text = step.selectorText,
                    expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
                    preDelayMs = 40L,
                    postDelayMs = 160L
                )
                true
            }
            else -> false
        }

        if (!issued) return KaiActionExecutionResult(false, "primary_action_not_found", currentState)

        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1400L, 2800L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            (currentState.findSendAction() != null && after.findSendAction() == null)
        return KaiActionExecutionResult(success, if (success) "press_primary_action_ok" else "press_primary_action_no_progress", after)
    }

    private suspend fun executeScroll(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_SCROLL,
            dir = step.dir.ifBlank { "down" },
            times = step.times.coerceAtLeast(1),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 180L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(success, if (success) "scroll_ok" else "scroll_no_progress", after)
    }

    private suspend fun executeSystemNav(cmd: String, step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = cmd,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 150L
        )
        val after = requestFreshScreen(timeoutMs = 2200L, expectedPackage = "")
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after) ||
            cmd == KaiAccessibilityService.CMD_HOME || cmd == KaiAccessibilityService.CMD_BACK
        return KaiActionExecutionResult(success, "${cmd}_ok", after)
    }

    private suspend fun executeVerify(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        val after = if (step.normalizedCommand() == "wait_for_text" && step.expectedTexts.isNotEmpty()) {
            waitForExpectedText(
                expectedTexts = step.expectedTexts,
                timeoutMs = step.timeoutMs.coerceIn(1000L, 8000L),
                expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
            )
        } else {
            requestFreshScreen(
                timeoutMs = step.timeoutMs.coerceIn(1000L, 2600L),
                expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
            )
        }

        val success = expectedStateSatisfied(step, after) || KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(success, if (success) "verify_state_ok" else "verify_state_not_satisfied", after)
    }

    private suspend fun executeTap(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = step.x,
            y = step.y,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 140L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(success, if (success) "tap_xy_ok" else "tap_xy_no_progress", after)
    }

    private suspend fun executeLongPress(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_LONG_PRESS_XY,
            x = step.x,
            y = step.y,
            holdMs = step.holdMs,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 160L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(success, if (success) "long_press_xy_ok" else "long_press_xy_no_progress", after)
    }

    private suspend fun executeSwipe(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_SWIPE_XY,
            x = step.x,
            y = step.y,
            endX = step.endX,
            endY = step.endY,
            holdMs = step.holdMs,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 40L,
            postDelayMs = 160L
        )
        val after = requestFreshScreen(
            timeoutMs = step.timeoutMs.coerceIn(1200L, 2400L),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )
        val success = KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)
        return KaiActionExecutionResult(success, if (success) "swipe_xy_ok" else "swipe_xy_no_progress", after)
    }

    private suspend fun executeWait(step: KaiActionStep, currentState: KaiScreenState): KaiActionExecutionResult {
        delay(step.waitMs.coerceIn(80L, 12_000L))
        val after = requestFreshScreen(timeoutMs = 1400L, expectedPackage = step.expectedPackage.ifBlank { currentState.packageName })
        return KaiActionExecutionResult(
            success = true,
            message = if (KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, after)) "wait_observed_progress" else "wait_complete",
            screenState = after
        )
    }

    internal fun selectSemanticElement(state: KaiScreenState, step: KaiActionStep): KaiUiElement? {
        if (step.expectedPackage.isNotBlank() && !state.matchesExpectedPackage(step.expectedPackage)) return null

        val query = step.selectorText.ifBlank { step.text }
        return when {
            step.selectorRole.equals("input", true) ||
                step.selectorRole.equals("editor", true) ||
                step.selectorRole.equals("search_field", true) -> state.findBestInputField(step.selectorHint.ifBlank { query })
            step.selectorRole.equals("send_button", true) -> state.findSendAction()
            query.isNotBlank() -> state.findBestClickableTarget(query)
            else -> state.likelyPrimaryActions.firstOrNull() ?: state.likelyNavigationTargets.firstOrNull()
        }
    }

    private fun tapSemanticElement(element: KaiUiElement): Boolean {
        val (cx, cy) = parseCenterFromBounds(element.bounds)
        if (cx == null || cy == null) return false
        sendKaiCmd(cmd = KaiAccessibilityService.CMD_TAP_XY, x = cx, y = cy)
        return true
    }

    private fun parseCenterFromBounds(bounds: String): Pair<Float?, Float?> {
        val match = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(bounds.trim()) ?: return null to null
        val left = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null to null
        val top = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null to null
        val right = match.groupValues.getOrNull(3)?.toFloatOrNull() ?: return null to null
        val bottom = match.groupValues.getOrNull(4)?.toFloatOrNull() ?: return null to null
        return ((left + right) / 2f) to ((top + bottom) / 2f)
    }

    private suspend fun waitForExpectedText(expectedTexts: List<String>, timeoutMs: Long, expectedPackage: String): KaiScreenState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = resolveCanonicalRuntimeState()
        while (System.currentTimeMillis() < deadline) {
            latest = requestFreshScreen(timeoutMs = 1200L, expectedPackage = expectedPackage)
            if (expectedTexts.all { latest.containsText(it) }) return latest
            delay(120L)
        }
        return latest
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
        timeoutMs: Long = 0L,
        expectedPackage: String = "",
        preDelayMs: Long = 0L,
        postDelayMs: Long = 0L
    ) {
        KaiBubbleManager.beginActionUiSuppression()
        try {
            if (preDelayMs > 0) delay(preDelayMs)
            sendKaiCmd(cmd, text, dir, times, x, y, endX, endY, holdMs, timeoutMs, expectedPackage)
            if (postDelayMs > 0) delay(postDelayMs)
        } finally {
            KaiBubbleManager.endActionUiSuppression()
        }
    }

    private fun sendKaiCmd(
        cmd: String,
        text: String = "",
        dir: String = "",
        times: Int = 1,
        x: Float? = null,
        y: Float? = null,
        endX: Float? = null,
        endY: Float? = null,
        holdMs: Long = 450L,
        timeoutMs: Long = 0L,
        expectedPackage: String = ""
    ) {
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, cmd)
            if (text.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_TEXT, text)
            if (dir.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_DIR, dir)
            if (times > 1) putExtra(KaiAccessibilityService.EXTRA_TIMES, times)
            if (x != null) putExtra(KaiAccessibilityService.EXTRA_X, x)
            if (y != null) putExtra(KaiAccessibilityService.EXTRA_Y, y)
            if (endX != null) putExtra(KaiAccessibilityService.EXTRA_END_X, endX)
            if (endY != null) putExtra(KaiAccessibilityService.EXTRA_END_Y, endY)
            if (holdMs > 0L) putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, holdMs)
            if (timeoutMs > 0L) putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, timeoutMs)
            if (expectedPackage.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
        }
        context.sendBroadcast(intent)
    }
}
