
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

object KaiObservationRuntime {

    private const val TAG = "KaiObsRuntime"
    private const val WATCH_INTERVAL_MS = 550L
    private const val WATCH_BOOTSTRAP_BURST = 3
    private const val WATCH_BOOTSTRAP_GAP_MS = 120L
    private const val FRESH_POLL_MS = 60L

    @Volatile
    var live: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    @Volatile
    var authoritative: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    @Volatile
    var isWatching: Boolean = false
        private set

    @Volatile
    private var bridgeRegistered = false

    @Volatile
    private var storedContext: Context? = null

    @Volatile
    private var lastWatchExpectedPackage: String = ""

    private val bridgeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    fun currentExpectedPackage(): String = lastWatchExpectedPackage

    fun reset() {
        val blank = KaiObservation("", "", updatedAt = 0L)
        live = blank
        authoritative = blank
    }

    fun hardReset(stopWatching: Boolean = true) {
        if (stopWatching) stopWatching()
        lastWatchExpectedPackage = ""
        reset()
        requestTransitionReset()
    }

    fun clearRuntimeState(keepWatching: Boolean = true) {
        if (!keepWatching) stopWatching()
        lastWatchExpectedPackage = ""
        reset()
        requestTransitionReset()
    }

    fun softCleanupAfterRun() {
        lastWatchExpectedPackage = ""
    }

    fun requestWarmupObservation(
        expectedPackage: String = "",
        burstCount: Int = 4,
        gapMs: Long = 140L,
        skipTransitionReset: Boolean = false
    ) {
        lastWatchExpectedPackage = expectedPackage
        if (!skipTransitionReset) requestTransitionReset()
        repeat(burstCount.coerceIn(1, 8)) { index ->
            scope.launch {
                if (index > 0) delay(index * gapMs)
                requestImmediateDump(expectedPackage)
            }
        }
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
                    val screenKind = i.getStringExtra(KaiAccessibilityService.EXTRA_SCREEN_KIND).orEmpty()
                    val confidence = i.getFloatExtra(
                        KaiAccessibilityService.EXTRA_SEMANTIC_CONFIDENCE,
                        0f
                    )

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

        val state = screenStateOf(obs)
        if (isUsefulScreenState(state)) {
            authoritative = obs
            runCatching {
                KaiAgentController.onObservationArrived(
                    packageName = pkg,
                    screenPreview = dump,
                    elements = elements,
                    screenKind = state.screenKind,
                    semanticConfidence = state.semanticConfidence
                )
            }
        } else if (authoritative.updatedAt <= 0L) {
            authoritative = obs
        }
    }

    fun requestImmediateDump(expectedPackage: String = "") {
        val context = storedContext ?: return
        lastWatchExpectedPackage = expectedPackage
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

    suspend fun awaitFresh(
        afterTime: Long,
        timeoutMs: Long = 2200L,
        expectedPackage: String = "",
        authoritativeOnly: Boolean = false
    ): KaiObservation {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(300L)
        while (System.currentTimeMillis() < deadline) {
            val candidate = getBestAvailable(expectedPackage, authoritativeOnly)
            if (candidate.updatedAt > afterTime) return candidate
            delay(FRESH_POLL_MS)
        }
        return getBestAvailable(expectedPackage, authoritativeOnly)
    }

    fun startWatching(immediateDump: Boolean = true, expectedPackage: String = "") {
        lastWatchExpectedPackage = expectedPackage

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

    fun hasUsableAuthoritative(expectedPackage: String = ""): Boolean {
        return hasRecentAuthoritative(
            maxAgeMs = 1800L,
            expectedPackage = expectedPackage,
            requireSemantic = expectedPackage.isNotBlank()
        )
    }

    @JvmOverloads
    fun hasRecentAuthoritative(
        maxAgeMs: Long,
        expectedPackage: String = "",
        requireSemantic: Boolean = false
    ): Boolean {
        val obs = authoritative
        if (obs.updatedAt <= 0L) return false
        if (System.currentTimeMillis() - obs.updatedAt > maxAgeMs) return false

        val state = screenStateOf(obs)
        if (!matchesExpectedPackage(state.packageName, expectedPackage)) return false
        if (requireSemantic && !isUsefulScreenState(state)) return false
        return state.packageName.isNotBlank()
    }

    @JvmOverloads
    fun hasRecentUsefulObservation(
        maxAgeMs: Long,
        expectedPackage: String = "",
        requireAuthoritative: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        val candidates = if (requireAuthoritative) listOf(authoritative) else listOf(authoritative, live)
        return candidates.any { obs ->
            obs.updatedAt > 0L &&
                now - obs.updatedAt <= maxAgeMs &&
                matchesExpectedPackage(obs.packageName, expectedPackage) &&
                (!requireAuthoritative || isUsefulScreenState(screenStateOf(obs)))
        }
    }

    @JvmOverloads
    fun getBestAvailable(
        expectedPackage: String = "",
        authoritativeOnly: Boolean = false
    ): KaiObservation {
        val candidates = if (authoritativeOnly) listOf(authoritative) else listOf(authoritative, live)

        val matching = candidates
            .filter { it.updatedAt > 0L }
            .filter { matchesExpectedPackage(it.packageName, expectedPackage) }

        if (matching.isNotEmpty()) {
            return matching.maxByOrNull(::scoreObservation) ?: matching.first()
        }

        val any = candidates.filter { it.updatedAt > 0L }
        if (any.isNotEmpty()) {
            return any.maxByOrNull(::scoreObservation) ?: any.first()
        }

        return if (authoritative.updatedAt >= live.updatedAt) authoritative else live
    }

    fun currentScreenState(): KaiScreenState = screenStateOf(getBestAvailable())

    private fun scoreObservation(obs: KaiObservation): Long {
        val state = screenStateOf(obs)
        var score = obs.updatedAt
        if (state.packageName.isNotBlank()) score += 5_000_000L
        if (isUsefulScreenState(state)) score += 10_000_000L
        if (!state.isWeakObservation()) score += 1_000_000L
        return score
    }

    private fun screenStateOf(obs: KaiObservation): KaiScreenState {
        return KaiScreenStateParser.fromDump(
            packageName = obs.packageName,
            dump = obs.screenPreview,
            elements = obs.elements,
            screenKindHint = obs.screenKind,
            semanticConfidence = obs.semanticConfidence
        )
    }

    private fun matchesExpectedPackage(observed: String, expected: String): Boolean {
        if (expected.isBlank()) return true
        if (observed.isBlank()) return false
        return KaiAppIdentityRegistry.packageMatchesFamily(expected, observed) ||
            KaiScreenStateParser.normalize(observed) == KaiScreenStateParser.normalize(expected) ||
            KaiScreenStateParser.normalize(observed).startsWith(
                KaiScreenStateParser.normalize(expected) + "."
            )
    }

    private fun isUsefulScreenState(state: KaiScreenState): Boolean {
        return state.packageName.isNotBlank() &&
            state.rawDump.isNotBlank() &&
            !state.isOverlayPolluted() &&
            state.isMeaningful()
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
