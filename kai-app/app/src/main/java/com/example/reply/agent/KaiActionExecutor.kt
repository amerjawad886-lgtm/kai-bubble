package com.example.reply.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.reply.ui.KaiAccessibilityService
import com.example.reply.ui.KaiBubbleManager
import com.example.reply.ui.KaiCommandParser
import kotlinx.coroutines.delay
import java.util.Locale

class KaiActionExecutor(
    internal val context: Context,
    internal val onLog: (String) -> Unit = {}
) {
    internal var canonicalRuntimeState: KaiScreenState? = null
    internal var lastGoodScreenState: KaiScreenState? = null
    internal var lastAcceptedFingerprint: String = ""
    internal var lastAcceptedObservationAt: Long = 0L
    internal var consecutiveWeakReads: Int = 0
    internal var consecutiveStaleReads: Int = 0
    internal var consecutiveNoProgressActions: Int = 0
    internal var lastRecoveryContextKey: String = ""
    internal var repeatedRecoveryContextCount: Int = 0

    data class ScreenRefreshMeta(
        val fingerprint: String,
        val changedFromPrevious: Boolean,
        val usable: Boolean,
        val fallback: Boolean,
        val weak: Boolean,
        val stale: Boolean,
        val reusedLastGood: Boolean
    )

    data class ObservationGateResult(
        val passed: Boolean,
        val state: KaiScreenState,
        val reason: String
    )

    data class ObservationReadinessResult(
        val passed: Boolean,
        val state: KaiScreenState,
        val reason: String,
        val attempts: Int
    )

    private data class LauncherAppCandidate(
        val label: String,
        val normalizedLabel: String,
        val packageHint: String,
        val x: Float?,
        val y: Float?,
        val clickable: Boolean,
        val source: String,
        val confidence: Int,
        val signature: String
    )

    enum class ObservationGateTier {
        APP_LAUNCH_SAFE,
        SEMANTIC_ACTION_SAFE
    }

    internal var lastRefreshMeta = ScreenRefreshMeta(
        fingerprint = "",
        changedFromPrevious = false,
        usable = false,
        fallback = false,
        weak = false,
        stale = false,
        reusedLastGood = false
    )

    fun resetRuntimeState(clearLastGoodScreen: Boolean = true) {
        canonicalRuntimeState = null
        lastAcceptedFingerprint = ""
        lastAcceptedObservationAt = 0L
        consecutiveWeakReads = 0
        consecutiveStaleReads = 0
        consecutiveNoProgressActions = 0
        lastRecoveryContextKey = ""
        repeatedRecoveryContextCount = 0
        lastRefreshMeta = ScreenRefreshMeta(
            fingerprint = "",
            changedFromPrevious = false,
            usable = false,
            fallback = false,
            weak = false,
            stale = false,
            reusedLastGood = false
        )
        if (clearLastGoodScreen) {
            lastGoodScreenState = null
        }
    }

    internal fun softResetObservationState() {
        softResetObservationStateImpl()
    }

    internal fun adoptCanonicalRuntimeState(state: KaiScreenState) {
        canonicalRuntimeState = state
        KaiAgentController.mirrorRuntimeObservation(state)
    }

    fun getCanonicalRuntimeState(): KaiScreenState? = canonicalRuntimeState

    internal fun resolveCanonicalRuntimeState(): KaiScreenState {
        return canonicalRuntimeState ?: lastGoodScreenState ?: KaiAgentController.getLatestScreenState()
    }

    private fun canonicalBeforePackage(): String {
        return canonicalRuntimeState?.packageName ?: lastGoodScreenState?.packageName.orEmpty()
    }

    private fun canonicalBeforeFingerprint(): String {
        return canonicalRuntimeState?.let { fingerprintFor(it.packageName, it.rawDump) } ?: lastAcceptedFingerprint
    }

    private fun recoveryContextKey(step: KaiActionStep, state: KaiScreenState): String {
        return KaiRecoveryPolicy.recoveryContextSemanticKey(step, state)
    }

    private suspend fun applyRecoveryDecision(
        state: KaiScreenState,
        step: KaiActionStep,
        decision: KaiRecoveryPolicy.RecoveryDecision
    ): KaiActionExecutionResult {
        return when (decision.recommendedAction) {
            KaiRecoveryAction.BACK, KaiRecoveryAction.DISMISS_SHEET -> {
                sendKaiCmdSuppressed(cmd = KaiAccessibilityService.CMD_BACK, preDelayMs = 70L)
                KaiActionExecutionResult(
                    success = true,
                    message = "recovery_back:${decision.reason}",
                    screenState = requestFreshScreen(2600L)
                )
            }

            KaiRecoveryAction.RETURN_TO_LIST -> {
                val fallback = if (state.packageName.contains("whatsapp", true)) "chats" else "messages"
                val issued = clickSemanticTarget(
                    step = KaiActionStep(
                        cmd = "click_best_match",
                        selectorRole = "tab",
                        selectorText = fallback,
                        text = fallback
                    ),
                    state = state,
                    fallbackText = fallback
                )
                KaiActionExecutionResult(
                    success = issued,
                    message = "recovery_return_to_list:${decision.reason}",
                    screenState = requestFreshScreen(2400L)
                )
            }

            KaiRecoveryAction.RETURN_HOME_TAB -> {
                val issued = clickSemanticTarget(
                    step = KaiActionStep(
                        cmd = "click_best_match",
                        selectorRole = "tab",
                        selectorText = "home",
                        text = "home"
                    ),
                    state = state,
                    fallbackText = "home"
                )
                val refreshed = requestFreshScreen(2600L)
                KaiActionExecutionResult(
                    success = issued,
                    message = "recovery_home_tab:${decision.reason}",
                    screenState = refreshed
                )
            }

            KaiRecoveryAction.OPEN_SEARCH -> {
                val issued = clickSemanticTarget(
                    step = KaiActionStep(
                        cmd = "click_best_match",
                        selectorRole = "search_field",
                        selectorText = step.selectorText.ifBlank { "search" },
                        text = step.text.ifBlank { "search" }
                    ),
                    state = state,
                    fallbackText = "search"
                )
                KaiActionExecutionResult(
                    success = issued,
                    message = "recovery_open_search:${decision.reason}",
                    screenState = requestFreshScreen(2600L)
                )
            }

            KaiRecoveryAction.NORMALIZE_APP_SURFACE -> recoverWrongSurfaceForStep(state, step)

            KaiRecoveryAction.REQUEST_FRESH_SCREEN, KaiRecoveryAction.NONE -> {
                KaiActionExecutionResult(
                    success = false,
                    message = "replan_required:no_clear_recovery:${decision.reason}",
                    screenState = state
                )
            }

            KaiRecoveryAction.BREAK_FOR_REPLAN -> {
                KaiActionExecutionResult(
                    success = false,
                    message = "replan_required:${decision.reason}",
                    screenState = state
                )
            }
        }
    }

    suspend fun attemptRecoveryForStep(step: KaiActionStep, state: KaiScreenState): KaiActionExecutionResult {
        val decision = KaiRecoveryPolicy.shouldRecoverForStep(state, step)
        if (!decision.needsRecovery) {
            return KaiActionExecutionResult(
                success = false,
                message = "replan_required:no_recovery_needed",
                screenState = state
            )
        }

        val key = recoveryContextKey(step, state)
        repeatedRecoveryContextCount = if (key == lastRecoveryContextKey) {
            repeatedRecoveryContextCount + 1
        } else {
            1
        }
        lastRecoveryContextKey = key

        if (repeatedRecoveryContextCount > 1) {
            return KaiActionExecutionResult(
                success = false,
                message = "replan_required:recovery_repeat_blocked",
                screenState = state
            )
        }

        return applyRecoveryDecision(state, step, decision)
    }

    private fun sendKaiCmd(
        cmd: String,
        text: String = "",
        expectedPackage: String = "",
        dir: String = "",
        times: Int = 1,
        x: Float? = null,
        y: Float? = null,
        endX: Float? = null,
        endY: Float? = null,
        holdMs: Long? = null,
        timeoutMs: Long? = null
    ) {
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, cmd)
            putExtra(KaiAccessibilityService.EXTRA_TEXT, text)
            if (expectedPackage.isNotBlank()) {
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
            }
            putExtra(KaiAccessibilityService.EXTRA_TIMES, times.coerceIn(1, 10))

            if (dir.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_DIR, dir)
            if (x != null) putExtra(KaiAccessibilityService.EXTRA_X, x)
            if (y != null) putExtra(KaiAccessibilityService.EXTRA_Y, y)
            if (endX != null) putExtra(KaiAccessibilityService.EXTRA_END_X, endX)
            if (endY != null) putExtra(KaiAccessibilityService.EXTRA_END_Y, endY)
            if (holdMs != null) putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, holdMs)
            if (timeoutMs != null) putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, timeoutMs)
        }
        context.sendBroadcast(intent)
    }

    suspend fun resetObservationTransitionStateForRun() {
        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_RESET_TRANSITION_STATE,
            preDelayMs = 20L,
            postDelayMs = 20L
        )
    }

    internal suspend fun sendKaiCmdSuppressed(
        cmd: String,
        text: String = "",
        expectedPackage: String = "",
        dir: String = "",
        times: Int = 1,
        x: Float? = null,
        y: Float? = null,
        endX: Float? = null,
        endY: Float? = null,
        holdMs: Long? = null,
        timeoutMs: Long? = null,
        preDelayMs: Long = 70L,
        postDelayMs: Long = 0L,
        strongObservationMode: Boolean = false
    ) {
        KaiBubbleManager.beginActionUiSuppression(strongObservationMode)
        try {
            delay(preDelayMs)
            sendKaiCmd(
                cmd = cmd,
                text = text,
                expectedPackage = expectedPackage,
                dir = dir,
                times = times,
                x = x,
                y = y,
                endX = endX,
                endY = endY,
                holdMs = holdMs,
                timeoutMs = timeoutMs
            )
            if (postDelayMs > 0L) delay(postDelayMs)
        } finally {
            KaiBubbleManager.endActionUiSuppression(strongObservationMode)
        }
    }

    private fun isOverlayPolluted(raw: String): Boolean {
        return isOverlayPollutedImpl(raw)
    }

    private fun isBaseDumpValid(raw: String, packageName: String = ""): Boolean {
        return isBaseDumpValidImpl(raw, packageName)
    }

    private fun isUsableDump(raw: String, packageName: String = ""): Boolean {
        return isUsableDumpImpl(raw, packageName)
    }

    private fun isFallbackAcceptable(raw: String, packageName: String = ""): Boolean {
        return isFallbackAcceptableImpl(raw, packageName)
    }

    private fun isWeakButMeaningfulDump(raw: String, packageName: String = ""): Boolean {
        return isWeakButMeaningfulDumpImpl(raw, packageName)
    }

    internal fun fingerprintFor(packageName: String, rawDump: String): String {
        return KaiScreenStateParser
            .fromDump(packageName = packageName, dump = rawDump)
            .semanticFingerprint()
            .take(5000)
    }

    internal fun sameFingerprint(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return a == b
    }

    private fun inferAppHintFromText(text: String): String {
        return KaiScreenStateParser.inferAppHint(text)
    }

    internal fun isExternalPackageChange(before: String, after: String): Boolean {
        if (before.isBlank() || after.isBlank()) return false
        if (before == context.packageName || after == context.packageName) return false
        return before != after
    }

    private fun normalizeQueryText(text: String): String {
        return text
            .trim()
            .lowercase(Locale.getDefault())
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .replace("ؤ", "و")
            .replace("ئ", "ي")
            .replace("ـ", "")
            .replace(Regex("""[\p{Punct}&&[^@._-]]+"""), " ")
            .replace(Regex("\\s+"), " ")
    }

    private fun clickTextVariants(text: String): List<String> {
        val normalized = normalizeQueryText(text)
        val tokens = normalized.split(" ").map { it.trim() }.filter { it.isNotBlank() }

        val broadCandidates = mutableSetOf<String>()
        if (normalized.isNotBlank()) broadCandidates.add(normalized)
        if (text.isNotBlank()) broadCandidates.add(text.trim())

        tokens.forEach { token ->
            if (token.length >= 2) broadCandidates.add(token)
        }

        if (tokens.size > 1) {
            broadCandidates.add(tokens.first())
            broadCandidates.add(tokens.last())
            broadCandidates.add(tokens.joinToString(" "))
        }

        return broadCandidates
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.getDefault())
        return p.contains("launcher") || p.contains("home") || p.contains("pixel") || p.contains("trebuchet")
    }

    private fun hasLauncherIconForTarget(state: KaiScreenState, target: String): Boolean {
        if (target.isBlank()) return false
        if (!isLauncherPackage(state.packageName)) return false
        return state.containsText(target) || state.containsText(inferAppHintFromText(target))
    }

    private fun launcherAliasVariants(rawTarget: String, inferredHint: String = ""): List<String> {
        val normalizedTarget = KaiScreenStateParser.normalize(rawTarget)
        val hint = KaiScreenStateParser.normalize(if (inferredHint.isNotBlank()) inferredHint else inferAppHintFromText(rawTarget))
        val canonical = KaiScreenStateParser.normalize(KaiCommandParser.resolveAppAlias(rawTarget))

        val aliases = linkedSetOf<String>()
        if (normalizedTarget.isNotBlank()) aliases += normalizedTarget
        if (canonical.isNotBlank()) aliases += canonical
        if (hint.isNotBlank()) aliases += hint
        aliases += KaiScreenStateParser.appAliasesForHint(hint)
        KaiAppIdentityRegistry.launcherLabelsForKey(hint).forEach { aliases += KaiScreenStateParser.normalize(it) }
        KaiAppIdentityRegistry.launcherLabelsForKey(canonical).forEach { aliases += KaiScreenStateParser.normalize(it) }

        if (normalizedTarget.isNotBlank()) {
            normalizedTarget.split(" ")
                .map { it.trim() }
                .filter { it.length >= 3 }
                .forEach { aliases += it }
        }

        return aliases.filter { it.isNotBlank() }
    }

    private fun launcherPackageHint(element: KaiUiElement): String {
        val joined = listOf(element.packageName, element.viewId, element.text, element.contentDescription)
            .joinToString(" ")
        return KaiScreenStateParser.normalize(joined)
    }

    private fun isLauncherSurfaceCoherent(state: KaiScreenState): Boolean {
        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        if (!state.isLauncher() && family != KaiSurfaceFamily.LAUNCHER_SURFACE) return false
        return state.elements.size >= 2 || state.lines.size >= 3
    }

    private fun extractVisibleLauncherCandidates(
        state: KaiScreenState,
        rawTarget: String,
        inferredHint: String,
        targetPackage: String
    ): List<LauncherAppCandidate> {
        if (!isLauncherSurfaceCoherent(state)) return emptyList()

        val variants = launcherAliasVariants(rawTarget, inferredHint)
        val normalizedPkgTarget = KaiScreenStateParser.normalize(targetPackage)

        val fromElements = state.elements.mapNotNull { element ->
            val label = semanticLabel(element)
            val normalizedLabel = KaiScreenStateParser.normalize(label)
            val pkgHint = launcherPackageHint(element)
            if (normalizedLabel.isBlank() && pkgHint.isBlank()) return@mapNotNull null

            val labelExact = variants.any { alias -> normalizedLabel == alias }
            val labelContains = variants.any { alias ->
                normalizedLabel.contains(alias) || alias.contains(normalizedLabel)
            }
            val labelLoose = variants.any { alias ->
                KaiScreenStateParser.isLooseTextMatch(normalizedLabel, alias)
            }
            val packageHintMatch =
                normalizedPkgTarget.isNotBlank() &&
                    (pkgHint.contains(normalizedPkgTarget) || normalizedPkgTarget.contains(pkgHint))

            var confidence = 0
            if (labelExact) confidence += 90
            if (labelContains) confidence += 56
            if (labelLoose) confidence += 26
            if (packageHintMatch) confidence += 72
            if (element.clickable) confidence += 12
            if (element.roleGuess in setOf("button", "image_button", "list_item", "chat_item")) confidence += 8
            if (element.bounds.isNotBlank()) confidence += 6

            val (cx, cy) = parseCenterFromBounds(element.bounds)
            val signature = KaiScreenStateParser.normalize("$normalizedLabel|$pkgHint|${element.bounds}")

            LauncherAppCandidate(
                label = label,
                normalizedLabel = normalizedLabel,
                packageHint = pkgHint,
                x = cx,
                y = cy,
                clickable = element.clickable,
                source = "element",
                confidence = confidence,
                signature = signature
            )
        }

        val fromLines = state.lines
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val normalizedLine = KaiScreenStateParser.normalize(line)
                var confidence = 0
                if (variants.any { alias -> normalizedLine == alias }) confidence += 70
                if (variants.any { alias -> normalizedLine.contains(alias) || alias.contains(normalizedLine) }) confidence += 42
                if (variants.any { alias -> KaiScreenStateParser.isLooseTextMatch(normalizedLine, alias) }) confidence += 18
                LauncherAppCandidate(
                    label = line,
                    normalizedLabel = normalizedLine,
                    packageHint = "",
                    x = null,
                    y = null,
                    clickable = false,
                    source = "line",
                    confidence = confidence,
                    signature = normalizedLine
                )
            }
            .filter { it.confidence >= 52 }
            .toList()

        return (fromElements + fromLines)
            .filter { it.confidence > 0 }
            .sortedByDescending { it.confidence }
            .distinctBy { it.signature }
    }

    private suspend fun launchViaVisibleLauncherCandidate(
        launcherState: KaiScreenState,
        rawTarget: String,
        inferredHint: String,
        targetPackage: String,
        reason: String
    ): Pair<Boolean, String> {
        val candidates = extractVisibleLauncherCandidates(
            state = launcherState,
            rawTarget = rawTarget,
            inferredHint = inferredHint,
            targetPackage = targetPackage
        )
        val best = candidates.firstOrNull() ?: return false to "no_launcher_candidate"
        if (best.confidence < 62) return false to "low_confidence_launcher_candidate:${best.confidence}"

        onLog(
            "launcher_target[$reason]: label='${best.label.ifBlank { best.normalizedLabel }}' conf=${best.confidence} src=${best.source}"
        )

        if (best.x != null && best.y != null) {
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_TAP_XY,
                x = best.x,
                y = best.y,
                preDelayMs = 70L
            )
            return true to "tap_xy"
        }

        val labelsToTry = linkedSetOf<String>()
        if (best.label.isNotBlank()) labelsToTry += best.label
        labelsToTry += launcherAliasVariants(rawTarget, inferredHint).take(4)

        labelsToTry.forEach { label ->
            if (label.isBlank()) return@forEach
            sendKaiCmdSuppressed(
                cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                text = label,
                preDelayMs = 65L
            )
        }

        return true to "click_text"
    }

    internal fun semanticLabel(element: KaiUiElement): String {
        return element.text.ifBlank {
            element.contentDescription.ifBlank {
                element.hint.ifBlank {
                    element.viewId
                }
            }
        }.trim()
    }

    internal fun parseCenterFromBounds(bounds: String): Pair<Float?, Float?> {
        val match = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(bounds.trim()) ?: return Pair(null, null)
        val left = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return Pair(null, null)
        val top = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: return Pair(null, null)
        val right = match.groupValues.getOrNull(3)?.toFloatOrNull() ?: return Pair(null, null)
        val bottom = match.groupValues.getOrNull(4)?.toFloatOrNull() ?: return Pair(null, null)
        return Pair((left + right) / 2f, (top + bottom) / 2f)
    }

    private fun roleMatches(element: KaiUiElement, selectorRole: String): Boolean {
        val role = KaiScreenStateParser.normalize(selectorRole)
        if (role.isBlank()) return true
        return KaiScreenStateParser.normalize(element.roleGuess) == role ||
            KaiScreenStateParser.normalize(element.roleGuess).contains(role)
    }

    private fun scoreElementForStep(element: KaiUiElement, step: KaiActionStep): Int {
        var score = 0
        val queryText = KaiScreenStateParser.normalize(step.selectorText.ifBlank { step.text })
        val selectorHint = KaiScreenStateParser.normalize(step.selectorHint)
        val selectorId = KaiScreenStateParser.normalize(step.selectorId)

        fun fieldScore(value: String, query: String, exact: Int, contains: Int): Int {
            val normalized = KaiScreenStateParser.normalize(value)
            if (query.isBlank() || normalized.isBlank()) return 0
            return when {
                normalized == query -> exact
                normalized.contains(query) || query.contains(normalized) -> contains
                KaiScreenStateParser.isLooseTextMatch(normalized, query) -> contains / 2
                else -> 0
            }
        }

        val textFields = listOf(element.text, element.contentDescription, element.hint, element.viewId)
        textFields.forEach { field ->
            score += fieldScore(field, queryText, exact = 80, contains = 40)
            score += fieldScore(field, selectorHint, exact = 40, contains = 20)
        }

        if (selectorId.isNotBlank()) {
            score += fieldScore(element.viewId, selectorId, exact = 90, contains = 45)
        }

        if (element.clickable) score += 20
        if (element.editable) score += 16
        if (element.roleGuess == "send_button") score += 18
        if (element.roleGuess == "create_button") score += 15
        if (roleMatches(element, step.selectorRole)) score += 24

        return score
    }

    internal fun selectSemanticElement(state: KaiScreenState, step: KaiActionStep): KaiUiElement? {
        val expectedPkg = KaiScreenStateParser.normalize(step.expectedPackage)
        if (expectedPkg.isNotBlank() && !KaiScreenStateParser.normalize(state.packageName).contains(expectedPkg)) {
            return null
        }

        val explicit = state.elements
            .filter { roleMatches(it, step.selectorRole) }
            .maxByOrNull { scoreElementForStep(it, step) }

        if (explicit != null && scoreElementForStep(explicit, step) >= 30) {
            return explicit
        }

        val query = step.selectorText.ifBlank { step.text }
        return when {
            step.selectorRole.equals("input", true) ||
                step.selectorRole.equals("editor", true) ||
                step.selectorRole.equals("search_field", true) -> state.findBestInputField(step.selectorHint.ifBlank { query })
            step.selectorRole.equals("send_button", true) -> state.findSendAction()
            step.selectorRole.equals("create_button", true) -> state.findCreateAction()
            step.selectorRole.equals("chat_item", true) -> state.findConversationCandidate(query)
            query.isNotBlank() -> state.findBestClickableTarget(query)
            else -> null
        }
    }

    internal fun expectedStateSatisfied(step: KaiActionStep, state: KaiScreenState): Boolean {
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
                "search" -> state.isSearchLikeSurface()
                "detail" -> state.isDetailSurface() || state.isPlayerSurface()
                else -> KaiScreenStateParser.normalize(state.screenKind) == expectedKind
            }
            if (!kindSatisfied) return false
        }

        if (step.expectedTexts.isNotEmpty()) {
            val allHit = step.expectedTexts.all { state.containsText(it) }
            if (!allHit) return false
        }

        return true
    }

    private fun hasMeaningfulProgress(
        beforePackage: String,
        afterPackage: String,
        beforeFingerprint: String,
        afterFingerprint: String,
        refreshMeta: ScreenRefreshMeta
    ): Boolean {
        val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
        val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
        val weakButChanged = refreshMeta.weak && refreshMeta.changedFromPrevious
        val externalObservation = afterPackage.isNotBlank() && afterPackage != context.packageName
        return packageChanged || fingerprintChanged || (weakButChanged && externalObservation)
    }

    private fun updateRefreshMeta(
        state: KaiScreenState,
        usable: Boolean,
        fallback: Boolean,
        weak: Boolean,
        previousFingerprint: String,
        observationUpdatedAt: Long,
        reusedLastGood: Boolean = false
    ) {
        updateRefreshMetaImpl(
            state = state,
            usable = usable,
            fallback = fallback,
            weak = weak,
            previousFingerprint = previousFingerprint,
            observationUpdatedAt = observationUpdatedAt,
            reusedLastGood = reusedLastGood
        )
    }

    fun getLastRefreshMeta(): ScreenRefreshMeta = getLastRefreshMetaImpl()
    fun getConsecutiveWeakReads(): Int = getConsecutiveWeakReadsImpl()
    fun getConsecutiveStaleReads(): Int = getConsecutiveStaleReadsImpl()

    private fun isExpectedPackageMatch(observedPackage: String, expectedPackage: String): Boolean {
        val observed = KaiScreenStateParser.normalize(observedPackage)
        val expected = KaiScreenStateParser.normalize(expectedPackage)
        if (expected.isBlank()) return true
        if (observed.isBlank()) return false
        return observed == expected || observed.startsWith("$expected.")
    }

    private fun evaluateObservationStrengthForSemantic(
        state: KaiScreenState,
        meta: ScreenRefreshMeta,
        expectedPackage: String,
        allowLauncherSurface: Boolean
    ): Pair<Boolean, String> {
        if (state.packageName.isBlank()) return false to "missing_package"
        if (!isExpectedPackageMatch(state.packageName, expectedPackage)) {
            return false to "expected_package_mismatch"
        }
        if (state.isOverlayPolluted()) return false to "overlay_polluted"

        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        val inExpectedApp = expectedPackage.isBlank() || isExpectedPackageMatch(state.packageName, expectedPackage)
        val coherentInAppContinuation =
            inExpectedApp &&
                !state.isLauncher() &&
                family in setOf(
                    KaiSurfaceFamily.LIST_SURFACE,
                    KaiSurfaceFamily.RESULT_LIST_SURFACE,
                    KaiSurfaceFamily.THREAD_SURFACE,
                    KaiSurfaceFamily.COMPOSER_SURFACE,
                    KaiSurfaceFamily.EDITOR_SURFACE,
                    KaiSurfaceFamily.DETAIL_SURFACE,
                    KaiSurfaceFamily.SEARCH_SURFACE,
                    KaiSurfaceFamily.TABBED_HOME_SURFACE,
                    KaiSurfaceFamily.CONTENT_FEED_SURFACE,
                    KaiSurfaceFamily.PLAYER_SURFACE,
                    KaiSurfaceFamily.APP_HOME_SURFACE,
                    KaiSurfaceFamily.BROWSER_LIKE_SURFACE
                )

        // Restore practical continuity: tolerate weak/stale-but-changed observations in coherent in-app continuation.
        if (coherentInAppContinuation && meta.changedFromPrevious && !meta.reusedLastGood) {
            return true to "in_app_continuation_tolerated"
        }

        if (state.isWeakObservation()) return false to "weak_observation"
        if (meta.weak) return false to "weak_refresh_meta"
        if (meta.fallback) return false to "fallback_observation"
        if (meta.reusedLastGood) return false to "reused_last_good_observation"
        if (meta.stale) return false to "stale_observation"
        if (state.semanticConfidence < 0.42f) return false to "low_semantic_confidence"
        if (state.elements.size < 2 && state.lines.size < 3) return false to "insufficient_semantic_structure"

        if (!allowLauncherSurface && family == KaiSurfaceFamily.LAUNCHER_SURFACE) {
            return false to "launcher_surface_not_semantic_ready"
        }
        if (family in setOf(
                KaiSurfaceFamily.UNKNOWN_SURFACE,
                KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE,
                KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE,
                KaiSurfaceFamily.SEARCH_SURFACE
            )
        ) {
            return false to "wrong_surface_family:${KaiSurfaceModel.familyName(family)}"
        }

        return true to "strong"
    }

    private fun isLauncherHomeCoherent(state: KaiScreenState): Boolean {
        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        return state.isLauncher() || family == KaiSurfaceFamily.LAUNCHER_SURFACE
    }

    private fun evaluateObservationStrengthForAppLaunch(
        state: KaiScreenState,
        meta: ScreenRefreshMeta,
        expectedPackage: String,
        allowLauncherSurface: Boolean
    ): Pair<Boolean, String> {
        if (state.packageName.isBlank()) return false to "missing_package"
        if (!isExpectedPackageMatch(state.packageName, expectedPackage)) {
            return false to "expected_package_mismatch"
        }
        if (state.isOverlayPolluted()) return false to "overlay_polluted"
        if (state.isWeakObservation()) return false to "weak_observation"
        if (meta.weak) return false to "weak_refresh_meta"
        if (meta.fallback) return false to "fallback_observation"
        if (meta.reusedLastGood) return false to "reused_last_good_observation"

        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        if (!allowLauncherSurface && family == KaiSurfaceFamily.LAUNCHER_SURFACE) {
            return false to "launcher_surface_not_semantic_ready"
        }

        // App launch from launcher/home is safe with stable screen snapshots.
        if (meta.stale && isLauncherHomeCoherent(state)) {
            return true to "app_launch_safe_launcher_coherent"
        }

        if (meta.stale) return false to "stale_observation"
        if (state.semanticConfidence < 0.28f) return false to "low_semantic_confidence"
        if (state.elements.isEmpty() && state.lines.size < 2) return false to "insufficient_app_launch_structure"

        return true to "app_launch_safe"
    }

    private fun evaluateObservationStrengthByTier(
        state: KaiScreenState,
        meta: ScreenRefreshMeta,
        expectedPackage: String,
        allowLauncherSurface: Boolean,
        tier: ObservationGateTier
    ): Pair<Boolean, String> {
        return when (tier) {
            ObservationGateTier.APP_LAUNCH_SAFE -> evaluateObservationStrengthForAppLaunch(
                state = state,
                meta = meta,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface
            )

            ObservationGateTier.SEMANTIC_ACTION_SAFE -> evaluateObservationStrengthForSemantic(
                state = state,
                meta = meta,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface
            )
        }
    }

    suspend fun ensureStrongObservationGate(
        expectedPackage: String = "",
        timeoutMs: Long = 2600L,
        maxAttempts: Int = 2,
        allowLauncherSurface: Boolean = false,
        tier: ObservationGateTier = ObservationGateTier.SEMANTIC_ACTION_SAFE,
        staleRetryAttempts: Int = 2,
        missingPackageRetryAttempts: Int = 2
    ): ObservationGateResult {
        var lastState = resolveCanonicalRuntimeState()
        var lastReason = "not_observed"

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val state = requestFreshScreen(timeoutMs = timeoutMs, expectedPackage = expectedPackage)
            val meta = getLastRefreshMeta()
            val (ok, reason) = evaluateObservationStrengthByTier(
                state = state,
                meta = meta,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface,
                tier = tier
            )

            lastState = state
            lastReason = reason

            if (ok) {
                return ObservationGateResult(
                    passed = true,
                    state = state,
                    reason = "strong_observation_gate_passed"
                )
            }

            if (reason == "stale_observation" && attempt == maxAttempts.coerceAtLeast(1) - 1) {
                repeat(staleRetryAttempts.coerceIn(1, 2)) {
                    delay(150L)
                    val retried = requestFreshScreen(
                        timeoutMs = (timeoutMs / 2).coerceIn(900L, 1800L),
                        expectedPackage = expectedPackage
                    )
                    val retryMeta = getLastRefreshMeta()
                    val (retryOk, retryReason) = evaluateObservationStrengthByTier(
                        state = retried,
                        meta = retryMeta,
                        expectedPackage = expectedPackage,
                        allowLauncherSurface = allowLauncherSurface,
                        tier = tier
                    )
                    lastState = retried
                    lastReason = retryReason
                    if (retryOk) {
                        return ObservationGateResult(
                            passed = true,
                            state = retried,
                            reason = "strong_observation_gate_passed_after_stale_retry"
                        )
                    }
                }
            }

            if (reason == "missing_package" && attempt == maxAttempts.coerceAtLeast(1) - 1) {
                repeat(missingPackageRetryAttempts.coerceIn(1, 2)) {
                    delay(150L)
                    val retried = requestFreshScreen(
                        timeoutMs = (timeoutMs / 2).coerceIn(900L, 1800L),
                        expectedPackage = expectedPackage
                    )
                    val retryMeta = getLastRefreshMeta()
                    val (retryOk, retryReason) = evaluateObservationStrengthByTier(
                        state = retried,
                        meta = retryMeta,
                        expectedPackage = expectedPackage,
                        allowLauncherSurface = allowLauncherSurface,
                        tier = tier
                    )
                    lastState = retried
                    lastReason = retryReason
                    if (retryOk) {
                        return ObservationGateResult(
                            passed = true,
                            state = retried,
                            reason = "strong_observation_gate_passed_after_missing_package_retry"
                        )
                    }
                }

                // Practical stabilization: if package was transiently blank but we recovered a coherent
                // non-launcher/non-overlay state, allow continuation and keep safety on destructive steps.
                val pragmaticPackageRecovery =
                    lastState.packageName.isNotBlank() &&
                        !lastState.isLauncher() &&
                        !lastState.isOverlayPolluted() &&
                        (expectedPackage.isBlank() || isExpectedPackageMatch(lastState.packageName, expectedPackage))
                if (pragmaticPackageRecovery) {
                    return ObservationGateResult(
                        passed = true,
                        state = lastState,
                        reason = "strong_observation_gate_passed_after_transient_missing_package"
                    )
                }
            }

            if (attempt < maxAttempts - 1) {
                delay(220L)
            }
        }

        return ObservationGateResult(
            passed = false,
            state = lastState,
            reason = "strong_observation_gate_failed:$lastReason"
        )
    }

    suspend fun ensureAuthoritativeObservationReady(
        timeoutMs: Long = 2600L,
        maxAttempts: Int = 3,
        allowLauncherSurface: Boolean = true,
        tier: ObservationGateTier = ObservationGateTier.APP_LAUNCH_SAFE
    ): ObservationReadinessResult {
        var lastState = resolveCanonicalRuntimeState()
        var lastReason = "not_observed"

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val gate = ensureStrongObservationGate(
                expectedPackage = "",
                timeoutMs = timeoutMs,
                maxAttempts = 1,
                allowLauncherSurface = allowLauncherSurface,
                tier = tier,
                staleRetryAttempts = 1,
                missingPackageRetryAttempts = 1
            )

            val state = gate.state
            val packageReady = state.packageName.isNotBlank()
            val notOverlayPolluted = !state.isOverlayPolluted()
            val meaningful = state.isMeaningful() &&
                !state.rawDump.equals("(semantic_observation_requires_retry)", ignoreCase = true)
            val strongEnough = !state.isWeakObservation()

            lastState = state

            if (gate.passed && packageReady && notOverlayPolluted && meaningful && strongEnough) {
                adoptCanonicalRuntimeState(state)
                return ObservationReadinessResult(
                    passed = true,
                    state = state,
                    reason = "authoritative_observation_ready",
                    attempts = attempt + 1
                )
            }

            lastReason = when {
                !packageReady -> "missing_package"
                !notOverlayPolluted -> "overlay_polluted"
                !meaningful -> "not_meaningful"
                !strongEnough -> "weak_observation"
                else -> gate.reason
            }

            if (attempt < maxAttempts - 1) {
                delay(180L)
            }
        }

        return ObservationReadinessResult(
            passed = false,
            state = lastState,
            reason = "authoritative_observation_not_ready:$lastReason",
            attempts = maxAttempts.coerceAtLeast(1)
        )
    }

    suspend fun requestFreshScreen(timeoutMs: Long = 2500L, expectedPackage: String = ""): KaiScreenState {
        return requestFreshScreenImpl(timeoutMs, expectedPackage)
    }

    internal fun markActionProgress(
        beforePackage: String,
        afterPackage: String,
        beforeFingerprint: String,
        afterFingerprint: String,
        message: String
    ) {
        markActionProgressImpl(
            beforePackage = beforePackage,
            afterPackage = afterPackage,
            beforeFingerprint = beforeFingerprint,
            afterFingerprint = afterFingerprint,
            message = message
        )
    }

    private fun mapAppNameToPackage(appName: String): String? {
        val canonical = KaiCommandParser.resolveAppAlias(appName).lowercase(Locale.ROOT)
        KaiAppIdentityRegistry.primaryPackageForKey(canonical)
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        return try {
            val pm = context.packageManager ?: return null
            val normalized = KaiScreenStateParser.normalize(canonical)

            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .mapNotNull { appInfo ->
                    val label = pm.getApplicationLabel(appInfo)?.toString().orEmpty()
                    Pair(appInfo.packageName.orEmpty(), KaiScreenStateParser.normalize(label))
                }
                .firstOrNull { (_, labelNorm) ->
                    labelNorm == normalized || labelNorm.contains(normalized) || normalized.contains(labelNorm)
                }
                ?.first
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTargetAppPackage(appName: String, launcherState: KaiScreenState?): String? {
        mapAppNameToPackage(appName)?.let { return it }

        if (launcherState != null && hasLauncherIconForTarget(launcherState, appName)) {
            val hint = KaiCommandParser.resolveAppAlias(appName).lowercase(Locale.ROOT)
            return mapAppNameToPackage(hint)
        }

        return null
    }

    private fun launchAppByPackage(packageName: String): Boolean {
        val pm = context.packageManager ?: return false
        return try {
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            onLog("Direct launch intent failed for $packageName: ${e.message}")
            false
        }
    }

    private fun weakNoProgressFailure(
        state: KaiScreenState,
        actionName: String
    ): KaiActionExecutionResult {
        return KaiActionExecutionResult(
            success = false,
            message = "$actionName produced weak/no verified progress",
            screenState = state
        )
    }

    private suspend fun verifyOpenAppInternal(
        rawTargetText: String,
        targetHint: String,
        targetPackage: String,
        beforeObservation: KaiObservation,
        beforeFingerprint: String,
        beforePackage: String
    ): KaiActionExecutionResult {
        var mostRecentState: KaiScreenState? = null
        var transitionObserved = false
        var wrongPackageSignals = 0
        var lastCredibleWrongPackage = ""
        var launcherStableCandidateStreak = 0
        var previousLauncherCandidateSignature = ""

        repeat(3) { attemptIdx ->
            if (attemptIdx > 0) delay(320L)

            // For open-app confirmation we must observe reality, not pre-filter by expected package.
            val state = requestFreshScreen(2200L + (attemptIdx * 280L), expectedPackage = "")
            mostRecentState = state

            if (isLauncherSurfaceCoherent(state)) {
                val launcherCandidates = extractVisibleLauncherCandidates(
                    state = state,
                    rawTarget = rawTargetText,
                    inferredHint = targetHint,
                    targetPackage = targetPackage
                )
                val bestLauncher = launcherCandidates.firstOrNull()
                if (bestLauncher != null && bestLauncher.confidence >= 62) {
                    if (bestLauncher.signature == previousLauncherCandidateSignature) {
                        launcherStableCandidateStreak += 1
                    } else {
                        previousLauncherCandidateSignature = bestLauncher.signature
                        launcherStableCandidateStreak = 1
                    }

                    val (issued, mode) = launchViaVisibleLauncherCandidate(
                        launcherState = state,
                        rawTarget = rawTargetText,
                        inferredHint = targetHint,
                        targetPackage = targetPackage,
                        reason = "verify_attempt_${attemptIdx + 1}"
                    )
                    if (issued) {
                        transitionObserved = true
                        onLog("open_app launcher retry dispatched via $mode (stable=$launcherStableCandidateStreak)")
                        delay(220L)
                    }
                }
            }

            val normalizedObserved = KaiScreenStateParser.normalize(state.packageName)
            val normalizedTarget = KaiScreenStateParser.normalize(targetPackage)
            val observedKnown = normalizedObserved.isNotBlank()
            val hintMatchesTarget = targetHint.isBlank() || state.likelyMatchesAppHint(targetHint)
            val packageMatchesTarget =
                normalizedTarget.isNotBlank() &&
                    (normalizedObserved == normalizedTarget || normalizedObserved.startsWith("$normalizedTarget."))

            val inTargetApp =
                if (normalizedTarget.isNotBlank()) {
                    packageMatchesTarget && hintMatchesTarget
                } else {
                    hintMatchesTarget && observedKnown && !isLauncherPackage(state.packageName)
                }

            val assessment = if (inTargetApp) {
                KaiSurfaceTransitionPolicy.assessCurrentSurface(
                    step = KaiActionStep(cmd = "open_app"),
                    state = state
                )
            } else {
                null
            }

            val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
            val movedOffLauncher =
                isLauncherPackage(beforePackage) && !isLauncherPackage(state.packageName) && state.packageName.isNotBlank()
            val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)

            if (inTargetApp && assessment != null) {
                val outcome = when (assessment.status) {
                    KaiSurfaceStatus.TARGET_READY -> KaiOpenAppOutcome.TARGET_READY
                    KaiSurfaceStatus.USABLE_INTERMEDIATE,
                    KaiSurfaceStatus.WRONG_BUT_RECOVERABLE -> KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
                    KaiSurfaceStatus.DEAD_END -> null
                }

                if (outcome != null) {
                    markActionProgress(
                        beforePackage,
                        state.packageName,
                        beforeFingerprint,
                        afterFingerprint,
                        "open_app verification"
                    )
                    return KaiActionExecutionResult(
                        success = true,
                        message = "open_app_classified:$outcome",
                        screenState = state,
                        openAppOutcome = outcome
                    )
                }
            }

            val credibleWrongPackage =
                normalizedTarget.isNotBlank() &&
                    observedKnown &&
                    !isLauncherPackage(state.packageName) &&
                    normalizedObserved != normalizedTarget &&
                    !normalizedObserved.startsWith("$normalizedTarget.")

            if (credibleWrongPackage) {
                if (normalizedObserved == lastCredibleWrongPackage) {
                    wrongPackageSignals += 1
                } else {
                    lastCredibleWrongPackage = normalizedObserved
                    wrongPackageSignals = 1
                }

                if (wrongPackageSignals >= 2) {
                    markActionProgress(
                        beforePackage,
                        state.packageName,
                        beforeFingerprint,
                        afterFingerprint,
                        "open_app verification"
                    )
                    return KaiActionExecutionResult(
                        success = false,
                        message = "open_app_classified:${KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED}:observed=${state.packageName}",
                        screenState = state,
                        openAppOutcome = KaiOpenAppOutcome.WRONG_PACKAGE_CONFIRMED
                    )
                }
            }

            // Unknown package is unconfirmed, not wrong. Keep it in transition if we saw motion or hint evidence.
            val unknownButHinted = !observedKnown && hintMatchesTarget
            if (movedOffLauncher || fingerprintChanged || unknownButHinted) {
                transitionObserved = true
            }
        }

        val terminalState = mostRecentState ?: resolveCanonicalRuntimeState()
        val terminalOutcome = if (transitionObserved) {
            KaiOpenAppOutcome.OPEN_TRANSITION_IN_PROGRESS
        } else {
            KaiOpenAppOutcome.OPEN_FAILED
        }

        markActionProgress(
            beforePackage,
            beforeObservation.packageName,
            beforeFingerprint,
            beforeFingerprint,
            "open_app verification"
        )

        return KaiActionExecutionResult(
            success = false,
            message = "open_app_classified:$terminalOutcome",
            screenState = terminalState,
            openAppOutcome = terminalOutcome
        )
    }

    private suspend fun clickSemanticTarget(
        step: KaiActionStep,
        state: KaiScreenState,
        fallbackText: String = step.text
    ): Boolean {
        return clickSemanticTargetImpl(step, state, fallbackText)
    }

    private suspend fun semanticPostVerify(
        actionName: String,
        beforePackage: String,
        beforeFingerprint: String,
        waitMs: Long,
        timeoutMs: Long,
        commandIssued: Boolean,
        verifyStep: KaiActionStep? = null,
        allowWeakSuccess: Boolean = true
    ): KaiActionExecutionResult {
        val result = semanticPostVerifyImpl(
            actionName = actionName,
            beforePackage = beforePackage,
            beforeFingerprint = beforeFingerprint,
            waitMs = waitMs,
            timeoutMs = timeoutMs,
            commandIssued = commandIssued,
            verifyStep = verifyStep,
            allowWeakSuccess = allowWeakSuccess
        )

        val meta = getLastRefreshMeta()
        val semanticContinuationActions = setOf(
            "click_best_match",
            "open_best_list_item",
            "focus_best_input",
            "input_into_best_field",
            "press_primary_action",
            "verify_state",
            "navigate_messages_surface",
            "navigate_conversation",
            "open_or_create_note",
            "focus_note_editor",
            "open_first_youtube_media"
        )
        if (actionName in semanticContinuationActions && (meta.weak || meta.fallback || meta.reusedLastGood)) {
            if (!result.success) {
                return result.copy(message = "$actionName failed_under_weak_observation")
            }
            return result.copy(message = "$actionName executed_with_guarded_weak_observation")
        }
        if (actionName in semanticContinuationActions && result.success) {
            return result.copy(message = "$actionName executed_on_strong_semantic_evidence")
        }
        return result
    }

    internal fun isMessagingAppPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        return p.contains("instagram") || p.contains("whatsapp") || p.contains("telegram") || p.contains("messag")
    }

    internal fun expectedThreadKindFor(state: KaiScreenState): String {
        return if (state.packageName.contains("instagram", true)) "instagram_dm_thread" else "chat_thread"
    }

    internal fun expectedListKindFor(state: KaiScreenState): String {
        return if (state.packageName.contains("instagram", true)) "instagram_dm_list" else "chat_list"
    }

    private suspend fun navigateToMessagesSurface(state: KaiScreenState): KaiActionExecutionResult {
        return navigateToMessagesSurfaceImpl(state)
    }

    private suspend fun navigateToConversation(state: KaiScreenState, query: String): KaiActionExecutionResult {
        return navigateToConversationImpl(state, query)
    }

    private suspend fun navigateToWritableComposer(state: KaiScreenState): KaiActionExecutionResult {
        return navigateToWritableComposerImpl(state)
    }

    private suspend fun normalizeInstagramSurface(state: KaiScreenState, preferThread: Boolean = false): KaiActionExecutionResult {
        return normalizeInstagramSurfaceImpl(state, preferThread)
    }

    private suspend fun normalizeNotesSurface(state: KaiScreenState): KaiActionExecutionResult {
        return normalizeNotesSurfaceImpl(state)
    }

    private suspend fun normalizeGeneralWorkingSurface(state: KaiScreenState): KaiActionExecutionResult {
        return normalizeGeneralWorkingSurfaceImpl(state)
    }

    private suspend fun openFirstYouTubeMedia(state: KaiScreenState): KaiActionExecutionResult {
        return openFirstYouTubeMediaImpl(state)
    }

    private suspend fun recoverWrongSurfaceForStep(state: KaiScreenState, step: KaiActionStep): KaiActionExecutionResult {
        val pkg = state.packageName.lowercase(Locale.ROOT)
        return when {
            pkg.contains("instagram") -> {
                val preferThread = step.cmd in setOf("focus_best_input", "input_into_best_field", "press_primary_action", "open_best_list_item")
                normalizeInstagramSurface(state, preferThread = preferThread)
            }
            pkg.contains("notes") || pkg.contains("keep") -> normalizeNotesSurface(state)
            else -> {
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_BACK,
                    preDelayMs = 70L
                )
                val refreshed = requestFreshScreen(2600L)
                KaiActionExecutionResult(
                    success = true,
                    message = "generic_surface_recovery_back",
                    screenState = refreshed
                )
            }
        }
    }

    private suspend fun writeIntoBestComposer(text: String): KaiActionExecutionResult {
        return writeIntoBestComposerImpl(text)
    }

    private suspend fun pressSendIfPossible(): KaiActionExecutionResult {
        return pressSendIfPossibleImpl()
    }

    private suspend fun openOrCreateNoteIfNeeded(state: KaiScreenState): KaiActionExecutionResult {
        return openOrCreateNoteIfNeededImpl(state)
    }

    private suspend fun focusNoteEditorIfNeeded(state: KaiScreenState): KaiActionExecutionResult {
        return focusNoteEditorIfNeededImpl(state)
    }

    private suspend fun writeIntoBestNoteEditor(text: String): KaiActionExecutionResult {
        return writeIntoBestNoteEditorImpl(text)
    }

    suspend fun executeStep(step: KaiActionStep): KaiActionExecutionResult {
        val cmd = step.cmd.trim().lowercase(Locale.ROOT)

        val policyGuardedCommands = setOf(
            "click_best_match",
            "focus_best_input",
            "input_into_best_field",
            "press_primary_action",
            "open_best_list_item",
            "verify_state"
        )
        if (cmd in policyGuardedCommands) {
            val expectedPkgHint = step.expectedPackage.ifBlank { canonicalBeforePackage() }
            val gate = ensureStrongObservationGate(
                expectedPackage = expectedPkgHint,
                timeoutMs = 2200L,
                maxAttempts = 2,
                allowLauncherSurface = false
            )
            if (!gate.passed) {
                val gateState = gate.state
                val canProceedPragmatically =
                    gateState.packageName.isNotBlank() &&
                        !isLauncherPackage(gateState.packageName) &&
                        (expectedPkgHint.isBlank() || isExpectedPackageMatch(gateState.packageName, expectedPkgHint)) &&
                        getLastRefreshMeta().changedFromPrevious
                if (canProceedPragmatically) {
                    onLog("observation_gate_soft_allow_in_app_continuation: ${gate.reason}")
                } else {
                    return KaiActionExecutionResult(
                        success = false,
                        message = gate.reason,
                        screenState = gate.state
                    )
                }
            }

            var current = gate.state
            var policy = KaiSurfaceActionPolicy.evaluate(step, current)
            val conservativeSemanticGate = cmd in setOf(
                "click_best_match",
                "focus_best_input",
                "input_into_best_field",
                "press_primary_action",
                "open_best_list_item"
            )
            val weakForSemanticContinuation =
                current.isWeakObservation() &&
                    (current.isOverlayPolluted() || getLastRefreshMeta().fallback || getLastRefreshMeta().reusedLastGood)
            if (conservativeSemanticGate && weakForSemanticContinuation) {
                current = requestFreshScreen(1400L, expectedPackage = expectedPkgHint.ifBlank { current.packageName })
                policy = KaiSurfaceActionPolicy.evaluate(step, current)
            }
            if (
                conservativeSemanticGate &&
                current.isWeakObservation() &&
                (getLastRefreshMeta().fallback || getLastRefreshMeta().reusedLastGood) &&
                !KaiExecutionDecisionAuthority.expectedEvidenceSatisfied(step, current)
            ) {
                onLog("semantic_action_guarded_continue_under_weak_observation")
            }
            if (!policy.allowed) {
                val reason = if (policy.reason.isNotBlank()) policy.reason else "surface_transition_required"
                onLog("surface_action_policy_blocked: $reason -> ${policy.preferredTransition}")

                val softAllow =
                    current.packageName.isNotBlank() &&
                        !isLauncherPackage(current.packageName) &&
                        (expectedPkgHint.isBlank() || isExpectedPackageMatch(current.packageName, expectedPkgHint)) &&
                        !current.isOverlayPolluted() &&
                        !current.isWeakObservation() &&
                        reason.contains("transition", ignoreCase = true)

                if (softAllow) {
                    onLog("surface_action_policy_soft_allow_in_app_continuation")
                } else {
                    if (cmd == "verify_state" && isLauncherPackage(current.packageName)) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "open_app_needed_from_launcher",
                            screenState = current
                        )
                    }

                    val messagePrefix = when (cmd) {
                        "focus_best_input", "input_into_best_field" -> "wrong_surface_for_composer"
                        "open_best_list_item" -> "wrong_surface_for_conversation_open"
                        "press_primary_action" -> "wrong_surface_for_send"
                        "verify_state" -> "verify_requires_transition"
                        else -> "weak_surface_evidence"
                    }

                    return KaiActionExecutionResult(
                        success = false,
                        message = "$messagePrefix:$reason",
                        screenState = current
                    )
                }
            }
        }

        return when (cmd) {
            "wait" -> {
                val ms = step.waitMs.coerceIn(80L, 12000L)
                onLog("Waiting ${ms}ms")
                delay(ms)
                KaiActionExecutionResult(
                    success = true,
                    message = "Waited ${ms}ms"
                )
            }

            "read_screen" -> {
                onLog("Refreshing screen understanding")
                val state = requestFreshScreen(step.timeoutMs)
                val meta = getLastRefreshMeta()

                val hardStalledWeakRead =
                    meta.weak &&
                        meta.stale &&
                        consecutiveWeakReads >= 8 &&
                        consecutiveStaleReads >= 8 &&
                        meta.reusedLastGood

                if (hardStalledWeakRead) {
                    onLog("Screen observation appears stalled/weak; returning strict failure")
                    KaiActionExecutionResult(
                        success = false,
                        message = "Screen observation is weak/stalled, cannot verify progress",
                        screenState = state
                    )
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = when {
                            meta.usable -> "Screen observation refreshed"
                            meta.fallback -> "Screen observation refreshed using fallback"
                            meta.weak && meta.changedFromPrevious -> "Screen observation refreshed weakly"
                            meta.reusedLastGood -> "Reused last known good screen dump"
                            else -> "Screen observation appears stable"
                        },
                        screenState = state
                    )
                }
            }

            "wait_for_text" -> {
                waitForText(
                    target = step.text.ifBlank { step.note },
                    timeoutMs = step.timeoutMs
                )
            }

            "open_app" -> {
                val canonicalBefore = resolveCanonicalRuntimeState()
                val beforeObservation = KaiObservation(
                    packageName = canonicalBefore.packageName,
                    screenPreview = canonicalBefore.rawDump,
                    elements = canonicalBefore.elements,
                    screenKind = canonicalBefore.screenKind,
                    semanticConfidence = canonicalBefore.semanticConfidence,
                    updatedAt = canonicalBefore.updatedAt
                )
                val beforeFingerprint = fingerprintFor(
                    beforeObservation.packageName,
                    beforeObservation.screenPreview
                )
                val beforePackage = beforeObservation.packageName

                val launchScreenState = canonicalRuntimeState ?: requestFreshScreen(1800L)
                val targetHint = inferAppHintFromText(step.text)

                var actionMode = "none"
                var workingLauncherState = launchScreenState

                val candidatePackage = resolveTargetAppPackage(step.text, launchScreenState)
                val targetPackage = candidatePackage ?: ""
                if (candidatePackage != null) {
                    onLog("open_app: resolved package '$candidatePackage' from '${step.text}', attempting direct launch.")
                    if (launchAppByPackage(candidatePackage)) {
                        onLog("open_app: direct launch intent attempted for $candidatePackage")
                        actionMode = "direct_intent"
                    } else {
                        onLog("open_app: direct launch intent failed for $candidatePackage")
                    }
                } else {
                    onLog("open_app: could not resolve package for '${step.text}'")
                }

                if (actionMode != "direct_intent" && !isLauncherSurfaceCoherent(workingLauncherState)) {
                    val refreshed = requestFreshScreen(1600L)
                    if (isLauncherSurfaceCoherent(refreshed)) {
                        workingLauncherState = refreshed
                    }
                }

                if (actionMode != "direct_intent" && isLauncherSurfaceCoherent(workingLauncherState)) {
                    val (openedViaLauncher, launcherMode) = launchViaVisibleLauncherCandidate(
                        launcherState = workingLauncherState,
                        rawTarget = step.text,
                        inferredHint = targetHint,
                        targetPackage = targetPackage,
                        reason = "primary_recovery_after_direct_launch"
                    )
                    if (openedViaLauncher) {
                        actionMode = "launcher_visible_target_$launcherMode"
                    } else {
                        onLog("open_app: no strong visible launcher candidate for '${step.text}'")
                    }
                }

                if (actionMode != "direct_intent" && actionMode == "none" && hasLauncherIconForTarget(workingLauncherState, step.text)) {
                    onLog("open_app: launcher text variants visible for '${step.text}', attempting click_text.")
                    clickTextVariants(step.text).forEach { variant ->
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                            text = variant,
                            preDelayMs = 60L
                        )
                    }
                    actionMode = "click_text"
                }

                if (actionMode != "direct_intent" && actionMode != "click_text") {
                    if (step.x != null && step.y != null) {
                        onLog("open_app: coordinate fallback for '${step.text}' at x=${step.x}, y=${step.y}")
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_TAP_XY,
                            x = step.x,
                            y = step.y,
                            preDelayMs = 80L
                        )
                        actionMode = "coordinate_fallback"
                    } else {
                        onLog("open_app: no valid coordinates available for '${step.text}'; trying CMD_OPEN_APP fallback.")
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_OPEN_APP,
                            text = step.text,
                            preDelayMs = 90L
                        )
                        actionMode = "open_app_cmd"
                    }
                }

                delay(step.waitMs.coerceAtLeast(700L))

                val verifyResult = verifyOpenAppInternal(
                    rawTargetText = step.text,
                    targetHint = targetHint,
                    targetPackage = targetPackage,
                    beforeObservation = beforeObservation,
                    beforeFingerprint = beforeFingerprint,
                    beforePackage = beforePackage
                )

                onLog("open_app verification result: ${verifyResult.message}")

                val finalState = verifyResult.screenState ?: resolveCanonicalRuntimeState()
                val afterFingerprint = fingerprintFor(finalState.packageName, finalState.rawDump)
                val afterPackage = finalState.packageName
                val runtimePrompt = KaiAgentController.getSnapshot().customPrompt
                    .ifBlank { KaiAgentController.getSnapshot().currentGoal }
                    .ifBlank { step.text }
                val stageSnapshot = KaiTaskStageEngine.evaluate(
                    userPrompt = runtimePrompt,
                    currentState = finalState,
                    openAppOutcome = verifyResult.openAppOutcome
                )

                markActionProgress(
                    beforePackage,
                    afterPackage,
                    beforeFingerprint,
                    afterFingerprint,
                    "open_app"
                )

                return KaiActionExecutionResult(
                    success = verifyResult.success,
                    message = buildString {
                        append("Requested app open: ${step.text} (strategy=$actionMode)")
                        append(" | ${verifyResult.message}")
                        if (stageSnapshot.appEntryComplete && !stageSnapshot.finalGoalComplete) {
                            append(" | stage_continue=${stageSnapshot.nextSemanticAction}")
                        }
                    },
                    screenState = finalState,
                    openAppOutcome = verifyResult.openAppOutcome
                )
            }

            "click_text" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()

                var clickDispatched = false

                suspend fun executeClickTextTargets() {
                    clickTextVariants(step.text).forEach { variant ->
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                            text = variant,
                            preDelayMs = 70L
                        )
                        clickDispatched = true
                    }
                }

                suspend fun applyGestureClickFallback() {
                    onLog("click_text gesture fallback skipped: no safe coordinates")
                }

                suspend fun performClickAttempt(): KaiScreenState {
                    executeClickTextTargets()
                    delay(step.waitMs.coerceAtLeast(300L))
                    return requestFreshScreen(3000L)
                }

                var state = performClickAttempt()
                var meta = getLastRefreshMeta()
                var afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                var afterPackage = state.packageName
                var fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                var packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                var hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && state.containsText(step.text)) {
                    onLog("click_text fallback attempt (click_text + gesture) for '${step.text}'")
                    executeClickTextTargets()
                    delay(120L)
                    applyGestureClickFallback()
                    delay(180L)

                    state = requestFreshScreen(3000L)
                    meta = getLastRefreshMeta()
                    afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                    afterPackage = state.packageName
                    fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                    packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                    hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)
                }

                if (!hasMeaningfulChange && state.containsText(step.text)) {
                    onLog("click_text second fallback (gesture center) for '${step.text}'")
                    applyGestureClickFallback()
                    delay(180L)

                    state = requestFreshScreen(3000L)
                    meta = getLastRefreshMeta()
                    afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                    afterPackage = state.packageName
                    fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                    packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                    hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)
                }

                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "click_text verification")

                if (hasMeaningfulChange) {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested click: ${step.text}",
                        screenState = state
                    )
                } else {
                    KaiActionExecutionResult(
                        success = false,
                        message = if (clickDispatched || meta.weak) {
                            "click_text expected evidence not satisfied"
                        } else {
                            "click_text had no visible effect: ${step.text}"
                        },
                        screenState = state
                    )
                }
            }

            "click_best_match" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                val state = requestFreshScreen(2400L)

                if (
                    step.strategy.contains("messages", true) ||
                    step.selectorRole.equals("tab", true) &&
                    KaiScreenStateParser.normalize(step.selectorText.ifBlank { step.text }).contains("message")
                ) {
                    return navigateToMessagesSurface(state)
                }

                // Semantic execution fallback: attempt structured target first, then primitive click_text/tap.
                val issued = clickSemanticTarget(step = step, state = state, fallbackText = step.text)

                val expectedKind = when {
                    step.selectorRole.equals("tab", true) &&
                        KaiScreenStateParser.normalize(step.selectorText.ifBlank { step.text }).contains("message") -> expectedListKindFor(state)
                    state.packageName.contains("youtube", true) -> "youtube_working_surface"
                    state.packageName.contains("instagram", true) && step.selectorRole.equals("chat_item", true) -> "instagram_dm_thread"
                    state.packageName.contains("notes", true) || state.packageName.contains("keep", true) -> "notes_list"
                    else -> step.expectedScreenKind.ifBlank { "detail" }
                }

                semanticPostVerify(
                    actionName = "click_best_match",
                    beforePackage = beforePackage,
                    beforeFingerprint = beforeFingerprint,
                    waitMs = step.waitMs,
                    timeoutMs = step.timeoutMs,
                    commandIssued = issued,
                    verifyStep = step.copy(
                        expectedPackage = step.expectedPackage.ifBlank { state.packageName },
                        expectedScreenKind = expectedKind
                    ),
                    allowWeakSuccess = true
                )
            }

            "focus_best_input" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                val state = requestFreshScreen(2400L)

                if (state.packageName.contains("notes", true)) {
                    if (state.isSearchLikeSurface()) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_create_note",
                            screenState = state
                        )
                    }
                    if (!(state.isStrictVerifiedNotesEditorSurface() || state.isNotesListSurface())) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_create_note",
                            screenState = state
                        )
                    }
                    val open = openOrCreateNoteIfNeeded(state)
                    val focus = focusNoteEditorIfNeeded(open.screenState ?: state)
                    return if (focus.success) focus else open
                }

                if (isMessagingAppPackage(state.packageName)) {
                    if (state.isCameraOrMediaOverlaySurface() || state.isInstagramSearchSurface() || state.isSearchLikeSurface()) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_composer",
                            screenState = state
                        )
                    }
                    if (!(state.isStrictVerifiedDmThreadSurface() || state.isChatComposerSurface())) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_composer",
                            screenState = state
                        )
                    }
                    val navComposer = navigateToWritableComposer(state)
                    if (navComposer.success) return navComposer
                }

                val inputCandidate = state.findBestInputField(
                    step.selectorHint.ifBlank { step.selectorText.ifBlank { step.text } }
                )

                val issued = if (inputCandidate != null) {
                    val label = semanticLabel(inputCandidate)
                    if (label.isNotBlank()) {
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                            text = label,
                            preDelayMs = 70L
                        )
                        true
                    } else {
                        val (cx, cy) = parseCenterFromBounds(inputCandidate.bounds)
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
                    clickSemanticTarget(step = step.copy(selectorRole = "input"), state = state, fallbackText = step.selectorText)
                }

                val expectedKind = when {
                    state.packageName.contains("notes", true) || state.packageName.contains("keep", true) -> "notes_editor"
                    state.packageName.contains("instagram", true) -> "instagram_dm_thread"
                    isMessagingAppPackage(state.packageName) -> "chat_thread"
                    else -> step.expectedScreenKind
                }

                semanticPostVerify(
                    actionName = "focus_best_input",
                    beforePackage = beforePackage,
                    beforeFingerprint = beforeFingerprint,
                    waitMs = step.waitMs,
                    timeoutMs = step.timeoutMs,
                    commandIssued = issued,
                    verifyStep = step.copy(
                        expectedPackage = step.expectedPackage.ifBlank { state.packageName },
                        expectedScreenKind = expectedKind
                    ),
                    allowWeakSuccess = true
                )
            }

            "input_into_best_field" -> {
                val state = requestFreshScreen(2500L)

                if (state.packageName.contains("notes", true)) {
                    if (state.isSearchLikeSurface()) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_create_note",
                            screenState = state
                        )
                    }
                    if (!(state.isStrictVerifiedNotesEditorSurface() || state.isNotesTitleInputSurface() || state.isNotesBodyInputSurface())) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_create_note",
                            screenState = state
                        )
                    }
                    if (step.text.isNotBlank()) return writeIntoBestNoteEditor(step.text)
                }

                if (isMessagingAppPackage(state.packageName) && step.text.isNotBlank()) {
                    if (state.isCameraOrMediaOverlaySurface() || state.isInstagramSearchSurface() || state.isSearchLikeSurface()) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_composer",
                            screenState = state
                        )
                    }
                    if (!(state.isStrictVerifiedDmThreadSurface() || state.isChatComposerSurface())) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_composer",
                            screenState = state
                        )
                    }
                    return writeIntoBestComposer(step.text)
                }

                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()

                val inputCandidate = state.findBestInputField(step.selectorHint.ifBlank { step.selectorText })
                val focusIssued = if (inputCandidate != null) {
                    val label = semanticLabel(inputCandidate)
                    if (label.isNotBlank()) {
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                            text = label,
                            preDelayMs = 60L,
                            postDelayMs = 90L
                        )
                        true
                    } else {
                        val (cx, cy) = parseCenterFromBounds(inputCandidate.bounds)
                        if (cx != null && cy != null) {
                            sendKaiCmdSuppressed(
                                cmd = KaiAccessibilityService.CMD_TAP_XY,
                                x = cx,
                                y = cy,
                                preDelayMs = 60L,
                                postDelayMs = 90L
                            )
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    false
                }

                val textToType = step.text.ifBlank { step.selectorText }
                val typeIssued = if (textToType.isNotBlank()) {
                    sendKaiCmdSuppressed(
                        cmd = KaiAccessibilityService.CMD_TYPE,
                        text = textToType,
                        preDelayMs = 60L
                    )
                    true
                } else {
                    false
                }

                val expectedKind = when {
                    state.packageName.contains("notes", true) || state.packageName.contains("keep", true) -> "notes_editor"
                    state.packageName.contains("instagram", true) -> "instagram_dm_thread"
                    isMessagingAppPackage(state.packageName) -> "chat_thread"
                    else -> step.expectedScreenKind
                }

                semanticPostVerify(
                    actionName = "input_into_best_field",
                    beforePackage = beforePackage,
                    beforeFingerprint = beforeFingerprint,
                    waitMs = step.waitMs.coerceAtLeast(620L),
                    timeoutMs = step.timeoutMs,
                    commandIssued = focusIssued || typeIssued,
                    verifyStep = step.copy(
                        expectedPackage = step.expectedPackage.ifBlank { state.packageName },
                        expectedScreenKind = expectedKind
                    ),
                    allowWeakSuccess = true
                )
            }

            "press_primary_action" -> {
                if (
                    step.selectorRole.equals("send_button", true) ||
                    KaiScreenStateParser.normalize(step.selectorText.ifBlank { step.text }).let {
                        it.contains("send") || it.contains("ارسال") || it.contains("reply")
                    }
                ) {
                    return pressSendIfPossible()
                }

                if (step.selectorRole.equals("create_button", true) || step.strategy.contains("create", true)) {
                    val now = requestFreshScreen(2200L)
                    if (now.isSearchLikeSurface()) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_create_note",
                            screenState = now
                        )
                    }
                    if (!(now.isNotesListSurface() || now.isNotesEditorSurface() || now.isNotesTitleInputSurface() || now.isNotesBodyInputSurface())) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_create_note",
                            screenState = now
                        )
                    }
                }

                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                val state = requestFreshScreen(2400L)

                val primary = state.findPrimaryAction()
                val issued = if (primary != null) {
                    val primaryLabel = semanticLabel(primary)
                    if (primaryLabel.isNotBlank()) {
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                            text = primaryLabel,
                            preDelayMs = 70L
                        )
                        true
                    } else {
                        val (cx, cy) = parseCenterFromBounds(primary.bounds)
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

                semanticPostVerify(
                    actionName = "press_primary_action",
                    beforePackage = beforePackage,
                    beforeFingerprint = beforeFingerprint,
                    waitMs = step.waitMs,
                    timeoutMs = step.timeoutMs,
                    commandIssued = issued,
                    verifyStep = step.copy(
                        expectedPackage = step.expectedPackage.ifBlank { state.packageName },
                        expectedScreenKind = step.expectedScreenKind.ifBlank {
                            if (state.packageName.contains("notes", true) || state.packageName.contains("keep", true)) {
                                "notes_editor"
                            } else {
                                "detail"
                            }
                        }
                    ),
                    allowWeakSuccess = true
                )
            }

            "open_best_list_item" -> {
                val state = requestFreshScreen(2500L)

                if (state.isWeakObservation() || state.isOverlayPolluted()) {
                    return KaiActionExecutionResult(
                        success = false,
                        message = "semantic_continuation_requires_stronger_observation",
                        screenState = state
                    )
                }

                if (state.packageName.contains("youtube", true)) {
                    return openFirstYouTubeMedia(state)
                }

                if ((state.packageName.contains("notes", true) || state.packageName.contains("keep", true)) &&
                    (state.isSheetOrDialogSurface() || state.isSearchLikeSurface())
                ) {
                    return KaiActionExecutionResult(
                        success = false,
                        message = "wrong_surface_for_create_note",
                        screenState = state
                    )
                }

                if (step.selectorRole.equals("chat_item", true) || step.strategy.contains("conversation", true)) {
                    return navigateToConversation(state, step.selectorText.ifBlank { step.text })
                }

                val genericQuery = step.selectorText.ifBlank { step.text }.trim()
                if (genericQuery.isBlank()) {
                    return KaiActionExecutionResult(
                        success = false,
                        message = "ambiguous_list_target_requires_specific_selector",
                        screenState = state
                    )
                }

                if (isMessagingAppPackage(state.packageName) && !(state.isInstagramDmListSurface() || state.isChatListScreen())) {
                    if (state.isCameraOrMediaOverlaySurface() || state.isInstagramSearchSurface() || state.isSearchLikeSurface()) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "wrong_surface_for_conversation_open",
                            screenState = state
                        )
                    }
                    return KaiActionExecutionResult(
                        success = false,
                        message = "wrong_surface_for_conversation_open",
                        screenState = state
                    )
                }

                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()

                val target = state.findBestClickableTarget(genericQuery)
                val issued = if (target != null) {
                    val label = semanticLabel(target)
                    if (label.isNotBlank()) {
                        sendKaiCmdSuppressed(
                            cmd = KaiAccessibilityService.CMD_CLICK_TEXT,
                            text = label,
                            preDelayMs = 70L
                        )
                        true
                    } else {
                        val (cx, cy) = parseCenterFromBounds(target.bounds)
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

                val expectedKind = when {
                    state.packageName.contains("instagram", true) -> "instagram_dm_thread"
                    isMessagingAppPackage(state.packageName) -> "chat_thread"
                    state.packageName.contains("notes", true) || state.packageName.contains("keep", true) -> "notes_editor"
                    state.packageName.contains("youtube", true) -> "detail"
                    else -> step.expectedScreenKind.ifBlank { "detail" }
                }

                semanticPostVerify(
                    actionName = "open_best_list_item",
                    beforePackage = beforePackage,
                    beforeFingerprint = beforeFingerprint,
                    waitMs = step.waitMs,
                    timeoutMs = step.timeoutMs,
                    commandIssued = issued,
                    verifyStep = step.copy(
                        expectedPackage = step.expectedPackage.ifBlank { state.packageName },
                        expectedScreenKind = expectedKind
                    ),
                    allowWeakSuccess = true
                )
            }

            "verify_state" -> {
                val state = requestFreshScreen(step.timeoutMs)
                if (isLauncherPackage(state.packageName)) {
                    val kind = KaiScreenStateParser.normalize(step.expectedScreenKind)
                    val appTargetedKind = kind in setOf(
                        "instagram_dm_list",
                        "chat_list",
                        "list",
                        "instagram_dm_thread",
                        "chat_thread",
                        "notes_editor",
                        "notes_title_input",
                        "notes_body_input",
                        "editor"
                    )
                    if (appTargetedKind || step.expectedPackage.isNotBlank()) {
                        return KaiActionExecutionResult(
                            success = false,
                            message = "open_app_needed_from_launcher",
                            screenState = state
                        )
                    }
                }
                val satisfied = expectedStateSatisfied(step, state)
                if (satisfied) {
                    KaiActionExecutionResult(
                        success = true,
                        message = "verify_state matched expected evidence",
                        screenState = state
                    )
                } else {
                    val kind = KaiScreenStateParser.normalize(step.expectedScreenKind)
                    if (kind in setOf("instagram_dm_list", "chat_list")) {
                        val nav = navigateToMessagesSurface(state)
                        val finalState = nav.screenState ?: state
                        val finalSatisfied = expectedStateSatisfied(step, finalState)
                        return KaiActionExecutionResult(
                            success = finalSatisfied,
                            message = if (finalSatisfied) {
                                "verify_state matched after semantic navigation"
                            } else {
                                "verify_state still not matched after navigation"
                            },
                            screenState = finalState
                        )
                    }

                    if (kind in setOf("instagram_dm_thread", "chat_thread")) {
                        val nav = navigateToConversation(state, step.selectorText.ifBlank { step.text })
                        val finalState = nav.screenState ?: state
                        val finalSatisfied = expectedStateSatisfied(step, finalState)
                        return KaiActionExecutionResult(
                            success = finalSatisfied,
                            message = if (finalSatisfied) {
                                "verify_state matched after conversation navigation"
                            } else {
                                "verify_state still not matched after conversation navigation"
                            },
                            screenState = finalState
                        )
                    }

                    if (kind in setOf("notes_editor", "notes_title_input", "notes_body_input")) {
                        val open = openOrCreateNoteIfNeeded(state)
                        val focus = focusNoteEditorIfNeeded(open.screenState ?: state)
                        val finalState = focus.screenState ?: open.screenState ?: state
                        val finalSatisfied = expectedStateSatisfied(step, finalState)
                        return KaiActionExecutionResult(
                            success = finalSatisfied,
                            message = if (finalSatisfied) {
                                "verify_state matched after notes semantic recovery"
                            } else {
                                "verify_state did not match expected evidence"
                            },
                            screenState = finalState
                        )
                    }

                    KaiActionExecutionResult(
                        success = !state.isLauncher() && !state.isOverlayPolluted() &&
                            step.expectedPackage.isNotBlank() &&
                            isExpectedPackageMatch(state.packageName, step.expectedPackage),
                        message = if (!state.isLauncher() && !state.isOverlayPolluted() &&
                            step.expectedPackage.isNotBlank() &&
                            isExpectedPackageMatch(state.packageName, step.expectedPackage)
                        ) {
                            "verify_state practical progress accepted in target app"
                        } else {
                            "verify_state did not match expected evidence"
                        },
                        screenState = state
                    )
                }
            }

            "long_press_text" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_LONG_PRESS_TEXT,
                    text = step.text,
                    holdMs = step.holdMs,
                    preDelayMs = 80L
                )
                delay(step.waitMs.coerceAtLeast(620L))
                val state = requestFreshScreen(3200L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "long_press_text verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)
                
                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "long_press_text")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested long press: ${step.text}",
                        screenState = state
                    )
                }
            }

            "input_text" -> {
                val beforeState = requestFreshScreen(2300L)
                val beforeFingerprint = fingerprintFor(beforeState.packageName, beforeState.rawDump)
                val beforePackage = beforeState.packageName
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_TYPE,
                    text = step.text,
                    preDelayMs = 60L
                )

                val derivedExpectedKind = when {
                    beforeState.packageName.contains("notes", true) || beforeState.packageName.contains("keep", true) -> "notes_editor"
                    beforeState.packageName.contains("instagram", true) -> "instagram_dm_thread"
                    isMessagingAppPackage(beforeState.packageName) -> "chat_thread"
                    else -> ""
                }
                val verifyStep = step.copy(
                    expectedPackage = step.expectedPackage.ifBlank { beforeState.packageName },
                    expectedScreenKind = step.expectedScreenKind.ifBlank { derivedExpectedKind },
                    expectedTexts = if (step.expectedTexts.isNotEmpty()) step.expectedTexts else emptyList()
                )

                if (verifyStep.expectedScreenKind.isBlank() && verifyStep.expectedTexts.isEmpty()) {
                    return KaiActionExecutionResult(
                        success = false,
                        message = "input_text_missing_expected_evidence",
                        screenState = beforeState
                    )
                }

                semanticPostVerify(
                    actionName = "input_text",
                    beforePackage = beforePackage,
                    beforeFingerprint = beforeFingerprint,
                    waitMs = step.waitMs.coerceAtLeast(650L),
                    timeoutMs = step.timeoutMs,
                    commandIssued = true,
                    verifyStep = verifyStep,
                    allowWeakSuccess = false
                )
            }

            "scroll" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_SCROLL,
                    dir = step.dir.ifBlank { "down" },
                    times = step.times,
                    preDelayMs = 60L
                )
                delay(step.waitMs.coerceAtLeast(620L))
                val state = requestFreshScreen(2800L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "scroll verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "scroll")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested scroll ${step.dir} x${step.times}",
                        screenState = state
                    )
                }
            }

            "back" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_BACK,
                    preDelayMs = 50L
                )
                delay(step.waitMs.coerceAtLeast(420L))
                val state = requestFreshScreen(2600L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "back verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "back")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested back",
                        screenState = state
                    )
                }
            }

            "home" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_HOME,
                    preDelayMs = 50L
                )
                delay(step.waitMs.coerceAtLeast(450L))
                val state = requestFreshScreen(2600L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "home verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "home")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested home",
                        screenState = state
                    )
                }
            }

            "recents" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_RECENTS,
                    preDelayMs = 50L
                )
                delay(step.waitMs.coerceAtLeast(500L))
                val state = requestFreshScreen(2800L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "recents verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "recents")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested recents",
                        screenState = state
                    )
                }
            }

            "tap_xy" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_TAP_XY,
                    x = step.x,
                    y = step.y,
                    preDelayMs = 50L
                )
                delay(step.waitMs.coerceAtLeast(420L))
                val state = requestFreshScreen(2800L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "tap_xy verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "tap_xy")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested tap_xy",
                        screenState = state
                    )
                }
            }

            "long_press_xy" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_LONG_PRESS_XY,
                    x = step.x,
                    y = step.y,
                    holdMs = step.holdMs,
                    preDelayMs = 60L
                )
                delay(step.waitMs.coerceAtLeast(650L))
                val state = requestFreshScreen(3000L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "long_press_xy verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "long_press_xy")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested long_press_xy",
                        screenState = state
                    )
                }
            }

            "swipe_xy" -> {
                val beforeFingerprint = canonicalBeforeFingerprint()
                val beforePackage = canonicalBeforePackage()
                sendKaiCmdSuppressed(
                    cmd = KaiAccessibilityService.CMD_SWIPE_XY,
                    x = step.x,
                    y = step.y,
                    endX = step.endX,
                    endY = step.endY,
                    holdMs = step.holdMs,
                    preDelayMs = 60L
                )
                delay(step.waitMs.coerceAtLeast(700L))
                val state = requestFreshScreen(3000L)
                val meta = getLastRefreshMeta()
                val afterFingerprint = fingerprintFor(state.packageName, state.rawDump)
                val afterPackage = state.packageName
                markActionProgress(beforePackage, afterPackage, beforeFingerprint, afterFingerprint, "swipe_xy verification")

                val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
                val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
                val hasMeaningfulChange = fingerprintChanged || packageChanged || (meta.changedFromPrevious && !meta.reusedLastGood)

                if (!hasMeaningfulChange && meta.weak) {
                    weakNoProgressFailure(state, "swipe_xy")
                } else {
                    KaiActionExecutionResult(
                        success = true,
                        message = "Requested swipe_xy",
                        screenState = state
                    )
                }
            }

            else -> {
                KaiActionExecutionResult(
                    success = false,
                    message = "Unknown step command: $cmd",
                    hardStop = true
                )
            }
        }
    }

    private suspend fun waitForText(
        target: String,
        timeoutMs: Long
    ): KaiActionExecutionResult {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) {
            return KaiActionExecutionResult(
                success = false,
                message = "wait_for_text needs a target text"
            )
        }

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs.coerceIn(800L, 18000L)) {
            val state = requestFreshScreen(2200L)
            if (state.containsText(cleanTarget)) {
                lastGoodScreenState = state
                lastAcceptedFingerprint = fingerprintFor(state.packageName, state.rawDump)
                consecutiveWeakReads = 0
                consecutiveStaleReads = 0
                consecutiveNoProgressActions = 0
                return KaiActionExecutionResult(
                    success = true,
                    message = "Text appeared: $cleanTarget",
                    screenState = state
                )
            }
            delay(320L)
        }

        val latest = canonicalRuntimeState ?: lastGoodScreenState ?: KaiAgentController.getLatestScreenState()
        return KaiActionExecutionResult(
            success = false,
            message = "Timed out waiting for text: $cleanTarget",
            screenState = latest
        )
    }
}
// ── KaiExecutorScreenOps ──────────────────────────────────────────────


