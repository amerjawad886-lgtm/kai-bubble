package com.example.reply.agent

import com.example.reply.ui.KaiAccessibilityService
import kotlinx.coroutines.delay

private fun isWeakFallbackSelector(text: String): Boolean {
    val n = KaiScreenStateParser.normalize(text)
    if (n.length < 3) return true
    val weakTokens = setOf("open", "go", "next", "ok", "click", "press", "tap", "item", "chat", "message", "note", "new")
    return n in weakTokens
}

private fun KaiActionExecutor.isStrongComposerSurface(state: KaiScreenState): Boolean {
    return (state.isStrictVerifiedDmThreadSurface() || state.isChatComposerSurface()) &&
        state.findBestInputField("message") != null &&
        state.findSendAction() != null
}

private fun KaiActionExecutor.isStrongNotesEditorSurface(state: KaiScreenState): Boolean {
    return (state.isStrictVerifiedNotesEditorSurface() || state.isNotesTitleInputSurface() || state.isNotesBodyInputSurface()) &&
        state.findBestInputField("body") != null
}

private fun KaiActionExecutor.hasTypedEvidence(state: KaiScreenState, typedText: String): Boolean {
    val nText = KaiScreenStateParser.normalize(typedText)
    if (nText.isBlank()) return false
    val fields = state.elements.filter { it.editable || it.roleGuess in setOf("input", "editor") }
    return fields.any {
        val joined = KaiScreenStateParser.normalize(
            listOf(it.text, it.contentDescription, it.hint, it.viewId).joinToString(" ")
        )
        joined.contains(nText) || nText.contains(joined)
    }
}

private fun isCriticalSemanticAction(actionName: String): Boolean {
    return actionName.trim().lowercase() in setOf(
        "open_app",
        "open_best_list_item",
        "click_best_match",
        "focus_best_input",
        "input_into_best_field",
        "input_text",
        "press_primary_action",
        "press_send_if_possible",
        "write_into_composer",
        "write_into_note_editor",
        "verify_state"
    )
}

internal suspend fun KaiActionExecutor.clickSemanticTargetImpl(
    step: KaiActionStep,
    state: KaiScreenState,
    fallbackText: String = step.text
): Boolean {
    val element = selectSemanticElement(state, step)
    val label = element?.let { semanticLabel(it) }.orEmpty()

    if (label.isNotBlank()) {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
            text = label,
            preDelayMs = 70L
        )
        return true
    }

    if (fallbackText.isNotBlank() && !isWeakFallbackSelector(fallbackText)) {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
            text = fallbackText,
            preDelayMs = 70L
        )
        return true
    }

    val (cx, cy) = element?.let { parseCenterFromBounds(it.bounds) } ?: Pair(null, null)
    if (cx != null && cy != null) {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = cx,
            y = cy,
            preDelayMs = 70L
        )
        return true
    }

    if (step.x != null || step.y != null) {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_TAP_XY,
            x = step.x,
            y = step.y,
            preDelayMs = 70L
        )
        return true
    }

    return false
}

internal suspend fun KaiActionExecutor.semanticPostVerifyImpl(
    actionName: String,
    beforePackage: String,
    beforeFingerprint: String,
    waitMs: Long,
    timeoutMs: Long,
    commandIssued: Boolean,
    verifyStep: KaiActionStep? = null,
    allowWeakSuccess: Boolean = true
): KaiActionExecutionResult {
    delay(waitMs.coerceAtLeast(420L))
    val state = requestFreshScreen(timeoutMs.coerceIn(1800L, 9000L))
    val meta = getLastRefreshMeta()
    val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
    val afterPackage = state.packageName
    markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "$actionName verification")

    val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
    val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
    val meaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)
    val expectationHit = verifyStep?.let { expectedStateSatisfied(it, state) } ?: false
    val strongExpectationHit = expectationHit && !meta.reusedLastGood && !(meta.weak && meta.stale)
    val expectedPkg = verifyStep?.expectedPackage.orEmpty().trim()
    val wrongExpectedPackage = expectedPkg.isNotBlank() &&
        !KaiScreenStateParser.normalize(state.packageName).contains(KaiScreenStateParser.normalize(expectedPkg))

    onLog(
        "$actionName verify: surface=${state.screenKind}, pkg=${state.packageName}, changed=$meaningfulChange, weak=${meta.weak}, fallback=${meta.fallback}, reusedLastGood=${meta.reusedLastGood}"
    )

    return when {
        wrongExpectedPackage -> KaiActionExecutionResult(
            success = false,
            message = "$actionName wrong_context_package",
            screenState = state
        )

        strongExpectationHit -> KaiActionExecutionResult(
            success = true,
            message = "$actionName verified expected state",
            screenState = state
        )

        verifyStep != null && meaningfulChange && !meta.weak && !meta.fallback -> KaiActionExecutionResult(
            success = true,
            message = "$actionName made semantic progress before full expected evidence",
            screenState = state
        )

        verifyStep != null && meaningfulChange &&
            !meta.reusedLastGood &&
            !state.isOverlayPolluted() &&
            !state.isLauncher() -> KaiActionExecutionResult(
            success = true,
            message = "$actionName practical progress accepted",
            screenState = state
        )

        verifyStep != null -> KaiActionExecutionResult(
            success = false,
            message = "$actionName expected evidence not satisfied",
            screenState = state
        )

        meaningfulChange -> KaiActionExecutionResult(
            success = true,
            message = "$actionName produced visible progress",
            screenState = state
        )

        allowWeakSuccess &&
            !isCriticalSemanticAction(actionName) &&
            !meta.fallback &&
            !meta.reusedLastGood &&
            (commandIssued || meta.weak) -> KaiActionExecutionResult(
            success = true,
            message = "$actionName produced weak/no verified progress, continuing",
            screenState = state
        )

        else -> KaiActionExecutionResult(
            success = false,
            message = "$actionName failed to produce progress | weak_surface_evidence",
            screenState = state
        )
    }
}

