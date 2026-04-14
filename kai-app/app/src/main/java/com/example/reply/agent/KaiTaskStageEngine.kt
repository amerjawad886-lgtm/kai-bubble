package com.example.reply.agent

object KaiTaskStageEngine {

    enum class GoalMode {
        OPEN_ONLY,
        MULTI_STAGE
    }

    enum class Stage {
        APP_ENTRY,
        REACH_MESSAGES_SURFACE,
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
            " then ", " and ", "ثم", "بعد", "message", "messages", "chat", "dm",
            "conversation", "note", "write", "type", "send", "reply", "play", "watch",
            "رسائل", "محادث", "اكتب", "ارسل", "إرسال"
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

        val appEntryComplete = when {
            openAppOutcome == KaiOpenAppOutcome.TARGET_READY -> true
            appHint.isNotBlank() &&
                currentState.likelyMatchesAppHint(appHint) &&
                currentState.packageName.isNotBlank() &&
                !currentState.isLauncher() -> true
            else -> false
        }

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
        val wantsConversation = listOf("conversation", "thread", "chat with", "message to", "dm to", "محادثة").any { prompt.contains(it) }
        val wantsMessages = listOf("message", "messages", "chat", "dm", "رسائل", "محادث").any { prompt.contains(it) }
        val wantsNote = appHint == "notes" || prompt.contains("note") || prompt.contains("ملاحظ")
        val wantsMedia = listOf("play", "watch", "شغل", "شاهد").any { prompt.contains(it) }

        return when {
            wantsMessages && !currentState.isChatListScreen() && !currentState.isChatThreadScreen() && !currentState.isChatComposerSurface() -> {
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
                StageSnapshot(
                    goalMode = goalMode,
                    stage = Stage.SUCCESS,
                    appEntryComplete = true,
                    finalGoalComplete = true,
                    shouldContinue = false,
                    nextSemanticAction = "none",
                    reason = "stage_goal_satisfied"
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
                stageHint = "app_entry",
                completionBoundary = KaiGoalBoundary.APP_ENTRY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = stageSnapshot.goalMode == GoalMode.OPEN_ONLY,
                note = "stage_continuation_open_app"
            )

            "open_messages" -> KaiActionStep(
                cmd = "click_text",
                text = if (currentState.packageName.contains("whatsapp", true)) "chats" else "messages",
                expectedPackage = expectedPackage,
                stageHint = "messages_surface",
                completionBoundary = KaiGoalBoundary.SURFACE_READY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                note = "stage_continuation_messages"
            )

            "open_target_conversation" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "chat_item",
                selectorText = extractConversationQuery(userPrompt),
                text = extractConversationQuery(userPrompt),
                expectedPackage = expectedPackage,
                expectedScreenKind = "chat_thread",
                stageHint = "conversation_open",
                completionBoundary = KaiGoalBoundary.ENTITY_OPENED,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                note = "stage_continuation_conversation"
            )

            "open_note_editor" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "create_button",
                selectorText = "new",
                text = "new",
                expectedPackage = expectedPackage,
                expectedScreenKind = "notes_editor",
                stageHint = "note_editor",
                completionBoundary = KaiGoalBoundary.SURFACE_READY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                note = "stage_continuation_note_editor"
            )

            "open_first_media" -> KaiActionStep(
                cmd = "open_best_list_item",
                selectorRole = "list_item",
                expectedPackage = expectedPackage,
                expectedScreenKind = "detail",
                stageHint = "media_open",
                completionBoundary = KaiGoalBoundary.ENTITY_OPENED,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                note = "stage_continuation_media"
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
