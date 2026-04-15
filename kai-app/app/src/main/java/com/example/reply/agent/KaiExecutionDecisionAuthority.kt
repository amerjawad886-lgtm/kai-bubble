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
        if (before.packageName != after.packageName && before.packageName.isNotBlank() && after.packageName.isNotBlank()) return true
        if (before.screenKind != after.screenKind) return true
        if (before.semanticFingerprint() != after.semanticFingerprint()) return true
        if (abs(before.semanticConfidence - after.semanticConfidence) >= 0.08f) return true
        return false
    }

    fun expectedEvidenceSatisfied(step: KaiActionStep, state: KaiScreenState): Boolean {
        if (!KaiVisionInterpreter.isUsableState(state)) return false
        if (step.expectedPackage.isNotBlank() && !state.matchesExpectedPackage(step.expectedPackage)) return false
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
        if (step.expectedTexts.isNotEmpty() && !step.expectedTexts.all { state.containsText(it) }) return false
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
        val unusable = telemetry.observationFallback || telemetry.observationReusedLastGood

        if (step.isOpenAppStep()) {
            return when (result.openAppOutcome) {
                KaiOpenAppOutcome.TARGET_READY -> RuntimeDecision(RuntimeDirective.CONTINUE, ProgressLevel.TARGET_READY, false, "open_app_target_ready")
                KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP -> RuntimeDecision(RuntimeDirective.CONTINUE, ProgressLevel.INTERMEDIATE, false, "open_app_target_visible_usable")
                KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS, null -> RuntimeDecision(
                    if (progress || telemetry.noProgressCycles < 1) RuntimeDirective.CONTINUE else RuntimeDirective.REPLAN,
                    if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                    false,
                    if (progress) "open_app_transition_progress" else "open_app_transition_wait"
                )
                KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED -> RuntimeDecision(
                    if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                    ProgressLevel.NONE, false, "open_app_wrong_package"
                )
                KaiOpenAppOutcome.OPEN_FAILED -> RuntimeDecision(
                    if (repeatedNoProgressSteps >= 2) RuntimeDirective.STOP_FAILURE else RuntimeDirective.REPLAN,
                    ProgressLevel.NONE, false, "open_app_failed"
                )
            }
        }

        if (result.hardStop) return RuntimeDecision(RuntimeDirective.STOP_FAILURE, ProgressLevel.NONE, false, "hard_stop:${result.message}")
        if (telemetry.loopSafetyLimitReached) return RuntimeDecision(RuntimeDirective.STOP_FAILURE, ProgressLevel.NONE, false, "loop_limit_reached")

        if (result.success && (progress || evidence)) {
            return RuntimeDecision(RuntimeDirective.CONTINUE, ProgressLevel.INTERMEDIATE, false, if (evidence) "expected_evidence_hit" else "progress_observed")
        }

        if (unusable) {
            return RuntimeDecision(
                if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                ProgressLevel.NONE, false, "observation_unusable"
            )
        }

        if (telemetry.observationWeak && telemetry.noProgressCycles >= 2) {
            return RuntimeDecision(
                if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                ProgressLevel.NONE, false, "observation_too_weak_for_too_long"
            )
        }

        if ((telemetry.weakReadStreak >= 4 || telemetry.staleReadStreak >= 4) && telemetry.noProgressCycles >= 2) {
            return RuntimeDecision(
                if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                ProgressLevel.NONE, false, "repeated_weak_or_stale_reads"
            )
        }

        if (repeatedNoProgressSteps >= 3) {
            return RuntimeDecision(
                if (recoverablePathExists) RuntimeDirective.RECOVER else RuntimeDirective.REPLAN,
                ProgressLevel.NONE, false, "repeated_no_progress"
            )
        }

        return RuntimeDecision(if (result.success) RuntimeDirective.CONTINUE else RuntimeDirective.REPLAN, if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE, false, if (result.success) "continue_after_success" else "soft_failure_replan")
    }

    fun evaluateCycleHealth(
        lastDecision: RuntimeDecision?,
        telemetry: RuntimeTelemetry = RuntimeTelemetry()
    ): RuntimeDecision {
        if (telemetry.loopSafetyLimitReached || telemetry.noProgressCycles >= 5) {
            return RuntimeDecision(RuntimeDirective.STOP_FAILURE, ProgressLevel.NONE, false, "cycle_limit_or_no_progress")
        }
        if (lastDecision == null) return RuntimeDecision(RuntimeDirective.CONTINUE, ProgressLevel.NONE, false, "cycle_bootstrap")
        if (lastDecision.directive == RuntimeDirective.STOP_FAILURE || lastDecision.directive == RuntimeDirective.STOP_SUCCESS) return lastDecision
        if (telemetry.observationWeak && telemetry.noProgressCycles >= 3) {
            return RuntimeDecision(RuntimeDirective.REPLAN, ProgressLevel.NONE, false, "cycle_observation_unstable")
        }
        return RuntimeDecision(RuntimeDirective.CONTINUE, lastDecision.progressLevel, false, "cycle_continue")
    }
}
