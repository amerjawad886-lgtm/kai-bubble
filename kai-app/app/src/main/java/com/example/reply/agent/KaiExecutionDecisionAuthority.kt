package com.example.reply.agent

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
        val repeatedSameSemanticIntentCycles: Int = 0,
        val consecutiveWrongSurfaceChunks: Int = 0,
        val wrongSurfaceSignals: Int = 0,
        val normalizationFailures: Int = 0,
        val weakReadStreak: Int = 0,
        val staleReadStreak: Int = 0,
        val observationWeak: Boolean = false,
        val observationFallback: Boolean = false,
        val observationReusedLastGood: Boolean = false,
        val optionalStep: Boolean = false,
        val loopSafetyLimitReached: Boolean = false
    )

    private fun isOpenOnlyGoal(prompt: String): Boolean {
        return KaiTaskStageEngine.classifyGoalMode(prompt) == KaiTaskStageEngine.GoalMode.OPEN_ONLY
    }

    private fun hasUsableOpenAppArrival(state: KaiScreenState): Boolean {
        if (state.packageName.isBlank() || state.isWeakObservation()) return false
        if (state.packageName.contains("launcher", true) || state.packageName.contains("home", true)) return false

        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        return KaiSurfaceModel.isPostOpenReadyFamily(family) || KaiSurfaceModel.isRecoverableFamily(family)
    }

    private fun extractWritePayload(prompt: String): String {
        val p = prompt.trim()
        val patterns = listOf(
            Regex("""(?i)(?:type|write|send|reply|play|watch|open)\s+(.+)$"""),
            Regex("""(?i)(?:اكتب|ارسل|أرسل|رد|شغل|شاهد|افتح)\s+(.+)$""")
        )
        val raw = patterns.asSequence()
            .mapNotNull { it.find(p)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull()
            .orEmpty()
        return raw
            .removePrefix("message ")
            .removePrefix("a message ")
            .removePrefix("رسالة ")
            .trim()
    }

    fun hasMeaningfulProgress(before: KaiScreenState, after: KaiScreenState): Boolean {
        val packageChanged = before.packageName != after.packageName
        val familyChanged = before.surfaceFamily() != after.surfaceFamily()
        val kindChanged = KaiScreenStateParser.normalize(before.screenKind) != KaiScreenStateParser.normalize(after.screenKind)
        val semanticFingerprintChanged = before.semanticFingerprint() != after.semanticFingerprint()
        val confidenceJump = kotlin.math.abs(before.semanticConfidence - after.semanticConfidence) >= 0.08f
        val roleShift = before.roleSignature() != after.roleSignature()

        return packageChanged || familyChanged || kindChanged || semanticFingerprintChanged || confidenceJump || roleShift
    }

    fun expectedEvidenceSatisfied(step: KaiActionStep, state: KaiScreenState): Boolean {
        if (state.isWeakObservation() || state.isOverlayPolluted()) {
            return false
        }

        val expectedPkg = KaiScreenStateParser.normalize(step.expectedPackage)
        if (expectedPkg.isNotBlank() && !KaiScreenStateParser.normalize(state.packageName).contains(expectedPkg)) {
            return false
        }

        val expectedKind = KaiScreenStateParser.normalize(step.expectedScreenKind)
        if (expectedKind.isNotBlank()) {
            val kindSatisfied = when (expectedKind) {
                "instagram_dm_list" -> KaiSurfaceModel.isVerifiedInstagramDmListSurface(state)
                "instagram_dm_thread", "chat_thread" -> KaiSurfaceModel.isVerifiedInstagramThreadTextSurface(state) || state.isChatThreadScreen()
                "instagram_camera_overlay" -> KaiSurfaceModel.isVerifiedInstagramCameraOverlay(state)
                "youtube_working_surface" -> KaiSurfaceModel.isVerifiedYouTubeWorkingSurface(state)
                "notes_list" -> KaiSurfaceModel.isVerifiedNotesListSurface(state)
                "notes_editor", "notes_title_input", "notes_body_input" -> KaiSurfaceModel.isVerifiedNotesEditorSurface(state)
                "chat_list" -> state.isChatListScreen() && !state.isSearchLikeSurface() && !state.isCameraOrMediaOverlaySurface()
                "detail" -> state.isDetailSurface() || state.isPlayerSurface()
                else -> KaiScreenStateParser.normalize(state.screenKind) == expectedKind
            }
            if (!kindSatisfied) return false
        }

        if (step.expectedTexts.isNotEmpty() && !step.expectedTexts.all { state.containsText(it) }) {
            return false
        }

        return true
    }

    fun isGoalCommitted(step: KaiActionStep, before: KaiScreenState, after: KaiScreenState, stepSucceeded: Boolean): Boolean {
        if (!stepSucceeded) return false
        val cmd = step.cmd.trim().lowercase()

        return when (cmd) {
            "open_app" -> {
                val inExternalApp = after.packageName.isNotBlank() && !after.packageName.contains("com.example.reply", true)
                val assessment = KaiSurfaceTransitionPolicy.assessCurrentSurface(step, after)
                inExternalApp && assessment.status in setOf(
                    KaiSurfaceStatus.TARGET_READY,
                    KaiSurfaceStatus.USABLE_INTERMEDIATE
                )
            }

            "open_best_list_item" -> after.isChatThreadScreen() || after.isChatComposerSurface() || after.isDetailSurface()

            "focus_best_input" -> {
                after.findBestInputField(step.selectorHint.ifBlank { step.selectorText.ifBlank { step.text } }) != null &&
                    !after.isSearchLikeSurface()
            }

            "input_into_best_field", "input_text" -> before.editableTextSignature() != after.editableTextSignature()

            "press_primary_action" -> before.findSendAction() != null && after.findSendAction() == null

            "verify_state" -> expectedEvidenceSatisfied(step, after)

            else -> false
        }
    }

    fun likelyGoalSatisfied(userPrompt: String, state: KaiScreenState): Boolean {
        val p = KaiScreenStateParser.normalize(userPrompt)
        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)

        if (appHint.isNotBlank() && !state.likelyMatchesAppHint(appHint)) {
            return false
        }

        if (isOpenOnlyGoal(userPrompt)) {
            return hasUsableOpenAppArrival(state)
        }

        val wantsMessagesSurface =
            p.contains("messages") ||
                p.contains("message") ||
                p.contains("chat") ||
                p.contains("dm") ||
                p.contains("inbox") ||
                p.contains("رسائل") ||
                p.contains("محادث")

        val wantsConversation =
            p.contains("conversation") ||
                p.contains("thread") ||
                p.contains("chat with") ||
                p.contains("dm with") ||
                p.contains("محادثة")

        val wantsWrite = p.contains("write") || p.contains("type") || p.contains("reply") || p.contains("اكتب")
        val wantsSend = p.contains("send") || p.contains("ارسال") || p.contains("إرسال")
        val wantsNote = appHint == "notes" || p.contains("note") || p.contains("ملاحظ")
        val wantsPlayback = p.contains("play") || p.contains("watch") || p.contains("شغل") || p.contains("ابدأ")
        val writePayload = extractWritePayload(userPrompt)

        if (wantsMessagesSurface && !state.isChatListScreen() && !state.isChatThreadScreen() && !state.isChatComposerSurface()) return false
        if (wantsConversation && !state.isChatThreadScreen() && !state.isChatComposerSurface()) return false
        if (wantsNote && !state.isNotesEditorSurface() && !state.isNotesBodyInputSurface() && !state.isNotesTitleInputSurface()) return false
        if (wantsPlayback && !(state.isDetailSurface() || state.isPlayerSurface())) return false
        if (wantsWrite && state.isSearchLikeSurface()) return false
        if (wantsWrite) {
            if (writePayload.isBlank()) return false
            val payloadNorm = KaiScreenStateParser.normalize(writePayload)
            val typedEvidence = state.containsText(writePayload) || state.editableTextSignature().contains(payloadNorm)
            if (!typedEvidence) return false
        }
        if (wantsSend && state.findSendAction() != null) return false

        return state.isMeaningful() && !state.isWeakObservation()
    }

    fun isCriticalCommand(step: KaiActionStep): Boolean {
        return step.cmd.trim().lowercase() in setOf(
            "open_app",
            "click_text",
            "open_best_list_item",
            "click_best_match",
            "focus_best_input",
            "input_into_best_field",
            "input_text",
            "press_primary_action",
            "verify_state"
        )
    }

    fun evaluateStepOutcome(
        step: KaiActionStep,
        before: KaiScreenState,
        after: KaiScreenState,
        result: KaiActionExecutionResult,
        userPrompt: String,
        repeatedNoProgressSteps: Int,
        recoverablePathExists: Boolean,
        telemetry: RuntimeTelemetry = RuntimeTelemetry()
    ): RuntimeDecision {
        val baseProgress = hasMeaningfulProgress(before, after)
        val progress = baseProgress && !telemetry.observationReusedLastGood
        val expectedEvidenceHit = expectedEvidenceSatisfied(step, after)
        val committed = isGoalCommitted(step, before, after, result.success)
        val strictGoalEvidence = expectedEvidenceHit && likelyGoalSatisfied(userPrompt, after)
        val openOnlyGoal = isOpenOnlyGoal(userPrompt)
        val authorityGoalSatisfied = when {
            openOnlyGoal -> {
                result.success &&
                    hasUsableOpenAppArrival(after) &&
                    !telemetry.observationWeak &&
                    !telemetry.observationFallback
            }

            else -> {
                result.success &&
                    strictGoalEvidence &&
                    !telemetry.observationWeak &&
                    !telemetry.observationFallback
            }
        }
        val critical = isCriticalCommand(step)
        val isOpenAppStep = step.cmd.equals("open_app", true)

        if (telemetry.loopSafetyLimitReached) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "loop_safety_limit_reached"
            )
        }

        if (result.hardStop) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                goalCommitted = false,
                reason = "hard_stop_requested"
            )
        }

        if (isOpenAppStep) {
            val openOutcome = result.openAppOutcome
            return when (openOutcome) {
                KaiOpenAppOutcome.TARGET_READY,
                KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP -> {
                    if (openOnlyGoal && hasUsableOpenAppArrival(after)) {
                        RuntimeDecision(
                            directive = RuntimeDirective.STOP_SUCCESS,
                            progressLevel = ProgressLevel.GOAL_COMMITTED,
                            goalCommitted = true,
                            reason = "open_only_goal_committed_after_app_arrival"
                        )
                    } else {
                        RuntimeDecision(
                            directive = RuntimeDirective.CONTINUE,
                            progressLevel = ProgressLevel.TARGET_READY,
                            goalCommitted = false,
                            reason = "app_entry_complete_continue"
                        )
                    }
                }

                KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS -> RuntimeDecision(
                    directive = RuntimeDirective.CONTINUE,
                    progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "open_app_transition_in_progress"
                )

                KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED -> RuntimeDecision(
                    directive = RuntimeDirective.STOP_FAILURE,
                    progressLevel = ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "open_app_wrong_package_confirmed"
                )

                KaiOpenAppOutcome.OPEN_FAILED,
                null -> RuntimeDecision(
                    directive = if (repeatedNoProgressSteps >= 1) RuntimeDirective.REPLAN else RuntimeDirective.CONTINUE,
                    progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "open_app_not_confirmed_yet"
                )
            }
        }

        if (authorityGoalSatisfied) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_SUCCESS,
                progressLevel = ProgressLevel.GOAL_COMMITTED,
                goalCommitted = true,
                reason = if (openOnlyGoal) {
                    "goal_committed_open_only_authority_evidence"
                } else {
                    "goal_committed_semantic_authority_evidence"
                }
            )
        }

        if (!result.success) {
            if (telemetry.optionalStep && !critical) {
                return RuntimeDecision(
                    directive = RuntimeDirective.CONTINUE,
                    progressLevel = ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "optional_noncritical_failure_tolerated"
                )
            }

            if (recoverablePathExists && repeatedNoProgressSteps <= 1) {
                return RuntimeDecision(
                    directive = RuntimeDirective.RECOVER,
                    progressLevel = ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "step_failed_recover_once"
                )
            }

            val failedDirective = if (repeatedNoProgressSteps >= 2 || (critical && repeatedNoProgressSteps >= 1)) {
                RuntimeDirective.REPLAN
            } else {
                RuntimeDirective.CONTINUE
            }

            return RuntimeDecision(
                directive = failedDirective,
                progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                goalCommitted = false,
                reason = "step_failed"
            )
        }

        if (critical && !expectedEvidenceHit && repeatedNoProgressSteps >= 1) {
            return RuntimeDecision(
                directive = RuntimeDirective.REPLAN,
                progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                goalCommitted = false,
                reason = "critical_step_missing_expected_evidence"
            )
        }

        if (critical && !expectedEvidenceHit && progress) {
            return RuntimeDecision(
                directive = RuntimeDirective.CONTINUE,
                progressLevel = ProgressLevel.INTERMEDIATE,
                goalCommitted = false,
                reason = "critical_step_progress_without_strict_evidence"
            )
        }

        if (critical && !progress && repeatedNoProgressSteps >= 1) {
            return RuntimeDecision(
                directive = RuntimeDirective.REPLAN,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "critical_step_without_verified_progress"
            )
        }

        if (!progress && repeatedNoProgressSteps >= 2 && !recoverablePathExists) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "no_progress_without_recoverable_path"
            )
        }

        return RuntimeDecision(
            directive = RuntimeDirective.CONTINUE,
            progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
            goalCommitted = committed && strictGoalEvidence,
            reason = if (progress) "progress_detected" else "continue_with_caution"
        )
    }

    fun evaluateCycleOutcome(
        userPrompt: String,
        state: KaiScreenState,
        recoverablePathExists: Boolean,
        telemetry: RuntimeTelemetry,
        strictTargetEvidenceSatisfied: Boolean = false,
        lastRequiredStepFailed: Boolean = false,
        plannerSignaledGoalComplete: Boolean = false
    ): RuntimeDecision {
        val openOnlyGoal = isOpenOnlyGoal(userPrompt)
        if (openOnlyGoal && hasUsableOpenAppArrival(state)) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_SUCCESS,
                progressLevel = ProgressLevel.GOAL_COMMITTED,
                goalCommitted = true,
                reason = "goal_committed_open_only_cycle_authority_evidence"
            )
        }

        if (strictTargetEvidenceSatisfied && !lastRequiredStepFailed) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_SUCCESS,
                progressLevel = ProgressLevel.GOAL_COMMITTED,
                goalCommitted = true,
                reason = "goal_committed_from_strict_cycle_evidence"
            )
        }

        if (!recoverablePathExists && telemetry.noProgressCycles >= 2) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "cycle_stall_no_recovery_path"
            )
        }

        if (telemetry.noProgressCycles >= 2 || telemetry.repeatedSameSemanticIntentCycles >= 2 || plannerSignaledGoalComplete) {
            return RuntimeDecision(
                directive = RuntimeDirective.REPLAN,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "cycle_replan_required"
            )
        }

        val stageSnapshot = KaiTaskStageEngine.evaluate(
            userPrompt = userPrompt,
            currentState = state
        )

        if (stageSnapshot.appEntryComplete && stageSnapshot.shouldContinue) {
            return RuntimeDecision(
                directive = RuntimeDirective.CONTINUE,
                progressLevel = ProgressLevel.INTERMEDIATE,
                goalCommitted = false,
                reason = "stage_continuation_required:${stageSnapshot.nextSemanticAction}"
            )
        }

        return RuntimeDecision(
            directive = RuntimeDirective.CONTINUE,
            progressLevel = ProgressLevel.INTERMEDIATE,
            goalCommitted = false,
            reason = "cycle_continue"
        )
    }
}
