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

    @Volatile
    var lastEventDrivenAt: Long = 0L
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

                    val dump =
                        i.getStringExtra(KaiAccessibilityService.EXTRA_DUMP).orEmpty()
                    val pkg =
                        i.getStringExtra(KaiAccessibilityService.EXTRA_PACKAGE).orEmpty()
                    val elements =
                        parseElementsFromJson(
                            i.getStringExtra(KaiAccessibilityService.EXTRA_ELEMENTS_JSON)
                        )
                    val screenKind =
                        i.getStringExtra(KaiAccessibilityService.EXTRA_SCREEN_KIND).orEmpty()
                    val confidence =
                        i.getFloatExtra(KaiAccessibilityService.EXTRA_SEMANTIC_CONFIDENCE, 0f)

                    onDumpArrived(
                        pkg = pkg,
                        dump = dump,
                        elements = elements,
                        screenKind = screenKind,
                        confidence = confidence
                    )
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

        // Capture hint locally before classification to avoid a race where a concurrent
        // requestImmediateDump() overwrites expectedPackageHint mid-evaluation.
        val localHint = expectedPackageHint

        val frame = KaiVisionInterpreter.classify(
            obs = obs,
            expectedPackage = localHint,
            allowLauncherSurface = true
        )

        if (frame.isStrong) {
            latestStrongObservation = obs
        }

        // Clear the per-request hint once the dump it was associated with has arrived,
        // so it does not contaminate the next step's classification.
        if (localHint.isNotBlank()) {
            expectedPackageHint = ""
        }

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

    fun onEventObservation(
        pkg: String,
        dump: String,
        elements: List<KaiUiElement>,
        screenKind: String,
        confidence: Float
    ) {
        lastEventDrivenAt = System.currentTimeMillis()
        onDumpArrived(
            pkg = pkg,
            dump = dump,
            elements = elements,
            screenKind = screenKind,
            confidence = confidence
        )
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
            putExtra(
                KaiAccessibilityService.EXTRA_CMD,
                KaiAccessibilityService.CMD_RESET_TRANSITION_STATE
            )
        }
        context.sendBroadcast(intent)
    }

    fun startWatching(
        immediateDump: Boolean = true,
        expectedPackage: String = ""
    ) {
        expectedPackageHint = expectedPackage

        if (isWatching && watchJob?.isActive == true) {
            if (immediateDump) requestImmediateDump(expectedPackage)
            return
        }

        isWatching = true
        watchJob?.cancel()

        watchJob = scope.launch {
            // Bootstrap burst: seed the history with fresh dumps unless event-driven
            // observation is already live (in which case extra dumps are redundant noise).
            if (immediateDump && !hasRecentEventDriven(400L)) {
                repeat(WATCH_BOOTSTRAP_BURST) { index ->
                    if (!isActive || !isWatching) return@repeat
                    requestImmediateDump(expectedPackageHint)
                    if (index < WATCH_BOOTSTRAP_BURST - 1) {
                        delay(WATCH_BOOTSTRAP_GAP_MS)
                    }
                }
            }

            while (isActive && isWatching) {
                // Event-driven observation is authoritative; dump-polling is fallback only.
                val interval = if (hasRecentEventDriven(700L)) {
                    POLL_INTERVAL_ADAPTIVE_MS
                } else {
                    WATCH_INTERVAL_MS
                }

                if (!hasRecentEventDriven(450L)) {
                    requestImmediateDump(expectedPackageHint)
                }

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

    fun hasRecentEventDriven(windowMs: Long = 700L): Boolean {
        val at = lastEventDrivenAt
        if (at <= 0L) return false
        return System.currentTimeMillis() - at <= windowMs
    }

    fun hasRecentAuthoritative(windowMs: Long = 1200L): Boolean {
        val obs = latestStrongObservation
        if (obs.updatedAt <= 0L) return false
        return System.currentTimeMillis() - obs.updatedAt <= windowMs
    }

    fun hasRecentUsefulObservation(windowMs: Long = 1200L): Boolean {
        val obs = latestObservation
        if (obs.updatedAt <= 0L) return false
        val frame = KaiVisionInterpreter.classify(obs, expectedPackageHint, allowLauncherSurface = true)
        if (!frame.isUsable) return false
        return System.currentTimeMillis() - obs.updatedAt <= windowMs
    }

    fun observationWindow(expectedPackage: String = ""): ObservationWindow {
        val items = synchronized(history) { history.toList() }

        val latest = items.maxByOrNull { it.updatedAt }

        val usable = items
            .filter { KaiVisionInterpreter.classify(it, expectedPackage, true).isUsable }
            .maxByOrNull { it.updatedAt }

        val strong = items
            .filter { KaiVisionInterpreter.classify(it, expectedPackage, true).isStrong }
            .maxByOrNull { it.updatedAt }

        val matched = items
            .filter { KaiVisionInterpreter.packageMatchesExpected(it.packageName, expectedPackage) }
            .maxByOrNull { it.updatedAt }

        val nonLauncher = items
            .filter {
                val state = KaiVisionInterpreter.toScreenState(it)
                !state.isLauncher() && it.packageName.isNotBlank()
            }
            .maxByOrNull { it.updatedAt }

        return ObservationWindow(
            observations = items,
            latest = latest,
            latestUsable = usable,
            latestStrong = strong,
            latestTargetMatched = matched,
            latestNonLauncher = nonLauncher
        )
    }

    fun bestObservation(
        expectedPackage: String = "",
        requireStrong: Boolean = false
    ): KaiObservation {
        val window = observationWindow(expectedPackage)

        // Priority: strong > target-matched > usable > non-launcher > raw latest.
        // The global latestStrongObservation is only used as a last resort when the
        // live history window has no strong candidate — callers that need freshness
        // guarantees should use awaitFreshObservation() instead.
        val best = when {
            requireStrong && window.latestStrong != null -> window.latestStrong
            expectedPackage.isNotBlank() && window.latestTargetMatched != null -> {
                if (requireStrong) window.latestStrong ?: window.latestTargetMatched
                else window.latestTargetMatched
            }
            requireStrong && latestStrongObservation.updatedAt > 0L -> latestStrongObservation
            window.latestUsable != null -> window.latestUsable
            window.latestNonLauncher != null -> window.latestNonLauncher
            window.latest != null -> window.latest
            else -> latestObservation.takeIf { it.updatedAt > 0L }
        }

        return best ?: KaiObservation("", "", updatedAt = 0L)
    }

    fun currentScreenState(
        expectedPackage: String = "",
        requireStrong: Boolean = false
    ): KaiScreenState {
        return KaiVisionInterpreter.toScreenState(
            bestObservation(expectedPackage = expectedPackage, requireStrong = requireStrong)
        )
    }

    suspend fun awaitFreshObservation(
        afterTime: Long,
        timeoutMs: Long = 2200L,
        expectedPackage: String = "",
        requireStrong: Boolean = false
    ): KaiObservation {
        val startedAt = System.currentTimeMillis()
        var best = bestObservation(expectedPackage, requireStrong)

        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val candidate = bestObservation(expectedPackage, requireStrong)
            if (candidate.updatedAt > afterTime) {
                return candidate
            }
            if (candidate.updatedAt > best.updatedAt) {
                best = candidate
            }
            delay(FRESH_POLL_MS)
        }

        return best
    }

    suspend fun awaitPostOpenStabilization(
        expectedPackage: String,
        dispatchTime: Long = System.currentTimeMillis(),
        timeoutMs: Long = 2400L
    ): KaiOpenAppOutcome {
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val obs = awaitFreshObservation(
                afterTime = dispatchTime,
                timeoutMs = 220L,
                expectedPackage = expectedPackage,
                requireStrong = false
            )

            val frame = KaiVisionInterpreter.classify(
                obs = obs,
                expectedPackage = expectedPackage,
                allowLauncherSurface = true
            )
            val state = frame.screenState

            if (!frame.expectedPackageMatched) {
                delay(65L)
                continue
            }

            if (frame.isStrong && !state.isLauncher()) {
                return KaiOpenAppOutcome.TARGET_READY
            }

            if (frame.isUsable && !state.isLauncher()) {
                return KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
            }

            if (!hasRecentEventDriven(280L)) {
                requestImmediateDump(expectedPackage)
            }

            delay(70L)
        }

        val finalObs = bestObservation(expectedPackage = expectedPackage, requireStrong = false)
        val finalFrame = KaiVisionInterpreter.classify(
            obs = finalObs,
            expectedPackage = expectedPackage,
            allowLauncherSurface = true
        )
        val finalState = finalFrame.screenState

        return when {
            finalFrame.isStrong && !finalState.isLauncher() -> KaiOpenAppOutcome.TARGET_READY
            finalFrame.isUsable && finalFrame.expectedPackageMatched && !finalState.isLauncher() ->
                KaiOpenAppOutcome.USABLE_INTERMEDIATE_IN_TARGET_APP
            else -> KaiOpenAppOutcome.OPEN_FAILED
        }
    }

    fun parseElementsFromJson(json: String?): List<KaiUiElement> {
        if (json.isNullOrBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
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
                            depth = obj.optInt("depth", 0),
                            packageName = obj.optString("packageName"),
                            roleGuess = obj.optString("roleGuess").ifBlank { "unknown" }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}