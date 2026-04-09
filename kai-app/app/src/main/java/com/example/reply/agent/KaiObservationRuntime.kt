package com.example.reply.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.reply.ui.KaiAccessibilityService
import kotlinx.coroutines.*
import org.json.JSONArray

object KaiObservationRuntime {

    private const val TAG = "KaiObsRuntime"
    private const val WATCH_INTERVAL_MS = 900L

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

    private var watchJob: Job? = null
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun reset() {
        val blank = KaiObservation("", "", updatedAt = 0L)
        live = blank
        authoritative = blank
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

        val normalized = KaiScreenStateParser.normalize(dump)

        val validDump =
            pkg.isNotBlank() &&
            normalized.isNotBlank() &&
            !isOverlayOnlyDump(normalized) &&
            !pkg.startsWith("com.example.reply")

        if (validDump) {
            authoritative = obs
        }

        KaiAgentController.onObservationArrived(pkg, dump, elements, screenKind, confidence)
    }

    // 🔥 FIX: بديل آمن بدل isOverlayOnlyDump المفقودة
    private fun isOverlayOnlyDump(text: String): Boolean {
        return text.contains("dynamic island", true) ||
               text.contains("kai os", true) ||
               text.contains("make action", true)
    }

    suspend fun awaitFresh(afterTime: Long, timeoutMs: Long): KaiObservation? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val a = authoritative
            if (a.updatedAt > afterTime) return a

            val l = live
            if (l.updatedAt > afterTime) return l

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
                putExtra(KaiAccessibilityService.EXTRA_TIMEOUT_MS, 2200L)
                if (expectedPackage.isNotBlank()) {
                    putExtra(KaiAccessibilityService.EXTRA_EXPECTED_PACKAGE, expectedPackage)
                }
            }
            appCtx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestImmediateDump failed: ${e.message}", e)
        }
    }

    fun startWatching(immediateDump: Boolean = true, expectedPackage: String = "") {
        val appCtx = storedContext ?: return
        ensureBridge(appCtx)

        if (isWatching && watchJob?.isActive == true) {
            if (immediateDump) requestImmediateDump(expectedPackage)
            return
        }

        isWatching = true
        watchJob?.cancel()

        watchJob = watchScope.launch {
            if (immediateDump) requestImmediateDump(expectedPackage)
            while (isActive && isWatching) {
                requestImmediateDump(expectedPackage)
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    fun stopWatching() {
        isWatching = false
        watchJob?.cancel()
        watchJob = null
    }

    fun hasRecentAuthoritative(maxAgeMs: Long): Boolean {
        val obs = authoritative
        return obs.updatedAt > 0 &&
               System.currentTimeMillis() - obs.updatedAt <= maxAgeMs
    }

    fun getBestAvailable(): KaiObservation {
        return if (authoritative.updatedAt > 0L) authoritative else live
    }


    fun currentScreenState(): KaiScreenState {
        val best = if (authoritative.updatedAt > 0) authoritative else live
        return KaiScreenStateParser.fromDump(best.packageName, best.screenPreview)
    }

    // 🔥 FIX: لازم تكون accessible
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