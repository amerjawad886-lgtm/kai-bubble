// Shrink pass: this file remains intentionally rich because it is the shared semantic
// screen vocabulary for the rest of Kai OS. The behavioral decision logic was moved out
// during the REWRITE phase; this file now acts mainly as semantic description + lookup helpers.

package com.example.reply.agent

import java.util.Locale
import kotlin.math.abs

data class KaiUiElement(
    val text: String = "",
    val contentDescription: String = "",
    val hint: String = "",
    val viewId: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val bounds: String = "",
    val depth: Int = 0,
    val packageName: String = "",
    val roleGuess: String = "unknown"
)

data class KaiScreenState(
    val packageName: String,
    val rawDump: String,
    val lines: List<String>,
    val elements: List<KaiUiElement> = emptyList(),
    val screenKind: String = "unknown",
    val likelyInputFields: List<KaiUiElement> = emptyList(),
    val likelyPrimaryActions: List<KaiUiElement> = emptyList(),
    val likelyNavigationTargets: List<KaiUiElement> = emptyList(),
    val semanticConfidence: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis()
) {
    private data class BoundsRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun parseBoundsRect(bounds: String): BoundsRect? {
        val match = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(bounds.trim()) ?: return null
        val left = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val top = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val right = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val bottom = match.groupValues.getOrNull(4)?.toIntOrNull() ?: return null
        return BoundsRect(left, top, right, bottom)
    }

    private fun centerYRatio(element: KaiUiElement): Float {
        val rect = parseBoundsRect(element.bounds) ?: return 0.5f
        if (rect.bottom <= 0) return 0.5f
        val centerY = (rect.top + rect.bottom) / 2f
        return (centerY / rect.bottom.toFloat()).coerceIn(0f, 1f)
    }

    private fun centerXRatio(element: KaiUiElement): Float {
        val rect = parseBoundsRect(element.bounds) ?: return 0.5f
        if (rect.right <= 0) return 0.5f
        val centerX = (rect.left + rect.right) / 2f
        return (centerX / rect.right.toFloat()).coerceIn(0f, 1f)
    }

    private fun semanticSurfaceKind(): String {
        return KaiScreenStateParser.normalize(screenKind)
    }

    private fun isInstagramPackage(): Boolean {
        val p = KaiScreenStateParser.normalize(packageName)
        return p.contains("instagram")
    }

    private fun isNotesPackage(): Boolean {
        val p = KaiScreenStateParser.normalize(packageName)
        return p.contains("notes") || p.contains("keep")
    }

    private fun isMessagingPackage(): Boolean {
        val p = KaiScreenStateParser.normalize(packageName)
        return p.contains("messag") || p.contains("whatsapp") || p.contains("telegram") || p.contains("sms")
    }

    private fun isWhatsAppPackage(): Boolean {
        val p = KaiScreenStateParser.normalize(packageName)
        return p.contains("whatsapp")
    }

    private fun isYouTubePackage(): Boolean {
        val p = KaiScreenStateParser.normalize(packageName)
        return p.contains("youtube")
    }

    private fun elementSemanticJoined(element: KaiUiElement): String {
        return KaiScreenStateParser.normalize(
            listOf(element.text, element.contentDescription, element.hint, element.viewId).joinToString(" ")
        )
    }

    private fun isLikelySearchField(element: KaiUiElement): Boolean {
        val joined = elementSemanticJoined(element)
        if (element.roleGuess == "search_field") return true
        return KaiScreenStateParser.containsAnyNormalized(joined, "search", "find", "lookup", "بحث", "discover", "explore")
    }

    private fun isLikelyMediaAction(element: KaiUiElement): Boolean {
        val joined = elementSemanticJoined(element)
        return KaiScreenStateParser.containsAnyNormalized(
            joined,
            "camera", "photo", "gallery", "media", "image", "video", "attach", "attachment", "sticker", "gif", "reel", "story", "lens",
            "كاميرا", "صوره", "صورة", "معرض", "وسائط", "مرفق", "مرفقات"
        )
    }

    private fun hasStrongChatThreadEvidence(): Boolean {
        val composer = elements.any {
            it.editable && !isLikelySearchField(it) &&
                KaiScreenStateParser.containsAnyNormalized(
                    elementSemanticJoined(it),
                    *KaiScreenStateParser.composerAliases().toTypedArray()
                )
        }
        val send = elements.any { it.clickable && it.roleGuess == "send_button" && !isLikelyMediaAction(it) }
        return composer && send
    }

    private fun hasStrongNotesEditorEvidence(): Boolean {
        val title = elements.any {
            it.editable && !isLikelySearchField(it) &&
                KaiScreenStateParser.containsAnyNormalized(
                    elementSemanticJoined(it),
                    *KaiScreenStateParser.notesTitleAliases().toTypedArray()
                )
        }
        val body = elements.any {
            it.editable && !isLikelySearchField(it) &&
                KaiScreenStateParser.containsAnyNormalized(
                    elementSemanticJoined(it),
                    *KaiScreenStateParser.notesBodyAliases().toTypedArray()
                )
        }
        return title || body
    }



    fun hasSemanticStructure(): Boolean {
        val meaningfulLines = lines.count { KaiScreenStateParser.normalize(it).length >= 2 }
        return elements.size >= 2 ||
            likelyInputFields.isNotEmpty() ||
            likelyPrimaryActions.isNotEmpty() ||
            likelyNavigationTargets.isNotEmpty() ||
            (semanticConfidence >= 0.35f && meaningfulLines >= 1) ||
            meaningfulLines >= 2
    }

    fun matchesExpectedPackage(expectedPackage: String): Boolean {
        val expected = KaiScreenStateParser.normalize(expectedPackage)
        if (expected.isBlank()) return true
        val observed = KaiScreenStateParser.normalize(packageName)
        if (observed.isBlank()) return false
        return observed == expected || observed.startsWith("$expected.") ||
            KaiAppIdentityRegistry.packageMatchesFamily(expectedPackage, packageName)
    }
    fun containsText(query: String): Boolean {
        val nQuery = KaiScreenStateParser.normalize(query)
        if (nQuery.isBlank()) return false

        val normalizedDump = KaiScreenStateParser.normalize(rawDump)

        if (normalizedDump.contains(nQuery)) return true

        return lines.any { line ->
            val nLine = KaiScreenStateParser.normalize(line)
            nLine.contains(nQuery) ||
                nQuery.contains(nLine) ||
                KaiScreenStateParser.isLooseTextMatch(nLine, nQuery)
        }
    }

    fun containsAny(vararg queries: String): Boolean {
        return queries.any { containsText(it) }
    }

    fun preview(maxChars: Int = 2200): String {
        return rawDump.take(maxChars).ifBlank { "No visible text captured yet." }
    }

    fun isMeaningful(): Boolean {
        val normalizedDump = KaiScreenStateParser.normalize(rawDump)
        if (packageName.isBlank()) return false
        if (normalizedDump.isBlank()) return false
        if (normalizedDump == KaiScreenStateParser.normalize("(no active window)")) return false
        if (normalizedDump == KaiScreenStateParser.normalize("(empty dump)")) return false
        if (isOverlayPolluted()) return false

        val meaningfulLines = lines.count {
            KaiScreenStateParser.normalize(it).length >= 2
        }
        val hasStructuredUi = hasSemanticStructure()
        val semanticallyUsable = semanticConfidence >= 0.28f &&
            (elements.isNotEmpty() || meaningfulLines >= 1)

        if (meaningfulLines >= 2 && rawDump.trim().length >= 16) return true
        if (hasStructuredUi && rawDump.trim().length >= 10) return true
        if (semanticallyUsable && rawDump.trim().length >= 10) return true
        return false
    }

    fun isWeakObservation(): Boolean {
        if (packageName.isBlank() && !hasSemanticStructure()) return true
        if (rawDump.isBlank()) return true

        val normalizedDump = KaiScreenStateParser.normalize(rawDump)
        if (normalizedDump.isBlank()) return true
        if (normalizedDump == KaiScreenStateParser.normalize("(no active window)")) return true
        if (normalizedDump == KaiScreenStateParser.normalize("(empty dump)")) return true
        if (isOverlayPolluted()) return true

        val meaningfulLines = lines.count {
            KaiScreenStateParser.normalize(it).length >= 2
        }
        val hasStructuredUi = hasSemanticStructure()
        val semanticallyUsable = semanticConfidence >= 0.32f &&
            (elements.isNotEmpty() || meaningfulLines >= 1)

        if (meaningfulLines < 1 && !hasStructuredUi && !semanticallyUsable) return true
        if (rawDump.trim().length < 10 && !hasStructuredUi) return true
        if (isLauncher() && meaningfulLines < 2 && elements.size < 3) return true
        if (semanticConfidence < 0.24f && !hasStructuredUi && meaningfulLines < 2) return true

        return false
    }

    fun isOverlayPolluted(): Boolean {
        val normalized = KaiScreenStateParser.normalize(rawDump)
        if (normalized.isBlank()) return false

        val hits = KaiScreenStateParser.overlayPollutionHints.count {
            normalized.contains(KaiScreenStateParser.normalize(it))
        }
        if (hits <= 1) return false

        val packageNormalized = KaiScreenStateParser.normalize(packageName)
        if (packageNormalized.contains("com example reply") || packageNormalized.contains("reply")) {
            return hits >= 1
        }

        val denseStructuredScreen =
            elements.size >= 5 || likelyInputFields.isNotEmpty() || likelyPrimaryActions.isNotEmpty() || semanticConfidence >= 0.45f

        return if (denseStructuredScreen && normalized.length >= 120) hits >= 3 else hits >= 2
    }

    fun likelyMatchesAppHint(appHint: String): Boolean {
        val hint = KaiScreenStateParser.normalize(appHint)
        if (hint.isBlank()) return false

        val aliases = KaiScreenStateParser.appAliasesForHint(hint)
        val pkg = KaiScreenStateParser.normalize(packageName)
        val dump = KaiScreenStateParser.normalize(rawDump)

        return aliases.any { alias ->
            pkg.contains(alias) ||
                dump.contains(alias) ||
                KaiScreenStateParser.isLooseTextMatch(pkg, alias) ||
                lines.any { KaiScreenStateParser.normalize(it).contains(alias) }
        }
    }

    fun fingerprint(): String {
        val compact = lines
            .take(12)
            .joinToString("|") { KaiScreenStateParser.normalize(it) }

        return "${KaiScreenStateParser.normalize(packageName)}::$compact::${rawDump.trim().length}"
    }

    fun roleSignature(): String {
        val grouped = elements
            .groupBy { it.roleGuess.ifBlank { "unknown" } }
            .toSortedMap()
        val compact = grouped.entries
            .joinToString("|") { (k, v) -> "$k:${v.size}" }
        return "${KaiScreenStateParser.normalize(screenKind)}::$compact"
    }

    fun editableTextSignature(): String {
        return elements
            .asSequence()
            .filter { it.editable || it.roleGuess in setOf("input", "editor", "search_field") }
            .map {
                KaiScreenStateParser.normalize(
                    listOf(it.text, it.contentDescription, it.hint, it.viewId).joinToString(" ")
                )
            }
            .filter { it.isNotBlank() }
            .take(20)
            .joinToString("|")
    }

    fun semanticFingerprint(): String {
        val compact = lines
            .take(6)
            .joinToString("|") { KaiScreenStateParser.normalize(it) }
        val family = KaiSurfaceModel.familyName(surfaceFamily())
        val roleSig = roleSignature()
        val inputSig = editableTextSignature()
        val appSurfaceTraits = listOf(
            if (isInstagramDmListSurface()) "ig_dm_list" else "",
            if (isInstagramSearchSurface()) "ig_search" else "",
            if (isInstagramCameraOverlaySurface()) "ig_overlay" else "",
            if (isWhatsAppChatsListSurface()) "wa_chats" else "",
            if (isWhatsAppConversationThreadSurface()) "wa_thread" else "",
            if (isNotesListSurface()) "notes_list" else "",
            if (isStrictVerifiedNotesEditorSurface()) "notes_editor" else "",
            if (isYouTubeBrowseSurface()) "yt_browse" else "",
            if (isYouTubeWatchSurface()) "yt_watch" else ""
        ).filter { it.isNotBlank() }.joinToString("|")
        val lineCountBucket = (lines.count { KaiScreenStateParser.normalize(it).isNotBlank() } / 3)
            .coerceIn(0, 8)
        return listOf(
            KaiScreenStateParser.normalize(packageName),
            KaiScreenStateParser.normalize(screenKind),
            family,
            appSurfaceTraits,
            roleSig,
            inputSig,
            compact,
            "lines:$lineCountBucket"
        ).joinToString("::")
    }

    fun recoverySemanticKey(): String {
        val family = KaiSurfaceModel.familyName(surfaceFamily())
        val traitFlags = listOf(
            if (isSearchLikeSurface()) "search" else "",
            if (isCameraOrMediaOverlaySurface()) "media_overlay" else "",
            if (isChatListScreen()) "chat_list" else "",
            if (isChatThreadScreen()) "chat_thread" else "",
            if (isNotesListSurface()) "notes_list" else "",
            if (isStrictVerifiedNotesEditorSurface()) "notes_editor" else "",
            if (isYouTubeBrowseSurface()) "yt_browse" else "",
            if (isYouTubeWatchSurface()) "yt_watch" else "",
            if (isOverlayPolluted()) "overlay_polluted" else "",
            if (isWeakObservation()) "weak" else "strong"
        ).filter { it.isNotBlank() }

        return listOf(
            KaiScreenStateParser.normalize(packageName),
            KaiScreenStateParser.normalize(screenKind),
            family,
            roleSignature(),
            traitFlags.joinToString("|")
        ).joinToString("::")
    }

    fun compactLines(maxLines: Int = 24, maxCharsPerLine: Int = 140): List<String> {
        return lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(maxLines.coerceAtLeast(1))
            .map { line ->
                if (line.length > maxCharsPerLine) {
                    line.take(maxCharsPerLine).trim()
                } else {
                    line
                }
            }
    }

    fun isLauncher(): Boolean {
        val p = packageName.lowercase(Locale.getDefault())
        return p.contains("launcher") || p.contains("home") || p.contains("pixel") || p.contains("trebuchet")
    }

    fun isEditorScreen(): Boolean {
        if (screenKind == "editor" || screenKind == "notes_editor" || screenKind == "notes_body_input") return true
        if (likelyInputFields.any { it.editable || it.roleGuess in setOf("input", "editor", "search_field") }) return true
        return containsAny("title", "body", "editor", "compose", "write", "ملاحظه", "ملاحظه", "نص", "بحث")
    }

    fun isChatListScreen(): Boolean {
        if (screenKind == "chat_list" || screenKind == "instagram_dm_list" || screenKind == "whatsapp_chats_list") return true
        if (likelyNavigationTargets.any { it.roleGuess == "chat_item" }) return true
        return containsAny("chats", "messages", "inbox", "conversations", "محادثات", "رسائل")
    }

    fun isChatThreadScreen(): Boolean {
        if (screenKind == "chat_thread" || screenKind == "instagram_dm_thread" || screenKind == "whatsapp_chat_thread") return true
        if (hasStrongChatThreadEvidence()) return true
        return containsAny("type a message", "message", "ارسال", "إرسال", "send")
    }

    fun isInstagramFeedSurface(): Boolean {
        return semanticSurfaceKind() == "instagram_feed"
    }

    fun isInstagramMessagesEntrySurface(): Boolean {
        return semanticSurfaceKind() == "instagram_messages_entry" || (isInstagramPackage() && findMessagesEntry() != null)
    }

    fun isInstagramDmListSurface(): Boolean {
        return semanticSurfaceKind() == "instagram_dm_list" || isInstagramPackage() && isChatListScreen()
    }

    fun isInstagramDmThreadSurface(): Boolean {
        return isStrictVerifiedDmThreadSurface()
    }

    fun isInstagramSearchSurface(): Boolean {
        val kind = semanticSurfaceKind()
        return kind == "instagram_search" || (isInstagramPackage() && isSearchLikeSurface())
    }

    fun isInstagramCameraOverlaySurface(): Boolean {
        return semanticSurfaceKind() == "instagram_camera_overlay" || isCameraOrMediaOverlaySurface()
    }

    fun isWhatsAppChatsListSurface(): Boolean {
        if (!isWhatsAppPackage()) return false
        val kind = semanticSurfaceKind()
        if (kind == "whatsapp_chats_list") return true
        if (isSearchLikeSurface()) return false
        val hasChatsTabOrHeader = elements.any {
            val joined = elementSemanticJoined(it)
            (it.selected || it.roleGuess == "tab" || it.roleGuess == "chat_item") &&
                KaiScreenStateParser.containsAnyNormalized(joined, "chats", "chat", "محادثات", "الدردشات")
        }
        val hasChatRows = elements.count { it.roleGuess in setOf("chat_item", "list_item") } >= 2
        return hasChatsTabOrHeader || hasChatRows
    }

    fun isWhatsAppConversationThreadSurface(): Boolean {
        if (!isWhatsAppPackage()) return false
        val kind = semanticSurfaceKind()
        if (kind == "whatsapp_chat_thread") return true
        if (isSearchLikeSurface() || isCameraOrMediaOverlaySurface()) return false
        return hasStrongChatThreadEvidence()
    }

    fun isNotesListSurface(): Boolean {
        return semanticSurfaceKind() == "notes_list" || isNotesPackage() && containsAny("notes", "all notes", "ملاحظات")
    }

    fun isNotesEditorSurface(): Boolean {
        return isStrictVerifiedNotesEditorSurface()
    }

    fun isYouTubeBrowseSurface(): Boolean {
        if (!isYouTubePackage()) return false
        val kind = semanticSurfaceKind()
        if (kind == "youtube_feed") return true
        if (isSearchLikeSurface() || isPlayerSurface() || isYouTubeWatchSurface()) return false
        return containsAny("home", "shorts", "subscriptions", "library", "explore", "for you", "youtube", "الرئيسية") &&
            elements.any { it.scrollable || it.roleGuess in setOf("list_item", "tab") }
    }

    fun isYouTubeWatchSurface(): Boolean {
        if (!isYouTubePackage()) return false
        val kind = semanticSurfaceKind()
        if (kind == "youtube_watch") return true
        val joined = KaiScreenStateParser.normalize(rawDump)
        val hasWatchSignals = KaiScreenStateParser.containsAnyNormalized(
            joined,
            "pause", "play", "up next", "autoplay", "watch", "comments", "like", "dislike", "seek", "تشغيل", "إيقاف"
        )
        return hasWatchSignals && !isSearchLikeSurface()
    }

    fun isNotesTitleInputSurface(): Boolean {
        return semanticSurfaceKind() == "notes_title_input" ||
            elements.any {
                it.editable && KaiScreenStateParser.containsAnyNormalized(
                    KaiScreenStateParser.normalize(listOf(it.hint, it.text, it.viewId).joinToString(" ")),
                    *KaiScreenStateParser.notesTitleAliases().toTypedArray()
                )
            }
    }

    fun isNotesBodyInputSurface(): Boolean {
        return semanticSurfaceKind() == "notes_body_input" ||
            elements.any {
                it.editable && KaiScreenStateParser.containsAnyNormalized(
                    KaiScreenStateParser.normalize(listOf(it.hint, it.text, it.viewId).joinToString(" ")),
                    *KaiScreenStateParser.notesBodyAliases().toTypedArray()
                )
            }
    }

    fun isChatComposerSurface(): Boolean {
        val composer = findBestInputField("message")
        return isStrictVerifiedDmThreadSurface() && composer != null && !isLikelySearchField(composer) && (findSendAction() != null || composer.editable)
    }

    fun isCameraOrMediaOverlaySurface(): Boolean {
        val kind = semanticSurfaceKind()
        if (kind == "instagram_camera_overlay") return true
        if (!isInstagramPackage()) return false
        val mediaHits = elements.count { isLikelyMediaAction(it) }
        val hasComposer = elements.any {
            it.editable && !isLikelySearchField(it) &&
                KaiScreenStateParser.containsAnyNormalized(elementSemanticJoined(it), *KaiScreenStateParser.composerAliases().toTypedArray())
        }
        return mediaHits >= 2 && !hasComposer
    }

    fun isSearchLikeSurface(): Boolean {
        val kind = semanticSurfaceKind()
        if (kind in setOf("search", "instagram_search", "notes_search")) return true
        val searchInputs = elements.count { it.editable && isLikelySearchField(it) }
        return searchInputs >= 1
    }

    fun isStrictVerifiedDmThreadSurface(): Boolean {
        if (!isInstagramPackage() && !isMessagingPackage()) return false
        if (isCameraOrMediaOverlaySurface() || isSearchLikeSurface()) return false
        val kind = semanticSurfaceKind()
        if (kind == "instagram_dm_thread" || kind == "chat_thread") return true
        return hasStrongChatThreadEvidence()
    }

    fun isStrictVerifiedNotesEditorSurface(): Boolean {
        if (!isNotesPackage()) return false
        if (isSearchLikeSurface()) return false
        val kind = semanticSurfaceKind()
        if (kind in setOf("notes_editor", "notes_body_input", "notes_title_input")) return true
        return hasStrongNotesEditorEvidence()
    }

    fun isSendButtonSurface(): Boolean {
        return findSendAction() != null
    }

    fun isSearchScreen(): Boolean {
        if (screenKind == "search") return true
        if (elements.any { it.roleGuess == "search_field" }) return true
        return containsAny("search", "find", "lookup", "بحث")
    }

    fun isTabbedHomeSurface(): Boolean {
        val kind = semanticSurfaceKind()
        val tabCount = elements.count { it.roleGuess == "tab" || it.selected }
        return kind == "instagram_messages_entry" || (tabCount >= 3 && !isSearchLikeSurface())
    }

    fun isContentFeedSurface(): Boolean {
        val kind = semanticSurfaceKind()
        if (kind == "instagram_feed" || kind == "youtube_feed") return true
        if (isSearchLikeSurface() || isChatListScreen() || isChatThreadScreen()) return false
        return containsAny("feed", "home", "for you", "stories", "reels", "timeline", "الرئيسية") && elements.any { it.scrollable }
    }

    fun isResultListSurface(): Boolean {
        val kind = semanticSurfaceKind()
        if (kind == "list" || kind == "chat_list" || kind == "instagram_dm_list" || kind == "whatsapp_chats_list") return true
        if (isYouTubeWatchSurface()) return false
        if (!isSearchLikeSurface() && !containsAny("results", "result", "نتائج", "بحث")) return false
        val listLike = elements.count { it.roleGuess in setOf("list_item", "chat_item") || it.scrollable }
        return listLike >= 2
    }

    fun isDetailSurface(): Boolean {
        if (isChatThreadScreen() || isNotesEditorSurface() || isSearchLikeSurface()) return false
        val joined = KaiScreenStateParser.normalize(rawDump)
        return KaiScreenStateParser.containsAnyNormalized(
            joined,
            "details", "profile", "post", "comment", "viewer", "full", "description", "تفاصيل", "الملف الشخصي"
        )
    }

    fun isPlayerSurface(): Boolean {
        if (isYouTubeWatchSurface()) return true
        val joined = KaiScreenStateParser.normalize(rawDump)
        val hasTransport = KaiScreenStateParser.containsAnyNormalized(
            joined,
            "play", "pause", "next", "previous", "playlist", "duration", "seek", "مشغل", "تشغيل", "ايقاف"
        )
        return hasTransport && !isSearchLikeSurface() && !isChatThreadScreen()
    }

    fun isSheetOrDialogSurface(): Boolean {
        val joined = KaiScreenStateParser.normalize(rawDump)
        return KaiScreenStateParser.containsAnyNormalized(
            joined,
            "allow", "deny", "cancel", "ok", "permissions", "dialog", "popup", "dismiss", "السماح", "رفض", "إلغاء"
        ) && elements.count { it.roleGuess == "button" || it.clickable } >= 2
    }

    fun isSettingsSurface(): Boolean {
        if (packageName.contains("settings", true)) return true
        val joined = KaiScreenStateParser.normalize(rawDump)
        return KaiScreenStateParser.containsAnyNormalized(
            joined,
            "settings", "preferences", "privacy", "security", "notifications", "الإعدادات", "الخصوصية"
        )
    }

    fun isBrowserLikeSurface(): Boolean {
        val pkg = KaiScreenStateParser.normalize(packageName)
        if (pkg.contains("chrome") || pkg.contains("browser")) return true
        val joined = KaiScreenStateParser.normalize(rawDump)
        return KaiScreenStateParser.containsAnyNormalized(joined, "http", "www", "tab", "address bar", "search or type")
    }

    fun surfaceFamily(): KaiSurfaceFamily {
        return KaiSurfaceModel.familyOf(this)
    }

    fun findBestInputField(queryHint: String = ""): KaiUiElement? {
        val hint = KaiScreenStateParser.normalize(queryHint)
        val candidates = elements.filter {
            it.editable || it.roleGuess in setOf("input", "editor", "search_field")
        }

        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { element ->
            var score = 0
            if (element.editable) score += 60
            if (element.roleGuess == "editor") score += 45
            if (element.roleGuess == "search_field") score += 40
            if (element.roleGuess == "input") score += 35
            if (element.hint.isNotBlank()) score += 10
            if (element.className.contains("EditText", ignoreCase = true)) score += 12
            val joined = elementSemanticJoined(element)
            if (isNotesPackage() && KaiScreenStateParser.containsAnyNormalized(joined, *KaiScreenStateParser.notesBodyAliases().toTypedArray())) score += 20
            if (isNotesPackage() && KaiScreenStateParser.containsAnyNormalized(joined, *KaiScreenStateParser.notesTitleAliases().toTypedArray())) score += 24
            if (isMessagingPackage() && KaiScreenStateParser.containsAnyNormalized(joined, *KaiScreenStateParser.composerAliases().toTypedArray())) score += 24
            if (isLikelySearchField(element)) score -= 44
            if (hint.contains("message") || hint.contains("composer") || hint.contains("reply")) {
                if (isLikelySearchField(element)) score -= 120
                if (isLikelyMediaAction(element)) score -= 80
            }
                if (KaiScreenStateParser.containsAnyNormalized(joined, "caption", "story", "reel", "title", "post", "تعليق", "عنوان")) score -= 120
            if (hint.contains("body") || hint.contains("title") || hint.contains("note")) {
                if (isLikelySearchField(element)) score -= 120
                if (KaiScreenStateParser.containsAnyNormalized(joined, "new note", "create", "plus")) score -= 50
            }
            if (hint.isNotBlank()) {
                score += semanticMatchScore(element, hint)
            }
            val y = centerYRatio(element)
            if (isMessagingPackage() && y >= 0.62f) score += 14
            if (isNotesPackage() && y >= 0.22f && y <= 0.92f) score += 8
            score
        }
    }

    fun findBestClickableTarget(query: String): KaiUiElement? {
        val normalized = KaiScreenStateParser.normalize(query)
        if (normalized.isBlank()) return null

        val candidates = elements.filter { it.clickable || it.roleGuess in setOf("button", "image_button", "tab", "list_item", "chat_item") }
        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { element ->
            var score = semanticMatchScore(element, normalized)
            if (element.clickable) score += 25
            if (element.roleGuess == "button") score += 20
            if (element.roleGuess == "tab") score += 12
            if (element.roleGuess == "chat_item") score += 14
            if (element.roleGuess == "list_item") score += 10
            val aliases = KaiScreenStateParser.queryAliasesForPackage(packageName, normalized)
            if (aliases.any { alias -> semanticMatchScore(element, alias) > 0 }) score += 24
            val y = centerYRatio(element)
            if (element.roleGuess == "tab" && y >= 0.72f) score += 16
            if (isInstagramPackage() && y <= 0.26f && aliases.any { it in KaiScreenStateParser.instagramMessagesAliases() }) score += 18
            score
        }
    }

    fun findPrimaryAction(): KaiUiElement? {
        if (likelyPrimaryActions.isNotEmpty()) return likelyPrimaryActions.first()

        val primaryTokens = listOf("send", "save", "done", "post", "submit", "ok", "ارسال", "إرسال", "حفظ", "تم", "نشر")
            .map { KaiScreenStateParser.normalize(it) }

        return elements
            .filter { it.clickable }
            .maxByOrNull { element ->
                var score = 0
                if (element.roleGuess in setOf("send_button", "create_button", "button")) score += 30
                if (primaryTokens.any { token -> semanticMatchScore(element, token) > 0 }) score += 40
                if (KaiScreenStateParser.sendAliases().any { token -> semanticMatchScore(element, token) > 0 }) score += 44
                if (element.selected) score += 8
                if (isMessagingPackage() && centerYRatio(element) >= 0.62f) score += 12
                if (isLikelyMediaAction(element)) score -= 120
                if (isLikelySearchField(element)) score -= 80
                score
            }
    }

    fun findCreateAction(): KaiUiElement? {
        val tokens = (listOf("new", "create", "compose", "plus", "add", "new chat", "انشاء", "إنشاء", "جديد", "+") + KaiScreenStateParser.notesCreateAliases())
            .map { KaiScreenStateParser.normalize(it) }

        return elements
            .filter { it.clickable }
            .maxByOrNull { element ->
                var score = 0
                val joined = elementSemanticJoined(element)
                if (element.roleGuess == "create_button") score += 45
                if (tokens.any { token -> semanticMatchScore(element, token) > 0 }) score += 36
                if (element.className.contains("FloatingActionButton", ignoreCase = true)) score += 20
                val y = centerYRatio(element)
                val x = centerXRatio(element)
                if (y >= 0.60f && x >= 0.62f) score += 12
                if (isLikelySearchField(element)) score -= 150
                if (KaiScreenStateParser.containsAnyNormalized(joined, "search", "find", "lookup", "toolbar", "menu", "filter", "بحث")) score -= 120
                if (KaiScreenStateParser.containsAnyNormalized(joined, "camera", "gallery", "attach", "media", "story", "reel", "كاميرا", "معرض")) score -= 160
                    if (KaiScreenStateParser.containsAnyNormalized(joined, "sort", "settings", "option", "menu", "overflow", "فرز", "خيارات")) score -= 90
                score
            }
    }

    fun findSendAction(): KaiUiElement? {
        val tokens = KaiScreenStateParser.sendAliases()
            .map { KaiScreenStateParser.normalize(it) }
        val inThreadContext = isChatThreadScreen() || semanticSurfaceKind() in setOf("chat_thread", "instagram_dm_thread")

        return elements
            .filter { it.clickable }
            .maxByOrNull { element ->
                var score = 0
                if (element.roleGuess == "send_button") score += 50
                if (tokens.any { token -> semanticMatchScore(element, token) > 0 }) score += 36
                if (element.viewId.contains("send", true) || element.viewId.contains("reply", true)) score += 18
                if (element.contentDescription.contains("arrow", true) || element.contentDescription.contains("paper", true)) score += 10
                if (isMessagingPackage() && centerYRatio(element) >= 0.60f) score += 10
                if (!inThreadContext) score -= 40
                if (isLikelyMediaAction(element)) score -= 180
                if (isLikelySearchField(element)) score -= 120
                score
            }
            ?.takeIf { !isLikelyMediaAction(it) }
    }

    fun findMessagesEntry(): KaiUiElement? {
        val tokens = KaiScreenStateParser.instagramMessagesAliases() +
            listOf("messages", "inbox", "chat", "dm", "رسائل", "محادثات")
            .map { KaiScreenStateParser.normalize(it) }

        return elements
            .filter { it.clickable || it.roleGuess in setOf("chat_item", "tab", "image_button") }
            .maxByOrNull { element ->
                var score = 0
                if (element.roleGuess == "chat_item") score += 28
                if (element.roleGuess == "tab") score += 20
                if (element.roleGuess == "image_button") score += 18
                if (tokens.any { token -> semanticMatchScore(element, token) > 0 }) score += 34
                if (tokens.any { token ->
                        val joined = KaiScreenStateParser.normalize(listOf(element.text, element.contentDescription, element.hint, element.viewId).joinToString(" "))
                        joined == token
                    }) score += 18
                if (element.viewId.contains("inbox", true) || element.viewId.contains("direct", true) || element.viewId.contains("message", true)) score += 30
                if (element.contentDescription.contains("paper", true) || element.contentDescription.contains("messenger", true)) score += 24
                val y = centerYRatio(element)
                val x = centerXRatio(element)
                if (isInstagramPackage() && y <= 0.30f && x >= 0.60f) score += 18
                if (!isInstagramPackage() && y >= 0.70f) score += 10
                if (isLikelyMediaAction(element)) score -= 150
                if (isLikelySearchField(element)) score -= 90
                val joined = elementSemanticJoined(element)
                if (KaiScreenStateParser.containsAnyNormalized(joined, "search", "explore", "discover", "camera", "gallery", "media", "reel", "story", "بحث", "استكشاف", "كاميرا", "معرض")) score -= 140
                score
            }
    }

    fun findConversationCandidate(query: String): KaiUiElement? {
        val normalized = KaiScreenStateParser.normalize(query)
        if (normalized.isBlank()) {
            return elements
                .filter { it.roleGuess == "chat_item" || it.roleGuess == "list_item" }
                .maxByOrNull {
                    var score = if (it.clickable) 14 else 0
                    if (it.roleGuess == "chat_item") score += 24
                    if (it.text.isNotBlank() || it.contentDescription.isNotBlank()) score += 10
                    if (centerYRatio(it) in 0.18f..0.92f) score += 8
                    if (isLikelyMediaAction(it) || isLikelySearchField(it)) score -= 80
                    score
                }
        }

        val strictCandidates = elements
            .filter { it.roleGuess in setOf("chat_item", "list_item") }
            .map { candidate ->
                var score = semanticMatchScore(candidate, normalized)
                if (candidate.clickable) score += 18
                if (candidate.roleGuess == "chat_item") score += 16
                if (candidate.text.isNotBlank() || candidate.contentDescription.isNotBlank()) score += 8
                if (centerYRatio(candidate) in 0.16f..0.94f) score += 8
                if (isLikelyMediaAction(candidate) || isLikelySearchField(candidate)) score -= 180
                val joined = elementSemanticJoined(candidate)
                if (KaiScreenStateParser.containsAnyNormalized(joined, "account", "follow", "explore", "discover", "profile", "حساب", "استكشاف")) score -= 120
                candidate to score
            }
            .sortedByDescending { it.second }

        val minRequiredScore = if (normalized.length <= 4) 72 else 58
        return strictCandidates.firstOrNull { it.second >= minRequiredScore }?.first
    }

    fun findYouTubePlayableCandidate(): KaiUiElement? {
        if (!isYouTubePackage()) return null
        return elements
            .filter { it.clickable || it.roleGuess in setOf("list_item", "button") }
            .map { candidate ->
                val joined = elementSemanticJoined(candidate)
                var score = 0
                if (candidate.roleGuess == "list_item") score += 24
                if (candidate.clickable) score += 18
                if (centerYRatio(candidate) in 0.18f..0.92f) score += 12
                if (KaiScreenStateParser.containsAnyNormalized(joined, "video", "watch", "play", "shorts", "مشاهدة", "تشغيل")) score += 24
                if (KaiScreenStateParser.containsAnyNormalized(joined, "search", "filter", "account", "profile", "notification", "بحث", "الملف")) score -= 140
                candidate to score
            }
            .sortedByDescending { it.second }
            .firstOrNull { it.second >= 36 }
            ?.first
    }

    private fun semanticMatchScore(element: KaiUiElement, query: String): Int {
        if (query.isBlank()) return 0

        val fields = listOf(element.text, element.contentDescription, element.hint, element.viewId)
            .map { KaiScreenStateParser.normalize(it) }
            .filter { it.isNotBlank() }

        var best = 0
        fields.forEach { field ->
            var score = 0
            if (field == query) score += 80
            if (field.contains(query) && field != query) score += 45
            if (query.contains(field) && query != field) score += 30
            if (KaiScreenStateParser.isLooseTextMatch(field, query)) score += 20
            if (score > best) best = score
        }

        return best
    }
}

