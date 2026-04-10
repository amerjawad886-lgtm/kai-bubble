package com.example.reply.agent

import android.content.Context
import android.util.Log
import com.example.reply.ai.KaiTask
import com.example.reply.ui.OpenAIClient
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
    fun getLatestObservation(): KaiObservation = KaiObservationRuntime.live
    fun getLatestScreenState(): KaiScreenState = KaiObservationRuntime.currentScreenState()

    fun ensureRuntimeObservationBridge(context: Context) {
        KaiObservationRuntime.ensureBridge(context)
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
            KaiObservationRuntime.startWatching(immediateDump = true)
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
        KaiObservationRuntime.stopWatching()
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

        KaiObservationRuntime.startWatching(immediateDump = true)
        try { onRequestDump() } catch (_: Exception) {}

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
                "Package: ${item.packageName.ifBlank { "Unknown" }}\nScreen:\n${item.screenPreview.take(1000)}"
            }
        }

        val appHint = inferPrimaryAppHint(effectivePrompt)
        val isMultiStepGoal =
            KaiTaskStageEngine.classifyGoalMode(effectivePrompt) == KaiTaskStageEngine.GoalMode.MULTI_STAGE
        val stageSnapshot = KaiTaskStageEngine.evaluate(
            userPrompt = effectivePrompt,
            currentState = currentScreenState
        )
        val effectiveMaxSteps = if (isMultiStepGoal) {
            maxStepsPerChunk.coerceIn(3, 5)
        } else {
            maxStepsPerChunk.coerceIn(1, 3)
        }

        val plannerPrompt = """
            You are Kai Agent inside Kai OS.
            Return STRICT JSON only.
            Build compact actionable chunk only; do not over-plan.
            Prefer concrete direct actions over retries.
            Max $effectiveMaxSteps steps.

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
            ${currentScreenState.preview(2600)}

            Recent memory:
            ${memoryText.ifBlank { "No memory yet." }}

            Progress so far:
            ${priorProgress.ifBlank { "No prior execution yet." }}

            Allowed commands:
            open_app, click_text, long_press_text, input_text, click_best_match,
            focus_best_input, input_into_best_field, press_primary_action,
            open_best_list_item, verify_state, scroll, read_screen, wait_for_text,
            tap_xy, long_press_xy, swipe_xy, back, home, recents, wait.

            JSON shape:
            {
              "summary": "short summary",
              "goalComplete": false,
              "steps": [{"cmd":"click_text","text":""}]
            }
        """.trimIndent()

        val raw = OpenAIClient.ask(plannerPrompt, task = KaiTask.ACTION_PLANNING)
        val plan = parseActionPlan(raw)
        val enrichedPlan = plan.copy(
            steps = plan.steps.take(effectiveMaxSteps),
            goalComplete = false
        )

        snapshot = snapshot.copy(
            customPrompt = effectivePrompt,
            lastSuggestion = enrichedPlan.summary,
            statusText = if (isRunning()) "Monitoring" else "Action plan ready",
            requiresApproval = false
        )

        return postProcessPlan(
            plan = enrichedPlan,
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

    fun startUnifiedActionLoop(
        context: android.content.Context,
        prompt: String,
        onLog: (role: String, text: String) -> Unit,
        onStatus: (String) -> Unit = {},
        onFinished: (KaiLoopResult) -> Unit
    ): KaiAgentLoopEngine {
        val clean = prompt.trim()
        ensureRuntimeObservationBridge(context.applicationContext)

        startActionLoopSession(clean)
        setCustomPrompt(clean)
        setGoal(clean)

        val engine = KaiAgentLoopEngine(
            context = context.applicationContext,
            onLog = onLog,
            onStatus = onStatus
        )
        engine.start(clean, onFinished)
        return engine
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
        val maxSteps = maxStepsPerChunk.coerceIn(1, 8)
        val currentMatches = currentScreenState.likelyMatchesAppHint(appHint) && !onLauncher
        val steps = plan.steps.filter { it.cmd.isNotBlank() }.toMutableList()

        if (steps.isEmpty() && onLauncher && appHint.isNotBlank() && !currentMatches) {
            return plan.copy(
                goalComplete = false,
                summary = "Starting from launcher/home: opening target app first.",
                steps = listOf(
                    KaiActionStep(
                        cmd = "open_app",
                        text = appHint,
                        note = "launcher_requires_open_app_first"
                    )
                )
            )
        }

        if (onLauncher && appHint.isNotBlank() && !currentMatches) {
            val existingOpen = steps.firstOrNull { it.cmd == "open_app" }
            val openFirst = existingOpen?.copy(
                text = existingOpen.text.ifBlank { appHint },
                note = if (existingOpen.note.isBlank()) {
                    "launcher_requires_open_app_first"
                } else {
                    existingOpen.note
                }
            ) ?: KaiActionStep(
                cmd = "open_app",
                text = appHint,
                note = "launcher_requires_open_app_first"
            )

            val rebuilt = mutableListOf<KaiActionStep>()
            rebuilt += openFirst
            rebuilt += steps.filterNot { it.cmd == "open_app" }

            return plan.copy(
                goalComplete = false,
                summary = "Starting from launcher/home: open target app before semantic actions.",
                steps = rebuilt.take(maxSteps)
            )
        }

        val cleanedSteps = buildList {
            var verifyOrReadRun = 0
            steps.forEach { step ->
                if (step.cmd in setOf("verify_state", "read_screen", "wait_for_text")) {
                    verifyOrReadRun += 1
                    if (verifyOrReadRun > 2) return@forEach
                } else {
                    verifyOrReadRun = 0
                }
                add(step)
            }
        }
        return plan.copy(steps = cleanedSteps.take(maxSteps), goalComplete = false)
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
                        confidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
                    )
                )
            }
        }

        return KaiActionPlan(summary = summary, steps = steps, goalComplete = goalComplete)
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
        KaiObservationRuntime.parseElementsFromJson(elementsJson)
}
