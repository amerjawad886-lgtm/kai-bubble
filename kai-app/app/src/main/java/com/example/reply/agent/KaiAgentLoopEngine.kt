package com.example.reply.agent

import android.content.Context
import com.example.reply.data.supabase.KaiAgentStateRepository
import com.example.reply.ui.KaiBubbleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class KaiAgentLoopEngine(
    private val context: Context,
    private val onLog: (role: String, text: String) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private var currentRunId: String = ""

    fun isRunning(): Boolean = runJob?.isActive == true

    fun cancel() {
        runJob?.cancel()
        runJob = null
        KaiBubbleManager.releaseAllSuppression()
        KaiBubbleManager.softResetUiState()
    }

    fun destroy() {
        cancel()
        scope.cancel()
    }

    fun start(userPrompt: String, onFinished: (KaiLoopResult) -> Unit) {
        cancel()
        currentRunId = UUID.randomUUID().toString()
        val runId = currentRunId

        runJob = scope.launch {
            try {
                val result = runLoop(userPrompt, runId)
                if (runId == currentRunId) onFinished(result)
            } catch (_: CancellationException) {
                if (runId == currentRunId) {
                    onStatus("Cancelled")
                    onFinished(KaiLoopResult(false, "Agent loop cancelled.", 0))
                }
            } finally {
                KaiLiveObservationRuntime.softCleanupAfterRun()
                KaiBubbleManager.releaseAllSuppression()
                KaiBubbleManager.softResetUiState()
                KaiAgentController.finishActionLoopSession()
                if (runId == currentRunId) runJob = null
            }
        }
    }

    private suspend fun runLoop(
        userPrompt: String,
        runId: String
    ): KaiLoopResult {
        val appContext = context.applicationContext
        val executor = KaiActionExecutor(appContext) { text ->
            scope.launch(Dispatchers.Main.immediate) { onLog("system", text) }
        }
        val agentStateRepo = KaiAgentStateRepository(appContext)

        suspend fun ensureActiveOrThrow() {
            val ctx = currentCoroutineContext()
            if (!ctx.isActive) throw CancellationException("Agent loop cancelled")
        }

        suspend fun pushAgentState(
            state: String,
            observation: String = "",
            decision: String = "",
            action: String = "",
            notes: String = ""
        ) {
            runCatching {
                agentStateRepo.upsertState(
                    id = "main_agent",
                    state = state,
                    lastObservation = observation.take(2500),
                    lastDecision = decision.take(1200),
                    lastAction = action.take(800),
                    notes = "run=$runId | ${notes.take(1700)}"
                )
            }
        }

        suspend fun finishLoop(
            success: Boolean,
            message: String,
            executedSteps: Int,
            observation: String,
            notes: String
        ): KaiLoopResult {
            pushAgentState(
                state = if (success) "idle" else "error",
                observation = observation,
                decision = message,
                action = "stop",
                notes = notes
            )
            KaiAgentController.finishActionLoopSession(message)
            executor.resetRuntimeState(clearLastGoodScreen = true)
            return KaiLoopResult(success, message, executedSteps)
        }

        return withContext(Dispatchers.IO) {
            ensureActiveOrThrow()

            KaiLiveObservationRuntime.ensureBridge(appContext)
            KaiLiveObservationRuntime.startWatching(immediateDump = true)
            executor.resetRuntimeState(clearLastGoodScreen = true)
            executor.resetObservationTransitionStateForRun()

            val startupGateTier =
                if (KaiTaskStageEngine.classifyGoalMode(userPrompt) == KaiTaskStageEngine.GoalMode.OPEN_ONLY) {
                    KaiActionExecutor.ObservationGateTier.APP_LAUNCH_SAFE
                } else {
                    KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE
                }

            val startup = executor.ensureAuthoritativeObservationReady(
                timeoutMs = 3200L,
                allowLauncherSurface = true,
                tier = startupGateTier,
                maxAttempts = 3
            )

            var currentState = if (startup.passed) {
                startup.state
            } else {
                KaiVisualInterpreter.resolveTruth(
                    allowLauncherSurface = true,
                    canonicalState = executor.getCanonicalRuntimeState(),
                    requireStrong = false
                ).state
            }

            executor.adoptCanonicalRuntimeState(currentState)
            executor.clearStartupFingerprintBaseline()

            pushAgentState(
                state = "planning",
                observation = currentState.rawDump,
                decision = userPrompt,
                action = "loop_start",
                notes = "Kai Live agent loop started"
            )

            withContext(Dispatchers.Main) {
                onStatus("Planning")
                onLog("system", "Agent loop started")
            }

            var totalSteps = 0
            var repeatedNoProgressSteps = 0
            var noProgressCycles = 0
            var lastDecision: KaiExecutionDecisionAuthority.RuntimeDecision? = null
            var lastOpenAppOutcome: KaiOpenAppOutcome? = null
            var appEntryJustCompleted = false

            repeat(10) { cycle ->
                ensureActiveOrThrow()

                val visualTruth = KaiVisualInterpreter.resolveTruth(
                    expectedPackage = currentState.packageName,
                    allowLauncherSurface = true,
                    requireStrong = false,
                    canonicalState = executor.getCanonicalRuntimeState()
                )
                currentState = visualTruth.state
                executor.adoptCanonicalRuntimeState(currentState)

                val stageSnapshot = KaiTaskStageEngine.evaluate(
                    userPrompt = userPrompt,
                    currentState = currentState,
                    openAppOutcome = lastOpenAppOutcome
                )

                val cycleGateTier = when {
                    !stageSnapshot.appEntryComplete -> startupGateTier
                    appEntryJustCompleted -> KaiActionExecutor.ObservationGateTier.APP_LAUNCH_SAFE
                    else -> KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE
                }
                appEntryJustCompleted = false

                val cycleGate = executor.ensureStrongObservationGate(
                    expectedPackage = if (stageSnapshot.appEntryComplete) currentState.packageName else "",
                    timeoutMs = 2200L,
                    maxAttempts = 2,
                    allowLauncherSurface = true,
                    tier = cycleGateTier
                )

                currentState = cycleGate.state
                executor.adoptCanonicalRuntimeState(currentState)
                KaiAgentController.markActionLoopObserved(currentState)

                if (stageSnapshot.finalGoalComplete) {
                    return@withContext finishLoop(
                        success = true,
                        message = "Final goal committed by stage engine.",
                        executedSteps = totalSteps,
                        observation = currentState.rawDump,
                        notes = "stage_success"
                    )
                }

                val step = KaiTaskStageEngine.buildContinuationStep(
                    stageSnapshot = stageSnapshot,
                    userPrompt = userPrompt,
                    currentState = currentState
                ) ?: return@withContext finishLoop(
                    success = false,
                    message = "No continuation step could be built.",
                    executedSteps = totalSteps,
                    observation = currentState.rawDump,
                    notes = "missing_continuation_step"
                )

                withContext(Dispatchers.Main) {
                    onStatus("Executing")
                    onLog(
                        "system",
                        "Step ${totalSteps + 1}: ${step.cmd} → ${
                            step.semanticPayload().ifBlank { step.note.ifBlank { step.selectorRole } }
                        }"
                    )
                }

                val beforeState = currentState
                val result = executor.executeStep(step, beforeState)
                totalSteps += 1

                currentState = result.screenState ?: KaiVisualInterpreter.resolveTruth(
                    expectedPackage = step.expectedPackage.ifBlank { beforeState.packageName },
                    allowLauncherSurface = true,
                    requireStrong = false,
                    canonicalState = executor.getCanonicalRuntimeState()
                ).state

                executor.adoptCanonicalRuntimeState(currentState)
                lastOpenAppOutcome = result.openAppOutcome ?: lastOpenAppOutcome

                if (step.isOpenAppStep() &&
                    result.openAppOutcome != null &&
                    result.openAppOutcome != KaiOpenAppOutcome.OPEN_FAILED &&
                    result.openAppOutcome != KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED
                ) {
                    appEntryJustCompleted = true
                }

                val telemetry = KaiExecutionDecisionAuthority.RuntimeTelemetry(
                    noProgressCycles = noProgressCycles,
                    weakReadStreak = executor.getConsecutiveWeakReads(),
                    staleReadStreak = executor.getConsecutiveStaleReads(),
                    observationWeak = !executor.getLastRefreshMeta().usable,
                    observationFallback = executor.getLastRefreshMeta().fallback,
                    observationReusedLastGood = executor.getLastRefreshMeta().reusedLastGood,
                    loopSafetyLimitReached = cycle >= 9
                )

                val decision = KaiExecutionDecisionAuthority.evaluateStepOutcome(
                    step = step,
                    before = beforeState,
                    after = currentState,
                    result = result,
                    repeatedNoProgressSteps = repeatedNoProgressSteps,
                    recoverablePathExists = true,
                    telemetry = telemetry
                )

                lastDecision = decision

                when (decision.directive) {
                    KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE -> {
                        val progressed = KaiExecutionDecisionAuthority.hasMeaningfulProgress(
                            before = beforeState,
                            after = currentState
                        )

                        repeatedNoProgressSteps = if (progressed) 0 else repeatedNoProgressSteps + 1
                        noProgressCycles = if (progressed) 0 else noProgressCycles + 1

                        pushAgentState(
                            state = "executing",
                            observation = currentState.rawDump,
                            decision = decision.reason,
                            action = step.cmd,
                            notes = "continue | source=${executor.getLastRefreshMeta().source}"
                        )
                    }

                    KaiExecutionDecisionAuthority.RuntimeDirective.RECOVER -> {
                        val recovery = executor.attemptRecoveryForStep(step, currentState)
                        currentState = recovery.screenState ?: currentState
                        executor.adoptCanonicalRuntimeState(currentState)
                        repeatedNoProgressSteps += 1
                        noProgressCycles += 1

                        pushAgentState(
                            state = "recovering",
                            observation = currentState.rawDump,
                            decision = decision.reason,
                            action = "recover:${step.cmd}",
                            notes = recovery.message
                        )
                    }

                    KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN -> {
                        repeatedNoProgressSteps += 1
                        noProgressCycles += 1

                        pushAgentState(
                            state = "replanning",
                            observation = currentState.rawDump,
                            decision = decision.reason,
                            action = "replan",
                            notes = "cycle=$cycle"
                        )
                    }

                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS -> {
                        return@withContext finishLoop(
                            success = true,
                            message = decision.reason,
                            executedSteps = totalSteps,
                            observation = currentState.rawDump,
                            notes = "authority_stop_success"
                        )
                    }

                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> {
                        return@withContext finishLoop(
                            success = false,
                            message = decision.reason,
                            executedSteps = totalSteps,
                            observation = currentState.rawDump,
                            notes = "authority_stop_failure"
                        )
                    }
                }
            }

            val finalReason = lastDecision?.reason ?: "loop_budget_exhausted"

            finishLoop(
                success = false,
                message = finalReason,
                executedSteps = totalSteps,
                observation = currentState.rawDump,
                notes = "loop_budget_exhausted"
            )
        }
    }
}