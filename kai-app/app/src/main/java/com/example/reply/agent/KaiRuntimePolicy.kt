// Shrink pass: kept the public policy surface stable, with this file intentionally
// remaining the central surface-classification + recovery map after the REWRITE phase.
// The heavier architectural changes were moved into KaiExecutionAuthority/KaiAgentLoopEngine.

package com.example.reply.agent

import kotlin.math.abs

// ── KaiSurfaceModel ──────────────────────────────────────────────

enum class KaiSurfaceFamily {
    LAUNCHER_SURFACE,
    APP_HOME_SURFACE,
    TABBED_HOME_SURFACE,
    CONTENT_FEED_SURFACE,
    LIST_SURFACE,
    SEARCH_SURFACE,
    RESULT_LIST_SURFACE,
    DETAIL_SURFACE,
    THREAD_SURFACE,
    COMPOSER_SURFACE,
    EDITOR_SURFACE,
    MEDIA_CAPTURE_SURFACE,
    PLAYER_SURFACE,
    SHEET_OR_DIALOG_SURFACE,
    SETTINGS_SURFACE,
    BROWSER_LIKE_SURFACE,
    MODAL_SURFACE,
    UNKNOWN_SURFACE
}

enum class KaiSurfaceStatus {
    TARGET_READY,
    USABLE_INTERMEDIATE,
    WRONG_BUT_RECOVERABLE,
    DEAD_END
}

object KaiSurfaceModel {
    private fun packageMatches(state: KaiScreenState, expected: String): Boolean {
        val pkg = KaiScreenStateParser.normalize(state.packageName)
        val e = KaiScreenStateParser.normalize(expected)
        if (pkg.isBlank() || e.isBlank()) return false
        return pkg == e || pkg.startsWith("$e.") ||
            KaiAppIdentityRegistry.packageMatchesFamily(expected, state.packageName)
    }

    fun isVerifiedInstagramDmListSurface(state: KaiScreenState): Boolean {
        return packageMatches(state, "com.instagram.android") &&
            state.isInstagramDmListSurface() &&
            !state.isCameraOrMediaOverlaySurface() &&
            !state.isSearchLikeSurface()
    }

    fun isVerifiedInstagramThreadTextSurface(state: KaiScreenState): Boolean {
        return packageMatches(state, "com.instagram.android") &&
            state.isStrictVerifiedDmThreadSurface() &&
            !state.isCameraOrMediaOverlaySurface() &&
            !state.isSearchLikeSurface()
    }

    fun isVerifiedInstagramCameraOverlay(state: KaiScreenState): Boolean {
        return packageMatches(state, "com.instagram.android") && state.isCameraOrMediaOverlaySurface()
    }

    fun isVerifiedYouTubeWorkingSurface(state: KaiScreenState): Boolean {
        if (!packageMatches(state, "com.google.android.youtube")) return false
        if (state.isWeakObservation() || state.isOverlayPolluted()) return false
        val family = normalizeLegacyFamily(familyOf(state))
        return family in setOf(
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.LIST_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.SEARCH_SURFACE,
            KaiSurfaceFamily.TABBED_HOME_SURFACE
        )
    }

    fun isVerifiedNotesListSurface(state: KaiScreenState): Boolean {
        return (packageMatches(state, "com.miui.notes") || state.packageName.contains("keep", true)) &&
            state.isNotesListSurface() &&
            !state.isSearchLikeSurface() &&
            !state.isSheetOrDialogSurface()
    }

    fun isVerifiedNotesEditorSurface(state: KaiScreenState): Boolean {
        return (packageMatches(state, "com.miui.notes") || state.packageName.contains("keep", true)) &&
            state.isStrictVerifiedNotesEditorSurface() &&
            !state.isSearchLikeSurface() &&
            !state.isSheetOrDialogSurface()
    }

    fun normalizeLegacyFamily(family: KaiSurfaceFamily): KaiSurfaceFamily {
        return when (family) {
            KaiSurfaceFamily.MODAL_SURFACE -> KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE
            else -> family
        }
    }

