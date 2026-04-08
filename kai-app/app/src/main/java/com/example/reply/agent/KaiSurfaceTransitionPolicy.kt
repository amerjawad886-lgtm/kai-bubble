package com.example.reply.agent

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
