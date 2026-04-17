
package com.example.reply.agent

import android.content.Context
import android.util.Log
import com.example.reply.ai.KaiTask
import com.example.reply.ui.KaiBubbleManager
import com.example.reply.ui.KaiVoice
import com.example.reply.ui.OpenAIClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KaiObservation(
    val packageName: String,
    val screenPreview: String,
    val elements: List<KaiUiElement> = emptyList(),
    val screenKind: String = "unknown",
    val semanticConfidence: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis()
)

data class KaiAgentSnapshot(
    val currentPackage: String = "",
    val currentGoal: String = "",
    val customPrompt: String = "",
    val lastSuggestion: String = "",
    val lastScreenPreview: String = "",
    val requiresApproval: Boolean = false,
    val memoryCount: Int = 0,
    val isRunning: Boolean = false,
    val statusText: String = "Idle",
    val actionLoopActive: Boolean = false,
    val actionLoopPrompt: String = "",
    val lastActionLoopFingerprint: String = "",
    val repeatedCompletionHints: Int = 0
)

object KaiAgentController {
    private const val TAG = "KaiAgentController"
    private const val MAX_MEMORY = 18
    private const val MIN_INSIGHT_GAP_MS = 2500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val memory = ArrayDeque<KaiObservation>()

    @Volatile private var snapshot = KaiAgentSnapshot()
    @Volatile private var continuousRunning = false
    @Volatile private var insightBusy = false
    @Volatile private var lastInsightAt = 0L
    @Volatile private var silentInsightCallback: ((String) -> Unit)? = null

    private var continuousJob: Job? = null

    fun getSnapshot(): KaiAgentSnapshot = snapshot
    fun getLatestObservation(): KaiObservation = KaiLiveObservationRuntime.bestObservation()
    fun getLatestScreenState(): KaiScreenState = KaiLiveObservationRuntime.currentScreenState(requireStrong = false)

    fun ensureRuntimeObservationBridge(context: Context) {
        KaiLiveObservationRuntime.ensureBridge(context)
    }

    fun isRuntimeObservationBridgeActive(): Boolean = true

    fun onObservationArrived(
        packageName: String,
        screenPreview: String,
        elements: List<KaiUiElement>,
        screenKind: String,
        semanticConfidence: Float
    ) {
        val obs = KaiObservation(
            packageName = packageName,
            screenPreview = screenPreview,
            elements = elements,
            screenKind = screenKind,
            semanticConfidence = semanticConfidence
        )
        synchronized(memory) {
            memory.addLast(obs)
            while (memory.size > MAX_MEMORY) memory.removeFirst()
        }

        if (packageName.isNotBlank() || screenPreview.isNotBlank()) {
            snapshot = snapshot.copy(
                currentPackage = packageName.ifBlank { snapshot.currentPackage },
                lastScreenPreview = screenPreview.take(1600),
                memoryCount = synchronized(memory) { memory.size },
                isRunning = isRunning(),
                statusText = if (isRunning()) {
                    if (snapshot.actionLoopActive) "Action loop active" else "Monitoring"
                } else {
                    "Idle"
                }
            )
        }
    }

    fun mirrorRuntimeObservation(state: KaiScreenState) {
        onObservationArrived(
            packageName = state.packageName,
            screenPreview = state.rawDump,
            elements = state.elements,
            screenKind = state.screenKind,
            semanticConfidence = state.semanticConfidence
        )
    }

    fun pruneObservationMemory(maxItems: Int) {
        val cap = maxItems.coerceIn(1, MAX_MEMORY)
        synchronized(memory) {
            while (memory.size > cap) memory.removeFirst()
        }
        snapshot = snapshot.copy(memoryCount = synchronized(memory) { memory.size })
    }

    fun setGoal(goal: String) {
        snapshot = snapshot.copy(currentGoal = goal.trim())
    }

    fun setCustomPrompt(prompt: String) {
        snapshot = snapshot.copy(customPrompt = prompt.trim())
    }

    fun isRunning(): Boolean = continuousRunning || snapshot.actionLoopActive

    fun startActionLoopSession(prompt: String) {
        snapshot = snapshot.copy(
            actionLoopActive = true,
            actionLoopPrompt = prompt.trim(),
            statusText = "Action loop active",
            isRunning = true
        )
    }

