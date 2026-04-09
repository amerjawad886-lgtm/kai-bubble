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

/**
 * Single owner of all live screen observation state.
 *
 * Lifetime : process-level singleton. Survives activity restarts.
 * Source   : ACTION_KAI_DUMP_RESULT broadcasts from KaiAccessibilityService.
 *
 * Two tiers:
 *   live          – updated on every dump, even low-quality ones
 *   authoritative – only non-blank-package, non-empty dumps
 *
 * Run-boundary contract:
 *   Call reset() before every new run.
 *   Both fields get updatedAt = 0L.  Any arriving dump (always has
 *   updatedAt > 0) is then unambiguously newer.
 *
 * Continuous observation:
 *   startWatching() sends CMD_DUMP every 1 200 ms in the background so the
 *   agent never starts from complete blindness.  When watching is active, a
 *   fresh authoritative observation is usually already available by the time
 *   the first planning cycle begins.
 */
object KaiObservationRuntime {

    private const val TAG = "KaiObsRuntime"
    private const val WATCH_INTERVAL_MS = 1200L

    // ── Live state ────────────────────────────────────────────────────────────

    /** Every dump arrival, including low-quality ones. */
    @Volatile var live: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    /** Only non-blank-package, non-empty dumps. */
    @Volatile var authoritative: KaiObservation = KaiObservation("", "", updatedAt = 0L)
        private set

    // ── Bridge ────────────────────────────────────────────────────────────────

    @Volatile private var bridgeRegistered = false
    private val bridgeLock = Any()
    @Volatile private var storedContext: Context? = null

    // ── Continuous watching ───────────────────────────────────────────────────

    @Volatile var isWatching: Boolean = false
        private set
    private var watchJob: Job? = null
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Run-boundary reset ────────────────────────────────────────────────────

    /**
     * Hard reset for run-boundary isolation.
     *
     * Sets updatedAt = 0L on both fields so that any dump arriving after
     * this call (always updatedAt > 0) is recognised as genuinely new by
     * awaitFresh() and requestFreshScreenImpl polling loops.
     *
     * Does NOT stop continuous watching — the next scheduled dump will
     * refresh the state naturally.
     */
    fun reset() {
        val blank = KaiObservation("", "", updatedAt = 0L)
        live = blank
        authoritative = blank
    }

    // ── Bridge registration ───────────────────────────────────────────────────