object KaiScreenStateParser {

    val overlayPollutionHints = listOf(
        "dynamic island",
        "custom prompt",
        "make action",
        "control panel",
        "agent loop active",
        "agent planning",
        "agent executing",
        "agent observing",
        "monitoring paused before action loop",
        "monitoring carried into action loop",
        "kai os",
        "open app",
        "working",
        "close",
        "type to kai",
        "new chat",
        "history",
        "presence",
        "soft reset"
    )

    fun fromDump(
        packageName: String?,
        dump: String,
        elements: List<KaiUiElement> = emptyList(),
        screenKindHint: String = "",
        semanticConfidence: Float = 0f
    ): KaiScreenState {
        val cleanDump = dump.trim()

        val lines = cleanDump
            .lines()
            .map { it.removePrefix("•").trim() }
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val normalizedPackage = packageName.orEmpty().trim()
        val semanticElements = if (elements.isNotEmpty()) {
            elements
        } else {
            // Compatibility fallback when structured accessibility payload is unavailable.
            inferElementsFromLines(lines, normalizedPackage)
        }

        val inputFields = semanticElements.filter {
            it.editable || it.roleGuess in setOf("input", "editor", "search_field")
        }.take(8)

        val primaryActions = semanticElements
            .filter { it.clickable && it.roleGuess in setOf("button", "send_button", "create_button", "image_button") }
            .sortedByDescending { if (it.roleGuess == "send_button") 2 else 1 }
            .take(8)

        val navTargets = semanticElements
            .filter { it.clickable && it.roleGuess in setOf("tab", "list_item", "chat_item", "button") }
            .take(12)

        val inferredKind = if (screenKindHint.isNotBlank()) {
            normalize(screenKindHint)
        } else {
            inferScreenKind(normalizedPackage, cleanDump, lines, semanticElements)
        }

        val confidence = semanticConfidence
            .coerceIn(0f, 1f)
            .takeIf { it > 0f }
            ?: inferSemanticConfidence(cleanDump, semanticElements, inferredKind)

        return KaiScreenState(
            packageName = normalizedPackage,
            rawDump = cleanDump,
            lines = lines,
            elements = semanticElements,
            screenKind = inferredKind,
            likelyInputFields = inputFields,
            likelyPrimaryActions = primaryActions,
            likelyNavigationTargets = navTargets,
            semanticConfidence = confidence
        )
    }

