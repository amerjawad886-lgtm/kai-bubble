package com.example.reply.ui

import android.content.Context
import com.example.reply.agent.KaiAgentController
import com.example.reply.agent.KaiAgentLoopEngine
import com.example.reply.agent.KaiLoopResult

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

    @Volatile
    private var lastStartTs = 0L

    private fun preflight(context: Context) {
        val appContext = context.applicationContext
        runCatching { KaiVoice.resetTransientStateForNewRun() }
        runCatching { OpenAIClient.resetTransientStateForNewRun() }
        KaiAgentController.resetTransientStateForNewRun()
        KaiAgentController.ensureRuntimeObservationBridge(appContext)
        KaiBubbleManager.releaseAllSuppression()
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
        if (now - lastStartTs < 450L) {
            appendLog("system", "Ignored duplicate loop start")
            return null
        }
        lastStartTs = now

        val clean = prompt.trim()
        if (clean.isBlank()) return null

        val appContext = context.applicationContext
        preflight(appContext)

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
        runCatching { engine?.cancel() }
    }
}