    fun familyName(family: KaiSurfaceFamily): String {
        return when (normalizeLegacyFamily(family)) {
            KaiSurfaceFamily.LAUNCHER_SURFACE -> "launcher"
            KaiSurfaceFamily.APP_HOME_SURFACE -> "app_home"
            KaiSurfaceFamily.TABBED_HOME_SURFACE -> "tabbed_home"
            KaiSurfaceFamily.CONTENT_FEED_SURFACE -> "content_feed"
            KaiSurfaceFamily.LIST_SURFACE -> "list_surface"
            KaiSurfaceFamily.SEARCH_SURFACE -> "search_surface"
            KaiSurfaceFamily.RESULT_LIST_SURFACE -> "result_list_surface"
            KaiSurfaceFamily.DETAIL_SURFACE -> "detail_surface"
            KaiSurfaceFamily.THREAD_SURFACE -> "thread_surface"
            KaiSurfaceFamily.COMPOSER_SURFACE -> "composer_surface"
            KaiSurfaceFamily.EDITOR_SURFACE -> "editor_surface"
            KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE -> "media_capture_surface"
            KaiSurfaceFamily.PLAYER_SURFACE -> "player_surface"
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE, KaiSurfaceFamily.MODAL_SURFACE -> "sheet_or_dialog_surface"
            KaiSurfaceFamily.SETTINGS_SURFACE -> "settings_surface"
            KaiSurfaceFamily.BROWSER_LIKE_SURFACE -> "browser_like_surface"
            KaiSurfaceFamily.UNKNOWN_SURFACE -> "unknown_surface"
        }
    }

    fun familyOf(state: KaiScreenState): KaiSurfaceFamily {
        if (state.isLauncher()) return KaiSurfaceFamily.LAUNCHER_SURFACE
        if (state.isSettingsSurface()) return KaiSurfaceFamily.SETTINGS_SURFACE
        if (state.isSheetOrDialogSurface()) return KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE
        if (state.isCameraOrMediaOverlaySurface()) return KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE
        if (state.isPlayerSurface()) return KaiSurfaceFamily.PLAYER_SURFACE
        if (state.isSearchLikeSurface() || state.isInstagramSearchSurface()) return KaiSurfaceFamily.SEARCH_SURFACE
        if (state.isResultListSurface()) return KaiSurfaceFamily.RESULT_LIST_SURFACE
        if (state.isDetailSurface()) return KaiSurfaceFamily.DETAIL_SURFACE
        if (state.isChatComposerSurface()) return KaiSurfaceFamily.COMPOSER_SURFACE
        if (state.isStrictVerifiedDmThreadSurface() || state.isChatThreadScreen()) return KaiSurfaceFamily.THREAD_SURFACE
        if (state.isStrictVerifiedNotesEditorSurface() || state.isNotesTitleInputSurface() || state.isNotesBodyInputSurface()) return KaiSurfaceFamily.EDITOR_SURFACE
        if (state.isInstagramDmListSurface() || state.isChatListScreen() || state.isNotesListSurface()) return KaiSurfaceFamily.LIST_SURFACE
        if (state.isInstagramMessagesEntrySurface() || state.isTabbedHomeSurface()) return KaiSurfaceFamily.TABBED_HOME_SURFACE
        if (state.isBrowserLikeSurface()) return KaiSurfaceFamily.BROWSER_LIKE_SURFACE
        if (state.isContentFeedSurface()) return KaiSurfaceFamily.CONTENT_FEED_SURFACE
        if (state.isInstagramFeedSurface()) return KaiSurfaceFamily.APP_HOME_SURFACE
        return KaiSurfaceFamily.UNKNOWN_SURFACE
    }

    fun equivalentFamilies(family: KaiSurfaceFamily): Set<KaiSurfaceFamily> {
        return when (normalizeLegacyFamily(family)) {
            KaiSurfaceFamily.APP_HOME_SURFACE -> setOf(KaiSurfaceFamily.APP_HOME_SURFACE, KaiSurfaceFamily.TABBED_HOME_SURFACE)
            KaiSurfaceFamily.TABBED_HOME_SURFACE -> setOf(KaiSurfaceFamily.TABBED_HOME_SURFACE, KaiSurfaceFamily.APP_HOME_SURFACE)
            KaiSurfaceFamily.CONTENT_FEED_SURFACE -> setOf(KaiSurfaceFamily.CONTENT_FEED_SURFACE)
            KaiSurfaceFamily.LIST_SURFACE -> setOf(KaiSurfaceFamily.LIST_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE)
            KaiSurfaceFamily.RESULT_LIST_SURFACE -> setOf(KaiSurfaceFamily.RESULT_LIST_SURFACE, KaiSurfaceFamily.LIST_SURFACE)
            KaiSurfaceFamily.THREAD_SURFACE -> setOf(KaiSurfaceFamily.THREAD_SURFACE, KaiSurfaceFamily.COMPOSER_SURFACE)
            KaiSurfaceFamily.COMPOSER_SURFACE -> setOf(KaiSurfaceFamily.COMPOSER_SURFACE, KaiSurfaceFamily.THREAD_SURFACE)
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE, KaiSurfaceFamily.MODAL_SURFACE -> setOf(KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE, KaiSurfaceFamily.MODAL_SURFACE)
            else -> setOf(normalizeLegacyFamily(family))
        }
    }

