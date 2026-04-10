package com.example.reply.agent

import java.util.Locale

object KaiAppIdentityRegistry {
    data class AppIdentity(
        val key: String,
        val aliases: List<String>,
        val packageCandidates: List<String>,
        val launcherLabels: List<String>
    )

    private val identities: List<AppIdentity> = listOf(
        AppIdentity(
            key = "instagram",
            aliases = listOf("instagram", "insta", "ig", "انستغرام", "انستا", "انستقرام", "انستاجرام"),
            packageCandidates = listOf("com.instagram.android"),
            launcherLabels = listOf("instagram", "insta", "ig", "انستغرام", "انستا")
        ),
        AppIdentity(
            key = "whatsapp",
            aliases = listOf("whatsapp", "whats app", "واتساب", "واتس"),
            packageCandidates = listOf("com.whatsapp"),
            launcherLabels = listOf("whatsapp", "واتساب", "واتس")
        ),
        AppIdentity(
            key = "telegram",
            aliases = listOf("telegram", "تلغرام", "تيليجرام", "تلجرام"),
            packageCandidates = listOf("org.telegram.messenger"),
            launcherLabels = listOf("telegram", "تلغرام", "تيليجرام", "تلجرام")
        ),
        AppIdentity(
            key = "messages",
            aliases = listOf("messages", "message", "sms", "messenger", "رسائل", "الرسائل", "محادثات", "chat", "inbox"),
            packageCandidates = listOf("com.google.android.apps.messaging"),
            launcherLabels = listOf("messages", "messenger", "sms", "رسائل")
        ),
        AppIdentity(
            key = "notes",
            aliases = listOf("notes", "note", "الملاحظات", "ملاحظات"),
            packageCandidates = listOf("com.miui.notes", "com.google.android.keep"),
            launcherLabels = listOf("notes", "note", "الملاحظات", "ملاحظات")
        ),
        AppIdentity(
            key = "youtube",
            aliases = listOf("youtube", "yt", "يوتيوب"),
            packageCandidates = listOf("com.google.android.youtube"),
            launcherLabels = listOf("youtube", "yt", "يوتيوب")
        ),
        AppIdentity(
            key = "settings",
            aliases = listOf("settings", "setting", "preferences", "الإعدادات", "الاعدادات", "ضبط"),
            packageCandidates = listOf("com.android.settings"),
            launcherLabels = listOf("settings", "الإعدادات", "الاعدادات", "ضبط")
        ),
        AppIdentity(
            key = "calendar",
            aliases = listOf("calendar", "cal", "agenda", "التقويم", "تقويم", "الرزنامه", "الروزنامة"),
            packageCandidates = listOf("com.google.android.calendar"),
            launcherLabels = listOf("calendar", "agenda", "التقويم", "تقويم")
        ),
        AppIdentity(
            key = "files",
            aliases = listOf("files", "file manager", "my files", "مدير الملفات", "الملفات", "ملفاتي"),
            packageCandidates = listOf("com.google.android.documentsui", "com.mi.android.globalFileexplorer"),
            launcherLabels = listOf("files", "file manager", "my files", "مدير الملفات", "الملفات")
        ),
        AppIdentity(
            key = "gallery",
            aliases = listOf("gallery", "photos", "pictures", "pics", "المعرض", "الصور", "الاستديو"),
            packageCandidates = listOf("com.miui.gallery", "com.google.android.apps.photos"),
            launcherLabels = listOf("gallery", "photos", "المعرض", "الصور", "الاستديو")
        ),
        AppIdentity(
            key = "chrome",
            aliases = listOf("chrome", "google chrome", "كروم", "جوجل كروم"),
            packageCandidates = listOf("com.android.chrome"),
            launcherLabels = listOf("chrome", "كروم", "جوجل كروم")
        ),
        AppIdentity(
            key = "calculator",
            aliases = listOf("calculator", "calc", "الحاسبة", "الآلة الحاسبة", "اله حاسبه", "الاله الحاسبه"),
            packageCandidates = listOf("com.google.android.calculator", "com.miui.calculator"),
            launcherLabels = listOf("calculator", "calc", "الحاسبة", "الآلة الحاسبة")
        ),
        AppIdentity(
            key = "chatgpt",
            aliases = listOf("chatgpt", "chat gpt", "openai", "تشات جي بي تي", "شات جي بي تي"),
            packageCandidates = listOf("com.openai.chatgpt"),
            launcherLabels = listOf("chatgpt", "chat gpt", "openai", "تشات جي بي تي", "شات جي بي تي")
        )
    )

