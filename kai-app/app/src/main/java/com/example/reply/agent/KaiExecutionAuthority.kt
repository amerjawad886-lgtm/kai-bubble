package com.example.reply.agent

// ── KaiExecutionDecisionAuthority ──────────────────────────────────────────────

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

    fun shouldDeferFinalCommit(stageSnapshot: KaiTaskStageEngine.StageSnapshot): Boolean {
        return stageSnapshot.appEntryComplete &&
            !stageSnapshot.finalGoalComplete &&
            stageSnapshot.goalMode != KaiTaskStageEngine.GoalMode.OPEN_ONLY
    }

    fun shouldAcceptPlannerGoalComplete(
        plan: KaiActionPlan,
        userPrompt: String,
        currentState: KaiScreenState
    ): Boolean {
        if (!plan.goalComplete && !plan.plannerGoalComplete) return false
        if (currentState.isWeakObservation() || currentState.isOverlayPolluted()) return false
        val stageSnapshot = KaiTaskStageEngine.evaluate(
            userPrompt = userPrompt,
            currentState = currentState
        )
        return stageSnapshot.finalGoalComplete && likelyGoalSatisfied(userPrompt, currentState)
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

                KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED -> {
                    val sameFamily = step.expectedPackage.isNotBlank() &&
                        KaiAppIdentityRegistry.packageMatchesFamily(step.expectedPackage, after.packageName)
                    if (sameFamily) {
                        RuntimeDecision(
                            directive = RuntimeDirective.CONTINUE,
                            progressLevel = ProgressLevel.TARGET_READY,
                            goalCommitted = false,
                            reason = "open_app_family_match_continue"
                        )
                    } else RuntimeDecision(
                        directive = RuntimeDirective.STOP_FAILURE,
                        progressLevel = ProgressLevel.NONE,
                        goalCommitted = false,
                        reason = "open_app_wrong_package_confirmed"
                    )
                }

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

// ── KaiTaskStageEngine ──────────────────────────────────────────────

object KaiTaskStageEngine {
    enum class GoalMode {
        OPEN_ONLY,
        MULTI_STAGE
    }

    enum class Stage {
        APP_ENTRY,
        REACH_MESSAGES_SURFACE,
        LOCATE_TARGET_CONVERSATION,
        OPEN_TARGET_CONVERSATION,
        REACH_NOTE_EDITOR,
        ENTER_NOTE_TITLE,
        ENTER_NOTE_BODY,
        REACH_BROWSABLE_SURFACE,
        OPEN_MEDIA,
        CONFIRM_PLAYER,
        GENERAL_CONTINUATION,
        SUCCESS
    }

    data class StageSnapshot(
        val goalMode: GoalMode,
        val stage: Stage,
        val appEntryComplete: Boolean,
        val finalGoalComplete: Boolean,
        val shouldContinue: Boolean,
        val nextSemanticAction: String,
        val reason: String
    )

    private val stageSemanticActionByStage = mapOf(
        Stage.APP_ENTRY to "open_app",
        Stage.REACH_MESSAGES_SURFACE to "open_messages",
        Stage.LOCATE_TARGET_CONVERSATION to "open_first_conversation",
        Stage.OPEN_TARGET_CONVERSATION to "open_target_conversation",
        Stage.REACH_NOTE_EDITOR to "open_note_editor",
        Stage.ENTER_NOTE_TITLE to "focus_note_title",
        Stage.ENTER_NOTE_BODY to "type_note_body",
        Stage.REACH_BROWSABLE_SURFACE to "open_browse_home",
        Stage.OPEN_MEDIA to "open_first_media",
        Stage.CONFIRM_PLAYER to "press_playback",
        Stage.GENERAL_CONTINUATION to "continue_semantic_navigation",
        Stage.SUCCESS to "none"
    )

    fun classifyGoalMode(prompt: String): GoalMode {
        val p = KaiScreenStateParser.normalize(prompt)
        if (p.isBlank()) return GoalMode.MULTI_STAGE

        val hasOpenIntent =
            p.contains("open") ||
                p.contains("launch") ||
                p.contains("افتح") ||
                p.contains("شغل")

        val hasArabicFollowUpPattern =
            Regex("""افتح\s+.+\s+(?:و|ثم)\s+.+""").containsMatchIn(p) ||
                Regex("""(?:^|\s)(?:و|ثم)\s*(?:انشاء|انشئ|اكتب|شغل|اضغط|ارسل|ابحث)(?:\s|$)""").containsMatchIn(p)

        val hasFollowUpIntent =
            p.contains(" and ") ||
                p.contains(" then ") ||
                p.contains(" ثم ") ||
                p.contains("بعد") ||
                hasArabicFollowUpPattern ||
                containsAny(
                    p,
                    "chat",
                    "conversation",
                    "thread",
                    "message",
                    "dm",
                    "write",
                    "type",
                    "send",
                    "note",
                    "play",
                    "search",
                    "browse",
                    "create"
                )

        return if (hasOpenIntent && !hasFollowUpIntent) GoalMode.OPEN_ONLY else GoalMode.MULTI_STAGE
    }

    fun evaluate(
        userPrompt: String,
        currentState: KaiScreenState,
        openAppOutcome: KaiOpenAppOutcome? = null
    ): StageSnapshot {
        val goalMode = classifyGoalMode(userPrompt)
        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        val normalizedPrompt = KaiScreenStateParser.normalize(userPrompt)

        val appEntryByOutcome = openAppOutcome in setOf(
            KaiOpenAppOutcome.TARGET_READY,
            KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
        )

        val appEntryBySurface =
            appHint.isNotBlank() &&
                currentState.likelyMatchesAppHint(appHint) &&
                !currentState.isLauncher() &&
                !currentState.isWeakObservation()

        val appEntryComplete = appEntryByOutcome || appEntryBySurface

        if (!appEntryComplete) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.APP_ENTRY,
                appEntryComplete = false,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.APP_ENTRY),
                reason = "app_entry_not_confirmed"
            )
        }

        if (goalMode == GoalMode.OPEN_ONLY) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.SUCCESS,
                appEntryComplete = true,
                finalGoalComplete = true,
                shouldContinue = false,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.SUCCESS),
                reason = "open_only_app_entry_confirmed"
            )
        }

        val wantsMessagesSurface = containsAny(
            normalizedPrompt,
            "messages",
            "message",
            "dm",
            "inbox",
            "messenger",
            "chat",
            "direct",
            "الرسائل",
            "محادث"
        )
        val wantsConversation = containsAny(
            normalizedPrompt,
            "conversation",
            "chat with",
            "open chat",
            "thread",
            "محادثه",
            "محادثة"
        )
        val wantsWrite = containsAny(
            normalizedPrompt,
            "type",
            "write",
            "reply",
            "compose",
            "draft",
            "اكتب",
            "اكتب رساله",
            "اكتب رسالة"
        )
        val wantsSend = containsAny(
            normalizedPrompt,
            "send",
            "ارسال",
            "إرسال",
            "submit",
            "post"
        )
        val writePayload = extractWritePayload(userPrompt)
        val wantsPlayback = containsAny(
            normalizedPrompt,
            "play",
            "start",
            "watch",
            "listen",
            "شغل",
            "ابدأ",
            "شاهد",
            "اسمع"
        )
        val wantsCreateNote =
            appHint == "notes" ||
                containsAny(normalizedPrompt, "note", "notes", "ملاحظه", "ملاحظة")

        val onMessagesSurface = currentState.isInstagramDmListSurface() || currentState.isChatListScreen()
        val onThreadSurface = currentState.isInstagramDmThreadSurface() || currentState.isChatThreadScreen() || currentState.isChatComposerSurface()
        val onNotesEditor =
            currentState.isNotesEditorSurface() ||
                currentState.isNotesTitleInputSurface() ||
                currentState.isNotesBodyInputSurface()
        val onBrowsable =
            currentState.isContentFeedSurface() ||
                currentState.isResultListSurface() ||
                currentState.isTabbedHomeSurface() ||
                currentState.isSearchLikeSurface()
        val onPlayer = currentState.isPlayerSurface() || currentState.isDetailSurface()

        if (wantsMessagesSurface && !onMessagesSurface && !onThreadSurface) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.REACH_MESSAGES_SURFACE,
                appEntryComplete = true,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.REACH_MESSAGES_SURFACE),
                reason = "messages_surface_not_reached"
            )
        }

        if (wantsConversation && !onThreadSurface) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = if (onMessagesSurface) Stage.OPEN_TARGET_CONVERSATION else Stage.LOCATE_TARGET_CONVERSATION,
                appEntryComplete = true,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = if (onMessagesSurface) {
                    stageSemanticActionByStage.getValue(Stage.OPEN_TARGET_CONVERSATION)
                } else {
                    stageSemanticActionByStage.getValue(Stage.LOCATE_TARGET_CONVERSATION)
                },
                reason = "conversation_thread_not_opened"
            )
        }

        if (wantsWrite && !wantsCreateNote) {
            val writeEvidence = writePayload.isNotBlank() && (
                currentState.containsText(writePayload) ||
                    currentState.editableTextSignature().contains(KaiScreenStateParser.normalize(writePayload))
                )
            if (!writeEvidence) {
                val writeStage = if (onThreadSurface) Stage.OPEN_TARGET_CONVERSATION else Stage.LOCATE_TARGET_CONVERSATION
                return StageSnapshot(
                    goalMode = goalMode,
                    stage = writeStage,
                    appEntryComplete = true,
                    finalGoalComplete = false,
                    shouldContinue = true,
                    nextSemanticAction = stageSemanticActionByStage.getValue(writeStage),
                    reason = "write_goal_not_committed"
                )
            }
        }

        if (wantsSend && !wantsCreateNote) {
            val sendCommitted = !currentState.isSendButtonSurface()
            if (!sendCommitted) {
                val sendStage = if (onThreadSurface) Stage.OPEN_TARGET_CONVERSATION else Stage.LOCATE_TARGET_CONVERSATION
                return StageSnapshot(
                    goalMode = goalMode,
                    stage = sendStage,
                    appEntryComplete = true,
                    finalGoalComplete = false,
                    shouldContinue = true,
                    nextSemanticAction = stageSemanticActionByStage.getValue(sendStage),
                    reason = "send_goal_not_committed"
                )
            }
        }

        if (wantsCreateNote && !onNotesEditor) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.REACH_NOTE_EDITOR,
                appEntryComplete = true,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.REACH_NOTE_EDITOR),
                reason = "note_editor_not_reached"
            )
        }

        if (wantsCreateNote && onNotesEditor) {
            val wantsTitle = containsAny(normalizedPrompt, "title", "titled", "عنوان")
            if (wantsTitle && !currentState.isNotesTitleInputSurface()) {
                return StageSnapshot(
                    goalMode = goalMode,
                    stage = Stage.ENTER_NOTE_TITLE,
                    appEntryComplete = true,
                    finalGoalComplete = false,
                    shouldContinue = true,
                    nextSemanticAction = stageSemanticActionByStage.getValue(Stage.ENTER_NOTE_TITLE),
                    reason = "note_title_field_not_ready"
                )
            }
            if (wantsWrite && !currentState.isNotesBodyInputSurface()) {
                return StageSnapshot(
                    goalMode = goalMode,
                    stage = Stage.ENTER_NOTE_BODY,
                    appEntryComplete = true,
                    finalGoalComplete = false,
                    shouldContinue = true,
                    nextSemanticAction = stageSemanticActionByStage.getValue(Stage.ENTER_NOTE_BODY),
                    reason = "note_body_field_not_ready"
                )
            }
        }

        if (wantsPlayback && !onBrowsable && !onPlayer) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.REACH_BROWSABLE_SURFACE,
                appEntryComplete = true,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.REACH_BROWSABLE_SURFACE),
                reason = "browsable_surface_not_reached"
            )
        }

        if (wantsPlayback && onBrowsable && !onPlayer) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.OPEN_MEDIA,
                appEntryComplete = true,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.OPEN_MEDIA),
                reason = "media_item_not_opened"
            )
        }

        if (wantsPlayback && !onPlayer) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.CONFIRM_PLAYER,
                appEntryComplete = true,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.CONFIRM_PLAYER),
                reason = "player_surface_not_confirmed"
            )
        }

        val writeSatisfied = if (!wantsWrite) {
            true
        } else if (writePayload.isBlank()) {
            false
        } else {
            currentState.containsText(writePayload) ||
                currentState.editableTextSignature().contains(KaiScreenStateParser.normalize(writePayload))
        }

        val sendSatisfied = if (!wantsSend) {
            true
        } else {
            !currentState.isSendButtonSurface()
        }

        val semanticStageSatisfied = when {
            wantsConversation -> onThreadSurface
            wantsMessagesSurface -> onMessagesSurface || onThreadSurface
            wantsCreateNote && wantsWrite -> onNotesEditor && writeSatisfied
            wantsCreateNote -> onNotesEditor
            wantsPlayback -> onPlayer
            wantsWrite || wantsSend -> (onThreadSurface || onNotesEditor) && writeSatisfied && sendSatisfied
            else -> currentState.isMeaningful() && !currentState.isWeakObservation()
        }

        return if (semanticStageSatisfied) {
            StageSnapshot(
                goalMode = goalMode,
                stage = Stage.SUCCESS,
                appEntryComplete = true,
                finalGoalComplete = true,
                shouldContinue = false,
                nextSemanticAction = stageSemanticActionByStage.getValue(Stage.SUCCESS),
                reason = "semantic_stage_satisfied"
            )
        } else {
            val fallbackStage = when {
                wantsCreateNote -> Stage.REACH_NOTE_EDITOR
                wantsPlayback -> if (onBrowsable) Stage.OPEN_MEDIA else Stage.REACH_BROWSABLE_SURFACE
                wantsConversation || wantsWrite || wantsSend -> if (onMessagesSurface) Stage.OPEN_TARGET_CONVERSATION else Stage.REACH_MESSAGES_SURFACE
                wantsMessagesSurface -> Stage.REACH_MESSAGES_SURFACE
                appHint in setOf("instagram", "whatsapp", "telegram", "messages") -> Stage.LOCATE_TARGET_CONVERSATION
                appHint == "youtube" -> if (onBrowsable) Stage.OPEN_MEDIA else Stage.REACH_BROWSABLE_SURFACE
                appHint == "notes" -> Stage.REACH_NOTE_EDITOR
                else -> Stage.GENERAL_CONTINUATION
            }
            StageSnapshot(
                goalMode = goalMode,
                stage = fallbackStage,
                appEntryComplete = true,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = stageSemanticActionByStage.getValue(fallbackStage),
                reason = "app_entry_explicit_surface_continuation"
            )
        }
    }

    fun buildContinuationStep(
        stageSnapshot: StageSnapshot,
        userPrompt: String,
        currentState: KaiScreenState
    ): KaiActionStep? {
        if (!stageSnapshot.shouldContinue || stageSnapshot.finalGoalComplete) return null
        if (stageSnapshot.nextSemanticAction == "none") return null

        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        val expectedPackage = expectedPackageForAppHint(appHint)
        val strictExpectedPackage = when (appHint) {
            "files", "gallery", "calculator" -> ""
            else -> expectedPackage
        }
        val conversationTarget = extractConversationQuery(userPrompt)
        val writePayload = extractWritePayload(userPrompt)

        return when (stageSnapshot.nextSemanticAction) {
            "open_app" -> KaiActionStep(
                cmd = "open_app",
                text = appHint.ifBlank { userPrompt.trim() },
                expectedPackage = expectedPackage,
                strategy = "stage_continuation_open_app",
                note = "stage_continuation",
                stageHint = Stage.APP_ENTRY.name,
                completionBoundary = KaiGoalBoundary.APP_ENTRY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            )

            "open_messages" -> KaiActionStep(
                cmd = "click_best_match",
                selectorRole = "tab",
                selectorText = "messages",
                text = "messages",
                expectedPackage = expectedPackage,
                expectedScreenKind = if (appHint == "instagram") "instagram_dm_list" else "chat_list",
                strategy = "stage_continuation_navigate_messages",
                note = "stage_continuation",
                stageHint = Stage.REACH_MESSAGES_SURFACE.name,
                completionBoundary = KaiGoalBoundary.SURFACE_READY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            )

            "open_first_conversation" -> KaiActionStep(
                cmd = "click_best_match",
                selectorRole = "tab",
                selectorText = if (appHint == "instagram") "messages" else "chats",
                text = if (appHint == "instagram") "messages" else "chats",
                expectedPackage = expectedPackage,
                expectedScreenKind = if (appHint == "instagram") "instagram_dm_list" else "chat_list",
                strategy = "stage_continuation_find_chat_target",
                note = "stage_continuation"
            )

            "open_target_conversation" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "chat_item",
                selectorText = conversationTarget,
                text = conversationTarget,
                expectedPackage = expectedPackage,
                expectedScreenKind = if (appHint == "instagram") "instagram_dm_thread" else "chat_thread",
                strategy = "stage_continuation_open_chat_target",
                note = "stage_continuation",
                stageHint = Stage.OPEN_TARGET_CONVERSATION.name,
                completionBoundary = KaiGoalBoundary.ENTITY_OPENED,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            )

            "open_note_editor" -> KaiActionStep(
                cmd = "press_primary_action",
                selectorRole = "create_button",
                selectorText = "new note",
                text = "new note",
                expectedPackage = expectedPackage,
                expectedScreenKind = "notes_editor",
                strategy = "stage_continuation_open_note_editor",
                note = "stage_continuation"
            )

            "focus_note_title" -> KaiActionStep(
                cmd = "focus_best_input",
                selectorRole = "input",
                selectorHint = "title",
                expectedPackage = expectedPackage,
                expectedScreenKind = "notes_title_input",
                strategy = "stage_continuation_focus_note_title",
                note = "stage_continuation"
            )

            "type_note_body" -> {
                if (writePayload.isNotBlank()) {
                    KaiActionStep(
                        cmd = "input_into_best_field",
                        selectorRole = "input",
                        selectorHint = "body",
                        text = writePayload,
                        expectedPackage = expectedPackage,
                        expectedScreenKind = "notes_editor",
                        strategy = "stage_continuation_type_note_body",
                        note = "stage_continuation",
                        stageHint = Stage.ENTER_NOTE_BODY.name,
                        completionBoundary = KaiGoalBoundary.CONTENT_COMMITTED,
                        continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                        allowsFinalCommit = false
                    )
                } else {
                    KaiActionStep(
                        cmd = "focus_best_input",
                        selectorRole = "input",
                        selectorHint = "body",
                        expectedPackage = expectedPackage,
                        expectedScreenKind = "notes_body_input",
                        strategy = "stage_continuation_focus_note_body",
                        note = "stage_continuation"
                    )
                }
            }

            "open_browse_home" -> KaiActionStep(
                cmd = "click_best_match",
                selectorRole = "tab",
                selectorText = "home",
                text = "home",
                expectedPackage = expectedPackage,
                strategy = "stage_continuation_navigate_browse_home",
                note = "stage_continuation"
            )

            "open_first_media" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "list_item",
                selectorText = writePayload,
                text = writePayload,
                expectedPackage = expectedPackage,
                expectedScreenKind = "detail",
                strategy = "stage_continuation_open_media_item",
                note = "stage_continuation"
            )

            "press_playback" -> {
                if (!currentState.isPlayerSurface() && !currentState.isDetailSurface()) {
                    KaiActionStep(
                        cmd = "open_best_list_item",
                        selectorRole = "list_item",
                        selectorText = writePayload,
                        text = writePayload,
                        expectedPackage = expectedPackage,
                        expectedScreenKind = "player",
                        strategy = "stage_continuation_confirm_player_surface",
                        note = "stage_continuation"
                    )
                } else {
                    KaiActionStep(
                        cmd = "press_primary_action",
                        selectorRole = "play_button",
                        selectorText = "play",
                        text = "play",
                        expectedPackage = expectedPackage,
                        expectedScreenKind = "player",
                        strategy = "stage_continuation_activate_player",
                        note = "stage_continuation",
                        stageHint = Stage.CONFIRM_PLAYER.name,
                        completionBoundary = KaiGoalBoundary.FINAL_GOAL,
                        continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                        allowsFinalCommit = true
                    )
                }
            }

            "continue_semantic_navigation" -> {
                val continuationVerifyKinds = mapOf(
                    "settings" to "settings"
                )
                if (appHint in setOf("settings", "calendar", "files", "gallery", "chrome", "calculator", "chatgpt")) {
                    KaiActionStep(
                        cmd = "verify_state",
                        expectedPackage = strictExpectedPackage,
                        expectedScreenKind = continuationVerifyKinds[appHint].orEmpty(),
                        strategy = "stage_continuation_verify_app_context",
                        note = "stage_continuation"
                    )
                } else when {
                    currentState.isSearchLikeSurface() || currentState.isCameraOrMediaOverlaySurface() || currentState.isSheetOrDialogSurface() -> {
                        KaiActionStep(
                            cmd = "back",
                            expectedPackage = expectedPackage,
                            strategy = "stage_continuation_general_back",
                            note = "stage_continuation"
                        )
                    }

                    currentState.isResultListSurface() || currentState.isContentFeedSurface() || currentState.isTabbedHomeSurface() -> {
                        KaiActionStep(
                            cmd = "open_best_list_item",
                            selectorRole = "list_item",
                            expectedPackage = expectedPackage,
                            strategy = "stage_continuation_general_open_item",
                            note = "stage_continuation"
                        )
                    }

                    currentState.isChatThreadScreen() || currentState.isChatComposerSurface() || currentState.isNotesEditorSurface() -> {
                        KaiActionStep(
                            cmd = "focus_best_input",
                            selectorRole = "input",
                            selectorHint = "message",
                            expectedPackage = expectedPackage,
                            strategy = "stage_continuation_general_focus_input",
                            note = "stage_continuation"
                        )
                    }

                    else -> {
                        KaiActionStep(
                            cmd = "verify_state",
                            expectedPackage = expectedPackage,
                            strategy = "stage_continuation_general_verify",
                            note = "stage_continuation"
                        )
                    }
                }
            }

            else -> null
        }
    }

    private fun expectedPackageForAppHint(appHint: String): String {
        return KaiAppIdentityRegistry.primaryPackageForKey(appHint)
    }

    private fun extractConversationQuery(prompt: String): String {
        val p = prompt.trim()
        val patterns = listOf(
            Regex("""(?i)(?:conversation|chat|thread)\s+([\p{L}\p{N}_\-.@]{2,})"""),
            Regex("""(?i)(?:to|with)\s+([\p{L}\p{N}_\-.@]{2,})"""),
            Regex("""(?i)(?:محادثه|محادثة|الدردشه|الدردشة)\s+([\p{L}\p{N}_\-.@]{2,})""")
        )
        return patterns.asSequence()
            .mapNotNull { it.find(p)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
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

    private fun containsAny(text: String, vararg values: String): Boolean {
        return values.any { text.contains(KaiScreenStateParser.normalize(it)) }
    }
}