    fun finishActionLoopSession(message: String = "") {
        snapshot = snapshot.copy(
            actionLoopActive = false,
            actionLoopPrompt = "",
            statusText = if (continuousRunning) "Monitoring" else "Idle",
            isRunning = continuousRunning,
            lastSuggestion = if (message.isNotBlank()) message else snapshot.lastSuggestion
        )
        if (continuousRunning) {
            KaiLiveObservationRuntime.startWatching(immediateDump = true)
        }
    }

    fun markActionLoopObserved(state: KaiScreenState) {
        snapshot = snapshot.copy(
            currentPackage = state.packageName,
            lastScreenPreview = state.preview(1600),
            lastActionLoopFingerprint = state.semanticFingerprint()
        )
    }

    fun resetTransientStateForNewRun() {
        synchronized(memory) { memory.clear() }
        insightBusy = false
        lastInsightAt = 0L
        snapshot = snapshot.copy(
            currentPackage = "",
            lastScreenPreview = "",
            lastSuggestion = "",
            memoryCount = 0,
            isRunning = continuousRunning,
            statusText = if (continuousRunning) "Monitoring" else "Idle",
            actionLoopActive = false,
            actionLoopPrompt = "",
            lastActionLoopFingerprint = "",
            repeatedCompletionHints = 0,
            requiresApproval = false
        )
    }

    fun stopContinuousAnalysis() {
        continuousRunning = false
        continuousJob?.cancel()
        continuousJob = null
        insightBusy = false
        KaiLiveObservationRuntime.stopWatching()
        snapshot = snapshot.copy(
            isRunning = snapshot.actionLoopActive,
            statusText = if (snapshot.actionLoopActive) "Action loop active" else "Idle"
        )
    }

    fun toggleContinuousAnalysis(
        userGoal: String,
        customPrompt: String,
        onRequestDump: () -> Unit = {},
        onInsight: (String) -> Unit
    ): Boolean {
        if (continuousRunning) {
            stopContinuousAnalysis()
            return false
        }

        setGoal(userGoal)
        setCustomPrompt(customPrompt)
        continuousRunning = true
        silentInsightCallback = onInsight
        snapshot = snapshot.copy(isRunning = true, statusText = "Monitoring")

        KaiLiveObservationRuntime.startWatching(immediateDump = true)
        runCatching { onRequestDump() }

        continuousJob = scope.launch {
            while (isActive && continuousRunning && !snapshot.actionLoopActive) {
                maybeGenerateContinuousInsight()
                delay(900L)
            }
        }
        return true
    }

    private fun maybeGenerateContinuousInsight() {
        if (!continuousRunning || insightBusy) return
        val now = System.currentTimeMillis()
        if (now - lastInsightAt < MIN_INSIGHT_GAP_MS) return

        val memoryText = synchronized(memory) {
            memory.takeLast(5).joinToString("\n\n") { obs ->
                "Package: ${obs.packageName.ifBlank { "Unknown" }}\nScreen:\n${obs.screenPreview.take(900)}"
            }
        }
        if (memoryText.isBlank()) return

        insightBusy = true
        lastInsightAt = now

        scope.launch {
            try {
                val reply = OpenAIClient.ask(
                    userText = """
                        You are Kai Agent inside Kai OS.
                        Goal:
                        ${snapshot.currentGoal.ifBlank { "Observe and suggest the next practical step." }}

                        Recent observations:
                        $memoryText

                        Reply briefly in the user's language:
                        1) What is happening now
                        2) Safest next action
                    """.trimIndent(),
                    task = KaiTask.BRAIN
                )
                snapshot = snapshot.copy(
                    lastSuggestion = reply,
                    statusText = if (continuousRunning) "Monitoring" else "Idle"
                )
                withContext(Dispatchers.Main) { silentInsightCallback?.invoke(reply) }
            } catch (e: Exception) {
                Log.e(TAG, "Continuous insight failed: ${e.message}", e)
            } finally {
                insightBusy = false
            }
        }
    }

