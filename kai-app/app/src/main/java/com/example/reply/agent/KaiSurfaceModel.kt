package com.example.reply.agent

enum class KaiSurfaceFamily {
    LAUNCHER_SURFACE,
    APP_HOME_SURFACE,
    TABBED_HOME_SURFACE,
    CONTENT_FEED_SURFACE,
    LIST_SURFACE,
    SEARCH_SURFACE,
    RESULT_LIST_SURFACE,
    DETAIL_SURFACE,
    THREAD_SURFACE,
    COMPOSER_SURFACE,
    EDITOR_SURFACE,
    MEDIA_CAPTURE_SURFACE,
    PLAYER_SURFACE,
    SHEET_OR_DIALOG_SURFACE,
    SETTINGS_SURFACE,
    BROWSER_LIKE_SURFACE,
    MODAL_SURFACE,
    UNKNOWN_SURFACE
}

enum class KaiSurfaceStatus {
    TARGET_READY,
    USABLE_INTERMEDIATE,
    WRONG_BUT_RECOVERABLE,
    DEAD_END
}

object KaiSurfaceModel {
    private fun packageMatches(state: KaiScreenState, expected: String): Boolean {
        val pkg = KaiScreenStateParser.normalize(state.packageName)
        val e = KaiScreenStateParser.normalize(expected)
        if (pkg.isBlank() || e.isBlank()) return false
        return pkg == e || pkg.startsWith("$e.")
    }

    fun isVerifiedInstagramDmListSurface(state: KaiScreenState): Boolean {
        return packageMatches(state, "com.instagram.android") &&
            state.isInstagramDmListSurface() &&
            !state.isCameraOrMediaOverlaySurface() &&
            !state.isSearchLikeSurface()
    }

    fun isVerifiedInstagramThreadTextSurface(state: KaiScreenState): Boolean {
        return packageMatches(state, "com.instagram.android") &&
            state.isStrictVerifiedDmThreadSurface() &&
            !state.isCameraOrMediaOverlaySurface() &&
            !state.isSearchLikeSurface()
    }

    fun isVerifiedInstagramCameraOverlay(state: KaiScreenState): Boolean {
        return packageMatches(state, "com.instagram.android") && state.isCameraOrMediaOverlaySurface()
    }

    fun isVerifiedYouTubeWorkingSurface(state: KaiScreenState): Boolean {
        if (!packageMatches(state, "com.google.android.youtube")) return false
        if (state.isWeakObservation() || state.isOverlayPolluted()) return false
        val family = normalizeLegacyFamily(familyOf(state))
        return family in setOf(
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.LIST_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.SEARCH_SURFACE,
            KaiSurfaceFamily.TABBED_HOME_SURFACE
        )
    }

    fun isVerifiedNotesListSurface(state: KaiScreenState): Boolean {
        return (packageMatches(state, "com.miui.notes") || state.packageName.contains("keep", true)) &&
            state.isNotesListSurface() &&
            !state.isSearchLikeSurface() &&
            !state.isSheetOrDialogSurface()
    }

    fun isVerifiedNotesEditorSurface(state: KaiScreenState): Boolean {
        return (packageMatches(state, "com.miui.notes") || state.packageName.contains("keep", true)) &&
            state.isStrictVerifiedNotesEditorSurface() &&
            !state.isSearchLikeSurface() &&
            !state.isSheetOrDialogSurface()
    }

    fun normalizeLegacyFamily(family: KaiSurfaceFamily): KaiSurfaceFamily {
        return when (family) {
            KaiSurfaceFamily.MODAL_SURFACE -> KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE
            else -> family
        }
    }

    fun familyName(family: KaiSurfaceFamily): String {
        return when (normalizeLegacyFamily(family)) {
            KaiSurfaceFamily.LAUNCHER_SURFACE -> "launcher"
            KaiSurfaceFamily.APP_HOME_SURFACE -> "app_home"
            KaiSurfaceFamily.TABBED_HOME_SURFACE -> "tabbed_home"
            KaiSurfaceFamily.CONTENT_FEED_SURFACE -> "content_feed"
            KaiSurfaceFamily.LIST_SURFACE -> "list_surface"
            KaiSurfaceFamily.SEARCH_SURFACE -> "search_surface"
            KaiSurfaceFamily.RESULT_LIST_SURFACE -> "result_list_surface"
            KaiSurfaceFamily.DETAIL_SURFACE -> "detail_surface"
            KaiSurfaceFamily.THREAD_SURFACE -> "thread_surface"
            KaiSurfaceFamily.COMPOSER_SURFACE -> "composer_surface"
            KaiSurfaceFamily.EDITOR_SURFACE -> "editor_surface"
            KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE -> "media_capture_surface"
            KaiSurfaceFamily.PLAYER_SURFACE -> "player_surface"
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE, KaiSurfaceFamily.MODAL_SURFACE -> "sheet_or_dialog_surface"
            KaiSurfaceFamily.SETTINGS_SURFACE -> "settings_surface"
            KaiSurfaceFamily.BROWSER_LIKE_SURFACE -> "browser_like_surface"
            KaiSurfaceFamily.UNKNOWN_SURFACE -> "unknown_surface"
        }
    }

