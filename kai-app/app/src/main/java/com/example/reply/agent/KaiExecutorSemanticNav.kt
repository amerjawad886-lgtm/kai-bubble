package com.example.reply.agent

import com.example.reply.ui.KaiAccessibilityService

private suspend fun KaiActionExecutor.tapSemanticElement(element: KaiUiElement?, actionLabel: String): Boolean {
    if (element == null) return false
    val label = semanticLabel(element)
    if (label.isNotBlank()) {
        onLog("$actionLabel: click_text('$label')")
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
            text = label,
            preDelayMs = 70L
        )
        return true
    }
    val (cx, cy) = parseCenterFromBounds(element.bounds)
    if (cx != null && cy != null) {
        onLog("$actionLabel: tap_xy($cx,$cy)")
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = cx,
            y = cy,
            preDelayMs = 70L
        )
        return true
    }
    return false
}

private fun isUncertainForSemanticContinuation(state: KaiScreenState): Boolean {
    return state.isWeakObservation() ||
        state.isOverlayPolluted() ||
        state.isSearchLikeSurface() ||
        state.isSheetOrDialogSurface() ||
        (state.packageName.contains("instagram", true) && state.isInstagramCameraOverlaySurface())
}

internal suspend fun KaiActionExecutor.normalizeInstagramSurfaceImpl(
    state: KaiScreenState,
    preferThread: Boolean = false
): KaiActionExecutionResult {
    val current = state
    onLog("normalizeInstagramSurface: current=${current.screenKind}")

    if (!current.packageName.contains("instagram", true)) {
        return KaiActionExecutionResult(false, "normalization_failed_instagram_not_in_target_app", current)
    }

    if (current.isStrictVerifiedDmThreadSurface()) {
        return KaiActionExecutionResult(true, "normalized_instagram_dm_thread", current)
    }

    if (current.isInstagramDmListSurface()) {
        return KaiActionExecutionResult(true, "normalized_instagram_dm_list", current)
    }

    val beforeFingerprint = fingerprintFor(current.packageName, current.rawDump)
    val beforePackage = current.packageName
    val needsBackRecovery =
        current.isCameraOrMediaOverlaySurface() ||
            current.isInstagramSearchSurface() ||
            current.isSearchLikeSurface()

    if (needsBackRecovery) {
        onLog("normalizeInstagramSurface: action=single_back_from_overlay_or_search")
        sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_BACK, preDelayMs = 70L)
        return semanticPostVerifyImpl(
            actionName = "normalize_instagram_back",
            beforePackage = beforePackage,
            beforeFingerprint = beforeFingerprint,
            waitMs = 320L,
            timeoutMs = 2200L,
            commandIssued = true,
            verifyStep = KaiActionStep(cmd = "verify_state", expectedPackage = "com.instagram.android"),
            allowWeakSuccess = true
        )
    }

    val target = current.findMessagesEntry()
    if (current.isInstagramMessagesEntrySurface() || current.isInstagramFeedSurface() || target != null) {
        val issued = tapSemanticElement(target, "normalizeInstagramSurface")
        val verified = semanticPostVerifyImpl(
            actionName = "normalize_instagram_messages",
            beforePackage = beforePackage,
            beforeFingerprint = beforeFingerprint,
            waitMs = 380L,
            timeoutMs = 2400L,
            commandIssued = issued,
            verifyStep = KaiActionStep(cmd = "verify_state", expectedScreenKind = "instagram_dm_list"),
            allowWeakSuccess = false
        )
        val next = verified.screenState ?: current
        if (next.isInstagramDmListSurface() || (preferThread && next.isStrictVerifiedDmThreadSurface())) {
            return verified.copy(success = true, message = "normalized_instagram_surface", screenState = next)
        }
        return verified.copy(success = false, message = "normalization_failed_instagram_single_attempt", screenState = next)
    }

    return KaiActionExecutionResult(false, "normalization_failed_instagram_requires_explicit_reobserve", current)
}