    private fun inferElementsFromLines(lines: List<String>, packageName: String): List<KaiUiElement> {
        return lines
            .take(120)
            .mapIndexedNotNull { index, line ->
                val clean = line.trim()
                if (clean.isBlank()) return@mapIndexedNotNull null
                val normalized = normalize(clean)
                val roleGuess = inferRoleFromText(normalized)

                KaiUiElement(
                    text = clean,
                    clickable = roleGuess in setOf("button", "send_button", "create_button", "tab", "chat_item", "list_item", "image_button"),
                    editable = roleGuess in setOf("input", "editor", "search_field"),
                    className = "line",
                    depth = index,
                    packageName = packageName,
                    roleGuess = roleGuess
                )
            }
            .distinctBy { normalize("${it.text}|${it.viewId}|${it.roleGuess}") }
    }

    private fun inferRoleFromText(normalizedText: String): String {
        if (normalizedText.isBlank()) return "unknown"
        return when {
            containsAny(normalizedText, "search", "بحث", "find") -> "search_field"
            containsAny(normalizedText, "message", "type", "اكتب", "write", "compose") -> "input"
            containsAny(normalizedText, "send", "ارسال", "إرسال", "post", "submit") -> "send_button"
            containsAny(normalizedText, "new", "create", "compose", "plus", "add", "انشاء", "إنشاء", "جديد") -> "create_button"
            containsAny(normalizedText, "chat", "inbox", "conversation", "محادث", "رسائل") -> "chat_item"
            containsAny(normalizedText, "tab", "home", "profile", "notifications", "search") -> "tab"
            else -> "unknown"
        }
    }

