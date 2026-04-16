package com.example.reply.agent

import android.content.Context
import android.content.Intent
import com.example.reply.ui.KaiAccessibilityService
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
        val reusedLastGood: Boolean = false,
        val source: String = "none"
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

    internal fun resolveCanonicalRuntimeState(): KaiScreenState {
        val truth = KaiVisualInterpreter.resolveTruth(
            canonicalState = canonicalRuntimeState,
            allowLauncherSurface = true,
            requireStrong = false
        )
        return truth.state
    }

    fun getLastRefreshMeta(): ScreenRefreshMeta = lastRefreshMeta
    fun getConsecutiveWeakReads(): Int = consecutiveWeakReads
    fun getConsecutiveStaleReads(): Int = consecutiveStaleReads
    fun bestRuntimeObservation(): KaiObservation =
        KaiLiveObservationRuntime.bestObservation(requireStrong = false)

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
        fun commitChosen(
            chosenState: KaiScreenState,
            usable: Boolean,
            fallback: Boolean,
            source: String
        ): KaiScreenState {
            val fingerprint = chosenState.semanticFingerprint().take(5000)
            val changed = fingerprint != lastAcceptedFingerprint
            val stale = !changed && lastAcceptedFingerprint.isNotBlank()

            lastRefreshMeta = ScreenRefreshMeta(
                fingerprint = fingerprint,
                changedFromPrevious = changed,
                usable = usable,
                fallback = fallback,
                weak = !usable,
                stale = stale,
                reusedLastGood = source == "canonical",
                source = source
            )

            if (usable) {
                consecutiveWeakReads = 0
                consecutiveStaleReads = if (stale) consecutiveStaleReads + 1 else 0
                adoptCanonicalRuntimeState(chosenState)
            } else {
                consecutiveWeakReads += 1
                if (stale) consecutiveStaleReads += 1
            }

            return chosenState
        }

        val immediateTruth = KaiVisualInterpreter.resolveTruth(
            expectedPackage = expectedPackage,
            allowLauncherSurface = true,
            requireStrong = false,
            canonicalState = canonicalRuntimeState,
            preferredFreshWindowMs = 800L,
            strictFreshWindowMs = 350L
        )

        if (immediateTruth.usable &&
            immediateTruth.source != KaiVisualInterpreter.Source.CANONICAL_FALLBACK &&
            immediateTruth.source != KaiVisualInterpreter.Source.EMPTY
        ) {
            return commitChosen(
                chosenState = immediateTruth.state,
                usable = true,
                fallback = false,
                source = immediateTruth.source.name.lowercase()
            )
        }

        val afterTime = System.currentTimeMillis()

        if (!KaiLiveObservationRuntime.hasRecentEventDriven(300L)) {
            KaiLiveObservationRuntime.requestImmediateDump(expectedPackage)
        }

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

        if (frame.isUsable && (expectedPackage.isBlank() || frame.expectedPackageMatched)) {
            return commitChosen(
                chosenState = frame.screenState,
                usable = true,
                fallback = false,
                source = if (KaiLiveObservationRuntime.hasRecentEventDriven(500L)) {
                    "event_driven"
                } else {
                    "live_dump"
                }
            )
        }

        val fallbackTruth = KaiVisualInterpreter.resolveTruth(
            expectedPackage = expectedPackage,
            allowLauncherSurface = true,
            requireStrong = false,
            canonicalState = canonicalRuntimeState,
            preferredFreshWindowMs = 1400L,
            strictFreshWindowMs = 650L
        )

        return commitChosen(
            chosenState = fallbackTruth.state,
            usable = fallbackTruth.usable,
            fallback = true,
            source = fallbackTruth.source.name.lowercase()
        )
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

            val ready = KaiVisionInterpreter.evaluateReadiness(
                state = state,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface,
                requireStrong = tier != ObservationGateTier.APP_LAUNCH_SAFE
            )
            if (ready.passed) {
                return ObservationGateResult(true, state, ready.reason)
            }

            if (lastRefreshMeta.stale) staleSeen += 1
            if (state.packageName.isBlank()) missingSeen += 1

            val shouldRetry = when {
                lastRefreshMeta.stale && staleSeen <= staleRetryAttempts -> true
                state.packageName.isBlank() && missingSeen <= missingPackageRetryAttempts -> true
                attempt < maxAttempts - 1 -> true
                else -> false
            }

            if (!shouldRetry) {
                return ObservationGateResult(false, state, ready.reason)
            }

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
            val truth = KaiVisualInterpreter.resolveTruth(
                allowLauncherSurface = allowLauncherSurface,
                requireStrong = attempt == 0 && tier != ObservationGateTier.APP_LAUNCH_SAFE,
                canonicalState = canonicalRuntimeState,
                preferredFreshWindowMs = 1000L,
                strictFreshWindowMs = 500L
            )

            lastState = truth.state

            val ready = KaiVisionInterpreter.evaluateReadiness(
                state = truth.state,
                allowLauncherSurface = allowLauncherSurface,
                requireStrong = tier != ObservationGateTier.APP_LAUNCH_SAFE
            )

            if (ready.passed) {
                adoptCanonicalRuntimeState(truth.state)
                return ObservationReadinessResult(true, truth.state, ready.reason, attempt + 1)
            }

            val fresh = requestFreshScreen(timeoutMs)
            lastState = fresh

            val readyFresh = KaiVisionInterpreter.evaluateReadiness(
                state = fresh,
                allowLauncherSurface = allowLauncherSurface,
                requireStrong = tier != ObservationGateTier.APP_LAUNCH_SAFE
            )

            if (readyFresh.passed) {
                adoptCanonicalRuntimeState(fresh)
                return ObservationReadinessResult(true, fresh, readyFresh.reason, attempt + 1)
            }

            if (attempt < maxAttempts - 1) {
                delay(160L)
            }
        }

        return ObservationReadinessResult(false, lastState, "observation_not_ready", maxAttempts)
    }

    suspend fun attemptRecoveryForStep(
        step: KaiActionStep,
        state: KaiScreenState
    ): KaiActionExecutionResult {
        val key =
            "${step.cmd}|${step.selectorRole}|${step.selectorText}|${state.packageName}|${state.surfaceFamily()}"

        repeatedRecoveryContextCount =
            if (key == lastRecoveryContextKey) repeatedRecoveryContextCount + 1 else 1
        lastRecoveryContextKey = key

        if (repeatedRecoveryContextCount > 1) {
            return KaiActionExecutionResult(
                success = false,
                message = "replan_required:recovery_repeat_blocked",
                screenState = state
            )
        }

        val needsBack =
            state.isOverlayPolluted() ||
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

    suspend fun executeStep(step: KaiActionStep): KaiActionExecutionResult =
        executeStep(step, resolveCanonicalRuntimeState())

    suspend fun executeStep(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult = when (step.normalizedCommand()) {
        "open_app" -> executeOpenApp(step, currentState)
        "click_text" ->
            simpleCommand(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = step.semanticPayload(),
                step = step,
                currentState = currentState,
                successMessage = "click_text_sent"
            )
        "long_press_text" ->
            simpleCommand(
                cmd = KaiAccessibilityService.CMD_LONG_PRESS_TEXT,
                text = step.semanticPayload(),
                step = step,
                currentState = currentState,
                successMessage = "long_press_text_sent",
                holdMs = step.holdMs
            )
        "click_best_match", "open_best_list_item" ->
            simpleCommand(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = step.selectorText.ifBlank { step.text },
                step = step,
                currentState = currentState,
                successMessage = "click_best_match_sent"
            )
        "focus_best_input" ->
            KaiActionExecutionResult(
                success = true,
                message = "focus_best_input_noop",
                screenState = currentState
            )
        "input_into_best_field", "input_text", "type_text" ->
            simpleCommand(
                cmd = KaiAccessibilityService.CMD_INPUT_TEXT,
                text = step.semanticPayload(),
                step = step,
                currentState = currentState,
                successMessage = "input_sent"
            )
        "press_primary_action" ->
            simpleCommand(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = step.text.ifBlank { "send" },
                step = step,
                currentState = currentState,
                successMessage = "primary_action_sent"
            )
        "scroll" -> scrollCommand(step, currentState)
        "back" -> navCommand(KaiAccessibilityService.CMD_BACK, step, currentState)
        "home" -> navCommand(KaiAccessibilityService.CMD_HOME, step, currentState)
        "recents" -> navCommand(KaiAccessibilityService.CMD_RECENTS, step, currentState)
        else ->
            KaiActionExecutionResult(
                success = false,
                message = "unsupported_step:${step.cmd}",
                screenState = currentState,
                hardStop = false
            )
    }

    private suspend fun executeOpenApp(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        val targetText = step.semanticPayload().ifBlank { step.text }.trim()
        val inferredAppKey = KaiScreenStateParser.inferAppHint(targetText)
        val expectedPackage = step.expectedPackage.ifBlank {
            KaiAppIdentityRegistry.primaryPackageForKey(inferredAppKey)
        }

        if (targetText.isBlank()) {
            return KaiActionExecutionResult(
                success = false,
                message = "open_app_missing_target",
                screenState = currentState,
                openAppOutcome = KaiOpenAppOutcome.OPEN_FAILED
            )
        }

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_OPEN_APP,
            text = targetText,
            expectedPackage = expectedPackage,
            preDelayMs = 40L,
            postDelayMs = 220L
        )

        val outcome = KaiLiveObservationRuntime.awaitPostOpenStabilization(
            expectedPackage = expectedPackage,
            timeoutMs = 2600L
        )

        val refreshed = requestFreshScreen(
            timeoutMs = 2400L,
            expectedPackage = expectedPackage
        )

        val message = when (outcome) {
            KaiOpenAppOutcome.TARGET_READY -> "action_progress:open_app_target_ready"
            KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP -> "action_progress:open_app_stabilized"
            KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS -> "action_progress:open_app_transition"
            KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED -> "open_app_wrong_package"
            KaiOpenAppOutcome.OPEN_FAILED -> "open_app_failed"
        }

        return KaiActionExecutionResult(
            success = outcome != KaiOpenAppOutcome.OPEN_FAILED &&
                outcome != KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED,
            message = message,
            screenState = refreshed,
            openAppOutcome = outcome
        )
    }

    private suspend fun simpleCommand(
        cmd: String,
        text: String,
        step: KaiActionStep,
        currentState: KaiScreenState,
        successMessage: String,
        holdMs: Long = 450L
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = cmd,
            text = text,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            holdMs = holdMs,
            preDelayMs = 30L,
            postDelayMs = 120L
        )

        val refreshed = requestFreshScreen(
            timeoutMs = 2200L,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        val progressed =
            KaiExecutionDecisionAuthority.hasMeaningfulProgress(currentState, refreshed)

        consecutiveNoProgressActions = if (progressed) 0 else consecutiveNoProgressActions + 1

        return KaiActionExecutionResult(
            success = true,
            message = if (progressed) successMessage else "${successMessage}_no_visible_progress",
            screenState = refreshed
        )
    }

    private suspend fun scrollCommand(
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_SCROLL,
            dir = step.direction.ifBlank { "down" },
            times = step.times.coerceIn(1, 4),
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 20L,
            postDelayMs = 140L
        )

        val refreshed = requestFreshScreen(
            timeoutMs = 2200L,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
        )

        return KaiActionExecutionResult(
            success = true,
            message = "scroll_sent",
            screenState = refreshed
        )
    }

    private suspend fun navCommand(
        cmd: String,
        step: KaiActionStep,
        currentState: KaiScreenState
    ): KaiActionExecutionResult {
        sendKaiCmdSuppressed(
            cmd = cmd,
            expectedPackage = step.expectedPackage.ifBlank { currentState.packageName },
            preDelayMs = 20L,
            postDelayMs = 120L
        )

        val refreshed = requestFreshScreen(timeoutMs = 1800L)

        return KaiActionExecutionResult(
            success = true,
            message = "${cmd}_sent",
            screenState = refreshed
        )
    }

    private suspend fun sendKaiCmdSuppressed(
        cmd: String,
        text: String = "",
        dir: String = "",
        times: Int = 1,
        expectedPackage: String = "",
        holdMs: Long = 450L,
        preDelayMs: Long = 0L,
        postDelayMs: Long = 0L
    ) {
        if (preDelayMs > 0) delay(preDelayMs)

        val appContext = context.applicationContext
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(appContext.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, cmd)
            if (text.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_TEXT, text)
            if (dir.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_DIR, dir)
            putExtra(KaiAccessibilityService.EXTRA_TIMES, times)
            putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, holdMs)
            if (expectedPackage.isNotBlank()) {
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
            }
        }

        appContext.sendBroadcast(intent)

        if (postDelayMs > 0) delay(postDelayMs)
    }
}