internal suspend fun KaiActionExecutor.normalizeNotesSurfaceImpl(state: KaiScreenState): KaiActionExecutionResult {
    val current = state
    onLog("normalizeNotesSurface: current=${current.screenKind}")

    if (!current.packageName.contains("notes", true) && !current.packageName.contains("keep", true)) {
        return KaiActionExecutionResult(false, "normalization_failed_notes_not_in_target_app", current)
    }

    if (current.isStrictVerifiedNotesEditorSurface() || current.isNotesTitleInputSurface() || current.isNotesBodyInputSurface()) {
        return KaiActionExecutionResult(true, "normalized_notes_editor", current)
    }

    if (current.isNotesListSurface() || current.findCreateAction() != null) {
        return KaiActionExecutionResult(true, "normalized_notes_list", current)
    }

    if (!current.isSearchLikeSurface()) {
        return KaiActionExecutionResult(false, "normalization_failed_notes_requires_explicit_reobserve", current)
    }

    val beforeFingerprint = fingerprintFor(current.packageName, current.rawDump)
    val beforePackage = current.packageName
    onLog("normalizeNotesSurface: action=single_back_from_notes_search")
    sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_BACK, preDelayMs = 70L)
    return semanticPostVerifyImpl(
        actionName = "normalize_notes_back",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 320L,
        timeoutMs = 2200L,
        commandIssued = true,
        verifyStep = KaiActionStep(cmd = "verify_state", expectedPackage = "notes"),
        allowWeakSuccess = true
    )
}

internal suspend fun KaiActionExecutor.navigateToMessagesSurfaceImpl(state: KaiScreenState): KaiActionExecutionResult {
    onLog("Semantic navigation: navigateToMessagesSurface from surface=${state.screenKind}")

    val normalizedState = when {
        state.packageName.contains("instagram", true) -> {
            val normalized = normalizeInstagramSurfaceImpl(state, preferThread = false)
            val s = normalized.screenState ?: state
            if (!normalized.success) return normalized
            s
        }
        else -> {
            val family = KaiSurfaceModel.normalizeLegacyFamily(state.surfaceFamily())
            if (KaiSurfaceModel.isDeadEndFamily(family)) {
                val recovered = normalizeGeneralWorkingSurfaceImpl(state)
                recovered.screenState ?: state
            } else {
                state
            }
        }
    }

    if (normalizedState.isInstagramDmListSurface() || normalizedState.isChatListScreen()) {
        return KaiActionExecutionResult(
            success = true,
            message = "Already on messages/chat list surface",
            screenState = normalizedState
        )
    }

    if (normalizedState.isStrictVerifiedDmThreadSurface() || normalizedState.isChatThreadScreen()) {
        return KaiActionExecutionResult(
            success = true,
            message = "Already in conversation thread",
            screenState = normalizedState
        )
    }

    val current = normalizedState
    if (isUncertainForSemanticContinuation(current)) {
        return KaiActionExecutionResult(
            success = false,
            message = "observation_too_uncertain_for_messages_navigation",
            screenState = current
        )
    }

    val beforeFingerprint = fingerprintFor(current.packageName, current.rawDump)
    val beforePackage = current.packageName
    val isWhatsApp = current.packageName.contains("whatsapp", true)
    val target = current.findMessagesEntry()
    val issued = when {
        target != null -> tapSemanticElement(target, "navigateToMessagesSurface")
        current.isInstagramMessagesEntrySurface() || current.isContentFeedSurface() -> {
            sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_CLICK_TEXT, text = "messages", preDelayMs = 70L)
            true
        }
        isWhatsApp -> {
            sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_CLICK_TEXT, text = "chats", preDelayMs = 70L)
            true
        }
        else -> false
    }

    if (!issued) {
        return KaiActionExecutionResult(
            success = false,
            message = "no_direct_messages_action_available",
            screenState = current
        )
    }

    val verify = semanticPostVerifyImpl(
        actionName = "navigate_messages_surface",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 380L,
        timeoutMs = 2300L,
        commandIssued = true,
        verifyStep = KaiActionStep(
            cmd = "verify_state",
            expectedScreenKind = expectedListKindFor(current),
            selectorRole = "tab",
            selectorText = "messages"
        ),
        allowWeakSuccess = true
    )

    val candidate = verify.screenState ?: current
    return if (candidate.isInstagramDmListSurface() || candidate.isChatListScreen()) {
        verify.copy(success = true, message = "Reached messages/chat list surface", screenState = candidate)
    } else {
        verify.copy(success = false, message = "single_step_messages_navigation_failed", screenState = candidate)
    }
}