    private fun inferScreenKind(
        packageName: String,
        rawDump: String,
        lines: List<String>,
        elements: List<KaiUiElement>
    ): String {
        val combined = normalize(buildString {
            append(packageName)
            append(' ')
            append(rawDump)
            append(' ')
            append(lines.take(20).joinToString(" "))
        })

        val hasInput = elements.any { it.editable || it.roleGuess in setOf("input", "editor") }
        val hasSearch = elements.any { it.roleGuess == "search_field" } || containsAny(combined, "search", "بحث")
        val hasSend = elements.any { it.roleGuess == "send_button" } || containsAny(combined, "send", "ارسال", "إرسال")
        val hasChatItems = elements.count { it.roleGuess == "chat_item" } >= 2 || containsAny(combined, "inbox", "conversations", "محادث", "رسائل")
        val hasMediaOverlay = elements.any {
            containsAnyNormalized(
                normalize(listOf(it.text, it.contentDescription, it.hint, it.viewId).joinToString(" ")),
                "camera", "gallery", "photo", "media", "attach", "image", "كاميرا", "معرض"
            )
        }
        val hasComposerInput = elements.any { e ->
            e.editable &&
                !containsAnyNormalized(normalize(listOf(e.text, e.contentDescription, e.hint, e.viewId).joinToString(" ")), "search", "بحث") &&
                containsAnyNormalized(normalize(listOf(e.text, e.contentDescription, e.hint, e.viewId).joinToString(" ")), *composerAliases().toTypedArray())
        }
        val hasRealSend = elements.any { e ->
            val joined = normalize(listOf(e.text, e.contentDescription, e.hint, e.viewId).joinToString(" "))
            (e.roleGuess == "send_button" || containsAnyNormalized(joined, *sendAliases().toTypedArray())) &&
                !containsAnyNormalized(joined, "camera", "gallery", "photo", "media", "attach", "كاميرا", "معرض")
        }
        val hasMessagesEntry = elements.any { e ->
            val joined = normalize(listOf(e.text, e.contentDescription, e.hint, e.viewId).joinToString(" "))
            containsAnyNormalized(joined, *instagramMessagesAliases().toTypedArray()) && (e.clickable || e.roleGuess in setOf("tab", "image_button", "button"))
        }
        val hasDmListEvidence = hasChatItems || elements.count {
            val joined = normalize(listOf(it.text, it.contentDescription, it.hint, it.viewId).joinToString(" "))
            (it.roleGuess in setOf("chat_item", "list_item")) && !containsAnyNormalized(joined, "search", "camera", "gallery", "new message", "new chat", "بحث", "كاميرا", "معرض")
        } >= 2
        val hasCreateNotes = elements.any { e ->
            val joined = normalize(listOf(e.text, e.contentDescription, e.hint, e.viewId).joinToString(" "))
            containsAnyNormalized(joined, *notesCreateAliases().toTypedArray())
        }
        val hasNotesSearch = elements.any { e ->
            e.editable && containsAnyNormalized(
                normalize(listOf(e.text, e.contentDescription, e.hint, e.viewId).joinToString(" ")),
                "search", "find", "lookup", "بحث"
            )
        }
        val hasNotesTitle = elements.any { e ->
            e.editable && containsAnyNormalized(normalize(listOf(e.text, e.hint, e.viewId).joinToString(" ")), *notesTitleAliases().toTypedArray())
        }
        val hasNotesBody = elements.any { e ->
            e.editable && containsAnyNormalized(normalize(listOf(e.text, e.hint, e.viewId).joinToString(" ")), *notesBodyAliases().toTypedArray())
        }
        val launcher = packageName.lowercase(Locale.ROOT).let {
            it.contains("launcher") || it.contains("home") || it.contains("pixel") || it.contains("trebuchet")
        }
        val p = normalize(packageName)
        val isInstagram = p.contains("instagram")
        val isWhatsApp = p.contains("whatsapp")
        val isNotes = p.contains("notes") || p.contains("keep")
        val isYouTube = p.contains("youtube")
        val hasWhatsAppChatsTab = elements.any { e ->
            val joined = normalize(listOf(e.text, e.contentDescription, e.hint, e.viewId).joinToString(" "))
            (e.selected || e.roleGuess == "tab" || e.roleGuess == "chat_item") &&
                containsAnyNormalized(joined, "chats", "chat", "محادثات", "الدردشات")
        }
        val hasWhatsAppThreadComposer = hasComposerInput && hasRealSend
        val hasYouTubeWatchSignals = containsAny(
            combined,
            "up next", "autoplay", "watch", "pause", "play", "comments", "like", "dislike", "تشغيل"
        )
        val hasYouTubeFeedSignals = containsAny(
            combined,
            "shorts", "subscriptions", "library", "home", "explore", "youtube", "الرئيسية"
        )

        return when {
            launcher -> "launcher"
            isInstagram && hasMediaOverlay && !hasComposerInput -> "instagram_camera_overlay"
            isInstagram && hasComposerInput && hasRealSend -> "instagram_dm_thread"
            isInstagram && hasDmListEvidence -> "instagram_dm_list"
            isInstagram && hasSearch -> "instagram_search"
            isInstagram && hasMessagesEntry -> "instagram_messages_entry"
            isInstagram -> "instagram_feed"
            isWhatsApp && hasWhatsAppThreadComposer -> "whatsapp_chat_thread"
            isWhatsApp && (hasWhatsAppChatsTab || hasDmListEvidence) -> "whatsapp_chats_list"
            isNotes && hasNotesTitle -> "notes_title_input"
            isNotes && hasNotesBody -> "notes_body_input"
            isNotes && (hasNotesTitle || hasNotesBody) -> "notes_editor"
            isNotes && hasNotesSearch -> "notes_search"
            isNotes && hasCreateNotes && !hasNotesSearch -> "notes_list"
            isNotes -> "notes_list"
            isYouTube && hasYouTubeWatchSignals -> "youtube_watch"
            isYouTube && hasYouTubeFeedSignals && !hasSearch -> "youtube_feed"
            hasSearch && hasInput -> "search"
            hasComposerInput && hasRealSend -> "chat_thread"
            hasChatItems -> "chat_list"
            hasInput -> "editor"
            containsAny(combined, "list", "feed", "timeline") -> "list"
            else -> "unknown"
        }
    }

