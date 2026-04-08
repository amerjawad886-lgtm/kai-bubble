package com.example.reply.agent

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
        val stage = KaiGoalInterpreter.inferStageForStep(step)
        return "$cmd|stage=$stage|${state.recoverySemanticKey()}"
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