internal suspend fun KaiActionExecutor.navigateToConversationImpl(
    state: KaiScreenState,
    query: String
): KaiActionExecutionResult {
    onLog("Semantic navigation: navigateToConversation query='${query.trim()}' surface=${state.screenKind}")

    val normalizedState = when {
        state.packageName.contains("instagram", true) -> {
            val normalized = normalizeInstagramSurfaceImpl(state, preferThread = true)
            val s = normalized.screenState ?: state
            if (!normalized.success) return normalized
            s
        }
        else -> {
            val family = KaiSurfaceModel.normalizeLegacyFamily(state.surfaceFamily())
            if (KaiSurfaceModel.isDeadEndFamily(family)) {
                val recovered = normalizeGeneralWorkingSurfaceImpl(state)
                recovered.screenState ?: state
            } else {
                state
            }
        }
    }

    if (normalizedState.isStrictVerifiedDmThreadSurface() || normalizedState.isChatComposerSurface()) {
        return KaiActionExecutionResult(
            success = true,
            message = "Already on conversation thread",
            screenState = normalizedState
        )
    }

    val current = normalizedState
    if (isUncertainForSemanticContinuation(current)) {
        return KaiActionExecutionResult(
            success = false,
            message = "observation_too_uncertain_for_conversation_navigation",
            screenState = current
        )
    }

    if (!current.isInstagramDmListSurface() && !current.isChatListScreen()) {
        return KaiActionExecutionResult(
            success = false,
            message = "wrong_surface_for_conversation_open",
            screenState = current
        )
    }

    if (current.packageName.contains("instagram", true) && !KaiSurfaceModel.isVerifiedInstagramDmListSurface(current)) {
        return KaiActionExecutionResult(
            success = false,
            message = "wrong_surface_for_conversation_open",
            screenState = current
        )
    }

    val beforeFingerprint = fingerprintFor(current.packageName, current.rawDump)
    val beforePackage = current.packageName
    val target = current.findConversationCandidate(query)
    val label = target?.let { semanticLabel(it) }.orEmpty()
    val issued = when {
        label.isNotBlank() -> {
            sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_CLICK_TEXT, text = label, preDelayMs = 70L)
            true
        }
        target != null -> tapSemanticElement(target, "navigateToConversation")
        query.trim().isNotBlank() && current.findBestInputField("search") != null -> {
            sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_CLICK_TEXT, text = "search", preDelayMs = 70L)
            sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_TYPE, text = query.trim(), preDelayMs = 90L)
            true
        }
        else -> false
    }

    if (!issued) {
        return KaiActionExecutionResult(
            success = false,
            message = "ambiguous_conversation_target",
            screenState = current
        )
    }

    val verify = semanticPostVerifyImpl(
        actionName = "navigate_conversation",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 420L,
        timeoutMs = 2500L,
        commandIssued = true,
        verifyStep = KaiActionStep(
            cmd = "verify_state",
            expectedScreenKind = expectedThreadKindFor(current),
            selectorRole = "chat_item",
            selectorText = query
        ),
        allowWeakSuccess = true
    )

    val candidate = verify.screenState ?: current
    return if (candidate.isInstagramDmThreadSurface() || candidate.isChatThreadScreen() || candidate.isChatComposerSurface()) {
        verify.copy(success = true, message = "Reached conversation thread", screenState = candidate)
    } else {
        verify.copy(success = false, message = "single_step_conversation_navigation_failed", screenState = candidate)
    }
}

internal suspend fun KaiActionExecutor.navigateToWritableComposerImpl(state: KaiScreenState): KaiActionExecutionResult {
    onLog("Semantic navigation: navigateToWritableComposer from surface=${state.screenKind}")
    val normalizedState = when {
        state.packageName.contains("instagram", true) -> {
            val normalized = normalizeInstagramSurfaceImpl(state, preferThread = true)
            val s = normalized.screenState ?: state
            if (!normalized.success) {
                return KaiActionExecutionResult(
                    success = false,
                    message = "wrong_surface_for_composer",
                    screenState = s
                )
            }
            s
        }
        else -> {
            normalizeGeneralWorkingSurfaceImpl(state).screenState ?: state
        }
    }
    if (!normalizedState.isStrictVerifiedDmThreadSurface() && !normalizedState.isChatComposerSurface()) {
        return KaiActionExecutionResult(
            success = false,
            message = "wrong_surface_for_composer",
            screenState = normalizedState
        )
    }
    if (normalizedState.findBestInputField("message") != null && normalizedState.isChatComposerSurface()) {
        return KaiActionExecutionResult(
            success = true,
            message = "Composer already detected",
            screenState = normalizedState
        )
    }

    val beforeFingerprint = fingerprintFor(normalizedState.packageName, normalizedState.rawDump)
    val beforePackage = normalizedState.packageName
    val field = normalizedState.findBestInputField("message")

    val issued = if (field != null) {
        val label = semanticLabel(field)
        if (label.isNotBlank()) {
            onLog("navigateToWritableComposer: focus by label='$label'")
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = label,
                preDelayMs = 70L
            )
            true
        } else {
            val (cx, cy) = parseCenterFromBounds(field.bounds)
            if (cx != null && cy != null) {
                onLog("navigateToWritableComposer: focus by bounds tap")
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_TAP_XY,
                    x = cx,
                    y = cy,
                    preDelayMs = 70L
                )
                true
            } else {
                false
            }
        }
    } else {
        false
    }

    return semanticPostVerifyImpl(
        actionName = "navigate_writable_composer",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 560L,
        timeoutMs = 3200L,
        commandIssued = issued,
        verifyStep = KaiActionStep(
            cmd = "verify_state",
            expectedScreenKind = expectedThreadKindFor(normalizedState),
            selectorRole = "input",
            selectorHint = "message"
        ),
        allowWeakSuccess = true
    )
}

