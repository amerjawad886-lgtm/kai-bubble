package com.example.reply.agent

import kotlin.math.abs

object KaiExecutionDecisionAuthority {

    enum class RuntimeDirective {
        CONTINUE,
        RECOVER,
        REPLAN,
        STOP_SUCCESS,
        STOP_FAILURE
    }

    enum class ProgressLevel {
        NONE,
        INTERMEDIATE,
        TARGET_READY,
        GOAL_COMMITTED
    }

    data class RuntimeDecision(
        val directive: RuntimeDirective,
        val progressLevel: ProgressLevel,
        val goalCommitted: Boolean,
        val reason: String
    )

    data class RuntimeTelemetry(
        val noProgressCycles: Int = 0,
        val weakReadStreak: Int = 0,
        val staleReadStreak: Int = 0,
        val observationWeak: Boolean = false,
        val observationFallback: Boolean = false,
        val observationReusedLastGood: Boolean = false,
        val loopSafetyLimitReached: Boolean = false
    )

    fun hasMeaningfulProgress(before: KaiScreenState, after: KaiScreenState): Boolean {
        if (before.packageName != after.packageName && before.packageName.isNotBlank() && after.packageName.isNotBlank()) {
            return true
        }
        if (before.screenKind != after.screenKind) return true
        if (before.semanticFingerprint() != after.semanticFingerprint()) return true
        if (abs(before.semanticConfidence - after.semanticConfidence) >= 0.08f) return true
        return false
    }

    fun expectedEvidenceSatisfied(step: KaiActionStep, state: KaiScreenState): Boolean {
        if (state.isWeakObservation() || state.isOverlayPolluted()) return false

        if (step.expectedPackage.isNotBlank() && !state.matchesExpectedPackage(step.expectedPackage)) {
            return false
        }

        if (step.expectedScreenKind.isNotBlank()) {
            val expectedKind = KaiScreenStateParser.normalize(step.expectedScreenKind)
            val actual = KaiScreenStateParser.normalize(state.screenKind)
            if (expectedKind.isNotBlank() && expectedKind != actual) {
                val semanticHit = when (expectedKind) {
                    "chat_thread" -> state.isChatThreadScreen() || state.isChatComposerSurface()
                    "chat_list" -> state.isChatListScreen()
                    "notes_editor" -> state.isNotesEditorSurface() || state.isNotesBodyInputSurface() || state.isNotesTitleInputSurface()
                    "detail", "player" -> state.isDetailSurface() || state.isPlayerSurface()
                    "search" -> state.isSearchLikeSurface()
                    else -> false
                }
                if (!semanticHit) return false
            }
        }

        if (step.expectedTexts.isNotEmpty() && !step.expectedTexts.all { state.containsText(it) }) {
            return false
        }

        return true
    }

    fun evaluateStepOutcome(
        step: KaiActionStep,
        before: KaiScreenState,
        after: KaiScreenState,
        result: KaiActionExecutionResult,
        repeatedNoProgressSteps: Int,
        recoverablePathExists: Boolean,
        telemetry: RuntimeTelemetry = RuntimeTelemetry()
    ): RuntimeDecision {
        val progress = hasMeaningfulProgress(before, after) && !telemetry.observationReusedLastGood
        val evidence = expectedEvidenceSatisfied(step, after)
        val unreliable = telemetry.observationWeak || telemetry.observationFallback || telemetry.observationReusedLastGood

        if (step.isOpenAppStep()) {
            return when (result.openAppOutcome) {
                KaiOpenAppOutcome.TARGET_READY -> RuntimeDecision(
                    directive = RuntimeDirective.CONTINUE,
                    progressLevel = ProgressLevel.TARGET_READY,
                    goalCommitted = false,
                    reason = "open_app_target_ready"
                )
                KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED -> RuntimeDecision(
                    directive = if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                    progressLevel = ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "open_app_wrong_package"
                )
                KaiOpenAppOutcome.OPEN_FAILED -> RuntimeDecision(
                    directive = if (repeatedNoProgressSteps >= 1) RuntimeDirective.STOP_FAILURE else RuntimeDirective.REPLAN,
                    progressLevel = ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "open_app_failed"
                )
                KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS,
                KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP,
                null -> RuntimeDecision(
                    directive = if (progress) RuntimeDirective.CONTINUE else RuntimeDirective.REPLAN,
                    progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = if (progress) "open_app_transition_progress" else "open_app_transition_pending"
                )
            }
        }

        if (result.hardStop) {
            return RuntimeDecision(RuntimeDirective.STOP_FAILURE, ProgressLevel.NONE, false, "hard_stop:${result.message}")
        }

        if (telemetry.loopSafetyLimitReached) {
            return RuntimeDecision(RuntimeDirective.STOP_FAILURE, ProgressLevel.NONE, false, "loop_limit_reached")
        }

        if (result.success && (progress || evidence)) {
            return RuntimeDecision(
                directive = RuntimeDirective.CONTINUE,
                progressLevel = ProgressLevel.INTERMEDIATE,
                goalCommitted = false,
                reason = if (evidence) "expected_evidence_hit" else "progress_observed"
            )
        }

        if (unreliable || telemetry.weakReadStreak >= 2 || telemetry.staleReadStreak >= 2) {
            return RuntimeDecision(
                directive = if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "observation_not_reliable"
            )
        }

        if (repeatedNoProgressSteps >= 2) {
            return RuntimeDecision(
                directive = if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "repeated_no_progress"
            )
        }

        return RuntimeDecision(
            directive = if (result.success) RuntimeDirective.CONTINUE else RuntimeDirective.REPLAN,
            progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
            goalCommitted = false,
            reason = if (result.success) "continue_after_success" else "soft_failure_replan"
        )
    }

    fun evaluateCycleHealth(
        lastDecision: RuntimeDecision?,
        telemetry: RuntimeTelemetry = RuntimeTelemetry()
    ): RuntimeDecision {
        if (telemetry.loopSafetyLimitReached || telemetry.noProgressCycles >= 4) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "cycle_limit_or_no_progress"
            )
        }

        val unreliable = telemetry.observationWeak || telemetry.observationFallback || telemetry.observationReusedLastGood ||
            telemetry.weakReadStreak >= 2 || telemetry.staleReadStreak >= 2
        if (unreliable) {
            return RuntimeDecision(
                directive = RuntimeDirective.REPLAN,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "cycle_observation_unstable"
            )
        }

        return lastDecision ?: RuntimeDecision(
            directive = RuntimeDirective.CONTINUE,
            progressLevel = ProgressLevel.NONE,
            goalCommitted = false,
            reason = "cycle_continue"
        )
    }
}
