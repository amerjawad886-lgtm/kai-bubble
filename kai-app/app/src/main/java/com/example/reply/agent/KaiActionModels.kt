package com.example.reply.agent

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
    // Compatibility-preserving semantic selectors for richer planning/execution.
    val selectorText: String = "",
    val selectorHint: String = "",
    val selectorId: String = "",
    val selectorRole: String = "",
    val expectedPackage: String = "",
    val expectedTexts: List<String> = emptyList(),
    val expectedScreenKind: String = "",
    val strategy: String = "",
    val confidence: Float = 0f
)

data class KaiActionPlan(
    val summary: String,
    val steps: List<KaiActionStep>,
    val goalComplete: Boolean = false
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