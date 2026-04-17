package com.example.reply.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.reply.agent.KaiAppIdentityRegistry
import com.example.reply.agent.KaiGestureUtils
import com.example.reply.agent.KaiLiveObservationRuntime
import java.util.Locale
import kotlin.math.abs

class KaiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KaiAccessibility"

        const val ACTION_KAI_COMMAND = "com.example.reply.KAI_COMMAND"
        const val ACTION_KAI_DUMP_RESULT = "com.example.reply.KAI_DUMP_RESULT"

        const val EXTRA_CMD = "cmd"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DUMP = "extra_dump"
        const val EXTRA_DIR = "dir"
        const val EXTRA_TIMES = "times"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_EXPECTED_PACKAGE = "expected_package"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_END_X = "end_x"
        const val EXTRA_END_Y = "end_y"
        const val EXTRA_HOLD_MS = "hold_ms"

        const val CMD_DUMP = "dump"
        const val CMD_BACK = "back"
        const val CMD_HOME = "home"
        const val CMD_RECENTS = "recents"
        const val CMD_CLICK_TEXT = "click_text"
        const val CMD_LONG_PRESS_TEXT = "long_press_text"
        const val CMD_OPEN_APP = "open_app"
        const val CMD_INPUT_TEXT = "input_text"
        const val CMD_TYPE = CMD_INPUT_TEXT
        const val CMD_SCROLL = "scroll"
        const val CMD_TAP_XY = "tap_xy"
        const val CMD_LONG_PRESS_XY = "long_press_xy"
        const val CMD_SWIPE_XY = "swipe_xy"
        const val CMD_RESET_TRANSITION_STATE = "reset_transition_state"

        private const val EVENT_DEBOUNCE_MS = 220L
        private const val ACTION_REFRESH_DELAY_MS = 220L
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var dumpInProgress = false

    private var lastDumpAt = 0L
    private var lastPublishedEventAt = 0L
    private var lastKnownPackage: String = ""
    private var expectedPackageHint: String = ""

    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.example.reply",
        "com.example.reply.ui"
    )

    private val overlayNoiseHints = listOf(
        "dynamic island",
        "custom prompt",
        "make action",
        "agent loop active",
        "agent planning",
        "agent executing",
        "kai os",
        "soft reset",
        "prompt ready",
        "type to kai"
    )

    private val launcherHints = listOf(
        "launcher",
        "home",
        "miui.home",
        "trebuchet",
        "pixel launcher"
    )

    private val keyboardHints = listOf(
        "inputmethod",
        "keyboard",
        "gboard",
        "latin",
        "swiftkey"
    )

    private val recorderHints = listOf(
        "screenrecorder",
        "screen_record",
        "screen.record",
        "recorder",
        "recording",
        "screen.capture",
        "screencap",
        "mobizen"
    )

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_KAI_COMMAND) return

            val cmd = intent.getStringExtra(EXTRA_CMD).orEmpty()
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            val dir = intent.getStringExtra(EXTRA_DIR).orEmpty()
            val times = intent.getIntExtra(EXTRA_TIMES, 1).coerceIn(1, 10)
            val holdMs = intent.getLongExtra(EXTRA_HOLD_MS, 450L)
            val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty().trim()

            val x = if (intent.hasExtra(EXTRA_X)) intent.getFloatExtra(EXTRA_X, 0f) else null
            val y = if (intent.hasExtra(EXTRA_Y)) intent.getFloatExtra(EXTRA_Y, 0f) else null
            val endX = if (intent.hasExtra(EXTRA_END_X)) intent.getFloatExtra(EXTRA_END_X, 0f) else null
            val endY = if (intent.hasExtra(EXTRA_END_Y)) intent.getFloatExtra(EXTRA_END_Y, 0f) else null

            when (cmd) {
                CMD_DUMP -> requestDump(expectedPackage)
                CMD_BACK -> performGlobal(GLOBAL_ACTION_BACK)
                CMD_HOME -> performGlobal(GLOBAL_ACTION_HOME)
                CMD_RECENTS -> performGlobal(GLOBAL_ACTION_RECENTS)
                CMD_RESET_TRANSITION_STATE -> resetTransitionState()

                CMD_CLICK_TEXT -> {
                    if (text.isNotBlank() && clickByVisibleText(text)) {
                        scheduleRefresh(expectedPackage)
                    }
                }

                CMD_LONG_PRESS_TEXT -> {
                    if (text.isNotBlank() && longPressByVisibleText(text, holdMs)) {
                        scheduleRefresh(expectedPackage)
                    }
                }

                CMD_OPEN_APP -> {
                    if (text.isNotBlank() && openInstalledApp(text)) {
                        scheduleRefresh(expectedPackage.ifBlank { resolveExpectedPackageForApp(text) }, bursts = 3)
                    }
                }

                CMD_INPUT_TEXT, CMD_TYPE -> {
                    if (text.isNotBlank() && inputTextIntoFocusedField(text)) {
                        scheduleRefresh(expectedPackage)
                    }
                }

                CMD_SCROLL -> {
                    if (scroll(dir, times)) {
                        scheduleRefresh(expectedPackage)
                    }
                }

                CMD_TAP_XY -> {
                    if (dispatchTapAt(x, y)) {
                        scheduleRefresh(expectedPackage)
                    }
                }

                CMD_LONG_PRESS_XY -> {
                    if (dispatchLongPressAt(x, y, holdMs)) {
                        scheduleRefresh(expectedPackage)
                    }
                }

                CMD_SWIPE_XY -> {
                    if (dispatchSwipe(x, y, endX, endY, holdMs)) {
                        scheduleRefresh(expectedPackage)
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    commandReceiver,
                    IntentFilter(ACTION_KAI_COMMAND),
                    RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(commandReceiver, IntentFilter(ACTION_KAI_COMMAND))
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed, retrying fallback", e)
            @Suppress("DEPRECATION")
            registerReceiver(commandReceiver, IntentFilter(ACTION_KAI_COMMAND))
        }

        Log.d(TAG, "KaiAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val pkg = safeEvent.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return
        if (isIgnorablePackage(pkg)) return

        lastKnownPackage = pkg

        val now = System.currentTimeMillis()
        val meaningful = when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> true

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> true
            else -> false
        }

        if (!meaningful) return
        if (dumpInProgress) return
        if (now - lastPublishedEventAt < EVENT_DEBOUNCE_MS) return

        lastPublishedEventAt = now
        publishObservation()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        try {
            unregisterReceiver(commandReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun requestDump(expectedPackage: String = "") {
        val now = System.currentTimeMillis()
        if (dumpInProgress) return
        if (now - lastDumpAt < 80L) return

        lastDumpAt = now
        expectedPackageHint = expectedPackage

        Thread {
            dumpInProgress = true
            try {
                val root = getBestRoot(expectedPackage)
                val pkg = resolvePackage(root?.packageName?.toString().orEmpty(), expectedPackage)
                val dump = dumpScreenText(root)
                sendDumpToApp(dump, pkg)
            } catch (e: Exception) {
                Log.e(TAG, "requestDump failed", e)
                sendDumpToApp("(no active window)", "")
            } finally {
                expectedPackageHint = ""
                dumpInProgress = false
            }
        }.start()
    }

    private fun scheduleRefresh(expectedPackage: String = "", bursts: Int = 2) {
        repeat(bursts.coerceIn(1, 4)) { index ->
            main.postDelayed(
                { requestDump(expectedPackage) },
                ACTION_REFRESH_DELAY_MS * (index + 1L)
            )
        }
    }

    private fun publishObservation() {
        try {
            val root = getBestRoot(expectedPackageHint) ?: return
            val pkg = resolvePackage(root.packageName?.toString().orEmpty(), expectedPackageHint)
            if (pkg.isBlank()) return

            val dump = dumpScreenText(root)
            if (dump == "(no active window)" || dump == "(empty dump)") return
            if (isOverlayPolluted(dump)) return

            KaiLiveObservationRuntime.onEventObservation(
                pkg = pkg,
                dump = dump,
                elements = emptyList(),
                screenKind = "unknown",
                confidence = 0.55f
            )
        } catch (e: Exception) {
            Log.e(TAG, "publishObservation failed", e)
        }
    }

    private fun sendDumpToApp(dump: String, packageName: String) {
        sendBroadcast(
            Intent(ACTION_KAI_DUMP_RESULT).apply {
                setPackage(this@KaiAccessibilityService.packageName)
                putExtra(EXTRA_DUMP, dump)
                putExtra(EXTRA_PACKAGE, packageName)
            }
        )
    }

    private fun resetTransitionState() {
        lastKnownPackage = ""
        expectedPackageHint = ""
        lastPublishedEventAt = 0L
        lastDumpAt = 0L
        dumpInProgress = false
    }

    private fun performGlobal(action: Int) {
        try {
            performGlobalAction(action)
        } catch (e: Exception) {
            Log.e(TAG, "performGlobal failed: $action", e)
        }
    }

    private fun getBestRoot(expectedPackage: String = ""): AccessibilityNodeInfo? {
        val allRoots = mutableListOf<AccessibilityNodeInfo>()

        try {
            windows?.forEach { window ->
                val root = window.root ?: return@forEach
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isBlank()) return@forEach
                if (isIgnorablePackage(pkg)) return@forEach

                if (Build.VERSION.SDK_INT >= 21) {
                    when (window.type) {
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                        AccessibilityWindowInfo.TYPE_SYSTEM,
                        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> return@forEach
                    }
                }

                allRoots += root
            }
        } catch (_: Exception) {
        }

        rootInActiveWindow?.let { active ->
            val pkg = active.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() && !isIgnorablePackage(pkg) && allRoots.none { it === active }) {
                allRoots += active
            }
        }

        if (allRoots.isEmpty()) return rootInActiveWindow

        if (expectedPackage.isNotBlank()) {
            allRoots.firstOrNull { packageMatchesExpected(it.packageName?.toString().orEmpty(), expectedPackage) }?.let {
                return it
            }
        }

        allRoots.firstOrNull { it.packageName?.toString().orEmpty() == lastKnownPackage }?.let {
            return it
        }

        allRoots.firstOrNull { isExternalAppPackage(it.packageName?.toString().orEmpty()) }?.let {
            return it
        }

        return allRoots.firstOrNull()
    }

    private fun dumpScreenText(root: AccessibilityNodeInfo?): String {
        val safeRoot = root ?: return "(no active window)"
        val lines = linkedSetOf<String>()
        collectText(safeRoot, lines)

        return lines
            .asSequence()
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }
            .filterNot { line -> isOverlayNoiseLine(line) }
            .take(280)
            .joinToString("\n") { "• $it" }
            .ifBlank { "(empty dump)" }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return

        val text = node.text?.toString().orEmpty().trim()
        val desc = node.contentDescription?.toString().orEmpty().trim()
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            node.hintText?.toString().orEmpty().trim()
        } else {
            ""
        }

        if (text.isNotBlank()) out += text
        if (desc.isNotBlank() && desc != text) out += desc
        if (hint.isNotBlank() && hint != text && hint != desc) out += hint

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out)
        }
    }

    private fun cleanLine(value: String): String {
        var text = value.trim()
        text = text.replace(Regex("""android\.[\w.]+"""), "")
        text = text.replace("[desc]", "", ignoreCase = true)
        text = text.replace(Regex("""[•●■◆▶︎]+"""), " ")
        text = text.replace(Regex("""\s+"""), " ").trim()

        if (text.length < 2) return ""
        if (text.all { !it.isLetterOrDigit() }) return ""

        return text
    }

    private fun isOverlayNoiseLine(line: String): Boolean {
        val n = norm(line)
        return overlayNoiseHints.any { n.contains(norm(it)) }
    }

    private fun isOverlayPolluted(raw: String): Boolean {
        val n = norm(raw)
        if (n.isBlank()) return false
        val hits = overlayNoiseHints.count { n.contains(norm(it)) }
        return hits >= 2
    }

    private fun resolvePackage(raw: String, expectedPackage: String = ""): String {
        if (raw.isNotBlank()) return raw
        if (expectedPackage.isNotBlank()) return expectedPackage
        if (lastKnownPackage.isNotBlank()) return lastKnownPackage
        return ""
    }

    private fun resolveExpectedPackageForApp(query: String): String {
        val appKey = KaiAppIdentityRegistry.resolveAppKey(query)
        return KaiAppIdentityRegistry.primaryPackageForKey(appKey).orEmpty()
    }

    private fun packageMatchesExpected(pkg: String, expected: String): Boolean {
        val p = norm(pkg)
        val e = norm(expected)
        if (e.isBlank()) return true
        if (p.isBlank()) return false
        return p == e || p.startsWith("$e.")
    }

    private fun norm(text: String): String =
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

    private fun isLauncherPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return launcherHints.any { p.contains(it) }
    }

    private fun isKeyboardPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return keyboardHints.any { p.contains(it) }
    }

    private fun isRecorderPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return recorderHints.any { p.contains(it) }
    }

    private fun isIgnorablePackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (ignoredPackages.contains(pkg)) return true
        if (isKeyboardPackage(pkg)) return true
        if (isRecorderPackage(pkg)) return true
        return false
    }

    private fun isExternalAppPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (isIgnorablePackage(pkg)) return false
        if (isLauncherPackage(pkg)) return false
        return true
    }

    private fun clickByVisibleText(query: String): Boolean {
        val target = findBestNodeByVisibleText(query) ?: return false
        val clickable = if (target.isClickable) target else findClickableParent(target)
        return performClick(clickable ?: target)
    }

    private fun longPressByVisibleText(query: String, holdMs: Long): Boolean {
        val target = findBestNodeByVisibleText(query) ?: return false

        val actionLongClick = try {
            if (target.isLongClickable) {
                target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        if (actionLongClick) return true

        val center = nodeCenter(target)
        return dispatchLongPressAt(center.first, center.second, holdMs)
    }

    private fun findBestNodeByVisibleText(query: String): AccessibilityNodeInfo? {
        val root = getBestRoot() ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()

        aliasCandidatesFor(query).forEach { variant ->
            collectMatchingNodes(root, variant, matches)
        }

        if (matches.isEmpty()) return null

        return matches
            .distinctBy { stableNodeKey(it) }
            .maxByOrNull { scoreNodeMatch(it, query) }
    }

    private fun aliasCandidatesFor(query: String): List<String> {
        val q = norm(query)
        val items = linkedSetOf(query.trim())

        fun addAll(vararg values: String) {
            values.forEach { if (it.isNotBlank()) items += it }
        }

        when {
            KaiCommandParser.containsAny(q, "instagram", "insta", "ig", "انستا", "انستغرام") ->
                addAll("Instagram", "instagram", "Insta", "IG", "انستغرام", "انستا")

            KaiCommandParser.containsAny(q, "messages", "message", "dm", "inbox", "direct", "رسائل", "محادثات") ->
                addAll("Messages", "messages", "Message", "DM", "Inbox", "Direct", "رسائل", "محادثات")

            KaiCommandParser.containsAny(q, "whatsapp", "واتساب", "واتس") ->
                addAll("WhatsApp", "whatsapp", "واتساب", "واتس")

            KaiCommandParser.containsAny(q, "notes", "note", "ملاحظات", "الملاحظات") ->
                addAll("Notes", "notes", "Note", "ملاحظات", "الملاحظات")

            KaiCommandParser.containsAny(q, "youtube", "يوتيوب") ->
                addAll("YouTube", "youtube", "يوتيوب")

            KaiCommandParser.containsAny(q, "search", "بحث") ->
                addAll("Search", "search", "بحث")

            KaiCommandParser.containsAny(q, "camera", "كاميرا", "الكاميرا") ->
                addAll("Camera", "camera", "الكاميرا")

            KaiCommandParser.containsAny(q, "gallery", "photos", "المعرض", "الصور") ->
                addAll("Gallery", "Photos", "gallery", "photos", "المعرض", "الصور")

            KaiCommandParser.containsAny(q, "send", "reply", "post", "ارسال", "إرسال") ->
                addAll("Send", "Reply", "Post", "ارسال", "إرسال")

            KaiCommandParser.containsAny(q, "new", "create", "compose", "add", "plus", "جديد", "إنشاء", "انشاء") ->
                addAll("New", "Create", "Compose", "Add", "Plus", "جديد", "إنشاء")
        }

        return items.toList()
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo?,
        query: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return

        val text = node.text?.toString().orEmpty().trim()
        val desc = node.contentDescription?.toString().orEmpty().trim()
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty().trim() else ""
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()

        if (textMatches(text, query) || textMatches(desc, query) || textMatches(hint, query) || textMatches(viewId, query)) {
            out += node
        }

        for (i in 0 until node.childCount) {
            collectMatchingNodes(node.getChild(i), query, out)
        }
    }

    private fun textMatches(value: String, query: String): Boolean {
        if (value.isBlank() || query.isBlank()) return false
        val nv = norm(value)
        val nq = norm(query)
        return nv == nq || nv.contains(nq) || nq.contains(nv) || levenshteinLiteClose(nv, nq)
    }

    private fun scoreNodeMatch(node: AccessibilityNodeInfo, query: String): Int {
        val q = norm(query)
        val text = norm(node.text?.toString().orEmpty())
        val desc = norm(node.contentDescription?.toString().orEmpty())
        val hint = if (Build.VERSION.SDK_INT >= 26) norm(node.hintText?.toString().orEmpty()) else ""
        val id = norm(node.viewIdResourceName?.substringAfterLast('/').orEmpty())

        var score = 0

        if (text == q) score += 140
        if (desc == q) score += 130
        if (hint == q) score += 90
        if (id == q) score += 80

        if (text.contains(q) && text != q) score += 70
        if (desc.contains(q) && desc != q) score += 62
        if (hint.contains(q) && hint != q) score += 45
        if (id.contains(q) && id != q) score += 40

        if (levenshteinLiteClose(text, q)) score += 35
        if (levenshteinLiteClose(desc, q)) score += 30

        if (node.isClickable) score += 35
        if (node.isEnabled) score += 18
        if (node.isVisibleToUser) score += 18

        return score
    }

    private fun stableNodeKey(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Exception) {
        }
        return listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
            node.viewIdResourceName.orEmpty(),
            rect.left,
            rect.top,
            rect.right,
            rect.bottom
        ).joinToString("|")
    }

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node ?: return null
        repeat(10) {
            if (current.isClickable) return current
            current = current.parent ?: return null
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        repeat(10) {
            val clicked = try {
                if (current.isClickable && current.isEnabled) {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else false
            } catch (_: Exception) {
                false
            }
            if (clicked) return true
            current = current.parent ?: return@repeat
        }

        val center = node?.let { nodeCenter(it) }
        return center != null && dispatchTapAt(center.first, center.second)
    }

    private fun openInstalledApp(query: String): Boolean {
        val pm = packageManager ?: return false
        val resolved = KaiCommandParser.resolveAppAlias(query)
        val appKey = KaiAppIdentityRegistry.resolveAppKey(resolved)
        val canonicalPackages = KaiAppIdentityRegistry.packageCandidatesForKey(appKey)

        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePkg == packageName || activePkg == "com.example.reply") {
            runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
            try {
                Thread.sleep(160L)
            } catch (_: Exception) {
            }
        }

        val packageCandidates = linkedSetOf<String>().apply {
            add(query.trim())
            add(resolved.trim())
            add(resolved.trim().replace(" ", ""))
            canonicalPackages.forEach { add(it) }
        }.filter { it.isNotBlank() }

        for (pkg in packageCandidates) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(launchIntent)
                    return true
                }
            } catch (_: Exception) {
            }
        }

        val normalizedResolved = norm(resolved)
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val best = apps
            .asSequence()
            .map { app ->
                val label = pm.getApplicationLabel(app)?.toString().orEmpty()
                val packageName = app.packageName.orEmpty()
                val shortPkg = packageName.substringAfterLast('.')

                val nLabel = norm(label)
                val nPkg = norm(shortPkg)
                val nFull = norm(packageName)

                var score = 0
                if (nLabel == normalizedResolved) score += 160
                if (nPkg == normalizedResolved) score += 150
                if (nFull.contains(normalizedResolved)) score += 110
                if (nLabel.contains(normalizedResolved)) score += 90
                if (nPkg.contains(normalizedResolved)) score += 70
                if (levenshteinLiteClose(nLabel, normalizedResolved)) score += 45
                if (levenshteinLiteClose(nPkg, normalizedResolved)) score += 35

                Triple(app, label, score)
            }
            .filter { it.third > 0 }
            .maxByOrNull { it.third }
            ?.first

        if (best != null) {
            val launchIntent = pm.getLaunchIntentForPackage(best.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(launchIntent)
                return true
            }
        }

        if (isLauncherPackage(currentVisiblePackage())) {
            return clickByVisibleText(query)
        }

        return false
    }

    private fun currentVisiblePackage(): String {
        val active = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (active.isNotBlank()) return active

        try {
            windows?.forEach { window ->
                val pkg = window.root?.packageName?.toString().orEmpty()
                if (pkg.isNotBlank()) return pkg
            }
        } catch (_: Exception) {
        }

        return ""
    }

    private fun inputTextIntoFocusedField(text: String): Boolean {
        val root = getBestRoot() ?: return false
        val focused =
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableFocused(root)
                ?: findFirstEditable(root)
                ?: return false

        try {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Exception) {
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        val setTextOk = try {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }

        if (setTextOk) return true
        return tryPaste(text, focused)
    }

    private fun tryPaste(text: String, node: AccessibilityNodeInfo): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("kai", text))
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Exception) {
            }
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }

    private fun findFirstEditableFocused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            findFirstEditableFocused(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun scroll(dir: String, times: Int): Boolean {
        val root = getBestRoot() ?: return false
        val target = findScrollable(root) ?: root
        val normalized = dir.lowercase(Locale.ROOT)

        var any = false
        repeat(times) {
            val ok = tryDirectionalScroll(target, normalized)
            any = any || ok
            if (!ok) {
                any = gestureScrollFallback(normalized) || any
            }
        }
        return any
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun tryDirectionalScroll(node: AccessibilityNodeInfo, dir: String): Boolean {
        val fallbackAction = when (dir) {
            "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        if (Build.VERSION.SDK_INT >= 23) {
            val directionalAction = when (dir) {
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> fallbackAction
            }

            val supportsDirectional = try {
                node.actionList?.any { it.id == directionalAction } == true
            } catch (_: Exception) {
                false
            }

            if (supportsDirectional) {
                val ok = try {
                    node.performAction(directionalAction, Bundle())
                } catch (_: Exception) {
                    false
                }
                if (ok) return true
            }
        }

        return try {
            node.performAction(fallbackAction)
        } catch (_: Exception) {
            false
        }
    }

    private fun gestureScrollFallback(dir: String): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false

        val w = screenWidth().toFloat()
        val h = screenHeight().toFloat()

        val (sx, sy, ex, ey) = when (dir.lowercase(Locale.ROOT)) {
            "up" -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f)
            "down" -> listOf(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.72f)
            "left" -> listOf(w * 0.78f, h * 0.5f, w * 0.22f, h * 0.5f)
            "right" -> listOf(w * 0.22f, h * 0.5f, w * 0.78f, h * 0.5f)
            else -> return false
        }

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun nodeCenter(node: AccessibilityNodeInfo): Pair<Float?, Float?> {
        val rect = Rect()
        return try {
            node.getBoundsInScreen(rect)
            rect.exactCenterX() to rect.exactCenterY()
        } catch (_: Exception) {
            null to null
        }
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels.coerceAtLeast(1)
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels.coerceAtLeast(1)

    private fun resolveX(value: Float?): Float =
        KaiGestureUtils.resolveCoordinate(value, screenWidth(), 0.5f)

    private fun resolveY(value: Float?): Float =
        KaiGestureUtils.resolveCoordinate(value, screenHeight(), 0.5f)

    private fun dispatchTapAt(x: Float?, y: Float?): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false

        val px = resolveX(x)
        val py = resolveY(y)

        val path = Path().apply {
            moveTo(px, py)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun dispatchLongPressAt(x: Float?, y: Float?, holdMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false

        val px = resolveX(x)
        val py = resolveY(y)
        val duration = KaiGestureUtils.resolveDuration(holdMs, 550L)

        val path = Path().apply {
            moveTo(px, py)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun dispatchSwipe(
        x: Float?,
        y: Float?,
        endX: Float?,
        endY: Float?,
        holdMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false

        val startX = resolveX(x)
        val startY = resolveY(y)
        val finishX = resolveX(endX)
        val finishY = resolveY(endY)
        val duration = KaiGestureUtils.resolveDuration(holdMs, 450L)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(finishX, finishY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun levenshteinLiteClose(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        if (a.length < 4 || b.length < 4) return false
        if (abs(a.length - b.length) > 2) return false
        if (a.contains(b) || b.contains(a)) return true

        var mismatches = 0
        val limit = minOf(a.length, b.length)
        for (i in 0 until limit) {
            if (a[i] != b[i]) mismatches++
            if (mismatches > 2) return false
        }

        mismatches += abs(a.length - b.length)
        return mismatches <= 2
    }
}