internal suspend fun KaiActionExecutor.openOrCreateNoteIfNeededImpl(state: KaiScreenState): KaiActionExecutionResult {
    onLog("Semantic navigation: openOrCreateNoteIfNeeded surface=${state.screenKind}")
    val normalized = normalizeNotesSurfaceImpl(state)
    val normalizedState = normalized.screenState ?: state
    if (!normalized.success) return normalized

    if (normalizedState.isStrictVerifiedNotesEditorSurface() || normalizedState.isNotesTitleInputSurface() || normalizedState.isNotesBodyInputSurface()) {
        return KaiActionExecutionResult(success = true, message = "Notes editor already open", screenState = normalizedState)
    }

    val beforeFingerprint = fingerprintFor(normalizedState.packageName, normalizedState.rawDump)
    val beforePackage = normalizedState.packageName
    val create = normalizedState.findCreateAction()

    val issued = if (create != null) {
        val label = semanticLabel(create)
        if (label.isNotBlank()) {
            onLog("openOrCreateNoteIfNeeded: winner='$label'")
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = label,
                preDelayMs = 70L
            )
            true
        } else {
            val (cx, cy) = parseCenterFromBounds(create.bounds)
            if (cx != null && cy != null) {
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_TAP_XY,
                    x = cx,
                    y = cy,
                    preDelayMs = 70L
                )
                true
            } else {
                false
            }
        }
    } else false

    return semanticPostVerifyImpl(
        actionName = "open_or_create_note",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 640L,
        timeoutMs = 3400L,
        commandIssued = issued,
        verifyStep = KaiActionStep(cmd = "verify_state", expectedScreenKind = "notes_editor"),
        allowWeakSuccess = true
    )
}

internal suspend fun KaiActionExecutor.focusNoteEditorIfNeededImpl(state: KaiScreenState): KaiActionExecutionResult {
    val normalized = normalizeNotesSurfaceImpl(state)
    val normalizedState = normalized.screenState ?: state
    if (!normalized.success) return normalized

    if (normalizedState.isNotesBodyInputSurface() || normalizedState.isNotesTitleInputSurface()) {
        return KaiActionExecutionResult(
            success = true,
            message = "Notes editor already focused",
            screenState = normalizedState
        )
    }

    val source = if (normalizedState.isNotesEditorSurface()) normalizedState else requestFreshScreen(2500L)
    val beforeFingerprint = fingerprintFor(source.packageName, source.rawDump)
    val beforePackage = source.packageName

    val targetInput = source.findBestInputField("body") ?: source.findBestInputField("title")
    val issued = if (targetInput != null) {
        val label = semanticLabel(targetInput)
        if (label.isNotBlank()) {
            onLog("focusNoteEditorIfNeeded: focusing '$label'")
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = label,
                preDelayMs = 70L
            )
            true
        } else {
            val (cx, cy) = parseCenterFromBounds(targetInput.bounds)
            if (cx != null && cy != null) {
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_TAP_XY,
                    x = cx,
                    y = cy,
                    preDelayMs = 70L
                )
                true
            } else {
                false
            }
        }
    } else {
        false
    }

    return semanticPostVerifyImpl(
        actionName = "focus_note_editor",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 560L,
        timeoutMs = 3000L,
        commandIssued = issued,
        verifyStep = KaiActionStep(cmd = "verify_state", expectedScreenKind = "notes_editor", selectorRole = "input"),
        allowWeakSuccess = true
    )
}

