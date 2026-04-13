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
                if (runId == currentRunId) {
                    onFinished(result)
                }
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
                if (runId == currentRunId) {
                    runJob = null
                }
            }
        }
    }

    private fun fingerprintOf(state: KaiScreenState): String {
        return state.semanticFingerprint()
    }

    private fun semanticStepKey(step: KaiActionStep): String {
        return listOf(
            step.cmd.trim().lowercase(),
            step.strategy.trim().lowercase(),
            step.note.trim().lowercase(),
            step.selectorRole.trim().lowercase(),
            step.selectorText.trim().lowercase(),
            step.text.trim().lowercase()
        ).joinToString("|")
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

        return try {
            withContext(Dispatchers.IO) {
                ensureActiveOrThrow()

                KaiObservationRuntime.ensureBridge(appContext)
                KaiObservationRuntime.softCleanupAfterRun()
                executor.resetRuntimeState(clearLastGoodScreen = false)
                executor.resetObservationTransitionStateForRun()

                val startupObservationBaseline = System.currentTimeMillis()
                if (!KaiObservationRuntime.isWatching) {
                    KaiObservationRuntime.startWatching(immediateDump = true)
                }
                if (!KaiObservationRuntime.hasRecentUsefulObservation(1600L)) {
                    KaiObservationRuntime.requestWarmupObservation(burstCount = 4)
                }
                KaiObservationRuntime.awaitFresh(
                    afterTime = startupObservationBaseline,
                    timeoutMs = 2200L
                )
                KaiBubbleManager.releaseAllSuppression()
                KaiBubbleManager.softResetUiState()

                fun shouldUseAppLaunchSafeGate(prompt: String): Boolean {
                    val normalized = KaiScreenStateParser.normalize(prompt)
                    if (normalized.isBlank()) return false
                    val openPrefixIntent =
                        normalized.startsWith("open_app") ||
                            normalized.startsWith("open app") ||
                            normalized.startsWith("open application") ||
                            normalized.startsWith("launch") ||
                            normalized.startsWith("افتح") ||
                            normalized.startsWith("شغل")
                    val appHint = KaiScreenStateParser.inferAppHint(prompt)
                    val aliases = if (appHint.isNotBlank()) KaiScreenStateParser.appAliasesForHint(appHint) else emptyList()
                    val startsWithAppAlias = aliases.any { alias ->
                        normalized == alias || normalized.startsWith("$alias ")
                    }
                    val openOnlyGoal =
                        KaiTaskStageEngine.classifyGoalMode(prompt) == KaiTaskStageEngine.GoalMode.OPEN_ONLY
                    return openPrefixIntent || startsWithAppAlias || openOnlyGoal
                }

                fun hasClearOpenIntent(prompt: String): Boolean {
                    val normalized = KaiScreenStateParser.normalize(prompt)
                    return normalized.startsWith("open ") ||
                        normalized.startsWith("launch ") ||
                        normalized.startsWith("افتح ") ||
                        normalized.startsWith("شغل ")
                }

                fun extractOpenTargetText(prompt: String): String {
                    val trimmed = prompt.trim()
                    val englishStripped = trimmed.replaceFirst(
                        Regex("""(?i)^\s*(open app|open application|open|launch)\s+"""),
                        ""
                    )
                    val arabicStripped = englishStripped.replaceFirst(
                        Regex("""^\s*(افتح|شغل)\s+"""),
                        ""
                    )
                    return arabicStripped.trim().ifBlank { trimmed }
                }

                val startupOpenIntentGate = shouldUseAppLaunchSafeGate(userPrompt)

                val startupGateTier = if (startupOpenIntentGate) {
                    KaiActionExecutor.ObservationGateTier.APP_LAUNCH_SAFE
                } else {
                    KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE
                }

                KaiAgentController.pruneObservationMemory(12)

                // Fast-path: continuous watching keeps KaiObservationRuntime.authoritative
                // live between runs.  If we already have a fresh authoritative observation
                // (arrived within the last 1 500 ms) skip the CMD_DUMP handshake entirely —
                // the agent starts with immediate, non-blind awareness of the current screen.
                var currentState: KaiScreenState
                if (KaiObservationRuntime.isWatching &&
                    KaiObservationRuntime.hasRecentAuthoritative(1500L)
                ) {
                    val obs = KaiObservationRuntime.authoritative
                    currentState = KaiScreenStateParser.fromDump(obs.packageName, obs.screenPreview)
                    executor.adoptCanonicalRuntimeState(currentState)
                    onLog("system", "startup_from_live_observation: pkg=${currentState.packageName}")
                } else {
                    val readiness = executor.ensureAuthoritativeObservationReady(
                        timeoutMs = 2600L,
                        allowLauncherSurface = true,
                        tier = startupGateTier,
                        maxAttempts = 3
                    )
                    if (!readiness.passed) {
                        val finalMessage =
                            "Authoritative observation handshake failed: ${readiness.reason}."
                        pushAgentState(
                            state = "warning",
                            observation = readiness.state.rawDump,
                            decision = finalMessage,
                            action = "observation_handshake",
                            notes = "startup_authoritative_observation_not_ready"
                        )
                        KaiAgentController.finishActionLoopSession(finalMessage)
                        executor.resetRuntimeState(clearLastGoodScreen = false)
                        return@withContext KaiLoopResult(
                            success = false,
                            finalMessage = finalMessage,
                            executedSteps = 0
                        )
                    }
                    currentState = readiness.state
                }

                // Both the fast-path (adoptCanonicalRuntimeState) and the slow-path
                // (ensureAuthoritativeObservationReady → requestFreshScreen) leave a fingerprint
                // baseline that represents the startup screen.  Cycle 0's observation gate would
                // immediately compare the next fresh dump against that baseline, see the same
                // screen (no action taken yet), and mark the observation as stale — aborting
                // before any semantic planning can occur.  Clear the baseline here so cycle 0
                // always treats its first dump as a genuinely fresh, non-stale observation.
                executor.clearStartupFingerprintBaseline()

                val progressLog = StringBuilder()
                var totalSteps = 0
                var lastFingerprint = fingerprintOf(currentState)
                var noProgressCycles = 0
                var repeatedWeakReadFailures = 0
                var repeatedOpenAppUnconfirmed = 0
                var repeatedNoProgressSteps = 0
                val repeatedSemanticFailuresByContext = mutableMapOf<String, Int>()
                val normalizationFailuresByContext = mutableMapOf<String, Int>()
                var lastSemanticIntentKey = ""
                var repeatedSameSemanticIntentCycles = 0
                var wrongSurfaceFamilyThisChunk = ""
                var lastWrongSurfaceFamilyAcrossChunks = ""
                var consecutiveWrongSurfaceChunks = 0
                var currentGoalStage = "OPEN_TARGET_APP"
                var postArrivalContinuationGraceSteps = 0
                val repeatedWrongFamilyByStage = mutableMapOf<String, Int>()
                var lastRequiredStepFailed = false
                var lastStageSnapshot = KaiTaskStageEngine.evaluate(userPrompt, currentState)
                var lastStageLogged = lastStageSnapshot.stage

                fun hasStrictCycleEvidence(prompt: String, state: KaiScreenState): Boolean {
                    return KaiExecutionDecisionAuthority.likelyGoalSatisfied(prompt, state)
                }

                fun hasRecoverableTransitionPath(state: KaiScreenState): Boolean {
                    val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
                    return KaiSurfaceModel.isPostOpenReadyFamily(family) ||
                        KaiSurfaceModel.isRecoverableFamily(family) ||
                        family in setOf(
                            KaiSurfaceFamily.LIST_SURFACE,
                            KaiSurfaceFamily.RESULT_LIST_SURFACE,
                            KaiSurfaceFamily.THREAD_SURFACE,
                            KaiSurfaceFamily.COMPOSER_SURFACE,
                            KaiSurfaceFamily.EDITOR_SURFACE,
                            KaiSurfaceFamily.DETAIL_SURFACE,
                            KaiSurfaceFamily.SEARCH_SURFACE,
                            KaiSurfaceFamily.TABBED_HOME_SURFACE,
                            KaiSurfaceFamily.CONTENT_FEED_SURFACE
                        )
                }

                suspend fun stabilizeObservationBeforePlanning(
                    baseline: KaiScreenState,
                    stageSnapshot: KaiTaskStageEngine.StageSnapshot,
                    cycleIndex: Int
                ): Pair<KaiScreenState, Boolean> {
                    if (!stageSnapshot.appEntryComplete) {
                        return baseline to true
                    }

                    val expectedPkg = baseline.packageName
                    var candidate = baseline
                    val baselineMeta = executor.getLastRefreshMeta()
                    val baselineUsable =
                        expectedPkg.isNotBlank() &&
                            !candidate.isWeakObservation() &&
                            !candidate.isOverlayPolluted() &&
                            !candidate.isLauncher() &&
                            !baselineMeta.stale
                    if (baselineUsable) {
                        return candidate to true
                    }

                    repeat(2) { settleAttempt ->
                        val gate = executor.ensureStrongObservationGate(
                            expectedPackage = expectedPkg,
                            timeoutMs = 2000L,
                            maxAttempts = 1,
                            allowLauncherSurface = false,
                            tier = KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE,
                            staleRetryAttempts = 2,
                            missingPackageRetryAttempts = 2
                        )

                        candidate = gate.state
                        val settleMeta = executor.getLastRefreshMeta()
                        val settleUsable =
                            gate.passed &&
                                candidate.packageName.isNotBlank() &&
                                !candidate.isWeakObservation() &&
                                !candidate.isOverlayPolluted() &&
                                !candidate.isLauncher() &&
                                !settleMeta.stale

                        if (settleUsable) {
                            return candidate to true
                        }

                        withContext(Dispatchers.Main) {
                            onLog(
                                "system",
                                "post_arrival_observation_settle_retry:${settleAttempt + 1}/2 reason=${gate.reason}"
                            )
                        }
                    }

                    val preservedCoherent =
                        baseline.packageName.isNotBlank() &&
                            !baseline.isLauncher() &&
                            !baseline.isWeakObservation() &&
                            !baseline.isOverlayPolluted()

                    if (preservedCoherent) {
                        withContext(Dispatchers.Main) {
                            onLog("system", "post_arrival_settle_preserving_last_coherent_target_observation")
                        }
                        return baseline to true
                    }

                    withContext(Dispatchers.Main) {
                        onLog(
                            "system",
                            "observation_not_stable_before_planning_cycle=${cycleIndex + 1}; delaying semantic planning"
                        )
                    }
                    return candidate to false
                }

                fun wrongSurfaceFamily(msg: String): String {
                    val m = msg.lowercase()
                    return when {
                        m.contains("wrong_surface_for_conversation_open") || m.contains("ambiguous_conversation_target") -> "conversation"
                        m.contains("wrong_surface_for_composer") -> "composer"
                        m.contains("wrong_surface_for_send") -> "send"
                        m.contains("wrong_surface_for_create_note") -> "note"
                        m.contains("normalization_failed_instagram") -> "instagram_normalization"
                        m.contains("normalization_failed_notes") -> "notes_normalization"
                        m.contains("normalization_failed_general") -> "general_normalization"
                        m.contains("replan_required_wrong_surface_repeat") -> "replan_wrong_surface"
                        m.contains("weak_surface_evidence") -> "weak_surface"
                        else -> ""
                    }
                }

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

                repeat(4) { cycle ->
                    ensureActiveOrThrow()
                    var plannerSignaledGoalComplete = false

                    val prePlanStageSnapshot = KaiTaskStageEngine.evaluate(
                        userPrompt = userPrompt,
                        currentState = currentState
                    )
                    val cycleGateTier = if (prePlanStageSnapshot.appEntryComplete) {
                        KaiActionExecutor.ObservationGateTier.SEMANTIC_ACTION_SAFE
                    } else {
                        startupGateTier
                    }
                    val cycleExpectedPackage =
                        if (
                            prePlanStageSnapshot.appEntryComplete &&
                                currentState.packageName.isNotBlank() &&
                                !currentState.isLauncher()
                        ) {
                            currentState.packageName
                        } else {
                            ""
                        }

                    val cycleObservationGate = executor.ensureStrongObservationGate(
                        expectedPackage = cycleExpectedPackage,
                        timeoutMs = 2400L,
                        maxAttempts = 2,
                        allowLauncherSurface = true,
                        tier = cycleGateTier,
                        staleRetryAttempts = 2
                    )
                    if (!cycleObservationGate.passed) {
                        currentState = cycleObservationGate.state
                        val canDelayPlanning =
                            prePlanStageSnapshot.appEntryComplete &&
                                cycleObservationGate.reason.contains("stale_observation")
                        if (canDelayPlanning) {
                            withContext(Dispatchers.Main) {
                                onLog(
                                    "system",
                                    "cycle_observation_stale_after_arrival; delaying planner and retrying observe"
                                )
                            }
                            noProgressCycles += 1
                            return@repeat
                        }

                        val finalMessage =
                            "Observation gate failed before semantic planning: ${cycleObservationGate.reason}."
                        pushAgentState(
                            state = "warning",
                            observation = cycleObservationGate.state.rawDump,
                            decision = finalMessage,
                            action = "observation_gate",
                            notes = "cycle_observation_not_strong"
                        )
                        KaiAgentController.finishActionLoopSession(finalMessage)
                        executor.resetRuntimeState(clearLastGoodScreen = false)
                        return@withContext KaiLoopResult(
                            success = false,
                            finalMessage = finalMessage,
                            executedSteps = totalSteps
                        )
                    }
                    currentState = cycleObservationGate.state

                    val (stabilizedState, planningAllowed) = stabilizeObservationBeforePlanning(
                        baseline = currentState,
                        stageSnapshot = prePlanStageSnapshot,
                        cycleIndex = cycle
                    )
                    currentState = stabilizedState
                    if (!planningAllowed) {
                        noProgressCycles += 1
                        pushAgentState(
                            state = "observing",
                            observation = currentState.rawDump,
                            decision = "post_arrival_settle_pending",
                            action = "observe_only",
                            notes = "cycle=${cycle + 1}"
                        )
                        return@repeat
                    }

                    withContext(Dispatchers.Main) {
                        onStatus("Planning")
                    }

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
                        priorProgress = progressLog.toString(),
                        maxStepsPerChunk = 4
                    )

                    if (plan.steps.isEmpty()) {
                        val strictEvidenceNow = hasStrictCycleEvidence(userPrompt, currentState)
                        if (!strictEvidenceNow) {
                            val stageFallbackSnapshot = KaiTaskStageEngine.evaluate(
                                userPrompt = userPrompt,
                                currentState = currentState
                            )
                            val continuationStep =
                                if (
                                    stageFallbackSnapshot.appEntryComplete &&
                                    stageFallbackSnapshot.goalMode != KaiTaskStageEngine.GoalMode.OPEN_ONLY &&
                                    !stageFallbackSnapshot.finalGoalComplete
                                ) {
                                    KaiTaskStageEngine.buildContinuationStep(
                                        stageSnapshot = stageFallbackSnapshot,
                                        userPrompt = userPrompt,
                                        currentState = currentState
                                    )
                                } else {
                                    null
                                }

                            if (continuationStep != null) {
                                withContext(Dispatchers.Main) {
                                    onLog(
                                        "system",
                                        "planner_empty_after_app_arrival; synthesized_stage_continuation:${continuationStep.cmd}"
                                    )
                                }
                                plan = plan.copy(
                                    summary = if (plan.summary.isBlank()) {
                                        "Planner empty after app arrival; stage continuation synthesized."
                                    } else {
                                        "${plan.summary}\nPlanner empty after app arrival; stage continuation synthesized."
                                    },
                                    steps = listOf(continuationStep),
                                    goalComplete = false
                                )
                            } else {
                                val inferredHint = KaiScreenStateParser.inferAppHint(userPrompt)
                                val openOnlyGoal =
                                    stageFallbackSnapshot.goalMode == KaiTaskStageEngine.GoalMode.OPEN_ONLY
                                val launcherFallbackEligible =
                                    currentState.isLauncher() &&
                                        inferredHint.isBlank() &&
                                        openOnlyGoal &&
                                        hasClearOpenIntent(userPrompt)

                                if (launcherFallbackEligible) {
                                    val fallbackOpenStep = KaiActionStep(
                                        cmd = "open_app",
                                        text = extractOpenTargetText(userPrompt),
                                        strategy = "launcher_open_fallback_blank_app_hint",
                                        note = "stage_continuation"
                                    )
                                    withContext(Dispatchers.Main) {
                                        onLog(
                                            "system",
                                            "planner_empty_open_only_blank_app_hint; synthesized_launcher_open_fallback"
                                        )
                                    }
                                    plan = plan.copy(
                                        summary = if (plan.summary.isBlank()) {
                                            "Planner empty with open-intent and blank app hint on launcher; launcher fallback synthesized."
                                        } else {
                                            "${plan.summary}\nPlanner empty with open-intent and blank app hint on launcher; launcher fallback synthesized."
                                        },
                                        steps = listOf(fallbackOpenStep),
                                        goalComplete = false
                                    )
                                }
                            }
                        }
                    }

                    val cycleSemanticIntentKey = plan.steps
                        .take(4)
                        .joinToString("||") { semanticStepKey(it) }

                    ensureActiveOrThrow()

                    if (plan.summary.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            onLog("assistant", plan.summary)
                        }
                    }

                    if (plan.goalComplete || plan.steps.isEmpty()) {
                        val plannerCompletionTrusted = KaiExecutionDecisionAuthority.shouldAcceptPlannerGoalComplete(
                            plan = plan,
                            userPrompt = userPrompt,
                            currentState = currentState
                        )
                        plannerSignaledGoalComplete = plannerCompletionTrusted
                        if (plan.goalComplete && !plannerCompletionTrusted) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "planner_completion_rejected_without_runtime_authority_evidence")
                            }
                        }
                        if (plan.steps.isEmpty() && !plannerCompletionTrusted) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "planner_no_steps_without_runtime_authority_evidence; delegated_to_authority")
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        onStatus("Executing")
                    }

                    var cycleMadeProgress = false
                    var breakCurrentChunk = false
                    var semanticStepsInChunk = 0
                    var alreadyStateNoProgressHits = 0
                    val repeatedSemanticNoProgressByContext = mutableMapOf<String, Int>()
                    wrongSurfaceFamilyThisChunk = ""
                    val executionQueue = plan.steps.toMutableList()
                    var stepCursor = 0

                    while (stepCursor < executionQueue.size) {
                        val step = executionQueue[stepCursor]
                        ensureActiveOrThrow()

                        totalSteps += 1
                        currentGoalStage = when (step.cmd.trim().lowercase()) {
                            "open_app" -> "OPEN_TARGET_APP"
                            "click_best_match", "verify_state" -> "REACH_TARGET_SURFACE"
                            "open_best_list_item" -> "OPEN_TARGET_ENTITY"
                            "focus_best_input" -> "FOCUS_TARGET_INPUT"
                            "input_into_best_field", "input_text" -> "WRITE_PAYLOAD"
                            "press_primary_action" -> "SUBMIT_OR_SEND"
                            else -> "VERIFY_COMPLETION"
                        }

                        val remainingInChunk = executionQueue.size - stepCursor - 1
                        val stepText = buildString {
                            append("Step $totalSteps/${totalSteps + remainingInChunk}: ${step.cmd}")
                            when {
                                step.text.isNotBlank() -> append(" → ${step.text}")
                                step.dir.isNotBlank() -> append(" → ${step.dir}")
                                step.x != null || step.y != null -> append(" → xy")
                            }
                        }

                        pushAgentState(
                            state = "executing",
                            observation = currentState.rawDump,
                            decision = plan.summary.ifBlank { userPrompt },
                            action = stepText,
                            notes = "cycle=${cycle + 1}"
                        )

                        withContext(Dispatchers.Main) {
                            onLog("system", stepText)
                        }

                        val beforeStepState = currentState
                        val beforeStepFingerprint = fingerprintOf(beforeStepState)
                        var stepResult = executor.executeStep(step)
                        ensureActiveOrThrow()

                        if (!stepResult.success && step.cmd.trim().equals("click_text", ignoreCase = true)) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "click_text failed once, retrying once before proceeding")
                            }
                            stepResult = executor.executeStep(step)
                            ensureActiveOrThrow()
                        }

                        progressLog.appendLine(
                            "run=$runId | cycle=${cycle + 1} | step=$totalSteps | cmd=${step.cmd} | success=${stepResult.success} | msg=${stepResult.message}"
                        )

                        stepResult.screenState?.let {
                            executor.adoptCanonicalRuntimeState(it)
                            currentState = executor.resolveCanonicalRuntimeState()
                        }

                        val msgLower = stepResult.message.lowercase()
                        val isOpenAppStep = step.cmd.trim().equals("open_app", ignoreCase = true)
                        val semanticStepCandidate =
                            step.cmd in setOf(
                                "click_best_match",
                                "open_best_list_item",
                                "focus_best_input",
                                "input_into_best_field",
                                "press_primary_action",
                                "verify_state"
                            )
                        val meta = executor.getLastRefreshMeta()
                        val recoverablePathNow = hasRecoverableTransitionPath(currentState)
                        val wrongSurfaceSignals = if (
                            msgLower.contains("wrong_surface") ||
                            msgLower.contains("ambiguous_conversation_target") ||
                            msgLower.contains("weak_surface_evidence")
                        ) {
                            1
                        } else {
                            0
                        }
                        val normalizationSignals = if (msgLower.contains("normalization_failed")) 1 else 0
                        val runtimeDecision = KaiExecutionDecisionAuthority.evaluateStepOutcome(
                            step = step,
                            before = beforeStepState,
                            after = currentState,
                            result = stepResult,
                            userPrompt = userPrompt,
                            repeatedNoProgressSteps = repeatedNoProgressSteps,
                            recoverablePathExists = recoverablePathNow,
                            telemetry = KaiExecutionDecisionAuthority.RuntimeTelemetry(
                                noProgressCycles = noProgressCycles,
                                repeatedSameSemanticIntentCycles = repeatedSameSemanticIntentCycles,
                                consecutiveWrongSurfaceChunks = consecutiveWrongSurfaceChunks,
                                wrongSurfaceSignals = wrongSurfaceSignals,
                                normalizationFailures = normalizationSignals,
                                weakReadStreak = executor.getConsecutiveWeakReads(),
                                staleReadStreak = executor.getConsecutiveStaleReads(),
                                observationWeak = meta.weak,
                                observationFallback = meta.fallback,
                                observationReusedLastGood = meta.reusedLastGood,
                                optionalStep = step.optional
                            )
                        )
                        val stageSnapshot = KaiTaskStageEngine.evaluate(
                            userPrompt = userPrompt,
                            currentState = currentState,
                            openAppOutcome = stepResult.openAppOutcome
                        )
                        lastStageSnapshot = stageSnapshot

                        if (stageSnapshot.stage != lastStageLogged) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "Stage transition: ${stageSnapshot.stage.name}")
                            }
                            lastStageLogged = stageSnapshot.stage
                        }

                        if (isOpenAppStep && stageSnapshot.appEntryComplete) {
                            if (!stageSnapshot.finalGoalComplete) {
                                postArrivalContinuationGraceSteps = 2
                            }
                            withContext(Dispatchers.Main) {
                                onLog(
                                    "system",
                                    "App entry confirmed | stage=${stageSnapshot.stage.name}"
                                )
                            }
                        }

                        val meaningfulTransition =
                            runtimeDecision.progressLevel != KaiExecutionDecisionAuthority.ProgressLevel.NONE
                        val openTransitionAcceptedByAuthority =
                            isOpenAppStep &&
                                stepResult.openAppOutcome == KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS &&
                                runtimeDecision.directive == KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE

                        val goalCommitted = runtimeDecision.goalCommitted
                        lastRequiredStepFailed =
                            !step.optional && !stepResult.success && !openTransitionAcceptedByAuthority

                        var effectiveDirective = runtimeDecision.directive
                        if (
                            runtimeDecision.directive == KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS &&
                                KaiExecutionDecisionAuthority.shouldDeferFinalCommit(stageSnapshot)
                        ) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "deferred_goal_commit_after_app_arrival_multi_step")
                            }
                            effectiveDirective = KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE
                        }
                        val inEarlyPostArrivalContinuation =
                            postArrivalContinuationGraceSteps > 0 &&
                                stageSnapshot.appEntryComplete &&
                                !stageSnapshot.finalGoalComplete

                        if (
                            inEarlyPostArrivalContinuation &&
                                effectiveDirective in setOf(
                                    KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN,
                                    KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE
                                ) &&
                                !isOpenAppStep &&
                                recoverablePathNow
                        ) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "post_arrival_continuation_grace_applied: ${runtimeDecision.reason}")
                            }
                            effectiveDirective = KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE
                        }

                        if (
                            effectiveDirective == KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS ||
                            effectiveDirective == KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE
                        ) {
                            val stopAsSuccess = effectiveDirective == KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS
                            val finalMessage = if (stopAsSuccess) {
                                "Final goal committed by runtime authority: ${runtimeDecision.reason}."
                            } else {
                                "Runtime authority requested stop: ${runtimeDecision.reason}"
                            }

                            pushAgentState(
                                state = if (stopAsSuccess) "idle" else "error",
                                observation = currentState.rawDump,
                                decision = finalMessage,
                                action = "stop",
                                notes = runtimeDecision.reason
                            )

                            KaiAgentController.finishActionLoopSession(finalMessage)
                            executor.resetRuntimeState(clearLastGoodScreen = false)

                            return@withContext KaiLoopResult(
                                success = stopAsSuccess,
                                finalMessage = finalMessage,
                                executedSteps = totalSteps
                            )
                        }

                        if (effectiveDirective == KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "runtime_authority_replan: ${runtimeDecision.reason}")
                            }
                            breakCurrentChunk = true
                            break
                        }

                        if (effectiveDirective == KaiExecutionDecisionAuthority.RuntimeDirective.RECOVER) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "runtime_authority_recover: ${runtimeDecision.reason}")
                            }
                            val recovery = executor.attemptRecoveryForStep(step, currentState)
                            recovery.screenState?.let {
                                executor.adoptCanonicalRuntimeState(it)
                                currentState = executor.resolveCanonicalRuntimeState()
                            }
                            progressLog.appendLine(
                                "run=$runId | cycle=${cycle + 1} | step=$totalSteps | recovery=${recovery.success} | msg=${recovery.message}"
                            )

                            val recoveryFailed = !recovery.success
                            if (recoveryFailed) {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "recovery_failed_escalate_to_replan")
                                }
                            }
                            breakCurrentChunk = true
                            break
                        }

                        if (goalCommitted) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "goal_commit_signal:${step.cmd}")
                            }
                        }

                        if (postArrivalContinuationGraceSteps > 0 && !isOpenAppStep) {
                            postArrivalContinuationGraceSteps -= 1
                        }

                        val currentFamily = KaiSurfaceModel.familyName(currentState.surfaceFamily())
                        val stageFamilyKey = "stage=$currentGoalStage|family=$currentFamily|cmd=${step.cmd.lowercase()}"

                        if (meaningfulTransition) {
                            cycleMadeProgress = true
                            repeatedNoProgressSteps = 0
                            alreadyStateNoProgressHits = 0
                        } else if (!openTransitionAcceptedByAuthority) {
                            repeatedNoProgressSteps += 1
                            val familyStageRepeats = (repeatedWrongFamilyByStage[stageFamilyKey] ?: 0) + 1
                            repeatedWrongFamilyByStage[stageFamilyKey] = familyStageRepeats
                            if (familyStageRepeats >= 3 && step.cmd in setOf("focus_best_input", "input_into_best_field", "press_primary_action")) {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "input_stage_family_repeated_without_progress; telemetry escalated to authority")
                                }
                            }
                        }

                        if (semanticStepCandidate && !meaningfulTransition) {
                            val contextKey = "${semanticStepKey(step)}::fp=$beforeStepFingerprint"
                            val count = (repeatedSemanticNoProgressByContext[contextKey] ?: 0) + 1
                            repeatedSemanticNoProgressByContext[contextKey] = count

                            if (count >= 2) {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "Repeated semantic intent without progress in same context; telemetry escalated to authority")
                                }
                            }
                        }

                        val alreadyContextMessage = stepResult.message.lowercase()
                        val isAlreadyInTargetContext =
                            alreadyContextMessage.contains("already on conversation thread") ||
                                alreadyContextMessage.contains("already on messages/chat list surface") ||
                                alreadyContextMessage.contains("notes editor already open") ||
                                alreadyContextMessage.contains("notes editor already focused") ||
                                alreadyContextMessage.contains("composer already detected")

                        if (isAlreadyInTargetContext && !meaningfulTransition) {
                            alreadyStateNoProgressHits += 1
                            if (alreadyStateNoProgressHits >= 2) {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "Already in target semantic context with no progress after targeted retry; telemetry escalated to authority")
                                }
                            }
                        }

                        val isReadScreenStep = step.cmd.trim().equals("read_screen", ignoreCase = true)
                        val isWaitForTextStep = step.cmd.trim().equals("wait_for_text", ignoreCase = true)

                        if (!stepResult.success && isReadScreenStep) {
                            repeatedWeakReadFailures += 1
                            withContext(Dispatchers.Main) {
                                onLog("system", "read_screen is stalled/weak (attempt=$repeatedWeakReadFailures)")
                            }

                            if (repeatedWeakReadFailures >= 8) {
                                repeatedWeakReadFailures = 0
                                pushAgentState(
                                    state = "warning",
                                    observation = currentState.rawDump,
                                    decision = stepResult.message,
                                    action = step.cmd,
                                    notes = "Repeated weak read_screen, continuing with tolerance"
                                )
                            }
                        } else if (isReadScreenStep) {
                            repeatedWeakReadFailures = 0
                        }

                        if (!stepResult.success && isOpenAppStep && !openTransitionAcceptedByAuthority) {
                            repeatedOpenAppUnconfirmed += 1
                            withContext(Dispatchers.Main) {
                                onLog("system", "open_app could not be confirmed yet (attempt=$repeatedOpenAppUnconfirmed)")
                            }

                            if (repeatedOpenAppUnconfirmed >= 8) {
                                repeatedOpenAppUnconfirmed = 0
                                pushAgentState(
                                    state = "warning",
                                    observation = currentState.rawDump,
                                    decision = stepResult.message,
                                    action = step.cmd,
                                    notes = "Repeated unconfirmed open_app with tolerance"
                                )
                            }
                        } else if (isOpenAppStep) {
                            repeatedOpenAppUnconfirmed = 0
                        }

                        if (!stepResult.success && isWaitForTextStep) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "wait_for_text timed out once, but continuing")
                            }
                        }

                        if (!stepResult.success && !step.optional && !openTransitionAcceptedByAuthority) {
                            val semanticFailureCandidate = semanticStepCandidate
                            val failureMsgLower = stepResult.message.lowercase()
                            val wrongSurfaceSignal =
                                failureMsgLower.contains("wrong_surface_for_composer") ||
                                    failureMsgLower.contains("wrong_surface_for_send") ||
                                    failureMsgLower.contains("wrong_surface_for_create_note") ||
                                    failureMsgLower.contains("wrong_surface_for_conversation_open") ||
                                    failureMsgLower.contains("ambiguous_conversation_target") ||
                                    failureMsgLower.contains("normalization_failed_instagram") ||
                                    failureMsgLower.contains("normalization_failed_notes") ||
                                    failureMsgLower.contains("normalization_failed_general") ||
                                    failureMsgLower.contains("replan_required_wrong_surface_repeat") ||
                                    failureMsgLower.contains("weak_surface_evidence")
                            if (wrongSurfaceSignal && wrongSurfaceFamilyThisChunk.isBlank()) {
                                wrongSurfaceFamilyThisChunk = wrongSurfaceFamily(stepResult.message)
                            }

                            if (wrongSurfaceSignal) {
                                val stageKey = "${wrongSurfaceFamily(stepResult.message)}::stage=$currentGoalStage::fp=$beforeStepFingerprint"
                                val stageCount = (repeatedWrongFamilyByStage[stageKey] ?: 0) + 1
                                repeatedWrongFamilyByStage[stageKey] = stageCount
                                if (stageCount >= 2) {
                                    withContext(Dispatchers.Main) {
                                        onLog("system", "wrong_surface_family_repeated")
                                        onLog("system", "recovery_first_replan")
                                    }
                                }
                            }

                            val normalizationFailureSignal =
                                failureMsgLower.contains("normalization_failed_instagram") ||
                                    failureMsgLower.contains("normalization_failed_notes")
                            if (normalizationFailureSignal) {
                                val normKey = "norm::${wrongSurfaceFamily(stepResult.message)}::fp=$beforeStepFingerprint"
                                val normCount = (normalizationFailuresByContext[normKey] ?: 0) + 1
                                normalizationFailuresByContext[normKey] = normCount
                                withContext(Dispatchers.Main) {
                                    onLog(
                                        "system",
                                        if (failureMsgLower.contains("instagram")) {
                                            "normalization_failed_instagram"
                                        } else {
                                            "normalization_failed_notes"
                                        }
                                    )
                                }
                                if (normCount >= 2) {
                                    withContext(Dispatchers.Main) {
                                        onLog("system", "recovery_first_replan")
                                    }
                                }
                            }

                            val failureContextKey = "${semanticStepKey(step)}::fp=$beforeStepFingerprint"
                            val repeatedForContext = (repeatedSemanticFailuresByContext[failureContextKey] ?: 0) + 1
                            repeatedSemanticFailuresByContext[failureContextKey] = repeatedForContext

                            progressLog.appendLine(
                                "run=$runId | cycle=${cycle + 1} | step=$totalSteps | semantic_failure_context=$failureContextKey | attempt=$repeatedForContext | success=false"
                            )

                            val finalMessage = if (semanticFailureCandidate && (wrongSurfaceSignal || repeatedForContext >= 2)) {
                                "Non-optional semantic step $totalSteps failed in wrong context; moving to next chunk for replan"
                            } else {
                                "Non-optional step $totalSteps failed: ${stepResult.message} (continuing with next step)"
                            }

                            pushAgentState(
                                state = "warning",
                                observation = currentState.rawDump,
                                decision = stepResult.message,
                                action = step.cmd,
                                notes = if (semanticFailureCandidate && (wrongSurfaceSignal || repeatedForContext >= 2)) {
                                    if (wrongSurfaceSignal) {
                                        "semantic_failure_signal=${stepResult.message}"
                                    } else {
                                        "Non-optional semantic failed repeatedly; chunk break for replan"
                                    }
                                } else if (semanticFailureCandidate) {
                                    "Non-optional semantic failed once; allowing one strategy shift"
                                } else {
                                    "Non-optional failed at step $totalSteps but continuing"
                                }
                            )

                            withContext(Dispatchers.Main) {
                                onLog("system", finalMessage)
                            }

                            if (semanticFailureCandidate && repeatedForContext == 1) {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "Planner hint: shift strategy for this semantic subgoal on next chunk")
                                }
                            }

                            if (semanticFailureCandidate && (wrongSurfaceSignal || repeatedForContext >= 2)) {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "semantic_failure_repeated_or_wrong_surface; delegated to authority for directive")
                                }
                            }
                        }

                        if (repeatedNoProgressSteps >= 6) {
                            val finalMessage =
                                "The agent has many no-progress steps; telemetry threshold reached and delegated to authority."

                            pushAgentState(
                                state = "warning",
                                observation = currentState.rawDump,
                                decision = "no_verified_progress",
                                action = step.cmd,
                                notes = "Too many no-progress steps, but continuing"
                            )

                            withContext(Dispatchers.Main) {
                                onLog("system", finalMessage)
                            }
                        }

                        if (semanticStepCandidate) {
                            semanticStepsInChunk += 1
                            if (semanticStepsInChunk >= 3) {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "cycle_semantic_budget_reached")
                                }
                                breakCurrentChunk = true
                                stepCursor += 1
                                break
                            }
                        }

                        stepCursor += 1
                    }

                    if (breakCurrentChunk) {
                        ensureActiveOrThrow()
                    }

                    ensureActiveOrThrow()

                    withContext(Dispatchers.Main) {
                        onStatus("Observing")
                    }

                    val beforeObserveFingerprint = fingerprintOf(currentState)
                    currentState = executor.requestFreshScreen(1800L)
                    currentState = executor.resolveCanonicalRuntimeState()
                    val afterObserveFingerprint = fingerprintOf(currentState)

                    if (afterObserveFingerprint != beforeObserveFingerprint) {
                        cycleMadeProgress = true
                    }

                    val overallFingerprintChanged = afterObserveFingerprint != lastFingerprint
                    if (overallFingerprintChanged || cycleMadeProgress) {
                        noProgressCycles = 0
                        lastFingerprint = afterObserveFingerprint
                    } else {
                        noProgressCycles += 1
                    }

                    if (wrongSurfaceFamilyThisChunk.isNotBlank()) {
                        consecutiveWrongSurfaceChunks =
                            if (wrongSurfaceFamilyThisChunk == lastWrongSurfaceFamilyAcrossChunks) {
                                consecutiveWrongSurfaceChunks + 1
                            } else {
                                1
                            }
                        lastWrongSurfaceFamilyAcrossChunks = wrongSurfaceFamilyThisChunk
                    } else {
                        consecutiveWrongSurfaceChunks = 0
                        lastWrongSurfaceFamilyAcrossChunks = ""
                    }

                    val recoverablePath = hasRecoverableTransitionPath(currentState)

                    repeatedSameSemanticIntentCycles =
                        if (!overallFingerprintChanged && !cycleMadeProgress && cycleSemanticIntentKey.isNotBlank() && cycleSemanticIntentKey == lastSemanticIntentKey) {
                            repeatedSameSemanticIntentCycles + 1
                        } else {
                            0
                        }
                    lastSemanticIntentKey = cycleSemanticIntentKey

                    pushAgentState(
                        state = "observing",
                        observation = currentState.rawDump,
                        decision = "post_cycle_refresh",
                        action = "requestFreshScreen",
                        notes = "cycle=${cycle + 1} done | noProgressCycles=$noProgressCycles"
                    )

                    val cycleMeta = executor.getLastRefreshMeta()
                    val cycleDecision = KaiExecutionDecisionAuthority.evaluateCycleOutcome(
                        userPrompt = userPrompt,
                        state = currentState,
                        recoverablePathExists = recoverablePath,
                        telemetry = KaiExecutionDecisionAuthority.RuntimeTelemetry(
                            noProgressCycles = noProgressCycles,
                            repeatedSameSemanticIntentCycles = repeatedSameSemanticIntentCycles,
                            consecutiveWrongSurfaceChunks = consecutiveWrongSurfaceChunks,
                            weakReadStreak = executor.getConsecutiveWeakReads(),
                            staleReadStreak = executor.getConsecutiveStaleReads(),
                            observationWeak = cycleMeta.weak,
                            observationFallback = cycleMeta.fallback,
                            observationReusedLastGood = cycleMeta.reusedLastGood
                        ),
                        strictTargetEvidenceSatisfied = hasStrictCycleEvidence(userPrompt, currentState),
                        lastRequiredStepFailed = lastRequiredStepFailed,
                        plannerSignaledGoalComplete = plannerSignaledGoalComplete
                    )

                    val deferCycleCommit =
                        cycleDecision.directive == KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS &&
                            KaiExecutionDecisionAuthority.shouldDeferFinalCommit(lastStageSnapshot)
                    val effectiveCycleDirective = if (deferCycleCommit) {
                        withContext(Dispatchers.Main) {
                            onLog("system", "deferred_cycle_commit_after_app_arrival_multi_step")
                        }
                        KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE
                    } else {
                        cycleDecision.directive
                    }

                    when (effectiveCycleDirective) {
                        KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN -> {
                            withContext(Dispatchers.Main) {
                                onLog("system", "runtime_authority_cycle_replan: ${cycleDecision.reason}")
                            }
                        }

                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS,
                        KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> {
                            val stopAsSuccess = cycleDecision.directive == KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS
                            val finalMessage = if (stopAsSuccess) {
                                "Final goal committed by runtime authority from strict cycle evidence."
                            } else {
                                "Runtime authority cycle stop: ${cycleDecision.reason}"
                            }

                            pushAgentState(
                                state = if (stopAsSuccess) "idle" else "error",
                                observation = currentState.rawDump,
                                decision = finalMessage,
                                action = "stop",
                                notes = cycleDecision.reason
                            )

                            KaiAgentController.finishActionLoopSession(finalMessage)
                            executor.resetRuntimeState(clearLastGoodScreen = false)

                            return@withContext KaiLoopResult(
                                success = stopAsSuccess,
                                finalMessage = finalMessage,
                                executedSteps = totalSteps
                            )
                        }

                        else -> Unit
                    }

                    KaiAgentController.pruneObservationMemory(12)
                }

                val loopLimitDecision = KaiExecutionDecisionAuthority.evaluateStepOutcome(
                    step = KaiActionStep(cmd = "wait"),
                    before = currentState,
                    after = currentState,
                    result = KaiActionExecutionResult(success = false, message = "loop_limit"),
                    userPrompt = userPrompt,
                    repeatedNoProgressSteps = repeatedNoProgressSteps,
                    recoverablePathExists = hasRecoverableTransitionPath(currentState),
                    telemetry = KaiExecutionDecisionAuthority.RuntimeTelemetry(
                        noProgressCycles = noProgressCycles,
                        repeatedSameSemanticIntentCycles = repeatedSameSemanticIntentCycles,
                        consecutiveWrongSurfaceChunks = consecutiveWrongSurfaceChunks,
                        weakReadStreak = executor.getConsecutiveWeakReads(),
                        staleReadStreak = executor.getConsecutiveStaleReads(),
                        observationWeak = executor.getLastRefreshMeta().weak,
                        observationFallback = executor.getLastRefreshMeta().fallback,
                        observationReusedLastGood = executor.getLastRefreshMeta().reusedLastGood,
                        loopSafetyLimitReached = true
                    )
                )

                val finalMessage =
                    "Runtime authority stop: ${loopLimitDecision.reason}. Refine the prompt and try again."

                pushAgentState(
                    state = "idle",
                    observation = currentState.rawDump,
                    decision = "loop_limit_reached",
                    action = "stop",
                    notes = "Agent reached current safety loop limit"
                )

                KaiAgentController.finishActionLoopSession(finalMessage)
                executor.resetRuntimeState(clearLastGoodScreen = false)

                KaiLoopResult(
                    success = false,
                    finalMessage = finalMessage,
                    executedSteps = totalSteps
                )
            }
        } catch (e: CancellationException) {
            pushAgentState(
                state = "cancelled",
                decision = "cancelled",
                action = "stop",
                notes = e.message.orEmpty()
            )
            KaiAgentController.finishActionLoopSession("Agent loop cancelled.")
            throw e
        } finally {
            KaiBubbleManager.releaseAllSuppression()
            KaiBubbleManager.softResetUiState()
        }
    }
}