    fun isWrongForInputOrSend(family: KaiSurfaceFamily): Boolean {
        val normalized = normalizeLegacyFamily(family)
        return normalized in setOf(
            KaiSurfaceFamily.SEARCH_SURFACE,
            KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE,
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE,
            KaiSurfaceFamily.SETTINGS_SURFACE,
            KaiSurfaceFamily.BROWSER_LIKE_SURFACE,
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.DETAIL_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.UNKNOWN_SURFACE,
            KaiSurfaceFamily.LAUNCHER_SURFACE
        )
    }

    fun isRecoverableFamily(family: KaiSurfaceFamily): Boolean {
        return normalizeLegacyFamily(family) in setOf(
            KaiSurfaceFamily.LAUNCHER_SURFACE,
            KaiSurfaceFamily.SEARCH_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE,
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE,
            KaiSurfaceFamily.MODAL_SURFACE,
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.TABBED_HOME_SURFACE,
            KaiSurfaceFamily.BROWSER_LIKE_SURFACE
        )
    }

    fun isDeadEndFamily(family: KaiSurfaceFamily): Boolean {
        return normalizeLegacyFamily(family) in setOf(
            KaiSurfaceFamily.UNKNOWN_SURFACE,
            KaiSurfaceFamily.SETTINGS_SURFACE
        )
    }

    fun isPostOpenReadyFamily(family: KaiSurfaceFamily): Boolean {
        return normalizeLegacyFamily(family) in setOf(
            KaiSurfaceFamily.APP_HOME_SURFACE,
            KaiSurfaceFamily.TABBED_HOME_SURFACE,
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.LIST_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.DETAIL_SURFACE,
            KaiSurfaceFamily.THREAD_SURFACE,
            KaiSurfaceFamily.COMPOSER_SURFACE,
            KaiSurfaceFamily.EDITOR_SURFACE,
            KaiSurfaceFamily.PLAYER_SURFACE,
            KaiSurfaceFamily.BROWSER_LIKE_SURFACE
        )
    }
}

// ── KaiSurfaceTransitionPolicy ──────────────────────────────────────────────

enum class KaiRecoveryAction {
    NONE,
    BACK,
    DISMISS_SHEET,
    RETURN_TO_LIST,
    RETURN_HOME_TAB,
    OPEN_SEARCH,
    NORMALIZE_APP_SURFACE,
    REQUEST_FRESH_SCREEN,
    BREAK_FOR_REPLAN
}

data class KaiTransitionIntent(
    val requiredFamily: KaiSurfaceFamily,
    val acceptableFamilies: Set<KaiSurfaceFamily> = emptySet(),
    val preferredIntermediateFamilies: Set<KaiSurfaceFamily> = emptySet(),
    val preferredTransition: String = "",
    val recoveryAction: KaiRecoveryAction = KaiRecoveryAction.NONE,
    val reason: String = ""
)

data class KaiTransitionSurfaceAssessment(
    val family: KaiSurfaceFamily,
    val targetFamily: KaiSurfaceFamily,
    val status: KaiSurfaceStatus,
    val compatible: Boolean,
    val preferredIntermediate: Boolean,
    val reason: String = ""
)

