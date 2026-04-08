package com.example.reply.agent

enum class KaiGoalStage {
    OPEN_TARGET_APP,
    REACH_TARGET_SURFACE,
    LOCATE_TARGET_ENTITY,
    OPEN_TARGET_ENTITY,
    FOCUS_TARGET_INPUT,
    WRITE_PAYLOAD,
    SUBMIT_OR_SEND,
    VERIFY_COMPLETION,
    RECOVER_WRONG_SURFACE
}

object KaiGoalInterpreter {
    fun inferStages(prompt: String, appHint: String): List<KaiGoalStage> {
        val p = KaiScreenStateParser.normalize(prompt)
        val stages = mutableListOf<KaiGoalStage>()

        if (appHint.isNotBlank()) stages += KaiGoalStage.OPEN_TARGET_APP
        stages += KaiGoalStage.REACH_TARGET_SURFACE

        val wantsSearch = containsAny(p, "search", "find", "lookup", "browse", "look for", "ابحث", "بحث")
        val wantsEntity = containsAny(
            p,
            "conversation", "chat", "thread", "open", "note", "entity", "song", "video", "playlist", "result",
            "محادثة", "ملاحظة", "اغنية", "أغنية", "فيديو", "نتيجة"
        )
        val wantsWrite = containsAny(p, "write", "type", "reply", "compose", "draft", "create", "اكتب", "حرر", "إنشاء", "انشاء")
        val wantsSend = containsAny(p, "send", "submit", "post", "publish", "reply", "ارسال", "إرسال")
        val wantsPlayback = containsAny(p, "play", "start", "playback", "watch", "listen", "شغل", "ابدأ", "شاهد", "اسمع")
        val wantsRecovery = containsAny(p, "back", "رجوع", "recover", "retry")

        if (wantsRecovery) stages += KaiGoalStage.RECOVER_WRONG_SURFACE

        if (wantsEntity) {
            stages += KaiGoalStage.LOCATE_TARGET_ENTITY
            stages += KaiGoalStage.OPEN_TARGET_ENTITY
        }
        if (wantsSearch && !wantsEntity) {
            stages += KaiGoalStage.LOCATE_TARGET_ENTITY
        }
        if (wantsWrite) {
            stages += KaiGoalStage.FOCUS_TARGET_INPUT
            stages += KaiGoalStage.WRITE_PAYLOAD
        }
        if (wantsSend) stages += KaiGoalStage.SUBMIT_OR_SEND
        if (wantsPlayback && !wantsSend && !wantsWrite) stages += KaiGoalStage.OPEN_TARGET_ENTITY
        stages += KaiGoalStage.VERIFY_COMPLETION

        return stages.distinct()
    }

    fun inferStageForStep(step: KaiActionStep): KaiGoalStage {
        return when (step.cmd.trim().lowercase()) {
            "open_app" -> KaiGoalStage.OPEN_TARGET_APP
            "click_best_match", "verify_state" -> KaiGoalStage.REACH_TARGET_SURFACE
            "open_best_list_item" -> KaiGoalStage.OPEN_TARGET_ENTITY
            "focus_best_input" -> KaiGoalStage.FOCUS_TARGET_INPUT
            "input_into_best_field", "input_text" -> KaiGoalStage.WRITE_PAYLOAD
            "press_primary_action" -> KaiGoalStage.SUBMIT_OR_SEND
            else -> KaiGoalStage.VERIFY_COMPLETION
        }
    }

    private fun containsAny(text: String, vararg values: String): Boolean {
        return values.any { text.contains(KaiScreenStateParser.normalize(it)) }
    }
}