    private fun inferSemanticConfidence(
        rawDump: String,
        elements: List<KaiUiElement>,
        screenKind: String
    ): Float {
        var score = 0f
        if (rawDump.isNotBlank() && !rawDump.equals("(no active window)", true)) score += 0.22f
        if (elements.isNotEmpty()) score += 0.35f
        if (elements.count { it.clickable || it.editable } >= 3) score += 0.18f
        if (elements.any { it.roleGuess != "unknown" }) score += 0.15f
        if (screenKind != "unknown") score += 0.1f
        return score.coerceIn(0f, 1f)
    }

    fun normalize(text: String): String =
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

    fun inferAppHint(text: String): String {
        return KaiAppIdentityRegistry.resolveAppKey(text)
    }

    fun appAliasesForHint(appHint: String): List<String> {
        val hint = normalize(appHint)
        val aliases = KaiAppIdentityRegistry.aliasesForKey(hint)
        return when {
            aliases.isNotEmpty() -> aliases
            hint.isNotBlank() -> listOf(hint)
            else -> emptyList()
        }
    }

    fun instagramMessagesAliases(): List<String> = listOf(
        "messages", "message", "dm", "inbox", "messenger", "chat", "paper plane", "direct", "الرسائل", "محادثات"
    ).map { normalize(it) }