internal suspend fun KaiActionExecutor.writeIntoBestComposerImpl(text: String): KaiActionExecutionResult {
    val clean = text.trim()
    val baseState = requestFreshScreen(2600L)
    if (clean.isBlank()) {
        return KaiActionExecutionResult(
            success = false,
            message = "writeIntoBestComposer: empty payload",
            screenState = baseState
        )
    }

    val nav = navigateToWritableComposerImpl(baseState)
    val state = nav.screenState ?: baseState
    if (!isStrongComposerSurface(state)) {
        return KaiActionExecutionResult(
            success = false,
            message = "wrong_surface_for_composer",
            screenState = state
        )
    }
    val beforeFingerprint = fingerprintFor(state.packageName, state.rawDump)
    val beforePackage = state.packageName

    onLog("writeIntoBestComposer: typing payload length=${clean.length}")
    sendKaiCmdSuppressed(
        cmd = KaiAccessibilityService.CMD_TYPE,
        text = clean,
        preDelayMs = 60L
    )

    val typed = semanticPostVerifyImpl(
        actionName = "write_into_composer",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 640L,
        timeoutMs = 3200L,
        commandIssued = true,
        verifyStep = KaiActionStep(
            cmd = "verify_state",
            expectedScreenKind = expectedThreadKindFor(state),
            selectorRole = "input"
        )
    )

    val typedState = typed.screenState ?: state
    if (!typed.success || !hasTypedEvidence(typedState, clean)) {
        return KaiActionExecutionResult(
            success = false,
            message = "composer_text_not_verified",
            screenState = typedState
        )
    }
    return if (typed.success && typedState.findSendAction() != null) {
        onLog("writeIntoBestComposer: send action visible after typing, pressing send directly")
        val sent = pressSendIfPossibleImpl()
        if (sent.success) sent else typed
    } else {
        typed
    }
}

internal suspend fun KaiActionExecutor.pressSendIfPossibleImpl(): KaiActionExecutionResult {
    val state = requestFreshScreen(2500L)
    if (!isStrongComposerSurface(state)) {
        return KaiActionExecutionResult(
            success = false,
            message = "wrong_surface_for_send",
            screenState = state
        )
    }
    val beforeFingerprint = fingerprintFor(state.packageName, state.rawDump)
    val beforePackage = state.packageName

    val send = state.findSendAction()
    val issued = if (send != null) {
        val label = semanticLabel(send)
        if (label.isNotBlank()) {
            onLog("pressSendIfPossible: clicking send candidate='$label' role=${send.roleGuess}")
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = label,
                preDelayMs = 70L
            )
            true
        } else {
            val (cx, cy) = parseCenterFromBounds(send.bounds)
            if (cx != null && cy != null) {
                onLog("pressSendIfPossible: using bounds fallback tap")
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

    val verify = semanticPostVerifyImpl(
        actionName = "press_send_if_possible",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 680L,
        timeoutMs = 3400L,
        commandIssued = issued,
        verifyStep = KaiActionStep(cmd = "verify_state", selectorRole = "send_button"),
        allowWeakSuccess = false
    )
    val after = verify.screenState ?: state
    return if (verify.success && !after.findSendAction().let { it != null && fingerprintFor(after.packageName, after.rawDump) == beforeFingerprint }) {
        verify.copy(message = "press_send_if_possible verified post-send change")
    } else if (verify.success) {
        verify.copy(success = false, message = "send_post_state_not_confirmed", screenState = after)
    } else {
        verify
    }
}

internal suspend fun KaiActionExecutor.writeIntoBestNoteEditorImpl(text: String): KaiActionExecutionResult {
    val clean = text.trim()
    val baseState = requestFreshScreen(2600L)
    if (clean.isBlank()) {
        return KaiActionExecutionResult(
            success = false,
            message = "writeIntoBestNoteEditor: empty payload",
            screenState = baseState
        )
    }

    val open = openOrCreateNoteIfNeededImpl(baseState)
    val focused = focusNoteEditorIfNeededImpl(open.screenState ?: baseState)
    val state = focused.screenState ?: open.screenState ?: baseState
    if (!isStrongNotesEditorSurface(state)) {
        return KaiActionExecutionResult(
            success = false,
            message = "wrong_surface_for_create_note",
            screenState = state
        )
    }

    val beforeFingerprint = fingerprintFor(state.packageName, state.rawDump)
    val beforePackage = state.packageName

    onLog("writeIntoBestNoteEditor: typing payload length=${clean.length}")
    sendKaiCmdSuppressed(
        cmd = KaiAccessibilityService.CMD_TYPE,
        text = clean,
        preDelayMs = 60L
    )

    val verified = semanticPostVerifyImpl(
        actionName = "write_into_note_editor",
        beforePackage = beforePackage,
        beforeFingerprint = beforeFingerprint,
        waitMs = 650L,
        timeoutMs = 3200L,
        commandIssued = true,
        verifyStep = KaiActionStep(
            cmd = "verify_state",
            expectedScreenKind = "notes_editor",
            selectorRole = "input"
        )
    )
    val after = verified.screenState ?: state
    return if (verified.success && hasTypedEvidence(after, clean)) {
        verified
    } else if (verified.success) {
        verified.copy(success = false, message = "note_text_not_verified", screenState = after)
    } else {
        verified
    }
}