    suspend fun buildActionPlan(
        userPrompt: String,
        currentScreenState: KaiScreenState = getLatestScreenState(),
        priorProgress: String = "",
        maxStepsPerChunk: Int = 4
    ): KaiActionPlan {
        val effectivePrompt = userPrompt.trim()
        if (effectivePrompt.isBlank()) {
            return KaiActionPlan(summary = "Empty prompt.", steps = emptyList(), goalComplete = false)
        }

        pruneObservationMemory(10)
        markActionLoopObserved(currentScreenState)

        val memoryText = synchronized(memory) {
            memory.takeLast(8).joinToString("\n\n") { item ->
                "Package: ${item.packageName.ifBlank { "Unknown" }}\nScreen:\n${item.screenPreview.take(900)}"
            }
        }

        val appHint = KaiActionPlanPostProcessor.inferPrimaryAppHint(effectivePrompt)
        val goalMode = KaiTaskStageEngine.classifyGoalMode(effectivePrompt)
        val stageSnapshot = KaiTaskStageEngine.evaluate(
            userPrompt = effectivePrompt,
            currentState = currentScreenState
        )

        val effectiveMaxSteps = if (goalMode == KaiTaskStageEngine.GoalMode.MULTI_STAGE) {
            maxStepsPerChunk.coerceIn(3, 5)
        } else {
            maxStepsPerChunk.coerceIn(1, 3)
        }

        val directStageStep = KaiTaskStageEngine.buildContinuationStep(
            stageSnapshot = stageSnapshot,
            userPrompt = effectivePrompt,
            currentState = currentScreenState
        )

        if (stageSnapshot.shouldContinue && directStageStep != null && stageSnapshot.nextSemanticAction != "open_app") {
            val summary = when (stageSnapshot.nextSemanticAction) {
                "open_messages" -> "Reach the messages surface inside the current app."
                "open_target_conversation" -> "Open the target conversation."
                "open_note_editor" -> "Reach the note editor."
                "open_first_media" -> "Open the first playable media item."
                else -> "Continue the current stage."
            }
            return KaiActionPlan(
                summary = summary,
                steps = listOf(directStageStep),
                goalComplete = false,
                plannerGoalComplete = false
            )
        }

        val plannerPrompt = """
            You are Kai Agent inside Kai OS.
            Return STRICT JSON only.
            Build a compact action chunk only.
            Do not claim final completion unless the current state already proves it.
            Prefer direct semantic actions over noisy retries.
            Max steps: $effectiveMaxSteps

            Stage snapshot:
            - stage=${stageSnapshot.stage.name}
            - appEntryComplete=${stageSnapshot.appEntryComplete}
            - finalGoalComplete=${stageSnapshot.finalGoalComplete}
            - nextSemanticAction=${stageSnapshot.nextSemanticAction}

            User goal:
            ${snapshot.currentGoal.ifBlank { effectivePrompt }}

            Custom prompt:
            ${snapshot.customPrompt.ifBlank { effectivePrompt }}

            Current app package:
            ${currentScreenState.packageName.ifBlank { "Unknown" }}

            Current visible screen:
            ${currentScreenState.preview(2400)}

            Recent memory:
            ${memoryText.ifBlank { "No memory yet." }}

            Progress so far:
            ${priorProgress.ifBlank { "No prior execution yet." }}

            Allowed commands:
            open_app, click_text, long_press_text, input_text,
            click_best_match, focus_best_input, input_into_best_field,
            press_primary_action, open_best_list_item, verify_state,
            scroll, wait_for_text, tap_xy, long_press_xy, swipe_xy,
            back, home, recents, wait

            JSON shape:
            {
              "summary": "short summary",
              "goalComplete": false,
              "steps": [
                {
                  "cmd": "click_text",
                  "text": "",
                  "dir": "",
                  "times": 1,
                  "waitMs": 500,
                  "x": null,
                  "y": null,
                  "endX": null,
                  "endY": null,
                  "holdMs": 450,
                  "timeoutMs": 4000,
                  "optional": false,
                  "note": "",
                  "selectorText": "",
                  "selectorHint": "",
                  "selectorId": "",
                  "selectorRole": "",
                  "expectedPackage": "",
                  "expectedTexts": [],
                  "expectedScreenKind": "",
                  "strategy": "",
                  "confidence": 0.0
                }
              ]
            }
        """.trimIndent()

        val raw = OpenAIClient.ask(plannerPrompt, task = KaiTask.ACTION_PLANNING)
        val parsedPlan = KaiActionPlanParser.parseActionPlan(raw)
        val plan = parsedPlan.copy(
            steps = parsedPlan.steps.take(effectiveMaxSteps),
            goalComplete = false,
            plannerGoalComplete = false
        )

        snapshot = snapshot.copy(
            customPrompt = effectivePrompt,
            lastSuggestion = plan.summary,
            statusText = if (isRunning()) "Monitoring" else "Action plan ready",
            requiresApproval = false
        )

        return KaiActionPlanPostProcessor.postProcessPlan(
            plan = plan,
            currentScreenState = currentScreenState,
            appHint = appHint,
            maxStepsPerChunk = effectiveMaxSteps,
            priorProgress = priorProgress
        )
    }

