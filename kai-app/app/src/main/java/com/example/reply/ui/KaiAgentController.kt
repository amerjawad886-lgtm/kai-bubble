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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var snapshot: KaiAgentSnapshot = KaiAgentSnapshot()
        private set(value) { field = value }

    private val memory = ArrayDeque<KaiObservation>()
    private val MAX_MEMORY = 18

    @Volatile
    private var storedContext: Context? = null

    fun getLatestObservation(): KaiObservation = synchronized(memory) {
        memory.maxByOrNull { it.updatedAt } ?: KaiObservation("", "", updatedAt = 0L)
    }

    fun getLatestScreenState(): KaiScreenState {
        val obs = getLatestObservation()
        return if (obs.updatedAt > 0L) KaiVisionInterpreter.toScreenState(obs)
        else KaiScreenState()
    }

    fun ensureRuntimeObservationBridge(context: Context) {
        storedContext = context.applicationContext
        KaiLiveVisionRuntime.ensureRunning()
    }

    fun isRuntimeObservationBridgeActive(): Boolean = KaiLiveVisionRuntime.isRunning()

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
        synchronized(memory) { while (memory.size > cap) memory.removeFirst() }
        snapshot = snapshot.copy(memoryCount = synchronized(memory) { memory.size })
    }

    fun setGoal(goal: String) {
        snapshot = snapshot.copy(currentGoal = goal.trim())
    }

    fun setCustomPrompt(prompt: String) {
        snapshot = snapshot.copy(customPrompt = prompt.trim())
    }

    fun isRunning(): Boolean = snapshot.actionLoopActive

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
            statusText = "Idle",
            isRunning = false,
            lastSuggestion = if (message.isNotBlank()) message else snapshot.lastSuggestion
        )
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
        snapshot = snapshot.copy(
            currentPackage = "",
            lastScreenPreview = "",
            lastSuggestion = "",
            memoryCount = 0,
            isRunning = false,
            statusText = "Idle",
            actionLoopActive = false,
            actionLoopPrompt = "",
            lastActionLoopFingerprint = "",
            repeatedCompletionHints = 0,
            requiresApproval = false
        )
    }

    fun stopContinuousAnalysis() { /* continuous insight loop removed */ }

    fun toggleContinuousAnalysis(
        userGoal: String,
        customPrompt: String,
        onRequestDump: () -> Unit = {},
        onInsight: (String) -> Unit
    ): Boolean = false

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

        val memoryText = synchronized(memory) { memory.takeLast(8) }.joinToString("\n\n") { item ->
            "Package: ${item.packageName.ifBlank { "Unknown" }}\nScreen:\n${item.screenPreview.take(900)}"
        }

        val appHint = KaiScreenStateParser.inferAppHint(effectivePrompt)
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

        storedContext = appContext
        KaiLiveVisionRuntime.reset()
        KaiLiveVisionRuntime.ensureRunning()
        requestImmediateDump()

        activeLoopJob = scope.launch {
            try {
                withContext(Dispatchers.Main) { onLog("system", "Agent loop starting…") }

                var currentState = awaitInitialActionLoopState(
                    afterTime = System.currentTimeMillis() - 1500L,
                    expectedPackage = ""
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

                        val resolvedExpectedPackage = when {
                            step.expectedPackage.isNotBlank() -> step.expectedPackage
                            step.isOpenAppStep() -> KaiAppIdentityRegistry.primaryPackageForKey(
                                KaiAppIdentityRegistry.resolveAppKey(step.text)
                            )
                            else -> ""
                        }

                        val beforeState = readActionLoopState(
                            afterTime = 0L,
                            expectedPackage = resolvedExpectedPackage.ifBlank { currentState.packageName },
                            fallback = currentState,
                            preferSnapshot = true,
                            timeoutMs = 900L
                        )

                        val dispatchTime = System.currentTimeMillis()
                        dispatchStepCommand(
                            appContext,
                            step.copy(expectedPackage = resolvedExpectedPackage)
                        )

                        val result: KaiActionExecutionResult
                        val afterState: KaiScreenState

                        if (step.isOpenAppStep()) {
                            withContext(Dispatchers.Main) {
                                onLog("system", "Awaiting app open: expected_pkg=$resolvedExpectedPackage")
                            }

                            val beforeVision = KaiLiveVisionRuntime.getState()
                            val visualEntry = KaiLiveVisionRuntime.awaitVisuallyVerifiedEntry(
                                expectedPackage = resolvedExpectedPackage,
                                dispatchSequence = beforeVision.frameSequence,
                                dispatchHash = beforeVision.frameHash,
                                dispatchFamily = beforeVision.surfaceFamily,
                                timeoutMs = step.timeoutMs.coerceIn(3200L, 8000L)
                            )
                            val openOutcome = when {
                                visualEntry.isStrongEntry() -> KaiOpenAppOutcome.TARGET_READY
                                visualEntry.isEntered() -> KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
                                else -> KaiOpenAppOutcome.OPEN_FAILED
                            }

                            afterState = readActionLoopState(
                                afterTime = dispatchTime,
                                expectedPackage = resolvedExpectedPackage,
                                fallback = currentState,
                                preferSnapshot = true,
                                timeoutMs = step.timeoutMs.coerceIn(1800L, 6000L)
                            )

                            result = KaiActionExecutionResult(
                                success = openOutcome == KaiOpenAppOutcome.TARGET_READY ||
                                    openOutcome == KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP ||
                                    packageReadyForStep(afterState, step.copy(expectedPackage = resolvedExpectedPackage)),
                                message = "open_app_outcome:$openOutcome",
                                screenState = afterState,
                                openAppOutcome = openOutcome
                            )

                            withContext(Dispatchers.Main) {
                                onLog("system", "App open world-state: $openOutcome")
                            }
                        } else {
                            afterState = readActionLoopState(
                                afterTime = dispatchTime,
                                expectedPackage = resolvedExpectedPackage.ifBlank { currentState.packageName },
                                fallback = beforeState,
                                preferSnapshot = true,
                                timeoutMs = step.waitMs.coerceIn(1400L, 6500L)
                            )

                            val visuallyUsable = KaiVisionInterpreter.isUsableState(afterState)
                            val changedAfterDispatch =
                                afterState.semanticFingerprint() != beforeState.semanticFingerprint() ||
                                    afterState.packageName != beforeState.packageName ||
                                    afterState.updatedAt > dispatchTime

                            result = KaiActionExecutionResult(
                                success = visuallyUsable && (
                                    changedAfterDispatch ||
                                        packageReadyForStep(afterState, step.copy(expectedPackage = resolvedExpectedPackage))
                                    ),
                                message = if (visuallyUsable) {
                                    if (changedAfterDispatch) "live_or_snapshot_state_changed" else "usable_state_reconfirmed"
                                } else {
                                    "state_not_usable"
                                },
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
                KaiLiveVisionRuntime.clearHints()
                KaiBubbleManager.releaseAllSuppression()
            }
        }
    }

    fun parseElementsFromJson(elementsJson: String?): List<KaiUiElement> {
        if (elementsJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(elementsJson)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(KaiUiElement(
                        text = obj.optString("text"),
                        contentDescription = obj.optString("contentDescription"),
                        hint = obj.optString("hint"),
                        viewId = obj.optString("viewId"),
                        className = obj.optString("className"),
                        clickable = obj.optBoolean("clickable"),
                        editable = obj.optBoolean("editable"),
                        scrollable = obj.optBoolean("scrollable"),
                        selected = obj.optBoolean("selected"),
                        checked = obj.optBoolean("checked"),
                        bounds = obj.optString("bounds"),
                        depth = obj.optInt("depth", 0),
                        packageName = obj.optString("packageName"),
                        roleGuess = obj.optString("roleGuess").ifBlank { "unknown" }
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun awaitInitialActionLoopState(
        afterTime: Long,
        expectedPackage: String
    ): KaiScreenState {
        val obs = awaitFreshObservationLocal(afterTime = afterTime, timeoutMs = 2200L, expectedPackage = expectedPackage)
        return KaiVisionInterpreter.toScreenState(obs)
    }

    private suspend fun readActionLoopState(
        afterTime: Long,
        expectedPackage: String,
        fallback: KaiScreenState,
        preferSnapshot: Boolean,
        timeoutMs: Long
    ): KaiScreenState {
        val obs = runCatching {
            awaitFreshObservationLocal(
                afterTime = afterTime,
                timeoutMs = timeoutMs.coerceIn(800L, 8000L),
                expectedPackage = expectedPackage
            )
        }.getOrNull()
        val state = obs?.let { KaiVisionInterpreter.toScreenState(it) }
        return when {
            state != null && KaiVisionInterpreter.isUsableState(state) -> state
            state != null -> state
            else -> fallback
        }
    }

    private suspend fun awaitFreshObservationLocal(
        afterTime: Long,
        timeoutMs: Long = 2200L,
        expectedPackage: String = ""
    ): KaiObservation {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val candidate = bestObservationLocal(expectedPackage)
            if (candidate.updatedAt > afterTime) return candidate
            delay(45L)
        }
        return bestObservationLocal(expectedPackage)
    }

    private fun bestObservationLocal(expectedPackage: String = ""): KaiObservation {
        val items = synchronized(memory) { memory.toList() }
        val matched = if (expectedPackage.isNotBlank())
            items.filter { it.packageName == expectedPackage || it.packageName.startsWith("$expectedPackage.") }
                .maxByOrNull { it.updatedAt }
        else null
        return matched ?: items.maxByOrNull { it.updatedAt } ?: KaiObservation("", "", updatedAt = 0L)
    }

    private fun captureSnapshotOrNull(expectedPackage: String = ""): KaiScreenState? = null

    private fun requestImmediateDump(expectedPackage: String = "") {
        val ctx = storedContext ?: return
        ctx.sendBroadcast(Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(ctx.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
            if (expectedPackage.isNotBlank())
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
        })
    }

    private fun dispatchStepCommand(context: Context, step: KaiActionStep) {
        val cmd = step.normalizedCommand()
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            if (step.expectedPackage.isNotBlank())
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, step.expectedPackage)
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
            "back" -> intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_BACK)
            "home" -> intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_HOME)
            "recents" -> intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_RECENTS)
            "verify_state", "read_screen", "wait_for_text" ->
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
            else -> return
        }
        context.sendBroadcast(intent)
    }

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
                goalComplete = false, plannerGoalComplete = false,
                summary = "Starting from launcher/home: opening target app first.",
                steps = listOf(KaiActionStep(
                    cmd = "open_app", text = appHint,
                    note = "launcher_requires_open_app_first",
                    completionBoundary = KaiGoalBoundary.APP_ENTRY,
                    continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                    allowsFinalCommit = false
                ))
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
                cmd = "open_app", text = appHint,
                note = "launcher_requires_open_app_first",
                completionBoundary = KaiGoalBoundary.APP_ENTRY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            )
            val rebuilt = mutableListOf(openFirst) + steps.filterNot { it.cmd == "open_app" }
            return plan.copy(
                goalComplete = false, plannerGoalComplete = false,
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
        return plan.copy(steps = cleanedSteps.take(maxSteps), goalComplete = false, plannerGoalComplete = false)
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.getDefault())
        return p.contains("launcher") || p.contains("home") || p.contains("pixel") || p.contains("trebuchet")
    }

    private fun packageReadyForStep(
        state: KaiScreenState,
        step: KaiActionStep
    ): Boolean {
        val expectedPackage = step.expectedPackage.trim()
        if (expectedPackage.isBlank()) return false

        val actual = state.packageName.trim()
        if (actual.isBlank()) return false

        return actual == expectedPackage || actual.startsWith("$expectedPackage.")
    }
}