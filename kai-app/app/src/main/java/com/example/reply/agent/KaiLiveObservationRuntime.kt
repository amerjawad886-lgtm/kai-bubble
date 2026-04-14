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

object KaiLiveObservationRuntime {
    private const val TAG = "KaiLiveObs"
    private const val WATCH_INTERVAL_MS = 500L
    private const val WATCH_BOOTSTRAP_BURST = 3
    private const val WATCH_BOOTSTRAP_GAP_MS = 110L
    private const val FRESH_POLL_MS = 55L

    @Volatile
    var latestObservation: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    @Volatile
    var latestStrongObservation: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    @Volatile
    var isWatching: Boolean = false
        private set

    @Volatile
    private var bridgeRegistered = false

    @Volatile
    private var storedContext: Context? = null

    @Volatile
    private var expectedPackageHint: String = ""

    private val bridgeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    fun currentExpectedPackage(): String = expectedPackageHint

    fun reset() {
        latestObservation = KaiObservation("", "", updatedAt = 0L)
        latestStrongObservation = KaiObservation("", "", updatedAt = 0L)
    }

    fun hardReset(stopWatching: Boolean = true) {
        if (stopWatching) stopWatching()
        expectedPackageHint = ""
        reset()
        requestTransitionReset()
    }

    fun softCleanupAfterRun() {
        expectedPackageHint = ""
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
        val now = System.currentTimeMillis()
        val obs = KaiObservation(
            packageName = pkg,
            screenPreview = dump,
            elements = elements,
            screenKind = screenKind,
            semanticConfidence = confidence,
            updatedAt = now
        )
        latestObservation = obs
        val frame = KaiVisionInterpreter.classify(obs, expectedPackageHint, allowLauncherSurface = true)
        if (frame.isStrong) {
            latestStrongObservation = obs
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

    suspend fun awaitFreshObservation(afterTime: Long, timeoutMs: Long = 2200L, expectedPackage: String = "", requireStrong: Boolean = false): KaiObservation {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(300L)
        while (System.currentTimeMillis() < deadline) {
            val candidate = bestObservation(expectedPackage, requireStrong)
            if (candidate.updatedAt > afterTime) return candidate
            delay(FRESH_POLL_MS)
        }
        return bestObservation(expectedPackage, requireStrong)
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
                requestImmediateDump(expectedPackageHint)
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    fun stopWatching() {
        isWatching = false
        expectedPackageHint = ""
        watchJob?.cancel()
        watchJob = null
    }

    fun bestObservation(expectedPackage: String = "", requireStrong: Boolean = false): KaiObservation {
        val candidates = if (requireStrong) listOf(latestStrongObservation) else listOf(latestStrongObservation, latestObservation)
        val matching = candidates.filter { it.updatedAt > 0L }
            .filter { KaiVisionInterpreter.packageMatchesExpected(it.packageName, expectedPackage) }
        if (matching.isNotEmpty()) return matching.maxByOrNull { it.updatedAt } ?: matching.first()
        val any = candidates.filter { it.updatedAt > 0L }
        return any.maxByOrNull { it.updatedAt } ?: latestObservation
    }

    fun currentScreenState(expectedPackage: String = "", requireStrong: Boolean = false): KaiScreenState {
        return KaiVisionInterpreter.toScreenState(bestObservation(expectedPackage, requireStrong))
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
                            hint = o.optString("hint"),
                            viewId = o.optString("viewId"),
                            className = o.optString("className"),
                            packageName = o.optString("packageName"),
                            bounds = o.optString("bounds"),
                            clickable = o.optBoolean("clickable"),
                            editable = o.optBoolean("editable"),
                            checked = o.optBoolean("checked"),
                            selected = o.optBoolean("selected"),
                            scrollable = o.optBoolean("scrollable"),
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