    private val byKey: Map<String, AppIdentity> = identities.associateBy { it.key }

    private fun normalize(text: String): String {
        return text
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
    }

    fun supportedAppKeys(): Set<String> = byKey.keys

    fun allAliases(): Set<String> {
        return identities.asSequence()
            .flatMap { it.aliases.asSequence() }
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun resolveAppKey(text: String): String {
        val value = normalize(text)
        if (value.isBlank()) return ""

        // First pass: exact normalized alias/key matches only.
        identities.forEach { identity ->
            val identityKey = normalize(identity.key)
            if (value == identityKey) return identity.key
            val exactAliasHit = identity.aliases.any { alias ->
                val candidate = normalize(alias)
                candidate.isNotBlank() && value == candidate
            }
            if (exactAliasHit) return identity.key
        }

        // Second pass: bounded phrase matching, avoiding generic broad aliases.
        var bestKey = ""
        var bestScore = Int.MIN_VALUE
        identities.forEach { identity ->
            identity.aliases.forEach { alias ->
                val candidate = normalize(alias)
                if (candidate.isBlank()) return@forEach
                if (candidate == "chat") return@forEach

                val bounded = (" $value ").contains(" $candidate ")
                if (bounded) {
                    val score = candidate.length
                    if (score > bestScore) {
                        bestScore = score
                        bestKey = identity.key
                    }
                }
            }
        }

        return bestKey
    }

    fun resolveAppKeyFromPackage(packageName: String): String {
        val normalizedPackage = normalize(packageName)
        if (normalizedPackage.isBlank()) return ""

        identities.forEach { identity ->
            val matched = identity.packageCandidates.any { pkg ->
                val p = normalize(pkg)
                p.isNotBlank() && (normalizedPackage == p || normalizedPackage.startsWith("$p."))
            }
            if (matched) return identity.key
        }

        return ""
    }

    fun aliasesForKey(appKey: String): List<String> {
        val key = normalize(appKey)
        val found = byKey[key] ?: return emptyList()
        return found.aliases
    }

    fun launcherLabelsForKey(appKey: String): List<String> {
        val key = normalize(appKey)
        val found = byKey[key] ?: return emptyList()
        return (found.launcherLabels + found.aliases).distinct()
    }

    fun packageCandidatesForKey(appKey: String): List<String> {
        val key = normalize(appKey)
        val found = byKey[key] ?: return emptyList()
        return found.packageCandidates
    }

    fun primaryPackageForKey(appKey: String): String {
        return packageCandidatesForKey(appKey).firstOrNull().orEmpty()
    }

    fun resolvePrimaryPackage(textOrKey: String): String {
        val key = resolveAppKey(textOrKey).ifBlank { normalize(textOrKey) }
        return primaryPackageForKey(key)
    }

    fun sameAppFamily(expectedPackage: String, observedPackage: String): Boolean {
        val expectedKey = resolveAppKeyFromPackage(expectedPackage)
        val observedKey = resolveAppKeyFromPackage(observedPackage)
        if (expectedKey.isBlank() || observedKey.isBlank()) return false
        return expectedKey == observedKey
    }

    fun packageMatchesFamily(expectedPackage: String, observedPackage: String): Boolean {
        val expected = normalize(expectedPackage)
        val observed = normalize(observedPackage)
        if (expected.isBlank() || observed.isBlank()) return false
        if (observed == expected || observed.startsWith("$expected.")) return true
        return sameAppFamily(expectedPackage, observedPackage)
    }

}