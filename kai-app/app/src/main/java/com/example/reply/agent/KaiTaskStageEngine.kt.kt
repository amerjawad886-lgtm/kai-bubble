package com.example.reply.agent

object KaiTaskStageEngine {
    enum class GoalMode { OPEN_ONLY, MULTI_STAGE }
    enum class Stage { APP_ENTRY, REACH_MESSAGES_SURFACE, OPEN_TARGET_CONVERSATION, REACH_NOTE_EDITOR, OPEN_MEDIA, GENERAL_CONTINUATION, SUCCESS }

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
        val openIntent = p.startsWith("open ") || p.startsWith("launch ") || p.startsWith("افتح ") || p.startsWith("شغل ")
        val followUpSignals = listOf(" then ", " and ", "ثم", "بعد", "message", "messages", "chat", "dm", "conversation", "note", "write", "type", "send", "reply", "play", "watch", "رسائل", "محادث", "اكتب", "ارسل", "إرسال").any { p.contains(it) }
        return if (openIntent && !followUpSignals) GoalMode.OPEN_ONLY else GoalMode.MULTI_STAGE
    }

    private fun isUsableForApp(state: KaiScreenState, expectedPackage: String): Boolean {
        return KaiVisionInterpreter.packageMatchesExpected(state.packageName, expectedPackage) &&
            KaiVisionInterpreter.isUsableState(state) &&
            !state.isLauncher()
    }

    fun evaluate(userPrompt: String, currentState: KaiScreenState, openAppOutcome: KaiOpenAppOutcome? = null): StageSnapshot {
        val goalMode = classifyGoalMode(userPrompt)
        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        val expectedPackage = KaiAppIdentityRegistry.primaryPackageForKey(appHint)
        val usableForApp = isUsableForApp(currentState, expectedPackage.ifBlank { currentState.packageName })

        val appEntryComplete = when (openAppOutcome) {
            KaiOpenAppOutcome.TARGET_READY, KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP -> true
            else -> appHint.isNotBlank() && usableForApp && currentState.likelyMatchesAppHint(appHint)
        }

        if (!appEntryComplete) return StageSnapshot(goalMode, Stage.APP_ENTRY, false, false, true, "open_app", "app_entry_not_confirmed")
        if (goalMode == GoalMode.OPEN_ONLY) return StageSnapshot(goalMode, Stage.SUCCESS, true, true, false, "none", "open_only_goal_satisfied")

        val prompt = KaiScreenStateParser.normalize(userPrompt)
        val wantsConversation = listOf("conversation", "thread", "chat with", "message to", "dm to", "محادثة").any { prompt.contains(it) }
        val wantsMessages = listOf("message", "messages", "chat", "dm", "رسائل", "محادث").any { prompt.contains(it) }
        val wantsNote = appHint == "notes" || prompt.contains("note") || prompt.contains("ملاحظ")
        val wantsMedia = listOf("play", "watch", "شغل", "شاهد").any { prompt.contains(it) }
        val strongForExpected = currentState.isKaiLiveStrong(expectedPackage)

        val finalGoalComplete = when {
            wantsConversation -> usableForApp && (currentState.isChatThreadScreen() || currentState.isChatComposerSurface())
            wantsMessages -> usableForApp && currentState.isChatListScreen()
            wantsNote -> usableForApp && (currentState.isNotesEditorSurface() || currentState.isNotesBodyInputSurface() || currentState.isNotesTitleInputSurface())
            wantsMedia -> usableForApp && (currentState.isDetailSurface() || currentState.isPlayerSurface())
            else -> usableForApp && strongForExpected
        }

        if (finalGoalComplete) return StageSnapshot(goalMode, Stage.SUCCESS, true, true, false, "none", "stage_goal_satisfied")

        return when {
            wantsMessages && !currentState.isChatListScreen() && !currentState.isChatThreadScreen() && !currentState.isChatComposerSurface() ->
                StageSnapshot(goalMode, Stage.REACH_MESSAGES_SURFACE, true, false, true, "open_messages", "messages_surface_not_reached")
            wantsConversation && !currentState.isChatThreadScreen() && !currentState.isChatComposerSurface() ->
                StageSnapshot(goalMode, Stage.OPEN_TARGET_CONVERSATION, true, false, true, "open_target_conversation", "conversation_not_opened")
            wantsNote && !(currentState.isNotesEditorSurface() || currentState.isNotesBodyInputSurface() || currentState.isNotesTitleInputSurface()) ->
                StageSnapshot(goalMode, Stage.REACH_NOTE_EDITOR, true, false, true, "open_note_editor", "note_editor_not_ready")
            wantsMedia && !(currentState.isDetailSurface() || currentState.isPlayerSurface()) ->
                StageSnapshot(goalMode, Stage.OPEN_MEDIA, true, false, true, "open_first_media", "media_not_opened")
            else -> StageSnapshot(goalMode, Stage.GENERAL_CONTINUATION, true, false, true, "verify_surface", "general_continuation")
        }
    }

    fun buildContinuationStep(stageSnapshot: StageSnapshot, userPrompt: String, currentState: KaiScreenState): KaiActionStep? {
        val appHint = KaiScreenStateParser.inferAppHint(userPrompt)
        val expectedPackage = KaiAppIdentityRegistry.primaryPackageForKey(appHint)
        return when (stageSnapshot.nextSemanticAction) {
            "open_app" -> KaiActionStep("open_app", text = appHint.ifBlank { userPrompt.trim() }, expectedPackage = expectedPackage, stageHint = "app_entry", completionBoundary = KaiGoalBoundary.APP_ENTRY, continuationKind = KaiContinuationKind.STAGE_CONTINUATION, allowsFinalCommit = stageSnapshot.goalMode == GoalMode.OPEN_ONLY, note = "stage_continuation_open_app")
            "open_messages" -> KaiActionStep("click_text", text = if (currentState.packageName.contains("whatsapp", true)) "chats" else "messages", expectedPackage = expectedPackage, expectedScreenKind = "chat_list", stageHint = "messages_surface", completionBoundary = KaiGoalBoundary.SURFACE_READY, continuationKind = KaiContinuationKind.STAGE_CONTINUATION, note = "stage_continuation_messages")
            "open_target_conversation" -> KaiActionStep("open_best_list_item", selectorRole = "chat_item", selectorText = extractConversationQuery(userPrompt), text = extractConversationQuery(userPrompt), expectedPackage = expectedPackage, expectedScreenKind = "chat_thread", stageHint = "conversation_open", completionBoundary = KaiGoalBoundary.ENTITY_OPENED, continuationKind = KaiContinuationKind.STAGE_CONTINUATION, note = "stage_continuation_conversation")
            "open_note_editor" -> KaiActionStep("open_best_list_item", selectorRole = "create_note", text = "new note", expectedPackage = expectedPackage, expectedScreenKind = "notes_editor", stageHint = "note_editor", completionBoundary = KaiGoalBoundary.SURFACE_READY, continuationKind = KaiContinuationKind.STAGE_CONTINUATION, note = "stage_continuation_note_editor")
            "open_first_media" -> KaiActionStep("open_best_list_item", selectorRole = "media_item", text = "", expectedPackage = expectedPackage, expectedScreenKind = "detail", stageHint = "media_open", completionBoundary = KaiGoalBoundary.ENTITY_OPENED, continuationKind = KaiContinuationKind.STAGE_CONTINUATION, note = "stage_continuation_media")
            "verify_surface" -> KaiActionStep("verify_state", expectedPackage = expectedPackage, stageHint = "verify_surface", completionBoundary = KaiGoalBoundary.SURFACE_READY, continuationKind = KaiContinuationKind.VERIFICATION, note = "stage_verify_surface")
            else -> null
        }
    }

    private fun extractConversationQuery(prompt: String): String {
        val normalized = KaiScreenStateParser.normalize(prompt)
        val markers = listOf("chat with", "message to", "dm to", "conversation with", "محادثة", "رسالة الى", "رساله الى")
        val marker = markers.firstOrNull { normalized.contains(it) } ?: return ""
        val idx = normalized.indexOf(marker)
        if (idx < 0) return ""
        return prompt.substring((idx + marker.length).coerceAtMost(prompt.length)).trim()
    }
}