object KaiSurfaceTransitionPolicy {
    fun inferIntent(step: KaiActionStep, state: KaiScreenState): KaiTransitionIntent {
        val cmd = step.cmd.trim().lowercase()
        val family = KaiSurfaceModel.familyOf(state)
        val expectedKind = KaiScreenStateParser.normalize(step.expectedScreenKind)

        return when {
            cmd == "open_app" -> KaiTransitionIntent(
                requiredFamily = KaiSurfaceFamily.APP_HOME_SURFACE,
                acceptableFamilies = setOf(
                    KaiSurfaceFamily.APP_HOME_SURFACE,
                    KaiSurfaceFamily.TABBED_HOME_SURFACE,
                    KaiSurfaceFamily.CONTENT_FEED_SURFACE,
                    KaiSurfaceFamily.LIST_SURFACE
                ),
                preferredIntermediateFamilies = setOf(
                    KaiSurfaceFamily.TABBED_HOME_SURFACE,
                    KaiSurfaceFamily.CONTENT_FEED_SURFACE,
                    KaiSurfaceFamily.LIST_SURFACE
                ),
                preferredTransition = "confirm_target_package_then_surface",
                recoveryAction = KaiRecoveryAction.NORMALIZE_APP_SURFACE,
                reason = "post_open_requires_working_surface"
            )

            cmd in setOf("focus_best_input", "input_into_best_field") -> {
                val required = if (state.packageName.contains("notes", true) || state.packageName.contains("keep", true)) {
                    KaiSurfaceFamily.EDITOR_SURFACE
                } else {
                    KaiSurfaceFamily.COMPOSER_SURFACE
                }
                KaiTransitionIntent(
                    requiredFamily = required,
                    acceptableFamilies = KaiSurfaceModel.equivalentFamilies(required),
                    preferredIntermediateFamilies = setOf(KaiSurfaceFamily.THREAD_SURFACE, KaiSurfaceFamily.EDITOR_SURFACE),
                    preferredTransition = "reach_writable_input_surface",
                    recoveryAction = when {
                        family == KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE -> KaiRecoveryAction.DISMISS_SHEET
                        family == KaiSurfaceFamily.SEARCH_SURFACE -> KaiRecoveryAction.BACK
                        family == KaiSurfaceFamily.RESULT_LIST_SURFACE -> KaiRecoveryAction.RETURN_TO_LIST
                        else -> KaiRecoveryAction.NORMALIZE_APP_SURFACE
                    },
                    reason = "input_requires_composer_or_editor"
                )
            }

            cmd == "press_primary_action" -> {
                val wantsSend = step.selectorRole.equals("send_button", true) ||
                    KaiScreenStateParser.normalize(step.selectorText.ifBlank { step.text }).let {
                        it.contains("send") || it.contains("ارسال") || it.contains("reply")
                    }
                val required = if (wantsSend) KaiSurfaceFamily.COMPOSER_SURFACE else KaiSurfaceFamily.DETAIL_SURFACE
                KaiTransitionIntent(
                    requiredFamily = required,
                    acceptableFamilies = if (wantsSend) {
                        setOf(KaiSurfaceFamily.COMPOSER_SURFACE, KaiSurfaceFamily.THREAD_SURFACE)
                    } else {
                        setOf(KaiSurfaceFamily.DETAIL_SURFACE, KaiSurfaceFamily.EDITOR_SURFACE, KaiSurfaceFamily.THREAD_SURFACE)
                    },
                    preferredIntermediateFamilies = setOf(KaiSurfaceFamily.THREAD_SURFACE, KaiSurfaceFamily.EDITOR_SURFACE),
                    preferredTransition = if (wantsSend) "reach_send_surface" else "reach_primary_action_surface",
                    recoveryAction = if (wantsSend) KaiRecoveryAction.NORMALIZE_APP_SURFACE else KaiRecoveryAction.REQUEST_FRESH_SCREEN,
                    reason = if (wantsSend) "send_requires_composer_or_thread" else "primary_action_needs_detail_or_editor"
                )
            }

            cmd == "open_best_list_item" -> {
                val isInstagram = state.packageName.contains("instagram", true)
                val isYouTube = state.packageName.contains("youtube", true)
                val isNotes = state.packageName.contains("notes", true) || state.packageName.contains("keep", true)

                when {
                    isInstagram && KaiSurfaceModel.isVerifiedInstagramCameraOverlay(state) -> KaiTransitionIntent(
                        requiredFamily = KaiSurfaceFamily.LIST_SURFACE,
                        acceptableFamilies = setOf(KaiSurfaceFamily.LIST_SURFACE),
                        preferredIntermediateFamilies = setOf(KaiSurfaceFamily.THREAD_SURFACE),
                        preferredTransition = "instagram_overlay_back_to_dm_list",
                        recoveryAction = KaiRecoveryAction.BACK,
                        reason = "instagram_camera_overlay_requires_dismiss_before_open_conversation"
                    )

                    isYouTube && !KaiSurfaceModel.isVerifiedYouTubeWorkingSurface(state) -> KaiTransitionIntent(
                        requiredFamily = KaiSurfaceFamily.LIST_SURFACE,
                        acceptableFamilies = setOf(KaiSurfaceFamily.CONTENT_FEED_SURFACE, KaiSurfaceFamily.LIST_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE),
                        preferredIntermediateFamilies = setOf(KaiSurfaceFamily.CONTENT_FEED_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE),
                        preferredTransition = "youtube_recover_and_reobserve",
                        recoveryAction = KaiRecoveryAction.REQUEST_FRESH_SCREEN,
                        reason = "youtube_open_list_item_requires_verified_youtube_working_surface"
                    )

                    isNotes -> KaiTransitionIntent(
                        requiredFamily = KaiSurfaceFamily.LIST_SURFACE,
                        acceptableFamilies = setOf(KaiSurfaceFamily.LIST_SURFACE),
                        preferredIntermediateFamilies = setOf(KaiSurfaceFamily.EDITOR_SURFACE),
                        preferredTransition = "notes_list_to_editor_transition",
                        recoveryAction = when {
                            family == KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE -> KaiRecoveryAction.DISMISS_SHEET
                            family == KaiSurfaceFamily.SEARCH_SURFACE -> KaiRecoveryAction.BACK
                            else -> KaiRecoveryAction.NORMALIZE_APP_SURFACE
                        },
                        reason = "notes_open_item_requires_verified_notes_list_or_editor_transition"
                    )

                    else -> KaiTransitionIntent(
                        requiredFamily = KaiSurfaceFamily.LIST_SURFACE,
                        acceptableFamilies = setOf(KaiSurfaceFamily.LIST_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE),
                        preferredIntermediateFamilies = setOf(KaiSurfaceFamily.SEARCH_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE),
                        preferredTransition = "reach_entity_list_surface",
                        recoveryAction = when {
                            family == KaiSurfaceFamily.SEARCH_SURFACE -> KaiRecoveryAction.RETURN_TO_LIST
                            family == KaiSurfaceFamily.THREAD_SURFACE -> KaiRecoveryAction.BACK
                            else -> KaiRecoveryAction.NORMALIZE_APP_SURFACE
                        },
                        reason = "open_entity_requires_list_context"
                    )
                }
            }

            cmd == "verify_state" && expectedKind in setOf("chat_thread", "instagram_dm_thread") -> KaiTransitionIntent(
                requiredFamily = KaiSurfaceFamily.THREAD_SURFACE,
                acceptableFamilies = setOf(KaiSurfaceFamily.THREAD_SURFACE, KaiSurfaceFamily.COMPOSER_SURFACE),
                preferredTransition = "verify_thread_surface",
                recoveryAction = KaiRecoveryAction.NORMALIZE_APP_SURFACE,
                reason = "verify_thread_requires_thread_surface"
            )

            cmd == "verify_state" && expectedKind in setOf("chat_list", "instagram_dm_list", "list") -> KaiTransitionIntent(
                requiredFamily = KaiSurfaceFamily.LIST_SURFACE,
                acceptableFamilies = setOf(KaiSurfaceFamily.LIST_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE),
                preferredTransition = "verify_list_surface",
                recoveryAction = KaiRecoveryAction.RETURN_TO_LIST,
                reason = "verify_list_requires_list_surface"
            )

            cmd == "verify_state" && expectedKind in setOf("notes_editor", "notes_body_input", "notes_title_input", "editor") -> KaiTransitionIntent(
                requiredFamily = KaiSurfaceFamily.EDITOR_SURFACE,
                acceptableFamilies = setOf(KaiSurfaceFamily.EDITOR_SURFACE),
                preferredTransition = "verify_editor_surface",
                recoveryAction = KaiRecoveryAction.NORMALIZE_APP_SURFACE,
                reason = "verify_editor_requires_editor_surface"
            )

            cmd == "click_best_match" -> KaiTransitionIntent(
                requiredFamily = KaiSurfaceFamily.LIST_SURFACE,
                acceptableFamilies = setOf(
                    KaiSurfaceFamily.LIST_SURFACE,
                    KaiSurfaceFamily.RESULT_LIST_SURFACE,
                    KaiSurfaceFamily.SEARCH_SURFACE,
                    KaiSurfaceFamily.TABBED_HOME_SURFACE,
                    KaiSurfaceFamily.CONTENT_FEED_SURFACE
                ),
                preferredIntermediateFamilies = setOf(KaiSurfaceFamily.SEARCH_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE),
                preferredTransition = "click_match_in_navigation_context",
                recoveryAction = KaiRecoveryAction.REQUEST_FRESH_SCREEN,
                reason = "best_match_prefers_list_or_search_context"
            )

            else -> KaiTransitionIntent(
                requiredFamily = family,
                acceptableFamilies = setOf(family),
                recoveryAction = KaiRecoveryAction.NONE
            )
        }
    }

