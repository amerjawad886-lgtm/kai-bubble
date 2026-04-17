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
import com.example.reply.agent.KaiAccessibilitySnapshotBridge
import com.example.reply.agent.KaiAppIdentityRegistry
import com.example.reply.agent.KaiUiElement
import com.example.reply.agent.KaiGestureUtils
import com.example.reply.agent.KaiLiveObservationRuntime
import com.example.reply.agent.KaiLiveVisionRuntime
import com.example.reply.agent.KaiScreenState
import com.example.reply.agent.KaiScreenStateParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class KaiAccessibilityService : AccessibilityService(), KaiAccessibilitySnapshotBridge.Provider {

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
        const val EXTRA_ELEMENTS_JSON = "elements_json"
        const val EXTRA_SCREEN_KIND = "screen_kind"
        const val EXTRA_SEMANTIC_CONFIDENCE = "semantic_confidence"
        const val EXTRA_EXPECTED_PACKAGE = "expected_package"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_END_X = "end_x"
        const val EXTRA_END_Y = "end_y"
        const val EXTRA_HOLD_MS = "hold_ms"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"

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
        private const val EVENT_DEBOUNCE_MS = 180L
    }

    private data class DumpCandidate(
        val root: AccessibilityNodeInfo?,
        val packageName: String,
        val dump: String,
        val score: Int,
        val fingerprint: String,
        val elements: List<KaiUiElement>,
        val screenKind: String,
        val semanticConfidence: Float
    )

    private data class SemanticExtraction(
        val elements: List<KaiUiElement>,
        val screenKind: String,
        val confidence: Float
    )

    private val main = Handler(Looper.getMainLooper())
    private var lastDumpAt = 0L
    private var lastKnownExternalPackage: String? = null
    private var lastDeliveredFingerprint: String = ""
    @Volatile private var dumpInProgress = false
    @Volatile private var dumpGeneration: Int = 0
    private var lastWindowEventAt = 0L
    private var lastPackageChangeAt = 0L
    private var previousExternalPackage: String? = null
    @Volatile private var expectedDumpPackage: String = ""
    @Volatile private var lastEventPublishAt = 0L
    private val eventPublishExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val recorderPackageHints = listOf(
        "screenrecorder", "screen_record", "screen.record",
        "recorder", "recording", "screen.capture",
        "screencap", "screenmaster", "mobizen"
    )

    private val keyboardPackageHints = listOf(
        "inputmethod", "keyboard", "gboard", "latin", "swiftkey"
    )

    private val launcherPackageHints = listOf(
        "launcher", "home", "miui.home", "trebuchet", "pixel launcher"
    )

    private val ignoredDumpTextHints = listOf(
        "dynamic island",
        "custom prompt",
        "make action",
        "agent loop active",
        "agent planning",
        "agent executing",
        "agent observing",
        "monitoring paused before action loop",
        "control panel",
        "talk mode",
        "kai os",
        "open app",
        "working",
        "close",
        "prompt ready",
        "type to kai",
        "new chat",
        "history",
        "presence",
        "soft reset"
    )

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_KAI_COMMAND) return

            val cmd = intent.getStringExtra(EXTRA_CMD).orEmpty()
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            val dir = intent.getStringExtra(EXTRA_DIR).orEmpty()
            val times = intent.getIntExtra(EXTRA_TIMES, 1).coerceIn(1, 10)
            val x = if (intent.hasExtra(EXTRA_X)) intent.getFloatExtra(EXTRA_X, 0f) else null
            val y = if (intent.hasExtra(EXTRA_Y)) intent.getFloatExtra(EXTRA_Y, 0f) else null
            val endX = if (intent.hasExtra(EXTRA_END_X)) intent.getFloatExtra(EXTRA_END_X, 0f) else null
            val endY = if (intent.hasExtra(EXTRA_END_Y)) intent.getFloatExtra(EXTRA_END_Y, 0f) else null
            val holdMs = intent.getLongExtra(EXTRA_HOLD_MS, 450L)
            val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, 4000L)
            val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty().trim()

            when (cmd) {
                CMD_DUMP -> {
                    val now = System.currentTimeMillis()
                    if (now - lastDumpAt < 60L) return
                    if (dumpInProgress) return
                    lastDumpAt = now

                    val captureGen = dumpGeneration
                    Thread {
                        dumpInProgress = true
                        expectedDumpPackage = expectedPackage
                        try {
                            val candidate = captureBestDump(timeoutMs, expectedPackage)
                            if (dumpGeneration == captureGen) lastDeliveredFingerprint = candidate.fingerprint
                            sendDumpToApp(
                                dump = candidate.dump,
                                packageName = candidate.packageName,
                                elements = candidate.elements,
                                screenKind = candidate.screenKind,
                                semanticConfidence = candidate.semanticConfidence
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "captureBestDump failed: ${e.message}", e)
                            sendDumpToApp("(no active window)", "")
                        } finally {
                            expectedDumpPackage = ""
                            dumpInProgress = false
                        }
                    }.start()
                }

                else -> {
                    main.post {
                        try {
                            when (cmd) {
                                CMD_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                                CMD_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                                CMD_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                                CMD_RESET_TRANSITION_STATE -> resetTransitionMemory()

                                CMD_CLICK_TEXT -> if (text.isNotBlank()) {
                                    val ok = clickByVisibleText(text)
                                    Log.d(TAG, "clickByVisibleText('$text') => $ok")
                                    if (ok) scheduleObservationRefresh(
                                        expectedPackage = expectedPackage.ifBlank { lastKnownExternalPackage.orEmpty() },
                                        bursts = 2,
                                        gapMs = 170L
                                    )
                                }

                                CMD_LONG_PRESS_TEXT -> if (text.isNotBlank()) {
                                    val ok = longPressByVisibleText(text, holdMs)
                                    Log.d(TAG, "longPressByVisibleText('$text') => $ok")
                                    if (ok) scheduleObservationRefresh(
                                        expectedPackage = expectedPackage.ifBlank { lastKnownExternalPackage.orEmpty() },
                                        bursts = 2,
                                        gapMs = 170L
                                    )
                                }

                                CMD_OPEN_APP -> if (text.isNotBlank()) {
                                    val resolvedKey = KaiAppIdentityRegistry.resolveAppKey(text)
                                    val resolvedExpected = expectedPackage.ifBlank {
                                        KaiAppIdentityRegistry.primaryPackageForKey(resolvedKey)
                                    }
                                    val ok = openInstalledApp(text)
                                    Log.d(TAG, "openInstalledApp('$text') => $ok")
                                    if (ok) {
                                        scheduleObservationRefresh(
                                            expectedPackage = resolvedExpected,
                                            bursts = 4,
                                            gapMs = 190L
                                        )
                                    }
                                }

                                CMD_INPUT_TEXT, CMD_TYPE -> if (text.isNotBlank()) {
                                    val ok = inputTextIntoFocusedField(text)
                                    Log.d(TAG, "inputTextIntoFocusedField('$text') => $ok")
                                    if (ok) scheduleObservationRefresh(
                                        expectedPackage = expectedPackage.ifBlank { lastKnownExternalPackage.orEmpty() },
                                        bursts = 2,
                                        gapMs = 160L
                                    )
                                }

                                CMD_SCROLL -> {
                                    val ok = scroll(dir, times)
                                    Log.d(TAG, "scroll('$dir', $times) => $ok")
                                    if (ok) scheduleObservationRefresh(
                                        expectedPackage = expectedPackage.ifBlank { lastKnownExternalPackage.orEmpty() },
                                        bursts = 2,
                                        gapMs = 220L
                                    )
                                }

                                CMD_TAP_XY -> {
                                    val ok = dispatchTapAt(x, y)
                                    Log.d(TAG, "tap_xy => $ok")
                                    if (ok) scheduleObservationRefresh(
                                        expectedPackage = expectedPackage.ifBlank { lastKnownExternalPackage.orEmpty() },
                                        bursts = 2,
                                        gapMs = 170L
                                    )
                                }

                                CMD_LONG_PRESS_XY -> {
                                    val ok = dispatchLongPressAt(x, y, holdMs)
                                    Log.d(TAG, "long_press_xy => $ok")
                                    if (ok) scheduleObservationRefresh(
                                        expectedPackage = expectedPackage.ifBlank { lastKnownExternalPackage.orEmpty() },
                                        bursts = 2,
                                        gapMs = 180L
                                    )
                                }

                                CMD_SWIPE_XY -> {
                                    val ok = dispatchSwipe(x, y, endX, endY, holdMs)
                                    Log.d(TAG, "swipe_xy => $ok")
                                    if (ok) scheduleObservationRefresh(
                                        expectedPackage = expectedPackage.ifBlank { lastKnownExternalPackage.orEmpty() },
                                        bursts = 2,
                                        gapMs = 220L
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Command failed: ${e.message}", e)
                        }

                        if (timeoutMs > 0) {
                            Log.d(TAG, "command timeout hint = $timeoutMs ms")
                        }
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
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED

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
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            registerReceiver(commandReceiver, IntentFilter(ACTION_KAI_COMMAND))
        }

        KaiAccessibilitySnapshotBridge.register(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val pkg = safeEvent.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return

        val now = System.currentTimeMillis()
        lastWindowEventAt = now

        val packageChanged = pkg != lastKnownExternalPackage
        if (!isIgnorablePackage(pkg) && packageChanged) {
            previousExternalPackage = lastKnownExternalPackage
            lastKnownExternalPackage = pkg
            lastPackageChangeAt = now
        }

        val eventType = safeEvent.eventType
        val meaningfulEvent = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> packageChanged
            AccessibilityEvent.TYPE_VIEW_CLICKED -> true
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> (now - lastEventPublishAt) > 450L
            else -> false
        }

        if (!meaningfulEvent) return
        if ((now - lastEventPublishAt) < EVENT_DEBOUNCE_MS) return
        if (dumpInProgress) return

        lastEventPublishAt = now
        val capturedPkg = pkg
        eventPublishExecutor.execute {
            publishEventObservation(capturedPkg)
        }
    }

    private fun publishEventObservation(eventPkg: String) {
        try {
            val root = getTargetRoot(eventPkg) ?: return
            val rawPkg = root.packageName?.toString().orEmpty()
            val effectivePkg = rawPkg.ifBlank { eventPkg }
            if (effectivePkg.isBlank() || isIgnorablePackage(effectivePkg)) return

            val dump = dumpScreenText(root)
            if (dump == "(no active window)" || dump == "(empty dump)") return
            if (isOverlayPolluted(dump)) return

            val semantic = extractSemanticUi(root, effectivePkg, dump)
            if (semantic.elements.isEmpty() && dump.length < 12) return

            // Primary: feed package hint to live vision runtime so the visual
            // world-state can correlate visual transitions with app focus.
            KaiLiveVisionRuntime.onPackageFocusChanged(
                packageName = effectivePkg,
                screenClass = semantic.screenKind
            )

            // Legacy: still publish to the old observation runtime until it
            // is removed in the final batch of the live-vision migration.
            KaiLiveObservationRuntime.onEventObservation(
                pkg = effectivePkg,
                dump = dump,
                elements = semantic.elements,
                screenKind = semantic.screenKind,
                confidence = semantic.confidence
            )
        } catch (e: Exception) {
            Log.e(TAG, "Event observation publish failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // KaiAccessibilitySnapshotBridge.Provider
    //
    // On-demand snapshot for UI targeting only. NOT a continuous truth source.
    // World-state truth lives in KaiLiveVisionRuntime / KaiVisualWorldState.
    // ------------------------------------------------------------------

    override fun captureSnapshot(expectedPackage: String): KaiScreenState? {
        return try {
            val root = getTargetRoot(expectedPackage) ?: return null
            val rawPkg = root.packageName?.toString().orEmpty()
            val effectivePkg = rawPkg.ifBlank { expectedPackage.trim() }
            if (effectivePkg.isBlank() || isIgnorablePackage(effectivePkg)) return null

            val dump = dumpScreenText(root)
            if (dump == "(no active window)") return null

            val semantic = extractSemanticUi(root, effectivePkg, dump)
            KaiScreenStateParser.fromDump(
                packageName = effectivePkg,
                dump = dump,
                elements = semantic.elements,
                screenKindHint = semantic.screenKind,
                semanticConfidence = semantic.confidence
            )
        } catch (e: Exception) {
            Log.e(TAG, "captureSnapshot failed: ${e.message}")
            null
        }
    }

    override fun onInterrupt() {}


    private fun scheduleObservationRefresh(
        expectedPackage: String = "",
        bursts: Int = 2,
        gapMs: Long = 180L
    ) {
        val count = bursts.coerceIn(1, 5)
        repeat(count) { index ->
            main.postDelayed({
                if (dumpInProgress) return@postDelayed
                val captureGen = dumpGeneration
                Thread {
                    dumpInProgress = true
                    expectedDumpPackage = expectedPackage
                    try {
                        val candidate = captureBestDump(
                            timeoutMs = 900L + (index * 180L),
                            expectedPackage = expectedPackage
                        )
                        if (dumpGeneration == captureGen) {
                            lastDeliveredFingerprint = candidate.fingerprint
                        }
                        sendDumpToApp(
                            dump = candidate.dump,
                            packageName = candidate.packageName,
                            elements = candidate.elements,
                            screenKind = candidate.screenKind,
                            semanticConfidence = candidate.semanticConfidence
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "scheduleObservationRefresh failed: ${e.message}", e)
                    } finally {
                        expectedDumpPackage = ""
                        dumpInProgress = false
                    }
                }.start()
            }, (index + 1L) * gapMs)
        }
    }

    override fun onDestroy() {
        KaiAccessibilitySnapshotBridge.unregister(this)
        try {
            unregisterReceiver(commandReceiver)
        } catch (_: Exception) {
        }
        eventPublishExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun resetTransitionMemory() {
        previousExternalPackage = null
        lastKnownExternalPackage = null
        lastWindowEventAt = 0L
        lastPackageChangeAt = 0L
        expectedDumpPackage = ""
        lastDeliveredFingerprint = ""
        dumpGeneration++
        dumpInProgress = false
        lastDumpAt = 0L
        lastEventPublishAt = 0L
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

    private fun isRecorderPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return recorderPackageHints.any { p.contains(it) }
    }

    private fun isKeyboardPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return keyboardPackageHints.any { p.contains(it) }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return launcherPackageHints.any { p.contains(it) }
    }

    private fun isIgnorablePackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == packageName) return true
        if (pkg == "com.android.systemui") return true
        if (pkg == "com.example.reply" || pkg == "com.example.reply.ui") return true
        if (isRecorderPackage(pkg)) return true
        if (isKeyboardPackage(pkg)) return true
        // Launcher packages are now penalized in scoring, not ignored entirely
        return false
    }

    private fun sendDumpToApp(
        dump: String,
        packageName: String,
        elements: List<KaiUiElement> = emptyList(),
        screenKind: String = "unknown",
        semanticConfidence: Float = 0f
    ) {
        val resolvedPackage = resolveEffectivePackage(packageName, dump)
        val elementsJson = serializeElements(elements)
        sendBroadcast(
            Intent(ACTION_KAI_DUMP_RESULT).apply {
                setPackage(this@KaiAccessibilityService.packageName)
                putExtra(EXTRA_DUMP, dump)
                putExtra(EXTRA_PACKAGE, resolvedPackage)
                putExtra(EXTRA_ELEMENTS_JSON, elementsJson)
                putExtra(EXTRA_SCREEN_KIND, screenKind)
                putExtra(EXTRA_SEMANTIC_CONFIDENCE, semanticConfidence)
            }
        )
    }

    private fun fingerprintFor(packageName: String, dump: String): String {
        val compact = dump
            .lines()
            .map { it.removePrefix("•").trim() }
            .filter { it.isNotBlank() }
            .take(12)
            .joinToString("|")
            .lowercase(Locale.ROOT)

        return "${packageName.lowercase(Locale.ROOT)}::$compact::${dump.length}"
    }

    private fun packageMatchesExpected(pkg: String, expectedPackage: String): Boolean {
        val p = norm(pkg)
        val e = norm(expectedPackage)
        if (e.isBlank()) return true
        if (p.isBlank()) return false
        return p == e || p.startsWith("$e.")
    }

    private fun currentVisibleLauncherPackage(): String {
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePkg.isNotBlank() && isLauncherPackage(activePkg)) return activePkg

        try {
            windows?.forEach { window ->
                val root = window.root ?: return@forEach
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isNotBlank() && isLauncherPackage(pkg)) {
                    return pkg
                }
            }
        } catch (_: Exception) {
        }

        return ""
    }

    private fun isSubstantiveDump(dump: String): Boolean {
        val normalizedDump = norm(dump)
        if (normalizedDump.isBlank()) return false
        if (normalizedDump == norm("(no active window)")) return false
        if (normalizedDump == norm("(empty dump)")) return false
        if (isOverlayPolluted(dump)) return false
        return dump.lines().count { it.trim().isNotBlank() } >= 2 || dump.trim().length >= 18
    }

    private fun fallbackExternalPackage(
        expectedPackage: String = expectedDumpPackage,
        dump: String = ""
    ): String {
        val expected = expectedPackage.trim()
        val dumpIsSubstantive = isSubstantiveDump(dump)
        if (expected.isNotBlank() && dumpIsSubstantive && isExternalAppPackage(expected)) {
            return expected
        }
        return ""
    }

    private fun resolveEffectivePackage(
        rawPackage: String,
        dump: String,
        expectedPackage: String = expectedDumpPackage
    ): String {
        if (rawPackage.isNotBlank()) return rawPackage

        val normalizedDump = norm(dump)
        if (normalizedDump.isBlank()) return ""
        if (normalizedDump == norm("(no active window)")) return ""
        if (normalizedDump == norm("(empty dump)")) return ""
        if (isOverlayPolluted(dump)) return ""

        return fallbackExternalPackage(expectedPackage, dump)
    }

    private fun isWeakDumpCandidate(candidate: DumpCandidate): Boolean {
        val normalizedDump = norm(candidate.dump)
        if (candidate.packageName.isBlank() && expectedDumpPackage.isBlank()) return true
        if (normalizedDump.isBlank()) return true
        if (normalizedDump == norm("(no active window)")) return true
        if (normalizedDump == norm("(empty dump)")) return true
        if (ignoredDumpTextHints.count { hint -> normalizedDump.contains(norm(hint)) } >= 3) return true

        val lineCount = candidate.dump.lines().count { it.trim().isNotBlank() }
        val elementCount = candidate.elements.size
        if (isLauncherPackage(candidate.packageName) && lineCount < 4 && elementCount < 3) {
            return true
        }
        return false
    }

    private fun captureBestDump(timeoutMs: Long, expectedPackage: String = ""): DumpCandidate {
        val budget = timeoutMs.coerceIn(600L, 2200L)
        val startedAt = System.currentTimeMillis()
        var best: DumpCandidate? = null

        while (System.currentTimeMillis() - startedAt < budget) {
            val root = getTargetRoot(expectedPackage)
            val rawPkg = root?.packageName?.toString().orEmpty()
            val dump = dumpScreenText(root)
            val pkg = resolveEffectivePackage(rawPkg, dump, expectedPackage)
            val fingerprint = fingerprintFor(pkg, dump)
            val semantic = extractSemanticUi(root, pkg, dump)
            val score = scoreDumpQuality(pkg, dump, fingerprint) +
                (semantic.elements.size.coerceAtMost(24) * 4) +
                (semantic.confidence * 90f).toInt()

            val candidate = DumpCandidate(
                root = root,
                packageName = pkg,
                dump = dump,
                score = score,
                fingerprint = fingerprint,
                elements = semantic.elements,
                screenKind = semantic.screenKind,
                semanticConfidence = semantic.confidence
            )

            if (best == null || candidate.score > best!!.score) {
                best = candidate
            }

            val packageAcceptable = expectedPackage.isBlank() || packageMatchesExpected(candidate.packageName, expectedPackage)
            val strongEnough = candidate.packageName.isNotBlank() &&
                packageAcceptable &&
                !isWeakDumpCandidate(candidate) &&
                candidate.score >= 220

            if (strongEnough) {
                return candidate
            }

            try {
                Thread.sleep(110L)
            } catch (_: Exception) {
            }
        }

        return best ?: DumpCandidate(
            root = null,
            packageName = "",
            dump = "(no active window)",
            score = Int.MIN_VALUE / 8,
            fingerprint = "",
            elements = emptyList(),
            screenKind = "unknown",
            semanticConfidence = 0f
        )
    }

    private fun scoreDumpQuality(packageName: String, dump: String, fingerprint: String): Int {
        var score = 0
        val clean = dump.trim()
        val normalized = norm(clean)

        if (packageName.isNotBlank()) score += 120
        if (isExternalAppPackage(packageName)) score += 150
        if (expectedDumpPackage.isNotBlank() && packageMatchesExpected(packageName, expectedDumpPackage)) score += 260
        if (packageName == lastKnownExternalPackage && isRecentPackageTransition()) score += 90

        if (fingerprint != lastDeliveredFingerprint) score += 120 else score -= 35
        if (isLauncherPackage(packageName)) score -= 30

        if (clean.isBlank()) score -= 650
        if (clean.equals("(no active window)", true)) score -= 650
        if (clean.equals("(empty dump)", true)) score -= 520

        val lines = clean.lines().map { it.trim() }.filter { it.isNotBlank() }
        score += minOf(lines.size, 50) * 8
        score += minOf(clean.length, 2200) / 14

        if (isOverlayPolluted(clean)) score -= 420
        if (!isRecorderPackage(packageName) && !isKeyboardPackage(packageName)) score += 70

        if (normalized.contains("instagram")) score += 18
        if (normalized.contains("whatsapp")) score += 18
        if (normalized.contains("notes") || normalized.contains("ملاحظات")) score += 20
        if (normalized.contains("youtube") || normalized.contains("يوتيوب")) score += 18
        if (normalized.contains("messages") || normalized.contains("رسائل") || normalized.contains("محادثات")) score += 14

        return score
    }

    private fun isOverlayPolluted(raw: String): Boolean {
        val clean = norm(raw)
        if (clean.isBlank()) return false
        val hits = ignoredDumpTextHints.count { clean.contains(norm(it)) }
        return hits >= 2
    }

    private fun getTargetRoot(expectedPackage: String = expectedDumpPackage): AccessibilityNodeInfo? {
        val preferred = lastKnownExternalPackage
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

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
                        else -> Unit
                    }
                }

                var score = scoreRoot(root, window, preferred, expectedPackage)
                if (window.isFocused) score += 220
                if (window.isActive) score += 200
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) score += 220
                candidates += root to score
            }
        } catch (_: Exception) {
        }

        rootInActiveWindow?.let { active ->
            val pkg = active.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() && !isIgnorablePackage(pkg) && candidates.none { it.first === active }) {
                var score = scoreRoot(active, null, preferred, expectedPackage) + 240
                candidates += active to score
            }
        }

        if (expectedPackage.isNotBlank()) {
            val expectedOnly = candidates.filter {
                val pkg = it.first.packageName?.toString().orEmpty()
                packageMatchesExpected(pkg, expectedPackage)
            }
            if (expectedOnly.isNotEmpty()) {
                return expectedOnly.maxByOrNull { it.second }?.first
            }
        }

        return candidates.maxByOrNull { it.second }?.first ?: rootInActiveWindow
    }

    private fun scoreRoot(
        root: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo?,
        preferredPackage: String?,
        expectedPackage: String = ""
    ): Int {
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return Int.MIN_VALUE / 4
        if (isIgnorablePackage(pkg)) return Int.MIN_VALUE / 8

        var score = 0

        if (expectedPackage.isNotBlank()) {
            if (packageMatchesExpected(pkg, expectedPackage)) {
                score += 980
            } else {
                score -= 520
            }
        }

        if (pkg == preferredPackage) score += 240
        if (pkg == lastKnownExternalPackage && isRecentPackageTransition()) score += 140
        if (isExternalAppPackage(pkg)) score += 160

        if (window != null && Build.VERSION.SDK_INT >= 21) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) score += 240
            if (window.type == 5 /* TYPE_APPLICATION_OVERLAY */) score -= 120
            if (window.isFocused) score += 240
            if (window.isActive) score += 200
        }

        val textCount = estimateMeaningfulNodeCount(root)
        score += max(0, textCount * 7)

        val rect = Rect()
        try {
            root.getBoundsInScreen(rect)
            val widthScore = (rect.width() / 16).coerceAtLeast(0)
            val heightScore = (rect.height() / 22).coerceAtLeast(0)
            score += widthScore + heightScore
        } catch (_: Exception) {
        }

        if (isLauncherPackage(pkg)) score -= 80
        if (isRecorderPackage(pkg) || isKeyboardPackage(pkg)) score -= 260

        return score
    }

    private fun estimateMeaningfulNodeCount(node: AccessibilityNodeInfo?): Int {
        if (node == null) return 0

        var count = 0
        val t = cleanLine(node.text?.toString().orEmpty())
        val d = cleanLine(node.contentDescription?.toString().orEmpty())
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            cleanLine(node.hintText?.toString().orEmpty())
        } else {
            ""
        }

        if (t.length >= 2) count++
        if (d.length >= 2) count++
        if (hint.length >= 2) count++

        for (i in 0 until node.childCount) {
            count += estimateMeaningfulNodeCount(node.getChild(i))
        }

        return count.coerceAtMost(160)
    }

    private fun dumpScreenText(root: AccessibilityNodeInfo?): String {
        val actualRoot = root ?: return "(no active window)"
        val lines = linkedSetOf<String>()
        collectText(actualRoot, lines)

        return lines
            .asSequence()
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }
            .filterNot { line ->
                val normalized = norm(line)
                ignoredDumpTextHints.any { normalized.contains(norm(it)) }
            }
            .distinct()
            .take(320)
            .joinToString("\n") { "• $it" }
            .ifBlank { "(empty dump)" }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            node.hintText?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val pane = if (Build.VERSION.SDK_INT >= 28) {
            node.paneTitle?.toString()?.trim().orEmpty()
        } else {
            ""
        }

        if (text.isNotBlank()) out.add(text)
        if (desc.isNotBlank() && desc != text) out.add(desc)
        if (hint.isNotBlank() && hint != text && hint != desc) out.add(hint)
        if (pane.isNotBlank() && pane != text && pane != desc && pane != hint) out.add(pane)

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

    private fun extractSemanticUi(
        root: AccessibilityNodeInfo?,
        packageName: String,
        dump: String
    ): SemanticExtraction {
        if (root == null) {
            val parsed = KaiScreenStateParser.fromDump(packageName = packageName, dump = dump)
            return SemanticExtraction(
                elements = emptyList(),
                screenKind = parsed.screenKind,
                confidence = parsed.semanticConfidence
            )
        }

        val elements = mutableListOf<KaiUiElement>()
        collectSemanticElements(root, packageName, depth = 0, out = elements)

        val deduped = elements
            .distinctBy { "${norm(it.text)}|${norm(it.contentDescription)}|${norm(it.viewId)}|${it.bounds}|${it.roleGuess}" }
            .take(240)

        val parsed = KaiScreenStateParser.fromDump(
            packageName = packageName,
            dump = dump,
            elements = deduped
        )

        return SemanticExtraction(
            elements = deduped,
            screenKind = parsed.screenKind,
            confidence = parsed.semanticConfidence
        )
    }

    private fun collectSemanticElements(
        node: AccessibilityNodeInfo?,
        packageName: String,
        depth: Int,
        out: MutableList<KaiUiElement>
    ) {
        if (node == null || depth > 42) return

        val text = cleanLine(node.text?.toString().orEmpty())
        val contentDescription = cleanLine(node.contentDescription?.toString().orEmpty())
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            cleanLine(node.hintText?.toString().orEmpty())
        } else {
            ""
        }
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty().trim()
        val className = node.className?.toString().orEmpty()

        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        val selected = node.isSelected
        val checked = node.isChecked

        val rect = Rect()
        val bounds = try {
            node.getBoundsInScreen(rect)
            "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
        } catch (_: Exception) {
            ""
        }

        val role = inferRoleGuess(
            text = text,
            contentDescription = contentDescription,
            hint = hint,
            viewId = viewId,
            className = className,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable
        )

        val keepNode = shouldKeepSemanticNode(
            text = text,
            contentDescription = contentDescription,
            hint = hint,
            viewId = viewId,
            role = role,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable
        )

        if (keepNode) {
            out += KaiUiElement(
                text = text,
                contentDescription = contentDescription,
                hint = hint,
                viewId = viewId,
                className = className,
                clickable = clickable,
                editable = editable,
                scrollable = scrollable,
                selected = selected,
                checked = checked,
                bounds = bounds,
                depth = depth,
                packageName = packageName,
                roleGuess = role
            )
        }

        for (i in 0 until node.childCount) {
            collectSemanticElements(node.getChild(i), packageName, depth + 1, out)
        }
    }

    private fun shouldKeepSemanticNode(
        text: String,
        contentDescription: String,
        hint: String,
        viewId: String,
        role: String,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean
    ): Boolean {
        val joined = norm(listOf(text, contentDescription, hint, viewId).joinToString(" "))
        val isOverlayNoise = joined.isNotBlank() &&
            ignoredDumpTextHints.any { hintText -> joined.contains(norm(hintText)) }

        if (isOverlayNoise && !editable && !clickable) return false

        return editable || clickable || scrollable ||
            role != "unknown" ||
            text.isNotBlank() ||
            contentDescription.isNotBlank() ||
            hint.isNotBlank() ||
            viewId.isNotBlank()
    }

    private fun inferRoleGuess(
        text: String,
        contentDescription: String,
        hint: String,
        viewId: String,
        className: String,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean
    ): String {
        val joined = norm(listOf(text, contentDescription, hint, viewId, className).joinToString(" "))
        val classNorm = norm(className)
        val mediaLike = containsAny(joined, "camera", "photo", "gallery", "attach", "media", "image", "كاميرا", "معرض", "وسائط", "مرفق")

        if (editable && containsAny(joined, "search", "find", "بحث")) return "search_field"
        if (editable && containsAny(joined, "message", "compose", "write", "title", "body", "editor", "اكتب", "العنوان", "المحتوى")) return "editor"
        if (editable) return "input"

        if (!mediaLike && containsAny(joined, "send", "ارسال", "إرسال", "post", "reply", "submit", "paper plane", "arrow", "ic_send", "send_icon")) return "send_button"
        if (containsAny(joined, "new", "create", "compose", "plus", "add", "انشاء", "إنشاء", "جديد", "new note", "ملاحظه", "ملاحظة")) return "create_button"
        if (containsAny(joined, "save", "done", "apply", "confirm", "next", "open", "play", "watch", "listen")) return "primary_action"
        if (containsAny(joined, "more", "menu", "options", "overflow", "toolbar")) return "toolbar_action"

        if (containsAny(classNorm, "imagebutton") || (mediaLike && clickable)) {
            return "image_button"
        }
        if (containsAny(classNorm, "button") || (clickable && containsAny(joined, "save", "done", "ok", "cancel", "next", "back", "send", "open"))) {
            return "button"
        }

        if (scrollable && containsAny(classNorm, "recyclerview", "listview", "scrollview")) return "list_item"
        if (clickable && containsAny(joined, "chat", "inbox", "conversation", "thread", "message", "messenger", "dm", "direct", "رسائل", "محادث")) return "chat_item"
        if (clickable && containsAny(joined, "tab", "home", "explore", "profile", "notifications", "search", "messages", "inbox", "direct", "paper plane")) return "tab"
        if (scrollable && roleLikeList(classNorm)) return "list_item"
        if (containsAny(joined, "search", "find", "lookup", "بحث") && clickable) return "search_field"

        return "unknown"
    }

    private fun roleLikeList(classNorm: String): Boolean {
        return containsAny(classNorm, "recyclerview", "listview", "collection", "grid")
    }

    private fun serializeElements(elements: List<KaiUiElement>): String {
        return JSONArray().apply {
            elements.take(240).forEach { element ->
                put(
                    JSONObject().apply {
                        put("text", element.text)
                        put("contentDescription", element.contentDescription)
                        put("hint", element.hint)
                        put("viewId", element.viewId)
                        put("className", element.className)
                        put("clickable", element.clickable)
                        put("editable", element.editable)
                        put("scrollable", element.scrollable)
                        put("selected", element.selected)
                        put("checked", element.checked)
                        put("bounds", element.bounds)
                        put("depth", element.depth)
                        put("packageName", element.packageName)
                        put("roleGuess", element.roleGuess)
                    }
                )
            }
        }.toString()
    }

    private fun containsAny(text: String, vararg values: String): Boolean {
        if (text.isBlank()) return false
        return values.any { value ->
            val normalized = norm(value)
            normalized.isNotBlank() && text.contains(normalized)
        }
    }

    private fun aliasCandidatesFor(query: String): List<String> {
        val q = norm(query)
        val candidates = linkedSetOf(query.trim())

        fun addAll(vararg values: String) {
            values.forEach { candidates.add(it) }
        }

        when {
            KaiCommandParser.containsAny(q, "instagram", "insta", "ig", "انستغرام", "انستا", "انستقرام", "انستاجرام") ->
                addAll("Instagram", "instagram", "Insta", "IG", "انستغرام", "انستا")

            KaiCommandParser.containsAny(q, "messages", "message", "dm", "inbox", "messenger", "chat", "paper plane", "direct", "الرسائل", "محادثات") ->
                addAll(
                    "Messages", "messages", "Message", "DM", "Inbox", "Messenger", "Direct", "paper plane", "Chat", "الرسائل", "محادثات"
                )

            KaiCommandParser.containsAny(q, "whatsapp", "واتساب", "واتس") ->
                addAll("WhatsApp", "whatsapp", "واتساب", "واتس")

            KaiCommandParser.containsAny(q, "messages", "message", "sms", "رسائل", "الرسائل", "محادثات", "chat", "inbox") ->
                addAll("Messages", "messages", "Message", "رسائل", "الرسائل", "sms", "Messenger", "Chats", "Inbox")

            KaiCommandParser.containsAny(q, "comments", "comment", "تعليق", "التعليقات", "تعليقات") ->
                addAll("Comments", "Comment", "comment", "تعليق", "التعليقات", "تعليقات")

            KaiCommandParser.containsAny(q, "send", "ارسال", "إرسال") ->
                addAll("Send", "send", "ارسال", "إرسال")

            KaiCommandParser.containsAny(q, "like", "اعجاب", "لايك", "heart") ->
                addAll("Like", "like", "اعجاب", "heart", "likes")

            KaiCommandParser.containsAny(q, "share", "مشاركه", "مشاركة") ->
                addAll("Share", "share", "مشاركة")

            KaiCommandParser.containsAny(q, "search", "بحث") ->
                addAll("Search", "search", "بحث")

            KaiCommandParser.containsAny(q, "notifications", "notification", "الاشعارات", "إشعارات") ->
                addAll("Notifications", "Notification", "notifications", "الإشعارات", "الاشعارات")

            KaiCommandParser.containsAny(q, "profile", "account", "البروفايل", "الملف الشخصي", "الحساب") ->
                addAll("Profile", "profile", "Account", "الحساب", "الملف الشخصي")

            KaiCommandParser.containsAny(q, "camera", "الكاميرا") ->
                addAll("Camera", "camera", "الكاميرا")

            KaiCommandParser.containsAny(q, "gallery", "photos", "المعرض", "الصور") ->
                addAll("Gallery", "Photos", "gallery", "photos", "المعرض", "الصور")

            KaiCommandParser.containsAny(q, "notes", "note", "ملاحظات", "الملاحظات") ->
                addAll("Notes", "notes", "Note", "ملاحظات", "الملاحظات")

            KaiCommandParser.containsAny(q, "new note", "create", "compose", "plus", "add", "جديد", "إنشاء", "انشاء", "ملاحظة") ->
                addAll("New Note", "Create", "Compose", "Add", "Plus", "جديد", "إنشاء", "ملاحظة")

            KaiCommandParser.containsAny(q, "send", "reply", "post", "submit", "ارسال", "إرسال") ->
                addAll("Send", "Reply", "Post", "Submit", "ارسال", "إرسال", "paper plane", "send icon")

            KaiCommandParser.containsAny(q, "google", "جوجل") ->
                addAll("Google", "google", "جوجل")

            KaiCommandParser.containsAny(q, "chrome", "كروم") ->
                addAll("Chrome", "chrome", "كروم")

            KaiCommandParser.containsAny(q, "youtube", "يوتيوب") ->
                addAll("YouTube", "youtube", "يوتيوب")

            KaiCommandParser.containsAny(q, "play store", "بلاي ستور", "متجر بلاي", "متجر play") ->
                addAll("Play Store", "Google Play", "play store", "متجر بلاي", "بلاي ستور")

            KaiCommandParser.containsAny(q, "calculator", "calc", "الحاسبة", "الآلة الحاسبة", "اله حاسبه") ->
                addAll("Calculator", "calculator", "Calc", "الحاسبة", "الآلة الحاسبة")
        }

        return candidates.toList()
    }

    private fun clickByVisibleText(query: String): Boolean {
        val target = findBestNodeByVisibleText(query) ?: return false
        val clickable = if (target.isClickable) target else findClickableParent(target)
        return performClick(clickable ?: target)
    }

    private fun longPressByVisibleText(query: String, holdMs: Long): Boolean {
        val target = findBestNodeByVisibleText(query) ?: return false

        val longClickOk = try {
            if (target.isLongClickable) {
                target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        if (longClickOk) return true

        val center = nodeCenter(target)
        return dispatchLongPressAt(center.first, center.second, holdMs)
    }

    private fun findBestNodeByVisibleText(query: String): AccessibilityNodeInfo? {
        val root = getTargetRoot() ?: return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        aliasCandidatesFor(query).forEach { variant ->
            collectMatchingNodes(root, variant, candidates)
        }

        if (candidates.isEmpty()) return null

        return candidates
            .distinctBy { stableNodeKey(it) }
            .sortedWith(
                compareByDescending<AccessibilityNodeInfo> { scoreClickCandidate(it, query) }
                    .thenBy { nodeDepth(it) }
            )
            .firstOrNull()
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
            rect.left.toString(),
            rect.top.toString(),
            rect.right.toString(),
            rect.bottom.toString()
        ).joinToString("|")
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo?,
        query: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            node.hintText?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()

        fun isHit(value: String): Boolean {
            if (value.isBlank()) return false
            val nValue = norm(value)
            val nQuery = norm(query)
            return nValue == nQuery ||
                nValue.contains(nQuery) ||
                nQuery.contains(nValue) ||
                levenshteinLiteClose(nValue, nQuery)
        }

        if (isHit(text) || isHit(desc) || isHit(hint) || isHit(viewId)) {
            out.add(node)
        }

        for (i in 0 until node.childCount) {
            collectMatchingNodes(node.getChild(i), query, out)
        }
    }

    private fun scoreClickCandidate(node: AccessibilityNodeInfo, query: String): Int {
        val nQuery = norm(query)
        val text = norm(node.text?.toString().orEmpty())
        val desc = norm(node.contentDescription?.toString().orEmpty())
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            norm(node.hintText?.toString().orEmpty())
        } else {
            ""
        }
        val viewId = norm(node.viewIdResourceName?.substringAfterLast('/').orEmpty())

        var score = 0
        if (text == nQuery) score += 140
        if (desc == nQuery) score += 130
        if (hint == nQuery) score += 95
        if (viewId == nQuery) score += 90

        if (text.contains(nQuery) && text != nQuery) score += 80
        if (desc.contains(nQuery) && desc != nQuery) score += 72
        if (hint.contains(nQuery) && hint != nQuery) score += 58
        if (viewId.contains(nQuery) && viewId != nQuery) score += 58

        if (levenshteinLiteClose(text, nQuery)) score += 46
        if (levenshteinLiteClose(desc, nQuery)) score += 40
        if (levenshteinLiteClose(viewId, nQuery)) score += 34

        if (node.isClickable) score += 44
        if (node.isEnabled) score += 20
        if (node.isVisibleToUser) score += 22
        if (node.className?.toString()?.contains("Button", true) == true) score += 28
        if (node.className?.toString()?.contains("ImageButton", true) == true) score += 26
        if (node.className?.toString()?.contains("TextView", true) == true) score += 8
        if (node.className?.toString()?.contains("ImageView", true) == true) score += 10
        if (node.viewIdResourceName?.contains("button", true) == true) score += 20
        if (node.contentDescription?.isNotBlank() == true) score += 10

        val joined = listOf(text, desc, hint, viewId).joinToString(" ")
        val isSearchLike = containsAny(joined, "search", "find", "discover", "explore", "lookup", "بحث", "استكشاف")
        val isMediaLike = containsAny(joined, "camera", "gallery", "photo", "media", "attach", "reel", "story", "كاميرا", "معرض", "وسائط")
        val isMessagesIntent = containsAny(nQuery, "message", "messages", "dm", "inbox", "direct", "messenger", "رسائل", "محادث")
        val isConversationIntent = containsAny(nQuery, "chat", "thread", "conversation", "محادث")
        val isCreateNoteIntent = containsAny(nQuery, "new note", "create note", "add note", "note", "notes", "ملاحظة", "ملاحظات")

        if ((isMessagesIntent || isConversationIntent) && isSearchLike) score -= 140
        if ((isMessagesIntent || isConversationIntent) && isMediaLike) score -= 180
        if (isCreateNoteIntent && isSearchLike) score -= 150
        if (isCreateNoteIntent && containsAny(joined, "toolbar", "menu", "filter", "sort", "settings", "overflow", "خيارات", "فرز")) score -= 100

        return score
    }

    private fun nodeDepth(node: AccessibilityNodeInfo?): Int {
        var depth = 0
        var current = node
        while (current?.parent != null && depth < 40) {
            depth++
            current = current.parent
        }
        return depth
    }

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node ?: return null
        var steps = 0
        while (steps < 12) {
            if (current.isClickable) return current
            current = current.parent ?: break
            steps++
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        var steps = 0

        while (steps < 12) {
            val result = try {
                if (current.isClickable && current.isEnabled) {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
            if (result) return true

            current = current.parent ?: break
            steps++
        }

        val center = node?.let { nodeCenter(it) }
        return if (center != null) {
            dispatchTapAt(center.first, center.second)
        } else {
            false
        }
    }

    private fun openInstalledApp(query: String): Boolean {
        val pm = packageManager ?: return false
        val resolvedName = KaiCommandParser.resolveAppAlias(query)
        val appKey = KaiAppIdentityRegistry.resolveAppKey(resolvedName)
        val normalizedResolved = norm(resolvedName)
        val canonicalPackages = KaiAppIdentityRegistry.packageCandidatesForKey(appKey)

        // If Kai's own app is foreground, go Home first so the target app can become
        // the active window for observation immediately after launch.
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePkg == packageName || activePkg == "com.example.reply") {
            runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
            try {
                Thread.sleep(160L)
            } catch (_: Exception) {
            }
        }

        val directPackageCandidates = linkedSetOf(
            query.trim(),
            resolvedName.trim(),
            resolvedName.trim().replace(" ", "")
        ).apply {
            canonicalPackages.forEach { add(it) }
        }.filter { it.isNotBlank() }

        for (candidate in directPackageCandidates) {
            val directIntent = pm.getLaunchIntentForPackage(candidate)
            if (directIntent != null) {
                Log.d(TAG, "openInstalledApp: direct launch candidate=$candidate")
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(directIntent)
                return true
            }
        }

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps
            .asSequence()
            .map { appInfo ->
                val label = pm.getApplicationLabel(appInfo)?.toString().orEmpty()
                val packageName = appInfo.packageName.orEmpty()
                val shortPkg = packageName.substringAfterLast('.')
                val nLabel = norm(label)
                val nPkg = norm(shortPkg)
                val fullPkg = norm(packageName)

                var score = 0
                if (nLabel == normalizedResolved) score += 160
                if (nPkg == normalizedResolved) score += 150
                if (fullPkg.contains(normalizedResolved)) score += 120
                if (nLabel.contains(normalizedResolved)) score += 90
                if (normalizedResolved.contains(nLabel) && nLabel.isNotBlank()) score += 60
                if (nPkg.contains(normalizedResolved)) score += 70

                if (levenshteinLiteClose(nLabel, normalizedResolved)) score += 55
                if (levenshteinLiteClose(nPkg, normalizedResolved)) score += 42

                Triple(appInfo, label, score)
            }
            .filter { it.third > 0 }
            .maxByOrNull { it.third }
            ?.first

        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName)
            if (intent != null) {
                Log.d(TAG, "openInstalledApp: best installed match=${match.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(intent)
                return true
            }
        }

        // Last fallback: only click the launcher icon when we are actually on launcher.
        val launcherVisible = isLauncherPackage(currentVisibleLauncherPackage())
        if (launcherVisible) {
            val clickOk = clickByVisibleText(query)
            Log.d(TAG, "openInstalledApp: clickByVisibleText('$query') => $clickOk")
            return clickOk
        }

        return false
    }

    private fun scroll(dir: String, times: Int): Boolean {
        val root = getTargetRoot() ?: return false
        val normalized = dir.lowercase(Locale.ROOT)

        val fallbackAction = when (normalized) {
            "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        val target = findBestScrollableNode(root, normalized)
            ?: findAnyScrollable(root)
            ?: root

        var okAny = false
        repeat(times) {
            val ok = tryDirectionalScroll(target, normalized, fallbackAction)
            okAny = okAny || ok
            if (!ok) {
                okAny = gestureScrollFallback(normalized) || okAny
            }
        }
        return okAny
    }

    private fun findBestScrollableNode(
        node: AccessibilityNodeInfo?,
        dir: String
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isScrollable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findBestScrollableNode(node.getChild(i), dir)
            if (found != null) return found
        }

        return null
    }

    private fun findAnyScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node

        for (i in 0 until node.childCount) {
            val found = findAnyScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun tryDirectionalScroll(
        node: AccessibilityNodeInfo,
        dir: String,
        fallbackAction: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 23) {
            val action = when (dir) {
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> fallbackAction
            }

            val supportsDirectional = try {
                node.actionList?.any { it.id == action } == true
            } catch (_: Exception) {
                false
            }

            if (supportsDirectional) {
                val ok = try {
                    node.performAction(action, Bundle())
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
            "left" -> listOf(w * 0.78f, h * 0.52f, w * 0.22f, h * 0.52f)
            "right" -> listOf(w * 0.22f, h * 0.52f, w * 0.78f, h * 0.52f)
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

    private fun inputTextIntoFocusedField(text: String): Boolean {
        val root = getTargetRoot() ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
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

        val okSet = try {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
        if (okSet) return true

        return tryPaste(text, focused)
    }

    private fun tryPaste(text: String, node: AccessibilityNodeInfo): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("kai", text))
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
        if (node.isFocused && node.isEditable) return node
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

    private fun nodeCenter(node: AccessibilityNodeInfo): Pair<Float?, Float?> {
        val rect = Rect()
        return try {
            node.getBoundsInScreen(rect)
            Pair(rect.exactCenterX(), rect.exactCenterY())
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels.coerceAtLeast(1)
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels.coerceAtLeast(1)

    private fun resolveX(value: Float?): Float {
        return KaiGestureUtils.resolveCoordinate(value, screenWidth(), 0.5f)
    }

    private fun resolveY(value: Float?): Float {
        return KaiGestureUtils.resolveCoordinate(value, screenHeight(), 0.5f)
    }

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

    private fun dispatchLongPressAt(
        x: Float?,
        y: Float?,
        holdMs: Long
    ): Boolean {
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
        if (abs(a.length - b.length) > 2) return false
        if (a.length < 4 || b.length < 4) return false

        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length > b.length) a else b

        if (longer.contains(shorter)) return true

        var mismatches = 0
        val min = minOf(a.length, b.length)
        for (i in 0 until min) {
            if (a[i] != b[i]) mismatches++
            if (mismatches > 2) return false
        }

        mismatches += abs(a.length - b.length)
        return mismatches <= 2
    }

    private fun isRecentPackageTransition(): Boolean {
        if (lastPackageChangeAt == 0L) return false
        val timeSinceChange = System.currentTimeMillis() - lastPackageChangeAt
        return timeSinceChange in 0L..1800L
    }

    private fun isExternalAppPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (isIgnorablePackage(pkg)) return false
        if (isLauncherPackage(pkg)) return false
        if (isKeyboardPackage(pkg)) return false
        if (isRecorderPackage(pkg)) return false
        return true
    }
}