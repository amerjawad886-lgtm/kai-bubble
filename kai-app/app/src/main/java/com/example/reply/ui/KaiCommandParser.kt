package com.example.reply.ui

import com.example.reply.agent.KaiAppIdentityRegistry
import java.util.Locale

sealed class KaiParsedCommand {
    data object Stop : KaiParsedCommand()
    data object ReadScreen : KaiParsedCommand()
    data object AnalyzeScreen : KaiParsedCommand()
    data object Report : KaiParsedCommand()
    data object ToggleAgent : KaiParsedCommand()
    data object SoftReset : KaiParsedCommand()
    data object Back : KaiParsedCommand()
    data object Home : KaiParsedCommand()
    data object Recents : KaiParsedCommand()
    data class Scroll(val dir: String, val times: Int) : KaiParsedCommand()
    data class Click(val target: String) : KaiParsedCommand()
    data class TypeText(val text: String) : KaiParsedCommand()
    data class OpenApp(val appName: String) : KaiParsedCommand()
    data class Ask(val text: String) : KaiParsedCommand()
    data class SaveMemory(val rawText: String, val value: String) : KaiParsedCommand()
}

object KaiCommandParser {
    private val appAliases by lazy {
        KaiAppIdentityRegistry.supportedAppKeys().associateWith { key ->
            KaiAppIdentityRegistry.aliasesForKey(key)
        }
    }

    private val uiElementAliases = mapOf(
        "messages" to listOf("messages", "message", "رسائل", "الرسائل", "المحادثات", "محادثات", "chat", "chats", "inbox"),
        "comments" to listOf("comments", "comment", "التعليقات", "تعليقات", "comment section"),
        "send" to listOf("send", "ارسال", "إرسال"),
        "search" to listOf("search", "بحث"),
        "notifications" to listOf("notifications", "notification", "الاشعارات", "إشعارات"),
        "profile" to listOf("profile", "account", "البروفايل", "الملف الشخصي", "الحساب"),
        "home" to listOf("home", "الرئيسية", "الصفحة الرئيسية"),
        "reels" to listOf("reels", "ريلز"),
        "explore" to listOf("explore", "استكشاف"),
        "likes" to listOf("likes", "like", "اعجاب", "إعجاب", "لايك"),
        "share" to listOf("share", "مشاركة", "مشاركه")
    )

    private val openVerbs = listOf(
        "open", "launch", "start", "run", "go to",
        "افتح", "شغل", "ابدأ", "روح", "انتقل"
    )

    private val clickVerbs = listOf(
        "click", "press", "tap", "select", "choose",
        "اضغط", "اكبس", "اختر", "حدد"
    )

    private val typeVerbs = listOf(
        "type", "write", "enter", "input",
        "اكتب", "ادخل", "أدخل"
    )

    private val scrollVerbs = listOf(
        "scroll", "move", "swipe", "لف", "اسكرول", "مرر", "حرك"
    )

    private val askPrefixes = listOf(
        "what", "why", "how", "who", "tell me", "explain",
        "ماذا", "ليش", "لماذا", "كيف", "من", "اشرح", "قل لي"
    )

    private val monitoringAliases = listOf(
        "toggle agent", "agent mode", "agent", "monitor", "monitoring", "watch screen", "watch the screen",
        "start monitoring", "stop monitoring", "eye mode", "kai eye", "eye",
        "ابدأ الوكيل", "شغل الوكيل", "وقف الوكيل", "الوضع الوكيل",
        "المراقبه", "المراقبة", "راقب", "راقب الشاشه", "راقب الشاشة", "العين", "عين كاي"
    )