internal fun KaiActionExecutor.softResetObservationStateImpl() {
    canonicalRuntimeState = null
    lastAcceptedFingerprint = ""
    lastAcceptedObservationAt = 0L
    lastGoodScreenState = null
    consecutiveWeakReads = 0
    consecutiveStaleReads = 0
    consecutiveNoProgressActions = 0
    lastRecoveryContextKey = ""
    repeatedRecoveryContextCount = 0
    lastRefreshMeta = lastRefreshMeta.copy(
        fingerprint = "",
        changedFromPrevious = false,
        usable = false,
        fallback = false,
        weak = false,
        stale = false,
        reusedLastGood = false
    )
}

internal fun KaiActionExecutor.isOverlayPollutedImpl(raw: String): Boolean {
    val clean = raw.trim().lowercase(Locale.ROOT)
    if (clean.isBlank()) return false

    val hints = listOf(
        "dynamic island",
        "custom prompt",
        "make action",
        "control panel",
        "agent loop active",
        "agent planning",
        "agent executing",
        "agent observing",
        "monitoring paused before action loop",
        "screen recorder",
        "recording",
        "tap to stop",
        "stop recording"
    )

    return hints.count { clean.contains(it) } >= 2
}

internal fun KaiActionExecutor.isBaseDumpValidImpl(raw: String, packageName: String = ""): Boolean {
    val clean = raw.trim()
    if (clean.isBlank()) return false
    if (clean.equals("(no active window)", ignoreCase = true)) return false
    if (clean.equals("(empty dump)", ignoreCase = true)) return false
    if (clean.lines().size < 2) return false
    if (packageName == context.packageName) return false
    return true
}

