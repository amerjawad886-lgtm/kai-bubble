package com.example.reply.agent

import android.content.Context
import android.content.Intent
import com.example.reply.ui.KaiAccessibilityService

/**
 * Step → accessibility broadcast dispatcher.
 *
 * Extracted from KaiAgentController as part of the live-vision migration.
 * Stateless: converts a normalized [KaiActionStep] into the matching
 * KAI_COMMAND broadcast and fires it at KaiAccessibilityService, which is
 * the action-execution endpoint (NOT the truth source — world-state lives in
 * KaiLiveVisionRuntime and targeting in KaiAccessibilitySnapshotBridge).
 *
 * Behavior is intentionally identical to the old in-controller implementation;
 * any change here must be deliberate.
 */
object KaiStepDispatcher {

    fun dispatchStepCommand(context: Context, step: KaiActionStep) {
        val cmd = step.normalizedCommand()
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            if (step.expectedPackage.isNotBlank()) {
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, step.expectedPackage)
            }
        }

        when (cmd) {
            "open_app" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_OPEN_APP)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.text)
            }
            "click_text", "click_best_match", "open_best_list_item" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_CLICK_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.semanticPayload())
            }
            "long_press_text" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_LONG_PRESS_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.semanticPayload())
                intent.putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, step.holdMs)
            }
            "input_text", "focus_best_input", "input_into_best_field" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_INPUT_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.text)
            }
            "press_primary_action" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_CLICK_TEXT)
                intent.putExtra(KaiAccessibilityService.EXTRA_TEXT, step.text.ifBlank { "submit" })
            }
            "scroll" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_SCROLL)
                intent.putExtra(KaiAccessibilityService.EXTRA_DIR, step.dir.ifBlank { "down" })
                intent.putExtra(KaiAccessibilityService.EXTRA_TIMES, step.times.coerceIn(1, 10))
            }
            "tap_xy" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_TAP_XY)
                step.x?.let { intent.putExtra(KaiAccessibilityService.EXTRA_X, it) }
                step.y?.let { intent.putExtra(KaiAccessibilityService.EXTRA_Y, it) }
            }
            "long_press_xy" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_LONG_PRESS_XY)
                step.x?.let { intent.putExtra(KaiAccessibilityService.EXTRA_X, it) }
                step.y?.let { intent.putExtra(KaiAccessibilityService.EXTRA_Y, it) }
                intent.putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, step.holdMs)
            }
            "swipe_xy" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_SWIPE_XY)
                step.x?.let { intent.putExtra(KaiAccessibilityService.EXTRA_X, it) }
                step.y?.let { intent.putExtra(KaiAccessibilityService.EXTRA_Y, it) }
                step.endX?.let { intent.putExtra(KaiAccessibilityService.EXTRA_END_X, it) }
                step.endY?.let { intent.putExtra(KaiAccessibilityService.EXTRA_END_Y, it) }
                intent.putExtra(KaiAccessibilityService.EXTRA_HOLD_MS, step.holdMs)
            }
            "back" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_BACK)
            }
            "home" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_HOME)
            }
            "recents" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_RECENTS)
            }
            "verify_state", "read_screen", "wait_for_text" -> {
                intent.putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
            }
            else -> return
        }

        context.sendBroadcast(intent)
    }
}
