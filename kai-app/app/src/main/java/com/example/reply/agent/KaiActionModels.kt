package com.example.reply.agent

enum class KaiGoalBoundary {
    UNKNOWN,
    APP_ENTRY,
    SURFACE_READY,
    ENTITY_OPENED,
    INPUT_READY,
    CONTENT_COMMITTED,
    FINAL_GOAL
}

enum class KaiContinuationKind {
    NONE,
    STAGE_CONTINUATION,
    RECOVERY_CONTINUATION,
    VERIFICATION
}

data class KaiActionStep(
    val cmd: String,
    val text: String = "",
    val dir: String = "",
    val times: Int = 1,
    val waitMs: Long = 500L,
    val x: Float? = null,
    val y: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val holdMs: Long = 450L,
    val timeoutMs: Long = 4000L,
    val optional: Boolean = false,
    val note: String = "",
    val selectorText: String = "",
    val selectorHint: String = "",
    val selectorId: String = "",
    val selectorRole: String = "",
    val expectedPackage: String = "",
    val expectedTexts: List<String> = emptyList(),
    val expectedScreenKind: String = "",
    val strategy: String = "",
    val confidence: Float = 0f,
    val stageHint: String = "",
    val completionBoundary: KaiGoalBoundary = KaiGoalBoundary.UNKNOWN,
    val continuationKind: KaiContinuationKind = KaiContinuationKind.NONE,
    val allowsFinalCommit: Boolean = false
) {
    fun semanticPayload(): String = text.ifBlank { selectorText }.trim()

    fun normalizedCommand(): String = cmd.trim().lowercase()

    fun isOpenAppStep(): Boolean = normalizedCommand() == "open_app"

    fun isVerificationStep(): Boolean = normalizedCommand() in setOf("verify_state", "read_screen", "wait_for_text")

    fun requiresStrongObservation(): Boolean = when (completionBoundary) {
        KaiGoalBoundary.APP_ENTRY,
        KaiGoalBoundary.SURFACE_READY,
        KaiGoalBoundary.ENTITY_OPENED,
        KaiGoalBoundary.INPUT_READY,
        KaiGoalBoundary.CONTENT_COMMITTED,
        KaiGoalBoundary.FINAL_GOAL -> true
        KaiGoalBoundary.UNKNOWN -> false
    }
}


data class KaiActionPlan(
    val summary: String,
    val steps: List<KaiActionStep>,
    val goalComplete: Boolean = false,
    val plannerGoalComplete: Boolean = goalComplete
)

data class KaiLoopResult(
    val success: Boolean,
    val finalMessage: String,
    val executedSteps: Int = 0
)

enum class KaiOpenAppOutcome {
    TARGET_READY,
    USABLE_INTERMEDIATE_IN_TARGET_APP,
    OPEN_TRANSITION_IN_PROGRESS,
    OPEN_FAILED,
    WRONG_PACKAGE_CONFIRMED
}

data class KaiActionExecutionResult(
    val success: Boolean,
    val message: String,
    val screenState: KaiScreenState? = null,
    val hardStop: Boolean = false,
    val openAppOutcome: KaiOpenAppOutcome? = null
)
