package com.example.reply.agent

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

/**
 * Background "monitoring" loop that periodically asks the planner for a brief
 * situational insight based on recent observation memory.
 *
 * Extracted from KaiAgentController as part of the live-vision migration.
 * Owns its own CoroutineScope, job handle, and throttling/busy flags. Writes
 * only to [KaiAgentSessionState.snapshot] (status + last suggestion) and reads
 * from [KaiAgentSessionState.recentObservations] — no direct coupling to the
 * controller's action-loop code path.
 *
 * Behavior is intentionally identical to the old in-controller implementation;
 * any change here must be deliberate. Higher-level policy that decides *when*
 * to pause this loop (e.g. while the direct action loop is active) remains on
 * the controller, which gates calls via [isRunning] / [stop].
 */
object KaiContinuousInsightLoop {

    private const val TAG = "KaiContinuousInsight"
    private const val MIN_INSIGHT_GAP_MS = 2500L
    private const val TICK_DELAY_MS = 900L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var running = false
    @Volatile private var insightBusy = false
    @Volatile private var lastInsightAt = 0L
    @Volatile private var insightCallback: ((String) -> Unit)? = null

    private var job: Job? = null

    fun isRunning(): Boolean = running

    fun start(
        onInsight: (String) -> Unit,
        onRequestDump: () -> Unit = {}
    ) {
        running = true
        insightCallback = onInsight
        KaiAgentSessionState.snapshot = KaiAgentSessionState.snapshot.copy(
            isRunning = true,
            statusText = "Monitoring"
        )
        KaiLiveObservationRuntime.startWatching(immediateDump = true)
        runCatching { onRequestDump() }

        job = scope.launch {
            while (isActive && running && !KaiAgentSessionState.snapshot.actionLoopActive) {
                maybeGenerateInsight()
                delay(TICK_DELAY_MS)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        insightBusy = false
        KaiLiveObservationRuntime.stopWatching()
        val s = KaiAgentSessionState.snapshot
        KaiAgentSessionState.snapshot = s.copy(
            isRunning = s.actionLoopActive,
            statusText = if (s.actionLoopActive) "Action loop active" else "Idle"
        )
    }

    /** Re-arm observation watching after the direct action loop finishes. */
    fun resumeObservationIfRunning() {
        if (running) KaiLiveObservationRuntime.startWatching(immediateDump = true)
    }

    /** Clear insight throttling state when starting a new run. */
    fun resetInsightTimers() {
        insightBusy = false
        lastInsightAt = 0L
    }

    private fun maybeGenerateInsight() {
        if (!running || insightBusy) return
        val now = System.currentTimeMillis()
        if (now - lastInsightAt < MIN_INSIGHT_GAP_MS) return

        val memoryText = KaiAgentSessionState.recentObservations(5).joinToString("\n\n") { obs ->
            "Package: ${obs.packageName.ifBlank { "Unknown" }}\nScreen:\n${obs.screenPreview.take(900)}"
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
                        ${KaiAgentSessionState.snapshot.currentGoal.ifBlank { "Observe and suggest the next practical step." }}

                        Recent observations:
                        $memoryText

                        Reply briefly in the user's language:
                        1) What is happening now
                        2) Safest next action
                    """.trimIndent(),
                    task = KaiTask.BRAIN
                )
                KaiAgentSessionState.snapshot = KaiAgentSessionState.snapshot.copy(
                    lastSuggestion = reply,
                    statusText = if (running) "Monitoring" else "Idle"
                )
                withContext(Dispatchers.Main) { insightCallback?.invoke(reply) }
            } catch (e: Exception) {
                Log.e(TAG, "Continuous insight failed: ${e.message}", e)
            } finally {
                insightBusy = false
            }
        }
    }
}
