package com.example.reply.agent

import com.example.reply.ui.KaiAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

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
