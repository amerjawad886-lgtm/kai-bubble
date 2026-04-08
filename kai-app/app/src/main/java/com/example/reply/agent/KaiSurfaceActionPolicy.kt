package com.example.reply.agent

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
