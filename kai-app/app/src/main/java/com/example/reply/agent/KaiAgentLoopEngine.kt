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

        suspend fun pushAgentState(state: String, observation: String = "", decision: String = "", action: String = "", notes: String = "") {
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

        suspend fun finishLoop(success: Boolean, message: String, executedSteps: Int, observation: String, notes: String): KaiLoopResult {
            pushAgentState(if (success) "idle" else "error", observation, message, "stop", notes)
            KaiAgentController.finishActionLoopSession(message)
            executor.resetRuntimeState(clearLastGoodScreen = false)
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

            var currentState = startup.state
            executor.adoptCanonicalRuntimeState(currentState)
            executor.clearStartupFingerprintBaseline()

            pushAgentState("planning", currentState.rawDump, userPrompt, "loop_start", "Kai Live v1 agent loop started")
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

            repeat(6) {
                ensureActiveOrThrow()

                val stageSnapshot = KaiTaskStageEngine.evaluate(userPrompt, currentState, lastOpenAppOutcome)

                // After app entry just completed, use a gentler gate tier so the
                // still-settling app screen doesn't block continuation.
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
                    return@withContext finishLoop(true, "Final goal committed by stage engine.", totalSteps, currentState.rawDump, "stage_success")
                }

                val step = KaiTaskStageEngine.buildContinuationStep(stageSnapshot, userPrompt, currentState)
                    ?: return@withContext finishLoop(false, "No continuation step could be built.", totalSteps, currentState.rawDump, "missing_continuation_step")

                withContext(Dispatchers.Main) {
                    onStatus("Executing")
                    onLog("system", "Step ${totalSteps + 1}: ${step.cmd} → ${step.semanticPayload().ifBlank { step.note.ifBlank { step.selectorRole } }}")
                }

                val beforeState = currentState
                val result = executor.executeStep(step, beforeState)
                totalSteps += 1
                currentState = result.screenState ?: executor.resolveCanonicalRuntimeState()
                executor.adoptCanonicalRuntimeState(currentState)
                lastOpenAppOutcome = result.openAppOutcome ?: lastOpenAppOutcome
                if (step.isOpenAppStep() && result.success) appEntryJustCompleted = true

                val progress = KaiExecutionDecisionAuthority.hasMeaningfulProgress(beforeState, currentState)
                repeatedNoProgressSteps = if (progress || result.success) 0 else repeatedNoProgressSteps + 1

                val decision = KaiExecutionDecisionAuthority.evaluateStepOutcome(
                    step, beforeState, currentState, result, repeatedNoProgressSteps, false,
                    KaiExecutionDecisionAuthority.RuntimeTelemetry(
                        noProgressCycles = noProgressCycles,
                        weakReadStreak = executor.getConsecutiveWeakReads(),
                        staleReadStreak = executor.getConsecutiveStaleReads(),
                        observationWeak = executor.getLastRefreshMeta().weak,
                        observationFallback = executor.getLastRefreshMeta().fallback,
                        observationReusedLastGood = executor.getLastRefreshMeta().reusedLastGood
                    )
                )
                lastDecision = decision

                pushAgentState(
                    state = when (decision.directive) {
                        KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE -> "executing"
                        KaiExecutionDecisionAuthority.RuntimeDirective.RECOVER -> "recovering"
                        KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN -> "planning"
                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS -> "idle"
                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> "error"
                    },
                    observation = currentState.rawDump,
                    decision = decision.reason,
                    action = step.cmd,
                    notes = result.message
                )

                when (decision.directive) {
                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS -> return@withContext finishLoop(true, decision.reason, totalSteps, currentState.rawDump, "directive_success")
                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> return@withContext finishLoop(false, decision.reason, totalSteps, currentState.rawDump, "directive_failure")
                    KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN,
                    KaiExecutionDecisionAuthority.RuntimeDirective.RECOVER -> noProgressCycles += 1
                    KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE -> noProgressCycles = 0
                }

                val cycleHealth = KaiExecutionDecisionAuthority.evaluateCycleHealth(
                    lastDecision,
                    KaiExecutionDecisionAuthority.RuntimeTelemetry(
                        noProgressCycles = noProgressCycles,
                        weakReadStreak = executor.getConsecutiveWeakReads(),
                        staleReadStreak = executor.getConsecutiveStaleReads(),
                        observationWeak = executor.getLastRefreshMeta().weak,
                        observationFallback = executor.getLastRefreshMeta().fallback,
                        observationReusedLastGood = executor.getLastRefreshMeta().reusedLastGood
                    )
                )
                if (cycleHealth.directive == KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE) {
                    return@withContext finishLoop(false, cycleHealth.reason, totalSteps, currentState.rawDump, "cycle_stop_failure")
                }
            }

            finishLoop(false, "Agent loop stopped without final commit.", totalSteps, currentState.rawDump, "loop_exhausted")
        }
    }
}