    fun notesCreateAliases(): List<String> = listOf(
        "note", "notes", "new note", "create", "compose", "plus", "add", "ملاحظة", "ملاحظات", "جديد", "إنشاء", "انشاء"
    ).map { normalize(it) }

    fun notesTitleAliases(): List<String> = listOf(
        "title", "note title", "العنوان", "عنوان"
    ).map { normalize(it) }

    fun notesBodyAliases(): List<String> = listOf(
        "body", "content", "editor", "write", "text", "المحتوى", "النص"
    ).map { normalize(it) }

    fun composerAliases(): List<String> = listOf(
        "message", "type a message", "compose", "reply", "write", "اكتب", "رساله", "رسالة"
    ).map { normalize(it) }

    fun sendAliases(): List<String> = listOf(
        "send", "إرسال", "ارسال", "reply", "post", "submit", "send icon"
    ).map { normalize(it) }

    fun queryAliasesForPackage(packageName: String, query: String): List<String> {
        val normalizedQuery = normalize(query)
        val aliases = linkedSetOf(normalizedQuery)
        val p = normalize(packageName)

        if (p.contains("instagram") && instagramMessagesAliases().any { normalizedQuery.contains(it) || it.contains(normalizedQuery) }) {
            aliases += instagramMessagesAliases()
        }

        if ((p.contains("notes") || p.contains("keep")) && notesCreateAliases().any { normalizedQuery.contains(it) || it.contains(normalizedQuery) }) {
            aliases += notesCreateAliases()
        }

        if (sendAliases().any { normalizedQuery.contains(it) || it.contains(normalizedQuery) }) {
            aliases += sendAliases()
        }

        return aliases.filter { it.isNotBlank() }
    }