internal suspend fun KaiActionExecutor.normalizeGeneralWorkingSurfaceImpl(state: KaiScreenState): KaiActionExecutionResult {
    val current = state
    val family = KaiSurfaceModel.familyOf(current)
    val normalizedFamily = KaiSurfaceModel.normalizeLegacyFamily(family)
    if (KaiSurfaceModel.isPostOpenReadyFamily(normalizedFamily)) {
        return KaiActionExecutionResult(
            success = true,
            message = "normalized_general_surface:${KaiSurfaceModel.familyName(normalizedFamily)}",
            screenState = current
        )
    }

    if (normalizedFamily == KaiSurfaceFamily.SEARCH_SURFACE || normalizedFamily == KaiSurfaceFamily.BROWSER_LIKE_SURFACE) {
        return KaiActionExecutionResult(
            success = true,
            message = "normalized_general_intermediate:${KaiSurfaceModel.familyName(normalizedFamily)}",
            screenState = current
        )
    }

    if (KaiSurfaceModel.isRecoverableFamily(normalizedFamily) || normalizedFamily == KaiSurfaceFamily.LAUNCHER_SURFACE) {
        val beforeFingerprint = fingerprintFor(current.packageName, current.rawDump)
        val beforePackage = current.packageName
        sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_BACK, preDelayMs = 70L)
        return semanticPostVerifyImpl(
            actionName = "normalize_general_back",
            beforePackage = beforePackage,
            beforeFingerprint = beforeFingerprint,
            waitMs = 360L,
            timeoutMs = 2300L,
            commandIssued = true,
            verifyStep = KaiActionStep(cmd = "verify_state", expectedPackage = current.packageName),
            allowWeakSuccess = true
        )
    }

    return KaiActionExecutionResult(
        success = false,
        message = "normalization_failed_general_single_attempt",
        screenState = current
    )
}

internal suspend fun KaiActionExecutor.openFirstYouTubeMediaImpl(state: KaiScreenState): KaiActionExecutionResult {
    var current = if (state.packageName.contains("youtube", true)) state else requestFreshScreen(2600L)
    if (!current.packageName.contains("youtube", true)) {
        return KaiActionExecutionResult(success = false, message = "wrong_surface_for_open_list_item", screenState = current)
    }

    if (isUncertainForSemanticContinuation(current)) {
        current = requestFreshScreen(2800L, expectedPackage = current.packageName)
    }
    if (isUncertainForSemanticContinuation(current)) {
        return KaiActionExecutionResult(success = false, message = "observation_requires_reobserve_before_open_media", screenState = current)
    }
    if (current.isYouTubeWatchSurface() || current.isPlayerSurface()) {
        return KaiActionExecutionResult(success = true, message = "Already on YouTube watch/player surface", screenState = current)
    }
    if (!(current.isYouTubeBrowseSurface() || KaiSurfaceModel.isVerifiedYouTubeWorkingSurface(current) || current.isResultListSurface())) {
        return KaiActionExecutionResult(success = false, message = "wrong_surface_for_open_list_item", screenState = current)
    }

    val beforeFingerprint = fingerprintFor(current.packageName, current.rawDump)
    val beforePackage = current.packageName
    val target = current.findYouTubePlayableCandidate()
    if (target == null) {
        return KaiActionExecutionResult(success = false, message = "ambiguous_media_target_requires_better_observation", screenState = current)
    }

    val issued = tapSemanticElement(target, "openFirstYouTubeMedia")
    val verify = semanticPostVerifyImpl(
        actionName = "open_first_youtube_media",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 720L,
        timeoutMs = 3800L,
        commandIssued = issued,
        verifyStep = KaiActionStep(cmd = "verify_state", expectedScreenKind = "detail", expectedPackage = "com.google.android.youtube"),
        allowWeakSuccess = true
    )
    val candidate = verify.screenState ?: requestFreshScreen(3000L, expectedPackage = current.packageName)
    return if (candidate.isYouTubeWatchSurface() || candidate.isPlayerSurface() || candidate.isDetailSurface()) {
        verify.copy(success = true, message = "Opened first YouTube media intentionally", screenState = candidate)
    } else {
        verify.copy(success = false, message = "Failed to confirm YouTube watch/player surface", screenState = candidate)
    }
}
