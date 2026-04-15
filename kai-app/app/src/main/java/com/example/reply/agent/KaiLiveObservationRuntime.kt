package com.example.reply.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.reply.ui.KaiAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

object KaiLiveObservationRuntime {
    private const val TAG = "KaiLiveObs"
    private const val WATCH_INTERVAL_MS = 420L
    private const val WATCH_BOOTSTRAP_BURST = 3
    private const val WATCH_BOOTSTRAP_GAP_MS = 90L
    private const val FRESH_POLL_MS = 45L
    private const val HISTORY_LIMIT = 24
    private const val POLL_INTERVAL_ADAPTIVE_MS = 1200L

    data class ObservationWindow(
        val observations: List<KaiObservation>,
        val latest: KaiObservation?,
        val latestUsable: KaiObservation?,
        val latestStrong: KaiObservation?,
        val latestTargetMatched: KaiObservation?,
        val latestNonLauncher: KaiObservation?
    )

    @Volatile var latestObservation: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set
    @Volatile var latestStrongObservation: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set
    @Volatile var isWatching: Boolean = false
        private set
    @Volatile private var bridgeRegistered = false
    @Volatile private var storedContext: Context? = null
    @Volatile private var expectedPackageHint: String = ""
    @Volatile var lastEventDrivenAt: Long = 0L
        private set

    private val bridgeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    private val history = ArrayDeque<KaiObservation>()

    fun currentExpectedPackage(): String = expectedPackageHint

    fun reset() {
        latestObservation = KaiObservation("", "", updatedAt = 0L)
        latestStrongObservation = KaiObservation("", "", updatedAt = 0L)
        lastEventDrivenAt = 0L
        synchronized(history) { history.clear() }
    }

    fun hardReset(stopWatching: Boolean = true) {
        if (stopWatching) stopWatching()
        expectedPackageHint = ""
        reset()
        requestTransitionReset()
    }

    fun softCleanupAfterRun() {
        expectedPackageHint = ""
        reset()
    }

