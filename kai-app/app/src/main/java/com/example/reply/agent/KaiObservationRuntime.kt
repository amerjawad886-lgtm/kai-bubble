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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

object KaiObservationRuntime {

    private const val TAG = "KaiObsRuntime"
    private const val WATCH_INTERVAL_MS = 550L
    private const val WATCH_BOOTSTRAP_BURST = 3
    private const val WATCH_BOOTSTRAP_GAP_MS = 120L
    private const val RESET_SETTLE_MS = 140L

    @Volatile
    var live: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    @Volatile
    var authoritative: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    @Volatile
    private var bridgeRegistered = false
    private val bridgeLock = Any()
    @Volatile
    private var storedContext: Context? = null

    @Volatile
    var isWatching: Boolean = false
        private set

    @Volatile
    private var lastWatchExpectedPackage: String = ""

    private var watchJob: Job? = null
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun reset() {
        val blank = KaiObservation("", "", updatedAt = 0L)
        live = blank
        authoritative = blank
    }

    fun hardReset(stopWatching: Boolean = true) {
        if (stopWatching) {
            this.stopWatching()
        }
        lastWatchExpectedPackage = ""
        reset()
        requestTransitionReset()
    }

    fun clearRuntimeState(keepWatching: Boolean = true) {
        if (!keepWatching) {
            stopWatching()
        }
        lastWatchExpectedPackage = ""
        reset()
        requestTransitionReset()
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
                    val elements = parseElementsFromJson(
                        i.getStringExtra(KaiAccessibilityService.EXTRA_ELEMENTS_JSON)
                    )
                    val screenKind =
                        i.getStringExtra(KaiAccessibilityService.EXTRA_SCREEN_KIND).orEmpty()
                    val confidence =
                        i.getFloatExtra(KaiAccessibilityService.EXTRA_SEMANTIC_CONFIDENCE, 0f)

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

    fun onDumpArrived(
        pkg: String,
        dump: String,
        elements: List<KaiUiElement>,
        screenKind: String,
        confidence: Float
    ) {
        val now = System.currentTimeMillis()
        val obs = KaiObservation(
            packageName = pkg,
            screenPreview = dump,
            elements = elements,
            screenKind = screenKind,
            semanticConfidence = confidence,
            updatedAt = now
        )

        live = obs

        if (isUsefulObservation(obs)) {
            authoritative = obs
        }

        KaiAgentController.onObservationArrived(pkg, dump, elements, screenKind, confidence)
    }

    private fun isOverlayOnlyDump(text: String): Boolean {
        return text.contains("dynamic island", true) ||
            text.contains("kai os", true) ||
            text.contains("make action", true)
    }

    private fun isUsefulObservation(obs: KaiObservation): Boolean {
        val normalized = KaiScreenStateParser.normalize(obs.screenPreview)
        if (obs.packageName.isBlank()) return false
        if (normalized.isBlank()) return false
        if (normalized == KaiScreenStateParser.normalize("(no active window)")) return false
        if (normalized == KaiScreenStateParser.normalize("(empty dump)")) return false
        if (isOverlayOnlyDump(normalized)) return false
        if (obs.packageName.startsWith("com.example.reply")) return false
        return true
    }

    suspend fun awaitFresh(afterTime: Long, timeoutMs: Long): KaiObservation? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var nudgedAt = 0L

        while (System.currentTimeMillis() < deadline) {
            val a = authoritative
            if (a.updatedAt > afterTime && isUsefulObservation(a)) return a

            val l = live
            if (l.updatedAt > afterTime && isUsefulObservation(l)) return l

            if (System.currentTimeMillis() - nudgedAt >= 320L) {
                nudgedAt = System.currentTimeMillis()
                requestImmediateDump(lastWatchExpectedPackage)
            }

            delay(80L)
        }
        return null
    }

    fun requestImmediateDump(expectedPackage: String = "") {
        val appCtx = storedContext ?: return
        try {
            val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
                setPackage(appCtx.packageName)
                putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
                putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, 2400L)
                if (expectedPackage.isNotBlank()) {
                    putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
                }
            }
            appCtx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestImmediateDump failed: ${e.message}", e)
        }
    }

    private fun requestTransitionReset() {
        val appCtx = storedContext ?: return
        try {
            val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
                setPackage(appCtx.packageName)
                putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_RESET_TRANSITION_STATE)
                putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, RESET_SETTLE_MS)
            }
            appCtx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestTransitionReset failed: ${e.message}", e)
        }
    }

    fun startWatching(immediateDump: Boolean = true, expectedPackage: String = "") {
        val appCtx = storedContext ?: return
        ensureBridge(appCtx)
        lastWatchExpectedPackage = expectedPackage

        if (isWatching && watchJob?.isActive == true) {
            if (immediateDump) {
                requestImmediateDump(lastWatchExpectedPackage)
            }
            return
        }

        isWatching = true
        watchJob?.cancel()

        watchJob = watchScope.launch {
            if (immediateDump) {
                repeat(WATCH_BOOTSTRAP_BURST) { index ->
                    if (!isActive || !isWatching) return@repeat
                    requestImmediateDump(lastWatchExpectedPackage)
                    if (index < WATCH_BOOTSTRAP_BURST - 1) delay(WATCH_BOOTSTRAP_GAP_MS)
                }
            }
            while (isActive && isWatching) {
                requestImmediateDump(lastWatchExpectedPackage)
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    fun stopWatching() {
        isWatching = false
        lastWatchExpectedPackage = ""
        watchJob?.cancel()
        watchJob = null
    }

    fun hasRecentAuthoritative(maxAgeMs: Long): Boolean {
        val obs = authoritative
        return isUsefulObservation(obs) &&
            obs.updatedAt > 0 &&
            System.currentTimeMillis() - obs.updatedAt <= maxAgeMs
    }

    fun hasRecentUsefulObservation(maxAgeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return (isUsefulObservation(authoritative) && authoritative.updatedAt > 0 && now - authoritative.updatedAt <= maxAgeMs) ||
            (isUsefulObservation(live) && live.updatedAt > 0 && now - live.updatedAt <= maxAgeMs)
    }

    fun getBestAvailable(): KaiObservation {
        return when {
            isUsefulObservation(authoritative) -> authoritative
            isUsefulObservation(live) -> live
            authoritative.updatedAt >= live.updatedAt -> authoritative
            else -> live
        }
    }

    fun currentScreenState(): KaiScreenState {
        val best = getBestAvailable()
        return KaiScreenStateParser.fromDump(best.packageName, best.screenPreview)
    }

    internal fun parseElementsFromJson(raw: String?): List<KaiUiElement> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        KaiUiElement(
                            text = o.optString("text"),
                            contentDescription = o.optString("contentDescription"),
                            viewId = o.optString("viewId"),
                            className = o.optString("className"),
                            packageName = o.optString("packageName"),
                            bounds = o.optString("bounds"),
                            clickable = o.optBoolean("clickable"),
                            editable = o.optBoolean("editable"),
                            checked = o.optBoolean("checked"),
                            selected = o.optBoolean("selected"),
                            roleGuess = o.optString("roleGuess")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