internal fun KaiActionExecutor.isUsableDumpImpl(raw: String, packageName: String = ""): Boolean {
    val clean = raw.trim()
    if (!isBaseDumpValidImpl(clean, packageName)) return false
    if (isOverlayPollutedImpl(clean)) return false
    return true
}

internal fun KaiActionExecutor.isFallbackAcceptableImpl(raw: String, packageName: String = ""): Boolean {
    val clean = raw.trim()
    if (!isBaseDumpValidImpl(clean, packageName)) return false

    val lineCount = clean.lines().size
    val likelyRealExternalScreen = packageName.isNotBlank() && packageName != context.packageName

    return likelyRealExternalScreen && lineCount >= 6
}

internal fun KaiActionExecutor.isWeakButMeaningfulDumpImpl(raw: String, packageName: String = ""): Boolean {
    val clean = raw.trim()
    if (clean.isBlank()) return false
    if (clean.equals("(no active window)", ignoreCase = true)) return false
    if (clean.equals("(empty dump)", ignoreCase = true)) return false
    if (packageName == context.packageName) return false
    return true
}

private fun packageMatchesExpected(actual: String, expectedPackage: String): Boolean {
    val a = KaiScreenStateParser.normalize(actual)
    val e = KaiScreenStateParser.normalize(expectedPackage)
    if (e.isBlank()) return true
    if (a.isBlank()) return false
    return a == e || a.startsWith("$e.")
}

