package com.example.reply.agent

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
                note = "stage_continuation"
            )

            "open_messages" -> KaiActionStep(
                cmd = "click_best_match",
                selectorRole = "tab",
                selectorText = "messages",
                text = "messages",
                expectedPackage = expectedPackage,
                expectedScreenKind = if (appHint == "instagram") "instagram_dm_list" else "chat_list",
                strategy = "stage_continuation_navigate_messages",
                note = "stage_continuation"
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
                note = "stage_continuation"
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
                        note = "stage_continuation"
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
                        note = "stage_continuation"
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