    fun familyOf(state: KaiScreenState): KaiSurfaceFamily {
        if (state.isLauncher()) return KaiSurfaceFamily.LAUNCHER_SURFACE
        if (state.isSettingsSurface()) return KaiSurfaceFamily.SETTINGS_SURFACE
        if (state.isSheetOrDialogSurface()) return KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE
        if (state.isCameraOrMediaOverlaySurface()) return KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE
        if (state.isPlayerSurface()) return KaiSurfaceFamily.PLAYER_SURFACE
        if (state.isSearchLikeSurface() || state.isInstagramSearchSurface()) return KaiSurfaceFamily.SEARCH_SURFACE
        if (state.isResultListSurface()) return KaiSurfaceFamily.RESULT_LIST_SURFACE
        if (state.isDetailSurface()) return KaiSurfaceFamily.DETAIL_SURFACE
        if (state.isChatComposerSurface()) return KaiSurfaceFamily.COMPOSER_SURFACE
        if (state.isStrictVerifiedDmThreadSurface() || state.isChatThreadScreen()) return KaiSurfaceFamily.THREAD_SURFACE
        if (state.isStrictVerifiedNotesEditorSurface() || state.isNotesTitleInputSurface() || state.isNotesBodyInputSurface()) return KaiSurfaceFamily.EDITOR_SURFACE
        if (state.isInstagramDmListSurface() || state.isChatListScreen() || state.isNotesListSurface()) return KaiSurfaceFamily.LIST_SURFACE
        if (state.isInstagramMessagesEntrySurface() || state.isTabbedHomeSurface()) return KaiSurfaceFamily.TABBED_HOME_SURFACE
        if (state.isBrowserLikeSurface()) return KaiSurfaceFamily.BROWSER_LIKE_SURFACE
        if (state.isContentFeedSurface()) return KaiSurfaceFamily.CONTENT_FEED_SURFACE
        if (state.isInstagramFeedSurface()) return KaiSurfaceFamily.APP_HOME_SURFACE
        return KaiSurfaceFamily.UNKNOWN_SURFACE
    }

    fun equivalentFamilies(family: KaiSurfaceFamily): Set<KaiSurfaceFamily> {
        return when (normalizeLegacyFamily(family)) {
            KaiSurfaceFamily.APP_HOME_SURFACE -> setOf(KaiSurfaceFamily.APP_HOME_SURFACE, KaiSurfaceFamily.TABBED_HOME_SURFACE)
            KaiSurfaceFamily.TABBED_HOME_SURFACE -> setOf(KaiSurfaceFamily.TABBED_HOME_SURFACE, KaiSurfaceFamily.APP_HOME_SURFACE)
            KaiSurfaceFamily.CONTENT_FEED_SURFACE -> setOf(KaiSurfaceFamily.CONTENT_FEED_SURFACE)
            KaiSurfaceFamily.LIST_SURFACE -> setOf(KaiSurfaceFamily.LIST_SURFACE, KaiSurfaceFamily.RESULT_LIST_SURFACE)
            KaiSurfaceFamily.RESULT_LIST_SURFACE -> setOf(KaiSurfaceFamily.RESULT_LIST_SURFACE, KaiSurfaceFamily.LIST_SURFACE)
            KaiSurfaceFamily.THREAD_SURFACE -> setOf(KaiSurfaceFamily.THREAD_SURFACE, KaiSurfaceFamily.COMPOSER_SURFACE)
            KaiSurfaceFamily.COMPOSER_SURFACE -> setOf(KaiSurfaceFamily.COMPOSER_SURFACE, KaiSurfaceFamily.THREAD_SURFACE)
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE, KaiSurfaceFamily.MODAL_SURFACE -> setOf(KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE, KaiSurfaceFamily.MODAL_SURFACE)
            else -> setOf(normalizeLegacyFamily(family))
        }
    }

    fun isWrongForInputOrSend(family: KaiSurfaceFamily): Boolean {
        val normalized = normalizeLegacyFamily(family)
        return normalized in setOf(
            KaiSurfaceFamily.SEARCH_SURFACE,
            KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE,
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE,
            KaiSurfaceFamily.SETTINGS_SURFACE,
            KaiSurfaceFamily.BROWSER_LIKE_SURFACE,
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.DETAIL_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.UNKNOWN_SURFACE,
            KaiSurfaceFamily.LAUNCHER_SURFACE
        )
    }

    fun isRecoverableFamily(family: KaiSurfaceFamily): Boolean {
        return normalizeLegacyFamily(family) in setOf(
            KaiSurfaceFamily.SEARCH_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.MEDIA_CAPTURE_SURFACE,
            KaiSurfaceFamily.SHEET_OR_DIALOG_SURFACE,
            KaiSurfaceFamily.MODAL_SURFACE,
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.TABBED_HOME_SURFACE
        )
    }

    fun isDeadEndFamily(family: KaiSurfaceFamily): Boolean {
        return normalizeLegacyFamily(family) in setOf(
            KaiSurfaceFamily.UNKNOWN_SURFACE,
            KaiSurfaceFamily.LAUNCHER_SURFACE,
            KaiSurfaceFamily.SETTINGS_SURFACE
        )
    }

    fun isPostOpenReadyFamily(family: KaiSurfaceFamily): Boolean {
        return normalizeLegacyFamily(family) in setOf(
            KaiSurfaceFamily.APP_HOME_SURFACE,
            KaiSurfaceFamily.TABBED_HOME_SURFACE,
            KaiSurfaceFamily.CONTENT_FEED_SURFACE,
            KaiSurfaceFamily.LIST_SURFACE,
            KaiSurfaceFamily.RESULT_LIST_SURFACE,
            KaiSurfaceFamily.DETAIL_SURFACE,
            KaiSurfaceFamily.THREAD_SURFACE,
            KaiSurfaceFamily.COMPOSER_SURFACE,
            KaiSurfaceFamily.EDITOR_SURFACE,
            KaiSurfaceFamily.PLAYER_SURFACE
        )
    }
}