    /**
     * Register the ACTION_KAI_DUMP_RESULT receiver once per process.
     * Idempotent — safe to call before every run.
     */
    fun ensureBridge(context: Context) {
        if (bridgeRegistered) return
        synchronized(bridgeLock) {
            if (bridgeRegistered) return
            val appCtx = context.applicationContext
            storedContext = appCtx
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action != KaiAccessibilityService.ACTION_KAI_DUMP_RESULT) return
                    val dump     = i.getStringExtra(KaiAccessibilityService.EXTRA_DUMP).orEmpty()
                    val pkg      = i.getStringExtra(KaiAccessibilityService.EXTRA_PACKAGE).orEmpty()
                    val elements = parseElementsFromJson(
                        i.getStringExtra(KaiAccessibilityService.EXTRA_ELEMENTS_JSON)
                    )
                    val kind       = i.getStringExtra(KaiAccessibilityService.EXTRA_SCREEN_KIND).orEmpty()
                    val confidence = i.getFloatExtra(KaiAccessibilityService.EXTRA_SEMANTIC_CONFIDENCE, 0f)
                    onDumpArrived(pkg, dump, elements, kind, confidence)
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

    // ── Dump arrival ──────────────────────────────────────────────────────────

    /** Called by the broadcast receiver on every dump. Updates live + authoritative. */
    fun onDumpArrived(
        pkg: String,
        dump: String,
        elements: List<KaiUiElement>,
        screenKind: String,
        confidence: Float
    ) {
        val obs = KaiObservation(
            packageName       = pkg,
            screenPreview     = dump,
            elements          = elements,
            screenKind        = screenKind,
            semanticConfidence = confidence,
            updatedAt         = System.currentTimeMillis()
        )
        live = obs

        val normalized = KaiScreenStateParser.normalize(dump)
        val isAuthoritative =
            pkg.isNotBlank() &&
                normalized.isNotBlank() &&
                normalized != KaiScreenStateParser.normalize("(no active window)") &&
                normalized != KaiScreenStateParser.normalize("(empty dump)")
        if (isAuthoritative) {
            authoritative = obs
        }

        // Keep the agent controller memory buffer in sync.
        // This is a same-package call; KaiAgentController is in com.example.reply.agent.
        KaiAgentController.onObservationArrived(pkg, dump, elements, screenKind, confidence)
    }

    // ── Observation primitives ────────────────────────────────────────────────

    /**
     * Suspend until an observation arrives with updatedAt strictly greater
     * than [afterTime].  Returns null on timeout.
     *
     * Polls at 80 ms — fine-grained enough that we don't miss a dump but
     * light enough to avoid busy-waiting.
     */
    suspend fun awaitFresh(afterTime: Long, timeoutMs: Long): KaiObservation? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val obs = live
            if (obs.updatedAt > afterTime) return obs
            delay(80L)
        }
        return null
    }

    /** Current live state parsed into KaiScreenState. */
    fun currentScreenState(): KaiScreenState {
        val obs = live
        return KaiScreenStateParser.fromDump(obs.packageName, obs.screenPreview)
    }

    /**
     * Returns true if we have an authoritative observation that arrived
     * within the last [withinMs] ms and has a non-blank package name.
     * Used by the startup fast-path to skip the CMD_DUMP handshake.
     */
    fun hasRecentAuthoritative(withinMs: Long = 2000L): Boolean {
        val obs = authoritative
        if (obs.packageName.isBlank()) return false
        return (System.currentTimeMillis() - obs.updatedAt) <= withinMs
    }

    // ── Continuous watching ───────────────────────────────────────────────────

    /**
     * Start lightweight continuous background observation.
     *
     * Sends CMD_DUMP every 1 200 ms.  The results arrive via the broadcast
     * receiver and update [live] / [authoritative] silently — no UI logging.
     * When the action loop starts, the agent can read from [authoritative]
     * directly rather than waiting for the first dump.
     *
     * Idempotent — safe to call multiple times.
     */
    fun startWatching() {
        if (isWatching) return
        isWatching = true
        watchJob?.cancel()
        watchJob = watchScope.launch {
            while (isActive && isWatching) {
                val ctx = storedContext
                if (ctx != null) {
                    try {
                        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
                            setPackage(ctx.packageName)
                            putExtra(KaiAccessibilityService.EXTRA_CMD, KaiAccessibilityService.CMD_DUMP)
                        }
                        ctx.sendBroadcast(intent)
                    } catch (_: Exception) {}
                }
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    fun stopWatching() {
        isWatching = false
        watchJob?.cancel()
        watchJob = null
    }

    // ── Element parsing ───────────────────────────────────────────────────────

    fun parseElementsFromJson(elementsJson: String?): List<KaiUiElement> {
        val payload = elementsJson.orEmpty().trim()
        if (payload.isBlank()) return emptyList()
        return try {
            val array = JSONArray(payload)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        KaiUiElement(
                            text               = obj.optString("text").trim(),
                            contentDescription = obj.optString("contentDescription").trim(),
                            hint               = obj.optString("hint").trim(),
                            viewId             = obj.optString("viewId").trim(),
                            className          = obj.optString("className").trim(),
                            clickable          = obj.optBoolean("clickable", false),
                            editable           = obj.optBoolean("editable", false),
                            scrollable         = obj.optBoolean("scrollable", false),
                            selected           = obj.optBoolean("selected", false),
                            checked            = obj.optBoolean("checked", false),
                            bounds             = obj.optString("bounds").trim(),
                            depth              = obj.optInt("depth", 0).coerceAtLeast(0),
                            packageName        = obj.optString("packageName").trim(),
                            roleGuess          = obj.optString("roleGuess").ifBlank { "unknown" }
                        )
                    )
                }
            }.take(260)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