    fun ensureBridge(context: Context) {
        val appCtx = context.applicationContext
        storedContext = appCtx
        if (bridgeRegistered) return
        synchronized(bridgeLock) {
            if (bridgeRegistered) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action != KaiAccessibilityService.ACTION_KAI_DUMP_RESULT) return
                    val dump = i.getStringExtra(KaiAccessibilityService.EXTRA_DUMP).orEmpty()
                    val pkg = i.getStringExtra(KaiAccessibilityService.EXTRA_PACKAGE).orEmpty()
                    val elements = parseElementsFromJson(i.getStringExtra(KaiAccessibilityService.EXTRA_ELEMENTS_JSON))
                    val screenKind = i.getStringExtra(KaiAccessibilityService.EXTRA_SCREEN_KIND).orEmpty()
                    val confidence = i.getFloatExtra(KaiAccessibilityService.EXTRA_SEMANTIC_CONFIDENCE, 0f)
                    onDumpArrived(pkg, dump, elements, screenKind, confidence)
                }
            }
            try {
                val filter = IntentFilter(KaiAccessibilityService.ACTION_KAI_DUMP_RESULT)
                if (Build.VERSION.SDK_INT >= 33) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appCtx.registerReceiver(receiver, filter)
                }
                bridgeRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Bridge registration failed: ${e.message}", e)
            }
        }
    }

    fun onDumpArrived(pkg: String, dump: String, elements: List<KaiUiElement>, screenKind: String, confidence: Float) {
        val obs = KaiObservation(
            packageName = pkg,
            screenPreview = dump,
            elements = elements,
            screenKind = screenKind,
            semanticConfidence = confidence,
            updatedAt = System.currentTimeMillis()
        )
        latestObservation = obs
        synchronized(history) {
            history.addLast(obs)
            while (history.size > HISTORY_LIMIT) history.removeFirst()
        }

        val frame = KaiVisionInterpreter.classify(obs, expectedPackageHint, allowLauncherSurface = true)
        if (frame.isStrong) latestStrongObservation = obs

        if (frame.isUsable) {
            runCatching {
                KaiAgentController.onObservationArrived(
                    packageName = frame.screenState.packageName,
                    screenPreview = frame.screenState.rawDump,
                    elements = frame.screenState.elements,
                    screenKind = frame.screenState.screenKind,
                    semanticConfidence = frame.screenState.semanticConfidence
                )
            }
        }
    }

    fun onEventObservation(pkg: String, dump: String, elements: List<KaiUiElement>, screenKind: String, confidence: Float) {
        lastEventDrivenAt = System.currentTimeMillis()
        onDumpArrived(pkg, dump, elements, screenKind, confidence)
    }

    fun requestImmediateDump(expectedPackage: String = "") {
        val context = storedContext ?: return
        expectedPackageHint = expectedPackage
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
            if (expectedPackage.isNotBlank()) {
                putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
            }
        }
        context.sendBroadcast(intent)
    }

    fun requestTransitionReset() {
        val context = storedContext ?: return
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_RESET_TRANSITION_STATE)
        }
        context.sendBroadcast(intent)
    }

    fun startWatching(immediateDump: Boolean = true, expectedPackage: String = "") {
        expectedPackageHint = expectedPackage
        if (isWatching && watchJob?.isActive == true) {
            if (immediateDump) requestImmediateDump(expectedPackage)
            return
        }
        isWatching = true
        watchJob?.cancel()
        watchJob = scope.launch {
            if (immediateDump) {
                repeat(WATCH_BOOTSTRAP_BURST) { index ->
                    if (!isActive || !isWatching) return@repeat
                    requestImmediateDump(expectedPackageHint)
                    if (index < WATCH_BOOTSTRAP_BURST - 1) delay(WATCH_BOOTSTRAP_GAP_MS)
                }
            }
            while (isActive && isWatching) {
                val interval = if (hasRecentEventDriven(600L)) {
                    POLL_INTERVAL_ADAPTIVE_MS
                } else {
                    WATCH_INTERVAL_MS
                }
                requestImmediateDump(expectedPackageHint)
                delay(interval)
            }
        }
    }

    fun stopWatching() {
        isWatching = false
        expectedPackageHint = ""
        watchJob?.cancel()
        watchJob = null
    }

    fun observationWindow(expectedPackage: String = ""): ObservationWindow {
        val items = synchronized(history) { history.toList() }
        val latest = items.maxByOrNull { it.updatedAt }
        val usable = items.filter { KaiVisionInterpreter.classify(it, expectedPackage, true).isUsable }.maxByOrNull { it.updatedAt }
        val strong = items.filter { KaiVisionInterpreter.classify(it, expectedPackage, true).isStrong }.maxByOrNull { it.updatedAt }
        val matched = items.filter { KaiVisionInterpreter.packageMatchesExpected(it.packageName, expectedPackage) }.maxByOrNull { it.updatedAt }
        val nonLauncher = items.filter {
            val state = KaiVisionInterpreter.toScreenState(it)
            !state.isLauncher() && it.packageName.isNotBlank()
        }.maxByOrNull { it.updatedAt }
        return ObservationWindow(items, latest, usable, strong, matched, nonLauncher)
    }

    suspend fun awaitFreshObservation(afterTime: Long, timeoutMs: Long = 2200L, expectedPackage: String = "", requireStrong: Boolean = false): KaiObservation {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(300L)
        while (System.currentTimeMillis() < deadline) {
            val candidate = bestObservation(expectedPackage, requireStrong)
            if (candidate.updatedAt > afterTime) return candidate
            delay(FRESH_POLL_MS)
        }
        return bestObservation(expectedPackage, requireStrong)
    }

    suspend fun awaitPostOpenStabilization(expectedPackage: String, timeoutMs: Long = 2600L): KaiObservation {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(600L)
        var best = bestObservation(expectedPackage, requireStrong = false)
        var lastExplicitDump = 0L

        while (System.currentTimeMillis() < deadline) {
            // Reduce dump request frequency when event-driven observations are arriving
            val now = System.currentTimeMillis()
            if (!hasRecentEventDriven(400L) || (now - lastExplicitDump) > 350L) {
                requestImmediateDump(expectedPackage)
                lastExplicitDump = now
            }
            delay(FRESH_POLL_MS)

            val candidate = bestObservation(expectedPackage, requireStrong = false)
            if (candidate.updatedAt > best.updatedAt) best = candidate

            val frame = KaiVisionInterpreter.classify(candidate, expectedPackage, allowLauncherSurface = false)
            if (frame.expectedPackageMatched && frame.isUsable && !frame.screenState.isLauncher()) {
                return candidate
            }
        }
        return best
    }

    fun bestObservation(expectedPackage: String = "", requireStrong: Boolean = false): KaiObservation {
        val window = observationWindow(expectedPackage)
        val ordered = if (requireStrong) {
            listOf(window.latestStrong, latestStrongObservation, window.latestTargetMatched, window.latestUsable, window.latest)
        } else {
            listOf(window.latestTargetMatched, window.latestUsable, window.latestNonLauncher, window.latestStrong, window.latest, latestObservation)
        }.filterNotNull()

        return ordered.maxByOrNull { it.updatedAt } ?: KaiObservation("", "", updatedAt = 0L)
    }

    @JvmOverloads
    fun currentScreenState(expectedPackage: String = "", requireStrong: Boolean = false): KaiScreenState {
        return KaiVisionInterpreter.toScreenState(bestObservation(expectedPackage, requireStrong))
    }

    fun parseElementsFromJson(json: String?): List<KaiUiElement> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        KaiUiElement(
                            text = obj.optString("text"),
                            contentDescription = obj.optString("contentDescription"),
                            hint = obj.optString("hint"),
                            viewId = obj.optString("viewId"),
                            className = obj.optString("className"),
                            clickable = obj.optBoolean("clickable"),
                            editable = obj.optBoolean("editable"),
                            scrollable = obj.optBoolean("scrollable"),
                            selected = obj.optBoolean("selected"),
                            checked = obj.optBoolean("checked"),
                            bounds = obj.optString("bounds"),
                            depth = obj.optInt("depth"),
                            packageName = obj.optString("packageName"),
                            roleGuess = obj.optString("roleGuess", "unknown")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun hasRecentAuthoritative(windowMs: Long): Boolean {
        return (System.currentTimeMillis() - latestStrongObservation.updatedAt) < windowMs
    }

    fun hasRecentUsefulObservation(windowMs: Long): Boolean {
        return (System.currentTimeMillis() - latestObservation.updatedAt) < windowMs
    }

    fun hasRecentEventDriven(windowMs: Long): Boolean {
        return (System.currentTimeMillis() - lastEventDrivenAt) < windowMs
    }

    fun serializeElements(elements: List<KaiUiElement>): String {
        val arr = JSONArray()
        elements.forEach { element ->
            arr.put(
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
        return arr.toString()
    }
}
