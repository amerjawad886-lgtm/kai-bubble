
package com.example.reply.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.reply.ai.KaiTask
import com.example.reply.ui.KaiAccessibilityService
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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

        val appHint = inferPrimaryAppHint(effectivePrompt)
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
        val parsedPlan = parseActionPlan(raw)
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

        return postProcessPlan(
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
                        dispatchStepCommand(appContext, step.copy(expectedPackage = resolvedExpectedPackage))

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

private fun dispatchStepCommand(context: Context, step: KaiActionStep) {
        val cmd = step.normalizedCommand()
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            if (step.expectedPackage.isNotBlank()) {
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, step.expectedPackage)
            }
            putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, step.timeoutMs)
        }

        when (cmd) {
            "open_app" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_OPEN_APP)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.text)
            }
            "click_text", "click_best_match", "open_best_list_item" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_CLICK_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.semanticPayload())
            }
            "long_press_text" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_LONG_PRESS_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.semanticPayload())
                intent.putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, step.holdMs)
            }
            "input_text", "focus_best_input", "input_into_best_field" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_INPUT_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.text)
            }
            "press_primary_action" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_CLICK_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.text.ifBlank { "submit" })
            }
            "scroll" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_SCROLL)
                intent.putExtra(KaiAccessibilityService.EXTRA_DIR, step.dir.ifBlank { "down" })
                intent.putExtra(KaiAccessibilityService.EXTRA_TIMES, step.times.coerceIn(1, 10))
            }
            "tap_xy" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_TAP_XY)
                step.x?.let { intent.putExtra(KaiAccessibilityService.EXTRA_X, it) }
                step.y?.let { intent.putExtra(KaiAccessibilityService.EXTRA_Y, it) }
            }
            "long_press_xy" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_LONG_PRESS_XY)
                step.x?.let { intent.putExtra(KaiAccessibilityService.EXTRA_X, it) }
                step.y?.let { intent.putExtra(KaiAccessibilityService.EXTRA_Y, it) }
                intent.putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, step.holdMs)
            }
            "swipe_xy" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_SWIPE_XY)
                step.x?.let { intent.putExtra(KaiAccessibilityService.EXTRA_X, it) }
                step.y?.let { intent.putExtra(KaiAccessibilityService.EXTRA_Y, it) }
                step.endX?.let { intent.putExtra(KaiAccessibilityService.EXTRA_END_X, it) }
                step.endY?.let { intent.putExtra(KaiAccessibilityService.EXTRA_END_Y, it) }
                intent.putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, step.holdMs)
            }
            "back" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_BACK)
            }
            "home" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_HOME)
            }
            "recents" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_RECENTS)
            }
            "verify_state", "read_screen", "wait_for_text" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
            }
            else -> return
        }

        context.sendBroadcast(intent)
    }

    private fun inferPrimaryAppHint(prompt: String): String =
        KaiScreenStateParser.inferAppHint(prompt)

    private fun isLauncherPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.getDefault())
        return p.contains("launcher") || p.contains("home") || p.contains("pixel") || p.contains("trebuchet")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun postProcessPlan(
        plan: KaiActionPlan,
        currentScreenState: KaiScreenState,
        appHint: String,
        maxStepsPerChunk: Int,
        priorProgress: String
    ): KaiActionPlan {
        val onLauncher = isLauncherPackage(currentScreenState.packageName)
        val currentMatches = currentScreenState.likelyMatchesAppHint(appHint) && !onLauncher
        val maxSteps = maxStepsPerChunk.coerceIn(1, 8)
        val steps = plan.steps.filter { it.cmd.isNotBlank() }.toMutableList()

        if (steps.isEmpty() && onLauncher && appHint.isNotBlank() && !currentMatches) {
            return plan.copy(
                goalComplete = false,
                plannerGoalComplete = false,
                summary = "Starting from launcher/home: opening target app first.",
                steps = listOf(
                    KaiActionStep(
                        cmd = "open_app",
                        text = appHint,
                        note = "launcher_requires_open_app_first",
                        completionBoundary = KaiGoalBoundary.APP_ENTRY,
                        continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                        allowsFinalCommit = false
                    )
                )
            )
        }

        if (onLauncher && appHint.isNotBlank() && !currentMatches) {
            val existingOpen = steps.firstOrNull { it.cmd == "open_app" }
            val openFirst = existingOpen?.copy(
                text = existingOpen.text.ifBlank { appHint },
                note = existingOpen.note.ifBlank { "launcher_requires_open_app_first" },
                completionBoundary = KaiGoalBoundary.APP_ENTRY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            ) ?: KaiActionStep(
                cmd = "open_app",
                text = appHint,
                note = "launcher_requires_open_app_first",
                completionBoundary = KaiGoalBoundary.APP_ENTRY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            )

            val rebuilt = mutableListOf<KaiActionStep>()
            rebuilt += openFirst
            rebuilt += steps.filterNot { it.cmd == "open_app" }

            return plan.copy(
                goalComplete = false,
                plannerGoalComplete = false,
                summary = "Starting from launcher/home: open target app before semantic actions.",
                steps = rebuilt.take(maxSteps)
            )
        }

        val cleanedSteps = buildList {
            var verifyRun = 0
            steps.forEach { step ->
                if (step.cmd in setOf("verify_state", "read_screen", "wait_for_text")) {
                    verifyRun += 1
                    if (verifyRun > 2) return@forEach
                } else {
                    verifyRun = 0
                }
                add(step)
            }
        }

        return plan.copy(
            steps = cleanedSteps.take(maxSteps),
            goalComplete = false,
            plannerGoalComplete = false
        )
    }

    private fun parseActionPlan(raw: String): KaiActionPlan {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val jsonText = when {
            clean.startsWith("{") && clean.endsWith("}") -> clean
            clean.contains("{") && clean.contains("}") ->
                clean.substring(clean.indexOf('{'), clean.lastIndexOf('}') + 1)
            else -> throw IllegalArgumentException("Planner did not return valid JSON.")
        }

        val obj = JSONObject(jsonText)
        val summary = obj.optString("summary").ifBlank { "Action plan generated." }
        val goalComplete = obj.optBoolean("goalComplete", false)
        val stepsJson = obj.optJSONArray("steps") ?: JSONArray()

        val allowedCommands = setOf(
            "open_app", "click_text", "long_press_text", "input_text",
            "click_best_match", "focus_best_input", "input_into_best_field",
            "press_primary_action", "open_best_list_item", "verify_state",
            "scroll", "read_screen", "wait_for_text", "tap_xy", "long_press_xy",
            "swipe_xy", "back", "home", "recents", "wait"
        )

        val steps = buildList {
            for (i in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(i) ?: continue
                val cmd = item.optString("cmd").trim().lowercase(Locale.ROOT)
                if (cmd.isBlank() || cmd !in allowedCommands) continue
                add(
                    KaiActionStep(
                        cmd = cmd,
                        text = item.optString("text").trim(),
                        dir = item.optString("dir").trim(),
                        times = item.optInt("times", 1).coerceIn(1, 10),
                        waitMs = item.optLong("waitMs", 500L).coerceIn(80L, 12000L),
                        x = item.optFloatOrNull("x"),
                        y = item.optFloatOrNull("y"),
                        endX = item.optFloatOrNull("endX"),
                        endY = item.optFloatOrNull("endY"),
                        holdMs = item.optLong("holdMs", 450L).coerceIn(80L, 8000L),
                        timeoutMs = item.optLong("timeoutMs", 4000L).coerceIn(500L, 18000L),
                        optional = item.optBoolean("optional", false),
                        note = item.optString("note").trim(),
                        selectorText = item.optString("selectorText").trim(),
                        selectorHint = item.optString("selectorHint").trim(),
                        selectorId = item.optString("selectorId").trim(),
                        selectorRole = item.optString("selectorRole").trim(),
                        expectedPackage = item.optString("expectedPackage").trim(),
                        expectedTexts = item.optStringList("expectedTexts"),
                        expectedScreenKind = item.optString("expectedScreenKind").trim(),
                        strategy = item.optString("strategy").trim(),
                        confidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                        completionBoundary = when (KaiScreenStateParser.normalize(item.optString("completionBoundary"))) {
                            "app_entry" -> KaiGoalBoundary.APP_ENTRY
                            "surface_ready" -> KaiGoalBoundary.SURFACE_READY
                            "entity_opened" -> KaiGoalBoundary.ENTITY_OPENED
                            "input_ready" -> KaiGoalBoundary.INPUT_READY
                            "content_committed" -> KaiGoalBoundary.CONTENT_COMMITTED
                            "final_goal" -> KaiGoalBoundary.FINAL_GOAL
                            else -> KaiGoalBoundary.UNKNOWN
                        },
                        continuationKind = when (KaiScreenStateParser.normalize(item.optString("continuationKind"))) {
                            "stage_continuation" -> KaiContinuationKind.STAGE_CONTINUATION
                            "recovery_continuation" -> KaiContinuationKind.RECOVERY_CONTINUATION
                            "verification" -> KaiContinuationKind.VERIFICATION
                            else -> KaiContinuationKind.NONE
                        },
                        allowsFinalCommit = item.optBoolean("allowsFinalCommit", false)
                    )
                )
            }
        }

        return KaiActionPlan(
            summary = summary,
            steps = steps,
            goalComplete = goalComplete,
            plannerGoalComplete = goalComplete
        )
    }

    private fun JSONObject.optFloatOrNull(name: String): Float? {
        if (!has(name) || isNull(name)) return null
        val value = optDouble(name, Double.NaN)
        return if (value.isNaN()) null else value.toFloat()
    }

    private fun JSONObject.optStringList(name: String): List<String> {
        if (!has(name) || isNull(name)) return emptyList()
        val arr = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotBlank()) add(item)
            }
        }
    }

    fun parseElementsFromJson(elementsJson: String?): List<KaiUiElement> =
        KaiLiveObservationRuntime.parseElementsFromJson(elementsJson)
}