private fun isLauncherRecoveryObservation(expectedPackage: String, state: KaiScreenState): Boolean {
    if (expectedPackage.isNotBlank()) return false
    if (!state.isLauncher()) return false
    return state.elements.size >= 2 || state.lines.size >= 3
}

internal fun KaiActionExecutor.updateRefreshMetaImpl(
    state: KaiScreenState,
    usable: Boolean,
    fallback: Boolean,
    weak: Boolean,
    previousFingerprint: String,
    observationUpdatedAt: Long,
    reusedLastGood: Boolean = false
) {
    val newFingerprint = fingerprintFor(state.packageName, state.rawDump)
    val changed = !sameFingerprint(previousFingerprint, newFingerprint)
    val stale = !changed

    if (usable) {
        consecutiveWeakReads = 0
        consecutiveStaleReads = if (stale) consecutiveStaleReads + 1 else 0
        lastGoodScreenState = state
        lastAcceptedFingerprint = newFingerprint
        lastAcceptedObservationAt = observationUpdatedAt
        adoptCanonicalRuntimeState(state)
    } else {
        consecutiveWeakReads += 1
        consecutiveStaleReads = if (stale) consecutiveStaleReads + 1 else 0
        // Strict runtime contract: weak/fallback/reused observations never become canonical semantic truth.
    }

    lastRefreshMeta = KaiActionExecutor.ScreenRefreshMeta(
        fingerprint = newFingerprint,
        changedFromPrevious = changed,
        usable = usable,
        fallback = fallback,
        weak = weak,
        stale = stale,
        reusedLastGood = reusedLastGood
    )
}

