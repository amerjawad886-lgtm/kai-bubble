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
    private const val EXECUTION_ACK = "Make Action accepted as full execution permission on your responsibility."
    private const val LOOP_START = "Agent loop starting…"

    private fun resetTransientStateForNewRun(context: Context) {
        // Primary global preflight reset path before launching a new action loop.
        val appContext = context.applicationContext
        KaiVoice.resetTransientStateForNewRun()
        OpenAIClient.resetTransientStateForNewRun()
        KaiAgentController.resetTransientStateForNewRun()
        KaiObservationRuntime.ensureBridge(appContext)
        KaiObservationRuntime.reset()
        KaiObservationRuntime.startWatching(immediateDump = true)
        KaiBubbleManager.releaseAllSuppression()
        KaiBubbleManager.softResetUiState()
    }

    fun startLoop(
        context: Context,
        prompt: String,
        appendLog: (role: String, text: String) -> Unit,
        onPhase: (KaiRuntimePhase) -> Unit,
        onFinished: (KaiLoopResult) -> Unit
    ): KaiAgentLoopEngine? {
        val clean = prompt.trim()
        if (clean.isBlank()) return null

        val appContext = context.applicationContext
        resetTransientStateForNewRun(appContext)

        // Activate runtime-owned observation intake and keep watching alive
        // through the handoff into action execution.
        KaiAgentController.ensureRuntimeObservationBridge(appContext)
        KaiObservationRuntime.ensureBridge(appContext)
        KaiObservationRuntime.startWatching(immediateDump = true)

        if (KaiAgentController.isRunning()) {
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
        // engine.cancel() handles BubbleManager suppression release, UI state reset, and
        // finishActionLoopSession.  Do not duplicate those calls here — doing so causes
        // finishActionLoopSession to fire twice, which races with voice/talk callbacks that
        // read snapshot.actionLoopActive between the two calls.
        try {
            engine?.cancel()
        } catch (_: Exception) {
        }
    }
}