    fun isFamilyCompatible(current: KaiSurfaceFamily, intent: KaiTransitionIntent): Boolean {
        val normalizedCurrent = KaiSurfaceModel.normalizeLegacyFamily(current)
        val accepted = mutableSetOf<KaiSurfaceFamily>()
        accepted += KaiSurfaceModel.normalizeLegacyFamily(intent.requiredFamily)
        accepted += intent.acceptableFamilies.map { KaiSurfaceModel.normalizeLegacyFamily(it) }
        val equivalent = accepted.flatMap { KaiSurfaceModel.equivalentFamilies(it) }.toSet()
        return normalizedCurrent in equivalent
    }

    fun assessCurrentSurface(step: KaiActionStep, state: KaiScreenState): KaiTransitionSurfaceAssessment {
        val cmd = step.cmd.trim().lowercase()
        val intent = inferIntent(step, state)
        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        val compatible = isFamilyCompatible(family, intent)

        val preferredIntermediateFamilies = intent.preferredIntermediateFamilies
            .map { KaiSurfaceModel.normalizeLegacyFamily(it) }
            .flatMap { KaiSurfaceModel.equivalentFamilies(it) }
            .toSet()

        val preferredIntermediate = family in preferredIntermediateFamilies

        val status = when {
            cmd == "open_app" && compatible && KaiSurfaceModel.isPostOpenReadyFamily(family) -> KaiSurfaceStatus.TARGET_READY
            cmd == "open_app" && preferredIntermediate -> KaiSurfaceStatus.USABLE_INTERMEDIATE
            compatible -> KaiSurfaceStatus.TARGET_READY
            preferredIntermediate -> KaiSurfaceStatus.USABLE_INTERMEDIATE
            KaiSurfaceModel.isDeadEndFamily(family) -> KaiSurfaceStatus.DEAD_END
            KaiSurfaceModel.isRecoverableFamily(family) || intent.recoveryAction != KaiRecoveryAction.NONE -> KaiSurfaceStatus.WRONG_BUT_RECOVERABLE
            else -> KaiSurfaceStatus.DEAD_END
        }

        return KaiTransitionSurfaceAssessment(
            family = family,
            targetFamily = intent.requiredFamily,
            status = status,
            compatible = compatible,
            preferredIntermediate = preferredIntermediate,
            reason = intent.reason
        )
    }
}