internal fun KaiActionExecutor.getLastRefreshMetaImpl(): KaiActionExecutor.ScreenRefreshMeta = lastRefreshMeta
internal fun KaiActionExecutor.getConsecutiveWeakReadsImpl(): Int = consecutiveWeakReads
internal fun KaiActionExecutor.getConsecutiveStaleReadsImpl(): Int = consecutiveStaleReads

private fun KaiScreenState.isWrongSurfaceFamilyForSemanticProgress(): Boolean {
    val kind = KaiScreenStateParser.normalize(screenKind)
    if (kind in setOf("instagram_camera_overlay", "instagram_search", "notes_search", "search")) return true
    return isCameraOrMediaOverlaySurface() || isInstagramSearchSurface() || isSearchLikeSurface()
}

private fun KaiActionExecutor.isSemanticContinuationContext(expectedPackage: String): Boolean {
    return expectedPackage.isNotBlank()
}

private fun KaiActionExecutor.isContinuationEligibleObservation(state: KaiScreenState, expectedPackage: String): Boolean {
    if (state.packageName.isBlank()) return false
    if (state.isOverlayPolluted()) return false
    if (state.isLauncher()) return false

    val expected = KaiScreenStateParser.normalize(expectedPackage)
    val observed = KaiScreenStateParser.normalize(state.packageName)
    if (expected.isNotBlank() && observed.isNotBlank() && observed != expected && !observed.startsWith("$expected.")) {
        return false
    }

    val weak = state.isWeakObservation()

    return when {
        observed.contains("instagram") -> {
            if (state.isInstagramCameraOverlaySurface() || state.isInstagramSearchSurface()) return false
            val strong = state.isInstagramDmListSurface() || state.isInstagramDmThreadSurface() || state.isInstagramMessagesEntrySurface()
            strong || !state.isWrongSurfaceFamilyForSemanticProgress()
        }

        observed.contains("whatsapp") -> {
            val strong = state.isWhatsAppChatsListSurface() || state.isWhatsAppConversationThreadSurface()
            strong || !state.isWrongSurfaceFamilyForSemanticProgress()
        }

        observed.contains("notes") || observed.contains("keep") -> {
            val strong = state.isNotesListSurface() || state.isStrictVerifiedNotesEditorSurface() || state.isNotesTitleInputSurface() || state.isNotesBodyInputSurface()
            strong || !state.isWrongSurfaceFamilyForSemanticProgress()
        }

        observed.contains("youtube") -> {
            val strong = state.isYouTubeBrowseSurface() || state.isYouTubeWatchSurface() || KaiSurfaceModel.isVerifiedYouTubeWorkingSurface(state)
            strong || !state.isWrongSurfaceFamilyForSemanticProgress()
        }

        else -> {
            !weak && !state.isWrongSurfaceFamilyForSemanticProgress()
        }
    }
}

