package com.example.reply.agent

enum class KaiTransitionState {
    TARGET_READY,
    INTERMEDIATE,
    RECOVERABLE,
    DEAD_END,
    GOAL_COMMITTED,
    NO_PROGRESS
}

object KaiTransitionEvaluator {

    fun isCriticalStep(step: KaiActionStep): Boolean {
        return KaiExecutionDecisionAuthority.isCriticalCommand(step)
    }

    fun classify(step: KaiActionStep, state: KaiScreenState): KaiTransitionState {
        val assessment = KaiSurfaceTransitionPolicy.assessCurrentSurface(step, state)
        return when (assessment.status) {
            KaiSurfaceStatus.TARGET_READY -> KaiTransitionState.TARGET_READY
            KaiSurfaceStatus.USABLE_INTERMEDIATE -> KaiTransitionState.INTERMEDIATE
            KaiSurfaceStatus.WRONG_BUT_RECOVERABLE -> KaiTransitionState.RECOVERABLE
            KaiSurfaceStatus.DEAD_END -> KaiTransitionState.DEAD_END
        }
    }

    fun hasMeaningfulProgress(
        before: KaiScreenState,
        after: KaiScreenState,
        message: String = ""
    ): Boolean {
        return KaiExecutionDecisionAuthority.hasMeaningfulProgress(before, after)
    }

    fun classifyProgress(
        before: KaiScreenState,
        after: KaiScreenState,
        message: String = ""
    ): KaiTransitionState {
        return if (hasMeaningfulProgress(before, after, message)) {
            KaiTransitionState.INTERMEDIATE
        } else {
            KaiTransitionState.NO_PROGRESS
        }
    }

    fun isGoalCommitted(
        step: KaiActionStep,
        before: KaiScreenState,
        after: KaiScreenState,
        message: String = ""
    ): Boolean {
        return KaiExecutionDecisionAuthority.isGoalCommitted(
            step = step,
            before = before,
            after = after,
            stepSucceeded = true
        )
    }

    fun likelyGoalSatisfied(userPrompt: String, state: KaiScreenState): Boolean {
        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        return when (appHint) {
            "instagram" -> KaiSurfaceModel.isVerifiedInstagramDmListSurface(state) || KaiSurfaceModel.isVerifiedInstagramThreadTextSurface(state)
            "youtube" -> KaiSurfaceModel.isVerifiedYouTubeWorkingSurface(state)
            "notes" -> KaiSurfaceModel.isVerifiedNotesListSurface(state) || KaiSurfaceModel.isVerifiedNotesEditorSurface(state)
            else -> KaiExecutionDecisionAuthority.likelyGoalSatisfied(userPrompt, state)
        }
    }

    fun strictTargetEvidenceSatisfied(userPrompt: String, state: KaiScreenState): Boolean {
        return likelyGoalSatisfied(userPrompt, state)
    }
}