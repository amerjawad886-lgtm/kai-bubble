
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

    fun hasMeaningfulProgress(before: KaiScreenState, after: KaiScreenState): Boolean {
        if (before.packageName != after.packageName &&
            before.packageName.isNotBlank() &&
            after.packageName.isNotBlank()
        ) {
            return true
        }
        if (before.screenKind != after.screenKind) return true
        if (before.semanticFingerprint() != after.semanticFingerprint()) return true
        if (kotlin.math.abs(before.semanticConfidence - after.semanticConfidence) >= 0.08f) return true
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
                    "notes_editor" -> state.isNotesEditorSurface() || state.isNotesBodyInputSurface()
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

    fun isGoalCommitted(
        step: KaiActionStep,
        before: KaiScreenState,
        after: KaiScreenState,
        stepSucceeded: Boolean
    ): Boolean {
        if (!stepSucceeded) return false

        return when (step.cmd.trim().lowercase()) {
            "open_app" -> {
                val targetPkg = step.expectedPackage
                when {
                    targetPkg.isNotBlank() -> after.matchesExpectedPackage(targetPkg) && !after.isLauncher()
                    else -> !after.isLauncher() && after.packageName.isNotBlank() &&
                        before.packageName != after.packageName
                }
            }
            "verify_state" -> expectedEvidenceSatisfied(step, after)
            "input_text", "input_into_best_field" -> {
                val payload = step.text.ifBlank { step.selectorText }.trim()
                payload.isNotBlank() &&
                    (after.containsText(payload) ||
                        after.editableTextSignature().contains(KaiScreenStateParser.normalize(payload)))
            }
            "press_primary_action" -> before.findSendAction() != null && after.findSendAction() == null
            else -> hasMeaningfulProgress(before, after)
        }
    }

    fun likelyGoalSatisfied(userPrompt: String, state: KaiScreenState): Boolean {
        if (state.packageName.isBlank()) return false
        if (state.isWeakObservation()) return false

        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        if (appHint.isNotBlank() && !state.likelyMatchesAppHint(appHint)) return false

        val goalMode = KaiTaskStageEngine.classifyGoalMode(userPrompt)
        if (goalMode == KaiTaskStageEngine.GoalMode.OPEN_ONLY) {
            return !state.isLauncher() && state.packageName.isNotBlank()
        }

        val prompt = KaiScreenStateParser.normalize(userPrompt)
        val wantsChat = listOf("message", "messages", "chat", "dm", "conversation", "رسائل", "محادث")
            .any { prompt.contains(it) }
        val wantsNote = appHint == "notes" || prompt.contains("note") || prompt.contains("ملاحظ")
        val wantsPlayer = listOf("play", "watch", "شغل", "ابدأ").any { prompt.contains(it) }

        return when {
            wantsChat -> state.isChatListScreen() || state.isChatThreadScreen() || state.isChatComposerSurface()
            wantsNote -> state.isNotesEditorSurface() || state.isNotesBodyInputSurface() || state.isNotesTitleInputSurface()
            wantsPlayer -> state.isDetailSurface() || state.isPlayerSurface()
            else -> state.isMeaningful()
        }
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
        return likelyGoalSatisfied(userPrompt, currentState)
    }

    fun isCriticalCommand(step: KaiActionStep): Boolean {
        return step.cmd.trim().lowercase() in setOf(
            "open_app",
            "click_text",
            "click_best_match",
            "open_best_list_item",
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
        val progress = hasMeaningfulProgress(before, after) && !telemetry.observationReusedLastGood
        val evidence = expectedEvidenceSatisfied(step, after)

        // open_app: require target package confirmation, stop after repeated failures
        if (step.cmd.equals("open_app", true)) {
            val targetPkg = step.expectedPackage
            val inTarget = when {
                targetPkg.isNotBlank() -> after.matchesExpectedPackage(targetPkg) && !after.isLauncher()
                else -> after.packageName.isNotBlank() && !after.isLauncher() &&
                    before.packageName != after.packageName
            }
            if (result.success && inTarget) {
                return RuntimeDecision(
                    directive = RuntimeDirective.CONTINUE,
                    progressLevel = ProgressLevel.TARGET_READY,
                    goalCommitted = false,
                    reason = "open_app_target_confirmed"
                )
            }
            if (repeatedNoProgressSteps >= 3) {
                return RuntimeDecision(
                    directive = RuntimeDirective.STOP_FAILURE,
                    progressLevel = ProgressLevel.NONE,
                    goalCommitted = false,
                    reason = "open_app_repeated_failure"
                )
            }
            return RuntimeDecision(
                directive = RuntimeDirective.CONTINUE,
                progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
                goalCommitted = false,
                reason = if (result.success) "open_app_no_target_match" else "open_app_not_confirmed"
            )
        }

        if (result.success && (progress || evidence)) {
            return RuntimeDecision(
                directive = RuntimeDirective.CONTINUE,
                progressLevel = ProgressLevel.INTERMEDIATE,
                goalCommitted = false,
                reason = if (evidence) "expected_evidence_hit" else "progress_observed"
            )
        }

        if (telemetry.loopSafetyLimitReached) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "loop_limit_reached"
            )
        }

        if (telemetry.observationWeak || telemetry.weakReadStreak >= 2 || telemetry.staleReadStreak >= 2) {
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

        if (result.hardStop) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "hard_stop:${result.message}"
            )
        }

        return RuntimeDecision(
            directive = RuntimeDirective.CONTINUE,
            progressLevel = if (progress) ProgressLevel.INTERMEDIATE else ProgressLevel.NONE,
            goalCommitted = false,
            reason = if (result.success) "continue_after_success" else "continue_after_soft_failure"
        )
    }

    fun evaluateCycleOutcome(
        currentState: KaiScreenState,
        userPrompt: String,
        lastDecision: RuntimeDecision? = null,
        telemetry: RuntimeTelemetry = RuntimeTelemetry()
    ): RuntimeDecision {
        if (likelyGoalSatisfied(userPrompt, currentState)) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_SUCCESS,
                progressLevel = ProgressLevel.GOAL_COMMITTED,
                goalCommitted = true,
                reason = "goal_satisfied_from_cycle_state"
            )
        }

        if (telemetry.loopSafetyLimitReached || telemetry.noProgressCycles >= 4) {
            return RuntimeDecision(
                directive = RuntimeDirective.STOP_FAILURE,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "cycle_limit_or_no_progress"
            )
        }

        if (telemetry.observationWeak || telemetry.weakReadStreak >= 2 || telemetry.staleReadStreak >= 2) {
            return RuntimeDecision(
                directive = RuntimeDirective.REPLAN,
                progressLevel = ProgressLevel.NONE,
                goalCommitted = false,
                reason = "cycle_observation_unstable"
            )
        }

        val stageSnapshot = KaiTaskStageEngine.evaluate(userPrompt, currentState)
        if (stageSnapshot.shouldContinue) {
            return RuntimeDecision(
                directive = RuntimeDirective.CONTINUE,
                progressLevel = if (stageSnapshot.appEntryComplete) {
                    ProgressLevel.TARGET_READY
                } else {
                    ProgressLevel.INTERMEDIATE
                },
                goalCommitted = false,
                reason = "stage_continuation_required:${stageSnapshot.nextSemanticAction}"
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
        OPEN_MEDIA,
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

    fun classifyGoalMode(prompt: String): GoalMode {
        val p = KaiScreenStateParser.normalize(prompt)
        if (p.isBlank()) return GoalMode.MULTI_STAGE

        val openIntent = p.startsWith("open ") ||
            p.startsWith("launch ") ||
            p.startsWith("افتح ") ||
            p.startsWith("شغل ")

        val followUpSignals = listOf(
            " and ", " then ", " ثم ", "بعد", "اكتب", "send", "reply",
            "message", "conversation", "chat", "note", "play", "watch"
        ).any { p.contains(it) }

        return if (openIntent && !followUpSignals) GoalMode.OPEN_ONLY else GoalMode.MULTI_STAGE
    }

    fun evaluate(
        userPrompt: String,
        currentState: KaiScreenState,
        openAppOutcome: KaiOpenAppOutcome? = null
    ): StageSnapshot {
        val goalMode = classifyGoalMode(userPrompt)
        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        val appEntryComplete =
            openAppOutcome in setOf(
                KaiOpenAppOutcome.TARGET_READY,
                KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
            ) ||
                (appHint.isNotBlank() &&
                    currentState.likelyMatchesAppHint(appHint) &&
                    !currentState.isLauncher() &&
                    currentState.packageName.isNotBlank())

        if (!appEntryComplete) {
            return StageSnapshot(
                goalMode = goalMode,
                stage = Stage.APP_ENTRY,
                appEntryComplete = false,
                finalGoalComplete = false,
                shouldContinue = true,
                nextSemanticAction = "open_app",
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
                nextSemanticAction = "none",
                reason = "open_only_goal_satisfied"
            )
        }

        val prompt = KaiScreenStateParser.normalize(userPrompt)
        val wantsMessages = listOf("message", "messages", "chat", "dm", "رسائل", "محادث")
            .any { prompt.contains(it) }
        val wantsConversation = listOf("conversation", "thread", "chat with", "محادثة")
            .any { prompt.contains(it) }
        val wantsNote = appHint == "notes" || prompt.contains("note") || prompt.contains("ملاحظ")
        val wantsMedia = listOf("play", "watch", "شغل", "شاهد").any { prompt.contains(it) }

        return when {
            wantsMessages && !currentState.isChatListScreen() && !currentState.isChatThreadScreen() -> {
                StageSnapshot(goalMode, Stage.REACH_MESSAGES_SURFACE, true, false, true, "open_messages", "messages_surface_not_reached")
            }
            wantsConversation && !currentState.isChatThreadScreen() && !currentState.isChatComposerSurface() -> {
                StageSnapshot(goalMode, Stage.OPEN_TARGET_CONVERSATION, true, false, true, "open_target_conversation", "conversation_not_opened")
            }
            wantsNote && !(currentState.isNotesEditorSurface() || currentState.isNotesBodyInputSurface() || currentState.isNotesTitleInputSurface()) -> {
                StageSnapshot(goalMode, Stage.REACH_NOTE_EDITOR, true, false, true, "open_note_editor", "note_editor_not_ready")
            }
            wantsMedia && !(currentState.isDetailSurface() || currentState.isPlayerSurface()) -> {
                StageSnapshot(goalMode, Stage.OPEN_MEDIA, true, false, true, "open_first_media", "media_not_opened")
            }
            else -> {
                val finalGoal = KaiExecutionDecisionAuthority.likelyGoalSatisfied(userPrompt, currentState)
                StageSnapshot(
                    goalMode = goalMode,
                    stage = if (finalGoal) Stage.SUCCESS else Stage.GENERAL_CONTINUATION,
                    appEntryComplete = true,
                    finalGoalComplete = finalGoal,
                    shouldContinue = !finalGoal,
                    nextSemanticAction = if (finalGoal) "none" else "continue_semantic_navigation",
                    reason = if (finalGoal) "goal_satisfied" else "general_continuation"
                )
            }
        }
    }

    fun buildContinuationStep(
        stageSnapshot: StageSnapshot,
        userPrompt: String,
        currentState: KaiScreenState
    ): KaiActionStep? {
        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        val expectedPackage = KaiAppIdentityRegistry.primaryPackageForKey(appHint)

        return when (stageSnapshot.nextSemanticAction) {
            "open_app" -> KaiActionStep(
                cmd = "open_app",
                text = appHint.ifBlank { userPrompt.trim() },
                expectedPackage = expectedPackage,
                note = "stage_continuation_open_app"
            )

            "open_messages" -> KaiActionStep(
                cmd = "click_text",
                text = if (currentState.packageName.contains("whatsapp", true)) "chats" else "messages",
                expectedPackage = expectedPackage,
                note = "stage_continuation_messages"
            )

            "open_target_conversation" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "chat_item",
                selectorText = extractConversationQuery(userPrompt),
                text = extractConversationQuery(userPrompt),
                expectedPackage = expectedPackage,
                expectedScreenKind = "chat_thread",
                note = "stage_continuation_conversation"
            )

            "open_note_editor" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "create_button",
                selectorText = "new",
                text = "new",
                expectedPackage = expectedPackage,
                expectedScreenKind = "notes_editor",
                note = "stage_continuation_note_editor"
            )

            "open_first_media" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "list_item",
                expectedPackage = expectedPackage,
                expectedScreenKind = "detail",
                note = "stage_continuation_media"
            )

            "continue_semantic_navigation" -> KaiActionStep(
                cmd = "verify_state",
                expectedPackage = expectedPackage,
                note = "stage_continuation_verify"
            )

            else -> null
        }
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
}