    fun requestActionPlan(
        userPrompt: String,
        currentScreenState: KaiScreenState = getLatestScreenState(),
        priorProgress: String = "",
        maxStepsPerChunk: Int = 4,
        onReady: (KaiActionPlan) -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                val plan = buildActionPlan(
                    userPrompt = userPrompt,
                    currentScreenState = currentScreenState,
                    priorProgress = priorProgress,
                    maxStepsPerChunk = maxStepsPerChunk
                )
                withContext(Dispatchers.Main) { onReady(plan) }
            } catch (e: Exception) {
                Log.e(TAG, "Action plan failed: ${e.message}", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Action plan failed") }
            }
        }
    }

    @Volatile
    private var activeLoopJob: Job? = null

    fun isActionLoopActive(): Boolean = activeLoopJob?.isActive == true

    fun cancelDirectActionLoop() {
        activeLoopJob?.cancel()
        activeLoopJob = null
        KaiBubbleManager.releaseAllSuppression()
        KaiBubbleManager.softResetUiState()
        finishActionLoopSession("Cancelled")
    }

    fun startDirectActionLoop(
        context: Context,
        prompt: String,
        onLog: (role: String, text: String) -> Unit,
        onFinished: (KaiLoopResult) -> Unit
    ) {
        cancelDirectActionLoop()

        val clean = prompt.trim()
        if (clean.isBlank()) {
            onFinished(KaiLoopResult(false, "Empty prompt.", 0))
            return
        }

        val appContext = context.applicationContext

        runCatching { KaiVoice.resetTransientStateForNewRun() }
        runCatching { OpenAIClient.resetTransientStateForNewRun() }
        resetTransientStateForNewRun()
        ensureRuntimeObservationBridge(appContext)
        KaiBubbleManager.releaseAllSuppression()
        if (KaiBubbleManager.isShowing()) KaiBubbleManager.softResetUiState()

        startActionLoopSession(clean)
        setCustomPrompt(clean)
        setGoal(clean)

        KaiLiveObservationRuntime.ensureBridge(appContext)
        KaiLiveObservationRuntime.hardReset(stopWatching = false)
        KaiLiveObservationRuntime.startWatching(immediateDump = true)

        activeLoopJob = scope.launch {
            try {
                withContext(Dispatchers.Main) { onLog("system", "Agent loop starting…") }

                var currentState = KaiVisionInterpreter.toScreenState(
                    KaiLiveObservationRuntime.awaitFreshObservation(
                        afterTime = System.currentTimeMillis() - 1500L,
                        timeoutMs = 2200L
                    )
                )
                markActionLoopObserved(currentState)

                var verifiedSteps = 0
                var anyWorldStateSuccess = false
                var noProgressCycles = 0
                var weakReadStreak = 0
                var priorProgress = ""
                var chunkCount = 0
                val maxChunks = 4

                while (isActive && chunkCount < maxChunks) {
                    chunkCount += 1
                    val stageBeforeChunk = KaiTaskStageEngine.evaluate(clean, currentState)
                    if (!stageBeforeChunk.shouldContinue) {
                        val msg = "Goal already satisfied from current world-state."
                        withContext(Dispatchers.Main) {
                            finishActionLoopSession(msg)
                            onFinished(KaiLoopResult(true, msg, verifiedSteps))
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) { onLog("system", "Planning…") }
                    val plan = buildActionPlan(
                        userPrompt = clean,
                        currentScreenState = currentState,
                        priorProgress = priorProgress
                    )

                    withContext(Dispatchers.Main) { onLog("system", "Plan: ${plan.summary}") }

                    if (plan.steps.isEmpty()) {
                        val msg = if (anyWorldStateSuccess) {
                            "No more actionable steps planned. Partial success already verified."
                        } else {
                            "No actionable steps planned."
                        }
                        withContext(Dispatchers.Main) {
                            finishActionLoopSession(msg)
                            onFinished(KaiLoopResult(anyWorldStateSuccess, msg, verifiedSteps))
                        }
                        return@launch
                    }

                    var chunkMadeVerifiedProgress = false
                    var replanAfterChunk = false

                    for ((stepIndex, step) in plan.steps.withIndex()) {
                        if (!isActive) break

                        val cmd = step.normalizedCommand()
                        val payload = step.semanticPayload().ifBlank { step.note }
                        withContext(Dispatchers.Main) {
                            onLog("system", "Step ${stepIndex + 1}/${plan.steps.size}: $cmd → $payload")
                        }

                        if (cmd == "wait") {
                            delay(step.waitMs.coerceIn(300L, 5000L))
                            verifiedSteps++
                            chunkMadeVerifiedProgress = true
                            anyWorldStateSuccess = true
                            continue
                        }

                        val beforeObs = KaiLiveObservationRuntime.bestObservation(expectedPackage = step.expectedPackage)
                        val beforeState = KaiVisionInterpreter.toScreenState(beforeObs)

                        val resolvedExpectedPackage = when {
                            step.expectedPackage.isNotBlank() -> step.expectedPackage
                            step.isOpenAppStep() -> KaiAppIdentityRegistry.primaryPackageForKey(
                                KaiAppIdentityRegistry.resolveAppKey(step.text)
                            )
                            else -> ""
                        }

                        val dispatchTime = System.currentTimeMillis()
                        KaiStepDispatcher.dispatchStepCommand(appContext, step.copy(expectedPackage = resolvedExpectedPackage))

                        val result: KaiActionExecutionResult
                        val afterState: KaiScreenState

                        if (step.isOpenAppStep()) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "Awaiting app open: expected_pkg=$resolvedExpectedPackage")
                            }
                            val openOutcome = KaiLiveObservationRuntime.awaitPostOpenStabilization(
                                expectedPackage = resolvedExpectedPackage,
                                dispatchTime = dispatchTime,
                                timeoutMs = step.timeoutMs.coerceIn(3200L, 8000L)
                            )
                            afterState = KaiLiveObservationRuntime.currentScreenState(
                                expectedPackage = resolvedExpectedPackage,
                                requireStrong = false
                            )
                            result = KaiActionExecutionResult(
                                success = openOutcome == KaiOpenAppOutcome.TARGET_READY ||
                                    openOutcome == KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP,
                                message = "open_app_outcome:$openOutcome",
                                screenState = afterState,
                                openAppOutcome = openOutcome
                            )
                            withContext(Dispatchers.Main) {
                                onLog("system", "App open world-state: $openOutcome")
                            }
                        } else {
                            val freshObs = KaiLiveObservationRuntime.awaitFreshObservation(
                                afterTime = dispatchTime,
                                timeoutMs = step.waitMs.coerceIn(1400L, 6500L),
                                expectedPackage = resolvedExpectedPackage.ifBlank { currentState.packageName }
                            )
                            afterState = KaiVisionInterpreter.toScreenState(freshObs)
                            result = KaiActionExecutionResult(
                                success = freshObs.updatedAt > dispatchTime &&
                                    KaiVisionInterpreter.isUsableState(afterState),
                                message = if (freshObs.updatedAt > dispatchTime) "fresh_observation_arrived" else "no_fresh_observation",
                                screenState = afterState
                            )
                        }

                        val observationWeak = !KaiVisionInterpreter.isUsableState(afterState)
                        weakReadStreak = if (observationWeak) weakReadStreak + 1 else 0

                        val telemetry = KaiExecutionDecisionAuthority.RuntimeTelemetry(
                            noProgressCycles = noProgressCycles,
                            weakReadStreak = weakReadStreak,
                            observationWeak = observationWeak,
                            observationFallback = !result.success,
                            observationReusedLastGood = afterState.semanticFingerprint() == beforeState.semanticFingerprint(),
                            loopSafetyLimitReached = chunkCount >= maxChunks && !result.success
                        )

                        val decision = KaiExecutionDecisionAuthority.evaluateStepOutcome(
                            step = step.copy(expectedPackage = resolvedExpectedPackage),
                            before = beforeState,
                            after = afterState,
                            result = result,
                            repeatedNoProgressSteps = noProgressCycles,
                            recoverablePathExists = true,
                            telemetry = telemetry
                        )

                        val progressMade = KaiExecutionDecisionAuthority.hasMeaningfulProgress(beforeState, afterState)
                        noProgressCycles = if (progressMade) 0 else noProgressCycles + 1
                        currentState = afterState
                        markActionLoopObserved(afterState)

                        withContext(Dispatchers.Main) {
                            onLog("system", "Step ${stepIndex + 1} → ${decision.directive.name} [${decision.reason}]")
                        }

                        when (decision.directive) {
                            KaiExecutionDecisionAuthority.RuntimeDirective.CONTINUE -> {
                                verifiedSteps++
                                anyWorldStateSuccess = true
                                chunkMadeVerifiedProgress = true

                                val stageAfterStep = KaiTaskStageEngine.evaluate(
                                    userPrompt = clean,
                                    currentState = afterState,
                                    openAppOutcome = result.openAppOutcome
                                )
                                if (step.isOpenAppStep() && stageAfterStep.shouldContinue) {
                                    replanAfterChunk = true
                                    priorProgress += "\nOpened app surface: ${afterState.packageName} / ${afterState.screenKind}"
                                    break
                                }
                            }
                            KaiExecutionDecisionAuthority.RuntimeDirective.STOP_SUCCESS -> {
                                verifiedSteps++
                                anyWorldStateSuccess = true
                                val msg = "Goal verified after step ${stepIndex + 1}/${plan.steps.size}. ${plan.summary}"
                                withContext(Dispatchers.Main) {
                                    finishActionLoopSession(msg)
                                    onFinished(KaiLoopResult(true, msg, verifiedSteps))
                                }
                                return@launch
                            }
                            KaiExecutionDecisionAuthority.RuntimeDirective.STOP_FAILURE -> {
                                val msg = "Step ${stepIndex + 1} hard-stopped: ${decision.reason}"
                                withContext(Dispatchers.Main) {
                                    finishActionLoopSession(msg)
                                    onFinished(KaiLoopResult(false, msg, verifiedSteps))
                                }
                                return@launch
                            }
                            KaiExecutionDecisionAuthority.RuntimeDirective.RECOVER,
                            KaiExecutionDecisionAuthority.RuntimeDirective.REPLAN -> {
                                withContext(Dispatchers.Main) {
                                    onLog("system", "Step ${stepIndex + 1} unverified: ${decision.reason}")
                                }
                                replanAfterChunk = true
                                if (!step.optional) {
                                    priorProgress += "\nUnverified step: ${step.cmd} (${decision.reason})"
                                }
                                break
                            }
                        }
                    }

                    val stageAfterChunk = KaiTaskStageEngine.evaluate(clean, currentState)
                    if (!stageAfterChunk.shouldContinue) {
                        val msg = "World-state verified $verifiedSteps steps. Goal satisfied."
                        withContext(Dispatchers.Main) {
                            finishActionLoopSession(msg)
                            onFinished(KaiLoopResult(true, msg, verifiedSteps))
                        }
                        return@launch
                    }

                    if (!replanAfterChunk && !chunkMadeVerifiedProgress) {
                        val msg = if (anyWorldStateSuccess) {
                            "Verified partial progress, but continuation could not be confirmed."
                        } else {
                            "Commands dispatched but world-state not confirmed for any step."
                        }
                        withContext(Dispatchers.Main) {
                            finishActionLoopSession(msg)
                            onFinished(KaiLoopResult(anyWorldStateSuccess, msg, verifiedSteps))
                        }
                        return@launch
                    }

                    priorProgress += "\nChunk $chunkCount summary: ${currentState.packageName} / ${currentState.screenKind}"
                }

                val finalStage = KaiTaskStageEngine.evaluate(clean, currentState)
                val msg = when {
                    !finalStage.shouldContinue && anyWorldStateSuccess ->
                        "World-state verified $verifiedSteps steps. Goal satisfied."
                    anyWorldStateSuccess ->
                        "World-state verified $verifiedSteps steps, but more continuation is still needed."
                    else ->
                        "Commands dispatched but world-state not confirmed for any step."
                }
                withContext(Dispatchers.Main) {
                    finishActionLoopSession(msg)
                    onFinished(KaiLoopResult(!finalStage.shouldContinue && anyWorldStateSuccess, msg, verifiedSteps))
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    finishActionLoopSession("Agent loop cancelled.")
                    onFinished(KaiLoopResult(false, "Agent loop cancelled.", 0))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct action loop error: ${e.message}", e)
                val msg = "Error: ${e.message}"
                withContext(Dispatchers.Main) {
                    finishActionLoopSession(msg)
                    onFinished(KaiLoopResult(false, msg, 0))
                }
            } finally {
                activeLoopJob = null
                KaiLiveObservationRuntime.softCleanupAfterRun()
                KaiBubbleManager.releaseAllSuppression()
            }
        }
    }

    fun parseElementsFromJson(elementsJson: String?): List<KaiUiElement> =
        KaiLiveObservationRuntime.parseElementsFromJson(elementsJson)
}