    fun norm(text: String): String =
        text
            .lowercase(Locale.ROOT)
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .replace("ؤ", "و")
            .replace("ئ", "ي")
            .replace("ـ", "")
            .replace(Regex("""[\p{Punct}&&[^@._-]]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun containsAny(base: String, vararg tokens: String): Boolean {
        val normalizedBase = norm(base)
        return tokens.any { normalizedBase.contains(norm(it)) }
    }

    fun resolveAppAlias(raw: String): String {
        return KaiAppIdentityRegistry.resolveAppKey(raw).ifBlank { raw.trim() }
    }

    private fun findAppAliasIn(raw: String): String? {
        return KaiAppIdentityRegistry.resolveAppKey(raw).ifBlank { null }
    }

    private fun findUiElementAliasIn(raw: String): String? {
        val value = norm(raw)
        uiElementAliases.forEach { (_, aliases) ->
            aliases.firstOrNull { value.contains(norm(it)) }?.let { return it }
        }
        return null
    }

    fun extractTimes(text: String): Int {
        Regex("""\b(\d{1,2})\b""").find(text)?.value?.toIntOrNull()?.let {
            return it.coerceIn(1, 10)
        }

        val lower = norm(text)
        return when {
            containsAny(lower, "مرتين", "twice") -> 2
            containsAny(lower, "ثلاث", "three") -> 3
            containsAny(lower, "اربع", "ارب", "four") -> 4
            containsAny(lower, "خمس", "five") -> 5
            else -> 1
        }
    }

    fun looksLikeCommand(text: String): Boolean {
        val lower = norm(text)
        if (lower.isBlank()) return false

        if (
            containsAny(
                lower,
                "اقرا الشاشه", "read screen", "analyze screen", "حلل الشاشه",
                "open", "launch", "click", "press", "tap", "type", "write",
                "back", "home", "recents", "scroll", "stop",
                "agent", "report", "prompt", "custom prompt", "monitor", "watch screen", "eye",
                "remember", "save memory", "store this", "note this", "تذكر", "احفظ", "خزن", "دوّن",
                "يمين", "يسار", "فوق", "تحت", "right", "left", "up", "down"
            )
        ) return true

        return appAliases.values.flatten().any { lower.contains(norm(it)) }
    }

    fun pickBestRecognitionCandidate(candidates: List<String>): String {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return ""

        return cleaned.maxByOrNull { candidate ->
            val normalized = norm(candidate)
            var score = candidate.length

            if (looksLikeCommand(candidate)) score += 120

            if (Regex("""[A-Za-z]{3,}""").containsMatchIn(candidate)) {
                score += 40
            }

            if (
                Regex(
                    """\b(open|launch|click|tap|press|type|write|agent|report|prompt|instagram|whatsapp|chrome|notes|weather|remember|save|scroll|left|right|down|up|messages|comments|send)\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(candidate)
            ) {
                score += 60
            }

            if (appAliases.values.flatten().any { normalized.contains(norm(it)) }) {
                score += 80
            }

            if (containsAny(normalized, "افتح", "شغل", "launch", "open")) {
                score += 50
            }

            if (containsAny(normalized, "اضغط", "اكبس", "click", "tap", "press")) {
                score += 50
            }

            if (containsAny(normalized, "remember", "save", "احفظ", "تذكر", "خزن", "دوّن")) {
                score += 80
            }

            if (containsAny(normalized, "يمين", "يسار", "لفوق", "لتحت", "فوق", "تحت", "up", "down", "left", "right")) {
                score += 55
            }

            score
        }.orEmpty()
    }

    private fun extractAfter(text: String, vararg keys: String): String {
        keys.forEach { key ->
            val index = text.indexOf(key, ignoreCase = true)
            if (index >= 0) {
                return text.substring(index + key.length).trim()
            }
        }
        return ""
    }

    private fun extractAfterNormalizedPhrase(originalText: String, phrases: Array<String>): String {
        val lowerOriginal = originalText.lowercase(Locale.ROOT)
        for (phrase in phrases) {
            val idx = lowerOriginal.indexOf(phrase.lowercase(Locale.ROOT))
            if (idx >= 0) {
                return originalText.substring(idx + phrase.length).trim()
            }
        }
        return ""
    }

    private fun looksLikeAppName(target: String): Boolean {
        val value = norm(target)
        if (value.isBlank()) return false

        return appAliases.values.flatten().any { alias ->
            val candidate = norm(alias)
            value == candidate || value.contains(candidate) || candidate.contains(value)
        }
    }

    private fun cleanupTargetText(raw: String): String {
        return raw
            .replace(Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(على|الى|إلى)\s+"""), "")
            .replace(Regex("""^(app|application|برنامج|تطبيق)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun maybeStripOpenPrepositions(raw: String): String {
        return raw
            .replace(Regex("""^(the\s+)?app\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(تطبيق|برنامج)\s+"""), "")
            .trim()
    }

    private fun isQuestionLike(text: String): Boolean {
        val n = norm(text)
        if (n.isBlank()) return false
        if (text.contains("?") || text.contains("؟")) return true
        return askPrefixes.any { n.startsWith(norm(it)) }
    }

    private fun isLikelyFreeTextTyping(text: String): Boolean {
        val n = norm(text)
        if (n.isBlank()) return false
        if (n.length >= 18) return true
        if (text.contains("@") || text.contains(".com")) return true
        if (text.contains(" ")) return true
        return false
    }

    private fun parseMemoryIntent(text: String): KaiParsedCommand.SaveMemory? {
        val lower = norm(text)
        if (lower.isBlank()) return null

        val rememberKeys = arrayOf(
            "remember", "save memory", "save this", "store this", "note this",
            "تذكر", "احفظ", "خزن", "دوّن", "احفظ هذا", "تذكر هذا"
        )

        val matched = rememberKeys.firstOrNull { lower.contains(norm(it)) } ?: return null

        val value = extractAfterNormalizedPhrase(text, rememberKeys)
            .trim()
            .ifBlank { text.trim() }

        return KaiParsedCommand.SaveMemory(
            rawText = text.trim(),
            value = value
        )
    }

    private fun parseScrollCommand(lower: String, text: String): KaiParsedCommand.Scroll? {
        val hasScrollVerb = scrollVerbs.any { lower.contains(norm(it)) } ||
            containsAny(lower, "يمين", "يسار", "فوق", "تحت", "لليمين", "لليسار", "لفوق", "لتحت")

        if (!hasScrollVerb) return null

        val dir = when {
            containsAny(lower, "up", "scroll up", "go up", "لفوق", "الى فوق", "إلى فوق", "اعلى", "فوق") -> "up"
            containsAny(lower, "down", "scroll down", "go down", "لتحت", "الى تحت", "إلى تحت", "انزل", "اسفل", "تحت") -> "down"
            containsAny(lower, "left", "scroll left", "go left", "يسار", "لليسار", "الى اليسار", "إلى اليسار") -> "left"
            containsAny(lower, "right", "scroll right", "go right", "يمين", "لليمين", "الى اليمين", "إلى اليمين") -> "right"
            else -> null
        }

        return if (dir != null) KaiParsedCommand.Scroll(dir, extractTimes(text)) else null
    }

    private fun shouldPreferUiClickTarget(rawText: String, target: String): Boolean {
        val full = norm(rawText)
        val t = norm(target)
        if (t.isBlank()) return false

        if (findUiElementAliasIn(t) != null) return true

        return containsAny(
            full,
            "open messages", "open message", "open chats", "open chat",
            "open comments", "open comment", "open inbox",
            "افتح الرسائل", "افتح المحادثات", "افتح التعليقات", "افتح التعليق",
            "اضغط الرسائل", "اضغط المحادثات", "اضغط التعليقات"
        )
    }

    fun parse(raw: String): KaiParsedCommand {
        val text = raw.trim()
        val lower = norm(text)

        if (text.isBlank()) return KaiParsedCommand.Ask("")

        parseMemoryIntent(text)?.let { return it }

        if (containsAny(lower, "stop", "وقف", "اوقف", "توقف", "cancel", "الغ", "إلغاء")) {
            return KaiParsedCommand.Stop
        }

        if (containsAny(lower, "refresh and reset", "reset and refresh", "soft reset", "refresh reset", "اعمل refresh و reset", "اعمل ريفرش و ريسيت", "ريفرش و ريسيت", "اعمل reset", "اعمل refresh", "اعمل سوفت ريسيت", "reset", "refresh")) {
            return KaiParsedCommand.SoftReset
        }

        if (containsAny(lower, "report", "تقرير", "اعطني تقرير", "give me a report", "custom prompt", "prompt")) {
            return KaiParsedCommand.Report
        }

        if (monitoringAliases.any { lower.contains(norm(it)) }) {
            return KaiParsedCommand.ToggleAgent
        }

        if (containsAny(lower, "حلل الشاشه", "ماذا على الشاشه", "شو على الشاشه", "analyze screen", "inspect screen", "see screen", "what is on screen")) {
            return KaiParsedCommand.AnalyzeScreen
        }

        if (containsAny(lower, "اقرا الشاشه", "read screen", "watch screen now", "اقرا اللي على الشاشه", "إقرأ اللي على الشاشة")) {
            return KaiParsedCommand.ReadScreen
        }

        if (containsAny(lower, "back", "go back", "ارجع", "رجع", "عودة")) {
            return KaiParsedCommand.Back
        }

        if (containsAny(lower, "home", "الرئيسيه", "الرئيسية", "هوم")) {
            return KaiParsedCommand.Home
        }

        if (containsAny(lower, "recent", "recents", "التطبيقات", "المهام")) {
            return KaiParsedCommand.Recents
        }

        parseScrollCommand(lower, text)?.let { return it }

        if (typeVerbs.any { lower.contains(norm(it)) }) {
            val typed = extractAfter(text, *typeVerbs.toTypedArray()).trim()
            return if (typed.isNotBlank()) {
                KaiParsedCommand.TypeText(typed)
            } else {
                KaiParsedCommand.Ask(text)
            }
        }

        if (openVerbs.any { lower.contains(norm(it)) }) {
            val targetRaw = extractAfter(text, *openVerbs.toTypedArray())
            val target = cleanupTargetText(maybeStripOpenPrepositions(targetRaw))
            val detectedApp = findAppAliasIn(target.ifBlank { text })

            return when {
                target.isNotBlank() && shouldPreferUiClickTarget(text, target) ->
                    KaiParsedCommand.Click(target)

                detectedApp != null ->
                    KaiParsedCommand.OpenApp(detectedApp)

                target.isNotBlank() && looksLikeAppName(target) ->
                    KaiParsedCommand.OpenApp(resolveAppAlias(target))

                target.isNotBlank() && !isQuestionLike(text) ->
                    KaiParsedCommand.Click(target)

                else ->
                    KaiParsedCommand.Ask(text)
            }
        }

        if (clickVerbs.any { lower.contains(norm(it)) }) {
            val targetRaw = extractAfter(text, *clickVerbs.toTypedArray())
            val target = cleanupTargetText(targetRaw)
            val detectedApp = findAppAliasIn(target)

            return when {
                target.isNotBlank() && shouldPreferUiClickTarget(text, target) ->
                    KaiParsedCommand.Click(target)

                detectedApp != null ->
                    KaiParsedCommand.OpenApp(detectedApp)

                looksLikeAppName(target) ->
                    KaiParsedCommand.OpenApp(resolveAppAlias(target))

                target.isNotBlank() ->
                    KaiParsedCommand.Click(target)

                else ->
                    KaiParsedCommand.Ask(text)
            }
        }

        findAppAliasIn(text)?.let { detected ->
            if (!isQuestionLike(text)) {
                return KaiParsedCommand.OpenApp(detected)
            }
        }

        if (looksLikeAppName(text) && !isQuestionLike(text)) {
            return KaiParsedCommand.OpenApp(resolveAppAlias(text))
        }

        if (containsAny(lower, "اكتب", "type", "write") && isLikelyFreeTextTyping(text)) {
            return KaiParsedCommand.TypeText(text)
        }

        return KaiParsedCommand.Ask(text)
    }
}