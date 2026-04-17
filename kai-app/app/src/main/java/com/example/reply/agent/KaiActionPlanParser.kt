package com.example.reply.agent

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON → KaiActionPlan parser.
 *
 * Extracted from KaiAgentController as part of the live-vision migration so the
 * controller can shrink toward a thin coordinator. This object is stateless and
 * has no coupling to runtime/world-state — it only converts a planner LLM
 * response string into a typed [KaiActionPlan].
 *
 * Behavior is intentionally identical to the old in-controller implementation;
 * any change here must be deliberate.
 */
object KaiActionPlanParser {

    private val ALLOWED_COMMANDS: Set<String> = setOf(
        "open_app", "click_text", "long_press_text", "input_text",
        "click_best_match", "focus_best_input", "input_into_best_field",
        "press_primary_action", "open_best_list_item", "verify_state",
        "scroll", "read_screen", "wait_for_text", "tap_xy", "long_press_xy",
        "swipe_xy", "back", "home", "recents", "wait"
    )

    fun parseActionPlan(raw: String): KaiActionPlan {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val jsonText = when {
            clean.startsWith("{") && clean.endsWith("}") -> clean
            clean.contains("{") && clean.contains("}") ->
                clean.substring(clean.indexOf('{'), clean.lastIndexOf('}') + 1)
            else -> throw IllegalArgumentException("Planner did not return valid JSON.")
        }

        val obj = JSONObject(jsonText)
        val summary = obj.optString("summary").ifBlank { "Action plan generated." }
        val goalComplete = obj.optBoolean("goalComplete", false)
        val stepsJson = obj.optJSONArray("steps") ?: JSONArray()

        val steps = buildList {
            for (i in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(i) ?: continue
                val cmd = item.optString("cmd").trim().lowercase(Locale.ROOT)
                if (cmd.isBlank() || cmd !in ALLOWED_COMMANDS) continue
                add(
                    KaiActionStep(
                        cmd = cmd,
                        text = item.optString("text").trim(),
                        dir = item.optString("dir").trim(),
                        times = item.optInt("times", 1).coerceIn(1, 10),
                        waitMs = item.optLong("waitMs", 500L).coerceIn(80L, 12000L),
                        x = item.optFloatOrNull("x"),
                        y = item.optFloatOrNull("y"),
                        endX = item.optFloatOrNull("endX"),
                        endY = item.optFloatOrNull("endY"),
                        holdMs = item.optLong("holdMs", 450L).coerceIn(80L, 8000L),
                        timeoutMs = item.optLong("timeoutMs", 4000L).coerceIn(500L, 18000L),
                        optional = item.optBoolean("optional", false),
                        note = item.optString("note").trim(),
                        selectorText = item.optString("selectorText").trim(),
                        selectorHint = item.optString("selectorHint").trim(),
                        selectorId = item.optString("selectorId").trim(),
                        selectorRole = item.optString("selectorRole").trim(),
                        expectedPackage = item.optString("expectedPackage").trim(),
                        expectedTexts = item.optStringList("expectedTexts"),
                        expectedScreenKind = item.optString("expectedScreenKind").trim(),
                        strategy = item.optString("strategy").trim(),
                        confidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                        completionBoundary = when (KaiScreenStateParser.normalize(item.optString("completionBoundary"))) {
                            "app_entry" -> KaiGoalBoundary.APP_ENTRY
                            "surface_ready" -> KaiGoalBoundary.SURFACE_READY
                            "entity_opened" -> KaiGoalBoundary.ENTITY_OPENED
                            "input_ready" -> KaiGoalBoundary.INPUT_READY
                            "content_committed" -> KaiGoalBoundary.CONTENT_COMMITTED
                            "final_goal" -> KaiGoalBoundary.FINAL_GOAL
                            else -> KaiGoalBoundary.UNKNOWN
                        },
                        continuationKind = when (KaiScreenStateParser.normalize(item.optString("continuationKind"))) {
                            "stage_continuation" -> KaiContinuationKind.STAGE_CONTINUATION
                            "recovery_continuation" -> KaiContinuationKind.RECOVERY_CONTINUATION
                            "verification" -> KaiContinuationKind.VERIFICATION
                            else -> KaiContinuationKind.NONE
                        },
                        allowsFinalCommit = item.optBoolean("allowsFinalCommit", false)
                    )
                )
            }
        }

        return KaiActionPlan(
            summary = summary,
            steps = steps,
            goalComplete = goalComplete,
            plannerGoalComplete = goalComplete
        )
    }

    private fun JSONObject.optFloatOrNull(name: String): Float? {
        if (!has(name) || isNull(name)) return null
        val value = optDouble(name, Double.NaN)
        return if (value.isNaN()) null else value.toFloat()
    }

    private fun JSONObject.optStringList(name: String): List<String> {
        if (!has(name) || isNull(name)) return emptyList()
        val arr = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotBlank()) add(item)
            }
        }
    }
}