// ── KaiSurfaceActionPolicy ──────────────────────────────────────────────

object KaiSurfaceActionPolicy {
    enum class Legality {
        ALLOWED,
        DISCOURAGED_RECOVERABLE,
        ILLEGAL
    }

    data class Decision(
        val allowed: Boolean,
        val reason: String = "",
        val legality: Legality = if (allowed) Legality.ALLOWED else Legality.ILLEGAL,
        val recoverable: Boolean = false,
        val recoveryReason: String = "",
        val preferredTransition: String = "",
        val targetFamily: KaiSurfaceFamily = KaiSurfaceFamily.UNKNOWN_SURFACE
    )

    fun evaluate(step: KaiActionStep, state: KaiScreenState): Decision {
        val cmd = step.cmd.trim().lowercase()
        val transition = KaiSurfaceTransitionPolicy.inferIntent(step, state)
        val surface = KaiSurfaceTransitionPolicy.assessCurrentSurface(step, state)
        val family = surface.family
        val familyCompatible = surface.compatible || surface.status == KaiSurfaceStatus.TARGET_READY

        return when (cmd) {
            "focus_best_input", "input_into_best_field" -> {
                if (familyCompatible) {
                    Decision(
                        allowed = true,
                        legality = Legality.ALLOWED,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                } else if (surface.status == KaiSurfaceStatus.USABLE_INTERMEDIATE || transition.recoveryAction != KaiRecoveryAction.NONE) {
                    Decision(
                        allowed = false,
                        reason = "recoverable_surface_for_input:$family",
                        legality = Legality.DISCOURAGED_RECOVERABLE,
                        recoverable = true,
                        recoveryReason = transition.reason,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                } else {
                    Decision(
                        allowed = false,
                        reason = "illegal_surface_for_input:$family",
                        legality = Legality.ILLEGAL,
                        recoverable = false,
                        targetFamily = transition.requiredFamily
                    )
                }
            }

            "press_primary_action" -> {
                val wantsSend = step.selectorRole.equals("send_button", true) ||
                    KaiScreenStateParser.normalize(step.selectorText.ifBlank { step.text }).let {
                        it.contains("send") || it.contains("ارسال") || it.contains("reply")
                    }
                if (wantsSend && !familyCompatible) {
                    Decision(
                        allowed = false,
                        reason = "illegal_surface_for_send:$family",
                        legality = Legality.DISCOURAGED_RECOVERABLE,
                        recoverable = true,
                        recoveryReason = transition.reason,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                } else {
                    Decision(
                        allowed = true,
                        legality = Legality.ALLOWED,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                }
            }

            "open_best_list_item" -> {
                if (familyCompatible || state.isInstagramMessagesEntrySurface()) {
                    Decision(
                        allowed = true,
                        legality = Legality.ALLOWED,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                } else {
                    Decision(
                        allowed = false,
                        reason = "illegal_surface_for_open_list_item:$family",
                        legality = Legality.DISCOURAGED_RECOVERABLE,
                        recoverable = true,
                        recoveryReason = transition.reason,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                }
            }

            "verify_state" -> {
                val stageContinuationVerify =
                    step.note.contains("stage_continuation", ignoreCase = true) &&
                        !step.note.contains("fallback", ignoreCase = true)
                val ambiguousSurface =
                    state.isWeakObservation() ||
                        state.isSearchLikeSurface() ||
                        state.isCameraOrMediaOverlaySurface() ||
                        state.isSheetOrDialogSurface()

                if (stageContinuationVerify && !ambiguousSurface) {
                    return Decision(
                        allowed = false,
                        reason = "stage_continuation_prefers_semantic_action:$family",
                        legality = Legality.DISCOURAGED_RECOVERABLE,
                        recoverable = true,
                        recoveryReason = "stage_continuation_should_execute_next_semantic_action",
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                }

                if (familyCompatible) {
                    Decision(
                        allowed = true,
                        legality = Legality.ALLOWED,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                } else {
                    Decision(
                        allowed = false,
                        reason = "verify_requires_transition:$family",
                        legality = Legality.DISCOURAGED_RECOVERABLE,
                        recoverable = true,
                        recoveryReason = transition.reason,
                        preferredTransition = transition.preferredTransition,
                        targetFamily = transition.requiredFamily
                    )
                }
            }

            "click_best_match", "open_app" -> Decision(
                allowed = true,
                legality = Legality.ALLOWED,
                preferredTransition = transition.preferredTransition,
                targetFamily = transition.requiredFamily
            )

            else -> Decision(allowed = true)
        }
    }
}

// ── KaiRecoveryPolicy ──────────────────────────────────────────────

object KaiRecoveryPolicy {
    data class RecoveryDecision(
        val needsRecovery: Boolean,
        val reason: String = "",
        val recommendedAction: KaiRecoveryAction = KaiRecoveryAction.NONE,
        val targetFamily: KaiSurfaceFamily = KaiSurfaceFamily.UNKNOWN_SURFACE,
        val retryAllowed: Boolean = true,
        val breakChunkIfRepeated: Boolean = false
    )

    private fun isLauncherPackage(packageName: String): Boolean {
        val p = packageName.trim().lowercase()
        return p.contains("launcher") || p.contains("home") || p.contains("pixel") || p.contains("trebuchet")
    }

    fun recoveryContextSemanticKey(step: KaiActionStep, state: KaiScreenState): String {
        val cmd = step.cmd.trim().lowercase()
        val stageKey = when (cmd) {
            "open_app" -> "OPEN_TARGET_APP"
            "click_best_match", "verify_state" -> "REACH_TARGET_SURFACE"
            "open_best_list_item" -> "OPEN_TARGET_ENTITY"
            "focus_best_input" -> "FOCUS_TARGET_INPUT"
            "input_into_best_field", "input_text" -> "WRITE_PAYLOAD"
            "press_primary_action" -> "SUBMIT_OR_SEND"
            else -> "VERIFY_COMPLETION"
        }
        return "$cmd|stage=$stageKey|${state.recoverySemanticKey()}"
    }

    fun shouldRecoverForStep(state: KaiScreenState, step: KaiActionStep): RecoveryDecision {
        val family = KaiSurfaceModel.normalizeLegacyFamily(KaiSurfaceModel.familyOf(state))
        val cmd = step.cmd.trim().lowercase()
        val intent = KaiSurfaceTransitionPolicy.inferIntent(step, state)
        val assessment = KaiSurfaceTransitionPolicy.assessCurrentSurface(step, state)
        val currentCompatible = assessment.status == KaiSurfaceStatus.TARGET_READY || assessment.status == KaiSurfaceStatus.USABLE_INTERMEDIATE

        if (currentCompatible) {
            return RecoveryDecision(false)
        }

        if (assessment.status == KaiSurfaceStatus.DEAD_END) {
            return RecoveryDecision(
                needsRecovery = true,
                reason = "recover_dead_end_surface:${KaiSurfaceModel.familyName(family)}",
                recommendedAction = if (cmd == "open_app") KaiRecoveryAction.BREAK_FOR_REPLAN else KaiRecoveryAction.NORMALIZE_APP_SURFACE,
                targetFamily = intent.requiredFamily,
                retryAllowed = true,
                breakChunkIfRepeated = true
            )
        }

        if (family == KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE || family == KaiSurfaceFamily.MODAL_SURFACE) {
            return RecoveryDecision(
                needsRecovery = true,
                reason = "recover_dismiss_sheet_or_dialog:${KaiSurfaceModel.familyName(family)}",
                recommendedAction = KaiRecoveryAction.DISMISS_SHEET,
                targetFamily = intent.requiredFamily,
                retryAllowed = true,
                breakChunkIfRepeated = true
            )
        }

        if (cmd in setOf("focus_best_input", "input_into_best_field", "press_primary_action") &&
            KaiSurfaceModel.isWrongForInputOrSend(family)
        ) {
            return RecoveryDecision(
                needsRecovery = true,
                reason = "recover_wrong_surface_for_input_send:${KaiSurfaceModel.familyName(family)}",
                recommendedAction = when (family) {
                    KaiSurfaceFamily.SEARCH_SURFACE -> KaiRecoveryAction.BACK
                    KaiSurfaceFamily.RESULT_LIST_SURFACE, KaiSurfaceFamily.LIST_SURFACE -> KaiRecoveryAction.RETURN_TO_LIST
                    KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE -> KaiRecoveryAction.BACK
                    else -> KaiRecoveryAction.NORMALIZE_APP_SURFACE
                },
                targetFamily = intent.requiredFamily,
                retryAllowed = true,
                breakChunkIfRepeated = true
            )
        }

        if (cmd == "open_best_list_item" && family !in setOf(KaiSurfaceFamily.LIST_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE, KaiSurfaceFamily.TABBED_HOME_SURFACE)) {
            return RecoveryDecision(
                needsRecovery = true,
                reason = "recover_wrong_surface_for_open_entity:${KaiSurfaceModel.familyName(family)}",
                recommendedAction = when (family) {
                    KaiSurfaceFamily.THREAD_SURFACE, KaiSurfaceFamily.COMPOSER_SURFACE -> KaiRecoveryAction.BACK
                    KaiSurfaceFamily.SEARCH_SURFACE -> KaiRecoveryAction.RETURN_TO_LIST
                    else -> KaiRecoveryAction.NORMALIZE_APP_SURFACE
                },
                targetFamily = KaiSurfaceFamily.LIST_SURFACE,
                retryAllowed = true,
                breakChunkIfRepeated = true
            )
        }

        if (cmd == "verify_state") {
            if (isLauncherPackage(state.packageName)) {
                return RecoveryDecision(
                    needsRecovery = false,
                    reason = "verify_state_requires_open_app_from_launcher",
                    recommendedAction = KaiRecoveryAction.NONE,
                    targetFamily = intent.requiredFamily,
                    retryAllowed = true,
                    breakChunkIfRepeated = false
                )
            }

            return RecoveryDecision(
                needsRecovery = true,
                reason = "recover_verify_transition_required:${KaiSurfaceModel.familyName(family)}",
                recommendedAction = if (intent.recoveryAction != KaiRecoveryAction.NONE) intent.recoveryAction else KaiRecoveryAction.REQUEST_FRESH_SCREEN,
                targetFamily = intent.requiredFamily,
                retryAllowed = true,
                breakChunkIfRepeated = true
            )
        }

        return RecoveryDecision(false)
    }
}

// Execution authority and stage engine were extracted to KaiExecutionAuthority.kt


object KaiLiveSurfacePolicy {
    fun isUsableForOpenApp(state: KaiScreenState, expectedPackage: String = ""): Boolean {
        return state.isKaiLiveStrong(expectedPackage = expectedPackage, allowLauncherSurface = false) &&
            KaiSurfaceModel.isPostOpenReadyFamily(KaiSurfaceModel.familyOf(state))
    }

    fun isUsableForSemanticAction(state: KaiScreenState, expectedPackage: String = ""): Boolean {
        val readiness = KaiObservationReadiness.evaluate(
            state = state,
            expectedPackage = expectedPackage,
            allowLauncherSurface = false,
            tier = KaiObservationReadiness.Tier.SEMANTIC_ACTION_SAFE
        )
        return readiness.passed
    }
}
