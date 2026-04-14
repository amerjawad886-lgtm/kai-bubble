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
                    onFinished(KaiLoopResult(success = false, finalMessage = "Agent loop cancelled.", executedSteps = 0))
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

    private suspend fun runLoop(userPrompt: String, runId: String): KaiLoopResult {
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
            executor.resetRuntimeState(clearLastGoodScreen = false)
            return KaiLoopResult(success = success, finalMessage = message, executedSteps = executedSteps)
        }

        return withContext(Dispatchers.IO) {
            ensureActiveOrThrow()

            KaiLiveObservationRuntime.ensureBridge(appContext)
            KaiLiveObservationRuntime.startWatching(immediateDump = true)
            executor.resetRuntimeState(clearLastGoodScreen = false)
            executor.resetObservationTransitionStateForRun()

            val startupTier = if (KaiTaskStageEngine.classifyGoalMode(userPrompt) == KaiTaskStageEngine.GoalMode.OPEN_ONLY) {
                KaiActionExecutor.ObservationGateTier.APP_LAUNCH_SAFE
            } else {
                KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE
            }

            val startup = executor.ensureAuthoritativeObservationReady(
                timeoutMs = 3200L,
                allowLauncherSurface = true,
                tier = startupTier,
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

            repeat(6) { cycle ->
                ensureActiveOrThrow()

                val stageSnapshot = KaiTaskStageEngine.evaluate(
                    userPrompt = userPrompt,
                    currentState = currentState,
                    openAppOutcome = lastOpenAppOutcome
                )

                val cycleGate = executor.ensureStrongObservationGate(
                    expectedPackage = if (stageSnapshot.appEntryComplete) currentState.packageName else "",
                    timeoutMs = 2200L,
                    maxAttempts = 2,
                    allowLauncherSurface = true,
                    tier = if (stageSnapshot.appEntryComplete) {
                        KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE
                    } else {
                        startupTier
                    },
                    staleRetryAttempts = 2,
                    missingPackageRetryAttempts = 2
                )

                currentState = cycleGate.state
                executor.adoptCanonicalRuntimeState(currentState)

                if (!cycleGate.passed && cycleGate.reason.contains("stale_observation")) {
                    noProgressCycles += 1
                    withContext(Dispatchers.Main) {
                        onLog("system", "observation_not_stable_before_planning_cycle=${cycle + 1}")
                    }
                    return@repeat
                }

                withContext(Dispatchers.Main) { onStatus("Planning") }
                pushAgentState(
                    state = "planning",
                    observation = currentState.rawDump,
                    decision = userPrompt,
                    action = "build_action_plan",
                    notes = "cycle=${cycle + 1} stage=${stageSnapshot.stage}"
                )

                var plan = KaiAgentController.buildActionPlan(
                    userPrompt = userPrompt,
                    currentScreenState = currentState,
                    priorProgress = "",
                    maxStepsPerChunk = 3
                )

                if (plan.steps.isEmpty()) {
                    KaiTaskStageEngine.buildContinuationStep(
                        stageSnapshot = stageSnapshot,
                        userPrompt = userPrompt,
                        currentState = currentState
                    )?.let { continuation ->
                        plan = plan.copy(
                            summary = if (plan.summary.isBlank()) "Using stage continuation" else plan.summary,
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

                var replanRequested = false
                var cycleHadProgress = false

                for (step in plan.steps) {
                    ensureActiveOrThrow()
                    totalSteps += 1

                    withContext(Dispatchers.Main) {
                        onLog(
                            "system",
                            "Step $totalSteps: ${step.cmd}" +
                                (step.semanticPayload().takeIf { it.isNotBlank() }?.let { " → $it" } ?: "")
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
                    if (step.isOpenAppStep()) {
                        lastOpenAppOutcome = result.openAppOutcome
                    }

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
                        cycleHadProgress = true
                    } else {
                        repeatedNoProgressSteps += 1
                    }

                    when (decision.directive) {
                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS -> {
                            return@withContext finishLoop(
                                success = true,
                                message = "Final goal committed by runtime authority.",
                                executedSteps = totalSteps,
                                observation = currentState.rawDump,
                                notes = decision.reason
                            )
                        }
                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> {
                            return@withContext finishLoop(
                                success = false,
                                message = "Runtime authority stop: ${decision.reason}",
                                executedSteps = totalSteps,
                                observation = currentState.rawDump,
                                notes = decision.reason
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
                        KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN -> {
                            replanRequested = true
                        }
                        KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE -> Unit
                    }

                    if (replanRequested) break
                }

                if (cycleHadProgress) {
                    noProgressCycles = 0
                } else {
                    noProgressCycles += 1
                }

                val cycleDecision = KaiExecutionDecisionAuthority.evaluateCycleHealth(
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
                        return@withContext finishLoop(
                            success = true,
                            message = "Final goal committed by stage/authority.",
                            executedSteps = totalSteps,
                            observation = currentState.rawDump,
                            notes = cycleDecision.reason
                        )
                    }
                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> {
                        return@withContext finishLoop(
                            success = false,
                            message = "Runtime authority cycle stop: ${cycleDecision.reason}",
                            executedSteps = totalSteps,
                            observation = currentState.rawDump,
                            notes = cycleDecision.reason
                        )
                    }
                    KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN -> {
                        withContext(Dispatchers.Main) {
                            onLog("system", "cycle_replan: ${cycleDecision.reason}")
                        }
                    }
                    KaiExecutionDecisionAuthority.RuntimeDirective.RECOVER,
                    KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE -> Unit
                }
            }

            return@withContext finishLoop(
                success = false,
                message = "Agent loop stopped without final commit.",
                executedSteps = totalSteps,
                observation = currentState.rawDump,
                notes = "loop_exhausted"
            )
        }
    }
}
