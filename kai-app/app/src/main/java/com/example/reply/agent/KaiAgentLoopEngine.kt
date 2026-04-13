
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

    fun start(
        userPrompt: String,
        onFinished: (KaiLoopResult) -> Unit
    ) {
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
                    onFinished(
                        KaiLoopResult(
                            success = false,
                            finalMessage = "Agent loop cancelled.",
                            executedSteps = 0
                        )
                    )
                }
            } finally {
                KaiObservationRuntime.softCleanupAfterRun()
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
            scope.launch(Dispatchers.Main.immediate) {
                onLog("system", text)
            }
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

        return withContext(Dispatchers.IO) {
            ensureActiveOrThrow()

            KaiObservationRuntime.ensureBridge(appContext)
            KaiObservationRuntime.startWatching(immediateDump = true)
            executor.resetRuntimeState(clearLastGoodScreen = false)
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

            var currentState = startup.state
            executor.adoptCanonicalRuntimeState(currentState)
            executor.clearStartupFingerprintBaseline()

            pushAgentState(
                state = "planning",
                observation = currentState.rawDump,
                decision = userPrompt,
                action = "loop_start",
                notes = "Kai agent loop started"
            )

            withContext(Dispatchers.Main) {
                onStatus("Planning")
                onLog("system", "Agent loop started")
            }

            var totalSteps = 0
            var repeatedNoProgressSteps = 0
            var noProgressCycles = 0
            var lastDecision: KaiExecutionDecisionAuthority.RuntimeDecision? = null

            repeat(5) { cycle ->
                ensureActiveOrThrow()

                val stageSnapshot = KaiTaskStageEngine.evaluate(
                    userPrompt = userPrompt,
                    currentState = currentState
                )

                val cycleGate = executor.ensureStrongObservationGate(
                    expectedPackage = if (stageSnapshot.appEntryComplete) currentState.packageName else "",
                    timeoutMs = 2200L,
                    maxAttempts = 2,
                    allowLauncherSurface = true,
                    tier = if (stageSnapshot.appEntryComplete) {
                        KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE
                    } else {
                        startupGateTier
                    }
                )

                if (cycleGate.passed) {
                    currentState = cycleGate.state
                } else {
                    currentState = cycleGate.state
                    if (stageSnapshot.appEntryComplete &&
                        cycleGate.reason.contains("stale_observation")
                    ) {
                        noProgressCycles += 1
                        withContext(Dispatchers.Main) {
                            onLog("system", "observation_not_stable_before_planning_cycle=${cycle + 1}")
                        }
                        return@repeat
                    }
                }

                if (KaiExecutionDecisionAuthority.likelyGoalSatisfied(userPrompt, currentState)) {
                    val finalMessage = "Final goal committed from current screen state."
                    pushAgentState(
                        state = "idle",
                        observation = currentState.rawDump,
                        decision = finalMessage,
                        action = "stop",
                        notes = "goal_satisfied_without_additional_steps"
                    )
                    KaiAgentController.finishActionLoopSession(finalMessage)
                    executor.resetRuntimeState(clearLastGoodScreen = false)
                    return@withContext KaiLoopResult(
                        success = true,
                        finalMessage = finalMessage,
                        executedSteps = totalSteps
                    )
                }

                withContext(Dispatchers.Main) { onStatus("Planning") }

                pushAgentState(
                    state = "planning",
                    observation = currentState.rawDump,
                    decision = userPrompt,
                    action = "build_action_plan",
                    notes = "cycle=${cycle + 1}"
                )

                var plan = KaiAgentController.buildActionPlan(
                    userPrompt = userPrompt,
                    currentScreenState = currentState,
                    priorProgress = "",
                    maxStepsPerChunk = 3
                )

                if (plan.steps.isEmpty()) {
                    val continuation = KaiTaskStageEngine.buildContinuationStep(
                        stageSnapshot = stageSnapshot,
                        userPrompt = userPrompt,
                        currentState = currentState
                    )
                    if (continuation != null) {
                        plan = plan.copy(
                            summary = if (plan.summary.isBlank()) {
                                "Using stage continuation"
                            } else {
                                plan.summary
                            },
                            steps = listOf(continuation)
                        )
                    }
                }

                if (plan.steps.isEmpty()) {
                    noProgressCycles += 1
                    return@repeat
                }

                withContext(Dispatchers.Main) {
                    onStatus("Executing")
                    onLog("kai", plan.summary.ifBlank { "Executing next step." })
                }

                for (step in plan.steps) {
                    ensureActiveOrThrow()
                    totalSteps += 1

                    withContext(Dispatchers.Main) {
                        onLog(
                            "system",
                            "Step $totalSteps: ${step.cmd}" +
                                (step.text.ifBlank { step.selectorText }.takeIf { it.isNotBlank() }?.let { " → $it" } ?: "")
                        )
                    }

                    val before = currentState
                    val result = executor.executeStep(step, currentState)
                    val after = result.screenState ?: executor.requestFreshScreen(
                        timeoutMs = 1600L,
                        expectedPackage = step.expectedPackage.ifBlank { currentState.packageName }
                    )
                    currentState = after
                    executor.adoptCanonicalRuntimeState(currentState)

                    pushAgentState(
                        state = "executing",
                        observation = currentState.rawDump,
                        decision = result.message,
                        action = step.cmd,
                        notes = "cycle=${cycle + 1} step=$totalSteps"
                    )

                    val decision = KaiExecutionDecisionAuthority.evaluateStepOutcome(
                        step = step,
                        before = before,
                        after = after,
                        result = result,
                        userPrompt = userPrompt,
                        repeatedNoProgressSteps = repeatedNoProgressSteps,
                        recoverablePathExists = true,
                        telemetry = KaiExecutionDecisionAuthority.RuntimeTelemetry(
                            noProgressCycles = noProgressCycles,
                            weakReadStreak = executor.getConsecutiveWeakReads(),
                            staleReadStreak = executor.getConsecutiveStaleReads(),
                            observationWeak = executor.getLastRefreshMeta().weak,
                            observationFallback = executor.getLastRefreshMeta().fallback,
                            observationReusedLastGood = executor.getLastRefreshMeta().reusedLastGood
                        )
                    )
                    lastDecision = decision

                    if (decision.progressLevel != KaiExecutionDecisionAuthority.ProgressLevel.NONE) {
                        repeatedNoProgressSteps = 0
                    } else {
                        repeatedNoProgressSteps += 1
                    }

                    when (decision.directive) {
                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS -> {
                            val finalMessage = "Final goal committed by runtime authority."
                            pushAgentState(
                                state = "idle",
                                observation = currentState.rawDump,
                                decision = finalMessage,
                                action = "stop",
                                notes = decision.reason
                            )
                            KaiAgentController.finishActionLoopSession(finalMessage)
                            executor.resetRuntimeState(clearLastGoodScreen = false)
                            return@withContext KaiLoopResult(
                                success = true,
                                finalMessage = finalMessage,
                                executedSteps = totalSteps
                            )
                        }

                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> {
                            val finalMessage = "Runtime authority stop: ${decision.reason}"
                            pushAgentState(
                                state = "error",
                                observation = currentState.rawDump,
                                decision = finalMessage,
                                action = "stop",
                                notes = decision.reason
                            )
                            KaiAgentController.finishActionLoopSession(finalMessage)
                            executor.resetRuntimeState(clearLastGoodScreen = false)
                            return@withContext KaiLoopResult(
                                success = false,
                                finalMessage = finalMessage,
                                executedSteps = totalSteps
                            )
                        }

                        KaiExecutionDecisionAuthority.RuntimeDirective.RECOVER -> {
                            val recovery = executor.attemptRecoveryForStep(step, currentState)
                            currentState = recovery.screenState ?: currentState
                            executor.adoptCanonicalRuntimeState(currentState)
                            withContext(Dispatchers.Main) {
                                onLog("system", "runtime_recovery: ${recovery.message}")
                            }
                        }

                        KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN,
                        KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE -> {
                            // Let the loop continue naturally.
                        }
                    }
                }

                val cycleDecision = KaiExecutionDecisionAuthority.evaluateCycleOutcome(
                    currentState = currentState,
                    userPrompt = userPrompt,
                    lastDecision = lastDecision,
                    telemetry = KaiExecutionDecisionAuthority.RuntimeTelemetry(
                        noProgressCycles = noProgressCycles,
                        weakReadStreak = executor.getConsecutiveWeakReads(),
                        staleReadStreak = executor.getConsecutiveStaleReads(),
                        observationWeak = executor.getLastRefreshMeta().weak,
                        observationFallback = executor.getLastRefreshMeta().fallback,
                        observationReusedLastGood = executor.getLastRefreshMeta().reusedLastGood
                    )
                )

                when (cycleDecision.directive) {
                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS -> {
                        val finalMessage = "Final goal committed from cycle evidence."
                        pushAgentState(
                            state = "idle",
                            observation = currentState.rawDump,
                            decision = finalMessage,
                            action = "stop",
                            notes = cycleDecision.reason
                        )
                        KaiAgentController.finishActionLoopSession(finalMessage)
                        executor.resetRuntimeState(clearLastGoodScreen = false)
                        return@withContext KaiLoopResult(
                            success = true,
                            finalMessage = finalMessage,
                            executedSteps = totalSteps
                        )
                    }

                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> {
                        val finalMessage = "Runtime authority cycle stop: ${cycleDecision.reason}"
                        pushAgentState(
                            state = "error",
                            observation = currentState.rawDump,
                            decision = finalMessage,
                            action = "stop",
                            notes = cycleDecision.reason
                        )
                        KaiAgentController.finishActionLoopSession(finalMessage)
                        executor.resetRuntimeState(clearLastGoodScreen = false)
                        return@withContext KaiLoopResult(
                            success = false,
                            finalMessage = finalMessage,
                            executedSteps = totalSteps
                        )
                    }

                    else -> Unit
                }

                noProgressCycles += 1
            }

            val finalMessage = "Runtime authority stop: loop_limit_reached. Refine the prompt and try again."
            pushAgentState(
                state = "idle",
                observation = currentState.rawDump,
                decision = "loop_limit_reached",
                action = "stop",
                notes = "Agent reached current loop limit"
            )
            KaiAgentController.finishActionLoopSession(finalMessage)
            executor.resetRuntimeState(clearLastGoodScreen = false)

            KaiLoopResult(
                success = false,
                finalMessage = finalMessage,
                executedSteps = totalSteps
            )
        }
    }
}