    fun containsAnyNormalized(text: String, vararg normalizedValues: String): Boolean {
        if (text.isBlank()) return false
        return normalizedValues.any { value ->
            val v = normalize(value)
            v.isNotBlank() && (text.contains(v) || isLooseTextMatch(text, v))
        }
    }

    fun isLooseTextMatch(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        if (a.length < 4 || b.length < 4) return false
        if (abs(a.length - b.length) > 2) return false

        var mismatches = 0
        val min = minOf(a.length, b.length)
        for (i in 0 until min) {
            if (a[i] != b[i]) mismatches++
            if (mismatches > 2) return false
        }
        mismatches += abs(a.length - b.length)
        return mismatches <= 2
    }

    private fun containsAny(text: String, vararg values: String): Boolean {
        return values.any {
            val normalized = normalize(it)
            text.contains(normalized) || isLooseTextMatch(text, normalized)
        }
    }
}

fun KaiScreenState.isKaiLiveUsable(expectedPackage: String = ""): Boolean {
    if (expectedPackage.isNotBlank() && !matchesExpectedPackage(expectedPackage)) return false
    return KaiVisionInterpreter.isUsableState(this)
}

fun KaiScreenState.isKaiLiveStrong(expectedPackage: String = "", allowLauncherSurface: Boolean = false): Boolean {
    return KaiVisionInterpreter.isStrongState(this, expectedPackage, allowLauncherSurface)
}

fun KaiScreenState.currentSurfaceHealth(expectedPackage: String = "", allowLauncherSurface: Boolean = false): KaiVisionInterpreter.ReadinessResult {
    return KaiVisionInterpreter.evaluateReadiness(
        state = this,
        expectedPackage = expectedPackage,
        allowLauncherSurface = allowLauncherSurface,
        requireStrong = true
    )
}
