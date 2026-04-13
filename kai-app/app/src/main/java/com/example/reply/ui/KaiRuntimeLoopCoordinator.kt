package com.example.reply.ui

import android.content.Context
import com.example.reply.agent.KaiAgentController
import com.example.reply.agent.KaiAgentLoopEngine
import com.example.reply.agent.KaiLoopResult
import com.example.reply.agent.KaiObservationRuntime

enum class KaiRuntimePhase {
    PLANNING,
    EXECUTING,
    OBSERVING,
    CANCELLED,
    IDLE
}

object KaiRuntimeLoopCoordinator {
    private const val EXECUTION_ACK =
        "Make Action accepted as full execution permission on your responsibility."
    private const val LOOP_START = "Agent loop starting…"

    @Volatile
    private var lastStartTs = 0L

    private fun resetTransientStateForNewRun(context: Context) {
        // Preflight should clean transient UI/voice/model state only.
        // Observation runtime ownership stays with KaiObservationRuntime / engine startup.
        val appContext = context.applicationContext

        KaiVoice.resetTransientStateForNewRun()
        OpenAIClient.resetTransientStateForNewRun()
        KaiAgentController.resetTransientStateForNewRun()

        KaiObservationRuntime.ensureBridge(appContext)
        KaiBubbleManager.releaseAllSuppression()

        // Softer reset: only touch bubble layout state if the overlay is actually showing.
        if (KaiBubbleManager.isShowing()) {
            KaiBubbleManager.softResetUiState()
        }
    }

    fun startLoop(
        context: Context,
        prompt: String,
        appendLog: (role: String, text: String) -> Unit,
        onPhase: (KaiRuntimePhase) -> Unit,
        onFinished: (KaiLoopResult) -> Unit
    ): KaiAgentLoopEngine? {
        val now = System.currentTimeMillis()
        if (now - lastStartTs < 500L) {
            appendLog("system", "Ignored duplicate loop start")
            return null
        }
        lastStartTs = now

        val clean = prompt.trim()
        if (clean.isBlank()) return null

        val appContext = context.applicationContext
        resetTransientStateForNewRun(appContext)

        KaiAgentController.ensureRuntimeObservationBridge(appContext)

        if (KaiObservationRuntime.isWatching) {
            KaiObservationRuntime.requestImmediateDump()
            appendLog("system", "Monitoring carried into action loop")
        }

        KaiAgentController.setCustomPrompt(clean)
        appendLog("user", clean)
        appendLog("system", EXECUTION_ACK)
        appendLog("system", LOOP_START)

        return KaiAgentController.startUnifiedActionLoop(
            context = appContext,
            prompt = clean,
            onLog = appendLog,
            onStatus = { status ->
                onPhase(
                    when (status) {
                        "Planning" -> KaiRuntimePhase.PLANNING
                        "Executing" -> KaiRuntimePhase.EXECUTING
                        "Observing" -> KaiRuntimePhase.OBSERVING
                        "Cancelled" -> KaiRuntimePhase.CANCELLED
                        else -> KaiRuntimePhase.IDLE
                    }
                )
            },
            onFinished = onFinished
        )
    }

    fun cancelLoop(engine: KaiAgentLoopEngine?) {
        // KaiAgentLoopEngine is the single owner of action-loop lifecycle cleanup.
        try {
            engine?.cancel()
        } catch (_: Exception) {
        }
    }
}
