package com.example.reply.agent

import java.util.Locale

/**
 * Plan post-processing + launcher heuristics.
 *
 * Extracted from KaiAgentController as part of the live-vision migration.
 * Stateless: takes a parsed [KaiActionPlan] plus the current targeting
 * [KaiScreenState] (produced via an on-demand accessibility snapshot — NOT
 * a standing observation cache) and returns a cleaned plan:
 *
 *  - If the user is on a launcher/home surface and the inferred target app
 *    doesn't match the current package, inject / promote an `open_app` step
 *    so the agent always enters the app before attempting semantic actions.
 *  - Cap consecutive verify/read/wait-style steps to avoid stall loops.
 *  - Enforce the per-chunk step budget.
 *
 * Behavior is intentionally identical to the old in-controller implementation;
 * any change here must be deliberate.
 */
object KaiActionPlanPostProcessor {

    fun inferPrimaryAppHint(prompt: String): String =
        KaiScreenStateParser.inferAppHint(prompt)

    @Suppress("UNUSED_PARAMETER")
    fun postProcessPlan(
        plan: KaiActionPlan,
        currentScreenState: KaiScreenState,
        appHint: String,
        maxStepsPerChunk: Int,
        priorProgress: String
    ): KaiActionPlan {
        val onLauncher = isLauncherPackage(currentScreenState.packageName)
        val currentMatches = currentScreenState.likelyMatchesAppHint(appHint) && !onLauncher
        val maxSteps = maxStepsPerChunk.coerceIn(1, 8)
        val steps = plan.steps.filter { it.cmd.isNotBlank() }.toMutableList()

        if (steps.isEmpty() && onLauncher && appHint.isNotBlank() && !currentMatches) {
            return plan.copy(
                goalComplete = false,
                plannerGoalComplete = false,
                summary = "Starting from launcher/home: opening target app first.",
                steps = listOf(
                    KaiActionStep(
                        cmd = "open_app",
                        text = appHint,
                        note = "launcher_requires_open_app_first",
                        completionBoundary = KaiGoalBoundary.APP_ENTRY,
                        continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                        allowsFinalCommit = false
                    )
                )
            )
        }

        if (onLauncher && appHint.isNotBlank() && !currentMatches) {
            val existingOpen = steps.firstOrNull { it.cmd == "open_app" }
            val openFirst = existingOpen?.copy(
                text = existingOpen.text.ifBlank { appHint },
                note = existingOpen.note.ifBlank { "launcher_requires_open_app_first" },
                completionBoundary = KaiGoalBoundary.APP_ENTRY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            ) ?: KaiActionStep(
                cmd = "open_app",
                text = appHint,
                note = "launcher_requires_open_app_first",
                completionBoundary = KaiGoalBoundary.APP_ENTRY,
                continuationKind = KaiContinuationKind.STAGE_CONTINUATION,
                allowsFinalCommit = false
            )

            val rebuilt = mutableListOf<KaiActionStep>()
            rebuilt += openFirst
            rebuilt += steps.filterNot { it.cmd == "open_app" }

            return plan.copy(
                goalComplete = false,
                plannerGoalComplete = false,
                summary = "Starting from launcher/home: open target app before semantic actions.",
                steps = rebuilt.take(maxSteps)
            )
        }

        val cleanedSteps = buildList {
            var verifyRun = 0
            steps.forEach { step ->
                if (step.cmd in setOf("verify_state", "read_screen", "wait_for_text")) {
                    verifyRun += 1
                    if (verifyRun > 2) return@forEach
                } else {
                    verifyRun = 0
                }
                add(step)
            }
        }

        return plan.copy(
            steps = cleanedSteps.take(maxSteps),
            goalComplete = false,
            plannerGoalComplete = false
        )
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.getDefault())
        return p.contains("launcher") || p.contains("home") || p.contains("pixel") || p.contains("trebuchet")
    }
}