internal suspend fun KaiActionExecutor.requestFreshScreenImpl(timeoutMs: Long = 2500L, expectedPackage: String = ""): KaiScreenState {
    if (consecutiveWeakReads >= 5 || consecutiveStaleReads >= 5) {
        onLog("Observation is weak/stale; keeping state intact and requesting a fresh dump")
    }

    var bestFallback: Pair<KaiScreenState, Long>? = null
    var weakUpdatedCandidate: Pair<KaiScreenState, Long>? = null
    var staleWeakCandidate: Pair<KaiScreenState, Long>? = null
    var weakExternalPackageHint: Pair<KaiScreenState, Long>? = null

    val previousFingerprint = if (lastAcceptedFingerprint.isNotBlank()) {
        lastAcceptedFingerprint
    } else {
        canonicalRuntimeState?.let { fingerprintFor(it.packageName, it.rawDump) }.orEmpty()
    }

    val previousPackage = canonicalRuntimeState?.packageName ?: lastGoodScreenState?.packageName.orEmpty()
    val fallbackSuppressed = consecutiveWeakReads >= 3 || consecutiveStaleReads >= 3
    val expectedIsNotesFamily = expectedPackage.contains("notes", true) || expectedPackage.contains("keep", true)
    val semanticContinuationContext = isSemanticContinuationContext(expectedPackage)

    repeat(3) { attempt ->
        val beforeObservation = KaiAgentController.getLatestObservation()
        val beforeUpdatedAt = beforeObservation.updatedAt

        sendKaiCmdSuppressed(
            cmd = KaiAccessibilityService.CMD_DUMP,
            expectedPackage = expectedPackage,
            timeoutMs = timeoutMs,
            preDelayMs = if (attempt == 0) 90L else 140L,
            strongObservationMode = true
        )

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(130)

            val latest = KaiAgentController.getLatestObservation()
            if (latest.updatedAt <= beforeUpdatedAt) continue

            val parsed = KaiScreenStateParser.fromDump(
                latest.packageName,
                latest.screenPreview
            )

            val notesAmbiguousWeak =
                expectedIsNotesFamily &&
                    parsed.isWeakObservation() &&
                    (parsed.isNotesListSurface() || parsed.isNotesEditorSurface() || parsed.isSearchLikeSurface())

            if (notesAmbiguousWeak) {
                continue
            }

            val parsedFingerprint = fingerprintFor(parsed.packageName, parsed.rawDump)
            val changed = !sameFingerprint(previousFingerprint, parsedFingerprint)
            val packageChanged = isExternalPackageChange(previousPackage, parsed.packageName)
            val matchesExpected = packageMatchesExpected(parsed.packageName, expectedPackage)

            if (expectedPackage.isNotBlank() && !matchesExpected) {
                continue
            }

            val continuationEligible = if (semanticContinuationContext) {
                isContinuationEligibleObservation(parsed, expectedPackage)
            } else {
                true
            }

            if (isUsableDumpImpl(parsed.rawDump, parsed.packageName)) {
                if (semanticContinuationContext && !continuationEligible) {
                    onLog("usable_dump_rejected_not_continuation_eligible: ${parsed.screenKind}")
                    continue
                }
                updateRefreshMetaImpl(
                    state = parsed,
                    usable = true,
                    fallback = false,
                    weak = false,
                    previousFingerprint = previousFingerprint,
                    observationUpdatedAt = latest.updatedAt,
                    reusedLastGood = false
                )
                return parsed
            }

            if (!fallbackSuppressed && bestFallback == null && isFallbackAcceptableImpl(parsed.rawDump, parsed.packageName)) {
                if (!semanticContinuationContext || continuationEligible) {
                    bestFallback = parsed to latest.updatedAt
                }
            }

            if (isWeakButMeaningfulDumpImpl(parsed.rawDump, parsed.packageName)) {
                if (semanticContinuationContext && !continuationEligible) {
                    continue
                }
                if (packageChanged && weakExternalPackageHint == null) {
                    weakExternalPackageHint = parsed to latest.updatedAt
                } else if (changed && weakUpdatedCandidate == null) {
                    weakUpdatedCandidate = parsed to latest.updatedAt
                } else if (!changed && staleWeakCandidate == null) {
                    staleWeakCandidate = parsed to latest.updatedAt
                }
            }
        }

        delay(180L)
    }

    bestFallback?.let { (state, updatedAt) ->
        if (semanticContinuationContext && !isContinuationEligibleObservation(state, expectedPackage)) {
            onLog("fallback_dump_rejected_not_continuation_eligible")
            return@let
        }
        updateRefreshMetaImpl(
            state = state,
            usable = false,
            fallback = true,
            weak = false,
            previousFingerprint = previousFingerprint,
            observationUpdatedAt = updatedAt,
            reusedLastGood = false
        )
        onLog("Using fallback screen dump")
        return state
    }

    weakExternalPackageHint?.let { (state, _) ->
        onLog("weak_external_package_change_seen_hint_only: ${state.packageName}")
    }

    weakUpdatedCandidate?.let { (state, updatedAt) ->
        if (fallbackSuppressed && !isLauncherRecoveryObservation(expectedPackage, state) &&
            !(semanticContinuationContext && isContinuationEligibleObservation(state, expectedPackage))
        ) {
            onLog("Skipping weak updated dump due to repeated weak/stale streak")
            return@let
        } else if (fallbackSuppressed) {
            onLog("Allowing weak updated dump despite weak/stale streak for coherent continuation")
        }
        if (semanticContinuationContext && !isContinuationEligibleObservation(state, expectedPackage)) {
            onLog("weak_updated_dump_rejected_not_continuation_eligible")
            return@let
        }
        updateRefreshMetaImpl(
            state = state,
            usable = false,
            fallback = false,
            weak = true,
            previousFingerprint = previousFingerprint,
            observationUpdatedAt = updatedAt,
            reusedLastGood = false
        )
        onLog("Using weak updated screen dump")
        return state
    }

    staleWeakCandidate?.let { (state, updatedAt) ->
        if (fallbackSuppressed && !isLauncherRecoveryObservation(expectedPackage, state) &&
            !(semanticContinuationContext && isContinuationEligibleObservation(state, expectedPackage))
        ) {
            onLog("Skipping weak stale dump due to repeated weak/stale streak")
            return@let
        } else if (fallbackSuppressed) {
            onLog("Allowing weak stale dump despite weak/stale streak for coherent continuation")
        }
        if (semanticContinuationContext && !isContinuationEligibleObservation(state, expectedPackage)) {
            onLog("weak_stale_dump_rejected_not_continuation_eligible")
            return@let
        }
        updateRefreshMetaImpl(
            state = state,
            usable = false,
            fallback = false,
            weak = true,
            previousFingerprint = previousFingerprint,
            observationUpdatedAt = updatedAt,
            reusedLastGood = false
        )
        onLog("Using weak stale screen dump")
        return state
    }

    lastGoodScreenState?.let { state ->
        val latestObservation = KaiAgentController.getLatestObservation()
        val ageMs = if (lastAcceptedObservationAt > 0L) {
            (System.currentTimeMillis() - lastAcceptedObservationAt).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val latestPackage = latestObservation.packageName
        val packageContradiction =
            latestPackage.isNotBlank() &&
                state.packageName.isNotBlank() &&
                !latestPackage.equals(state.packageName, ignoreCase = true)
        val expectedMismatch = expectedPackage.isNotBlank() && !packageMatchesExpected(state.packageName, expectedPackage)

        if (state.isWrongSurfaceFamilyForSemanticProgress()) {
            onLog("Skipping last known good dump because it is a wrong-surface family: ${state.screenKind}")
            return@let
        }
        if (packageContradiction) {
            onLog("Skipping last known good dump because package changed from ${state.packageName} to $latestPackage")
            return@let
        }
        if (expectedMismatch) {
            onLog("Skipping last known good dump because expected package is $expectedPackage but state package is ${state.packageName}")
            return@let
        }
        if (expectedIsNotesFamily && state.isWeakObservation()) {
            onLog("Skipping last known good dump because notes context is still weak/ambiguous")
            return@let
        }
        if (fallbackSuppressed) {
            onLog("Skipping last known good dump due to repeated weak/stale streak")
            return@let
        }
        if (semanticContinuationContext) {
            val continuationReuseAllowed =
                isContinuationEligibleObservation(state, expectedPackage) &&
                    ageMs <= 1400L
            if (!continuationReuseAllowed) {
                onLog("Skipping last known good dump in semantic continuation context")
                return@let
            }
        }
        if (ageMs <= if (semanticContinuationContext) 1200L else 1800L) {
            updateRefreshMetaImpl(
                state = state,
                usable = false,
                fallback = false,
                weak = true,
                previousFingerprint = previousFingerprint,
                observationUpdatedAt = lastAcceptedObservationAt,
                reusedLastGood = true
            )
            onLog("Reusing recent last known good screen dump")
            return state
        }
        onLog("Skipping stale last known good screen dump (age=${ageMs}ms)")
    }

    val latest = KaiAgentController.getLatestObservation()
    if (latest.packageName.isBlank()) {
        onLog("refresh_failed_no_observation_arrived: packageName is blank in final fallback")
        val rejected = KaiScreenStateParser.fromDump(packageName = "", dump = "(no_observation_arrived)")
        updateRefreshMetaImpl(
            state = rejected,
            usable = false,
            fallback = false,
            weak = true,
            previousFingerprint = previousFingerprint,
            observationUpdatedAt = latest.updatedAt,
            reusedLastGood = false
        )
        return rejected
    }
    val parsed = KaiScreenStateParser.fromDump(latest.packageName, latest.screenPreview)
    if (
        semanticContinuationContext &&
        (parsed.isWeakObservation() && !isContinuationEligibleObservation(parsed, expectedPackage))
    ) {
        onLog("refresh_failed_weak_semantic_observation")
        val rejected = KaiScreenStateParser.fromDump(
            packageName = parsed.packageName,
            dump = "(semantic_observation_requires_retry)"
        )
        updateRefreshMetaImpl(
            state = rejected,
            usable = false,
            fallback = false,
            weak = true,
            previousFingerprint = previousFingerprint,
            observationUpdatedAt = latest.updatedAt,
            reusedLastGood = false
        )
        return rejected
    }
    if (expectedPackage.isNotBlank() && !packageMatchesExpected(parsed.packageName, expectedPackage)) {
        onLog("expected_package_rejected_final_fallback: expected=$expectedPackage observed=${parsed.packageName}")
        onLog("refresh_failed_wrong_package")
        val rejected = KaiScreenStateParser.fromDump(
            packageName = parsed.packageName.ifBlank { previousPackage },
            dump = "(expected package rejected final fallback)"
        )
        updateRefreshMetaImpl(
            state = rejected,
            usable = false,
            fallback = false,
            weak = true,
            previousFingerprint = previousFingerprint,
            observationUpdatedAt = latest.updatedAt,
            reusedLastGood = false
        )
        return rejected
    }
    updateRefreshMetaImpl(
        state = parsed,
        usable = false,
        fallback = false,
        weak = true,
        previousFingerprint = previousFingerprint,
        observationUpdatedAt = latest.updatedAt,
        reusedLastGood = false
    )
    return parsed
}

internal fun KaiActionExecutor.markActionProgressImpl(
    beforePackage: String,
    afterPackage: String,
    beforeFingerprint: String,
    afterFingerprint: String,
    message: String
) {
    val fingerprintChanged = !sameFingerprint(beforeFingerprint, afterFingerprint)
    val packageChanged = isExternalPackageChange(beforePackage, afterPackage)
    val hasMeaningfulChange = fingerprintChanged || packageChanged

    if (!hasMeaningfulChange) {
        consecutiveNoProgressActions += 1
        onLog("$message | no visible progress")
    } else {
        consecutiveNoProgressActions = 0
    }

    if (consecutiveNoProgressActions >= 8) {
        onLog("Too many no-progress actions in a row. Soft-resetting observation state, but keeping agent alive.")
        softResetObservationStateImpl()
    }
}

// ── KaiExecutorSemanticActions ──────────────────────────────────────────────


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

// ── KaiExecutorSemanticNav ──────────────────────────────────────────────


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
