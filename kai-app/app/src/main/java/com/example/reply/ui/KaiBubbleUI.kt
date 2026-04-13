package com.example.reply.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reply.R
import com.example.reply.agent.KaiAgentController
import com.example.reply.agent.KaiAgentLoopEngine
import com.example.reply.agent.KaiObservationRuntime
import com.example.reply.data.supabase.KaiMemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val ACTION_KAI_APPEND_CHAT = "com.example.reply.APPEND_CHAT"
private const val EXTRA_APPEND_TEXT = "append_text"
private const val EXTRA_APPEND_ROLE = "append_role"

@Composable
fun KaiBubbleUI(
    context: Context,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    var promptComposerOpen by remember { mutableStateOf(false) }
    var customPromptText by remember { mutableStateOf("") }

    var bubbleListening by remember { mutableStateOf(false) }
    var bubbleLoop by remember { mutableStateOf(false) }

    var pendingAgentSilentDump by remember { mutableStateOf(false) }
    var eyeWatching by remember { mutableStateOf(KaiObservationRuntime.isWatching) }
    var agentRunning by remember { mutableStateOf(KaiAgentController.isRunning()) }
    var recognizerErrorCount by remember { mutableIntStateOf(0) }

    var loopRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(if (agentRunning) "Monitoring" else "Ready") }

    var lastVoiceAt by remember { mutableLongStateOf(0L) }
    var lastListenStartAt by remember { mutableLongStateOf(0L) }
    var listenSession by remember { mutableIntStateOf(0) }
    var actionRunToken by remember { mutableIntStateOf(0) }
    var recognizerEpoch by remember { mutableIntStateOf(0) }

    var agentLoopEngine by remember { mutableStateOf<KaiAgentLoopEngine?>(null) }
    var restartListenJob by remember { mutableStateOf<Job?>(null) }
    var restartListenGuard by remember { mutableStateOf(false) }
    var needsBubbleRecognizerRestart by remember { mutableStateOf(false) }

    val recognizer = remember(recognizerEpoch) {
        SpeechRecognizer.createSpeechRecognizer(context)
    }
    val memoryRepo = remember { KaiMemoryRepository(context.applicationContext) }

    fun computedIdleStatus(): String {
        return when {
            loopRunning -> "Agent working"
            bubbleListening -> "Listening"
            bubbleLoop -> "Talk mode"
            agentRunning -> "Monitoring"
            eyeWatching -> {
                if (KaiObservationRuntime.hasRecentAuthoritative(2200L)) "Watching"
                else "Watching…"
            }
            else -> "Ready"
        }
    }

    LaunchedEffect(Unit) {
        KaiObservationRuntime.ensureBridge(context.applicationContext)
        eyeWatching = KaiObservationRuntime.isWatching
        statusText = computedIdleStatus()
    }

    fun appendToMainChat(text: String, role: String = "assistant") {
        val safeText = text.trim().take(1800)
        if (safeText.isBlank()) return
        context.sendBroadcast(
            Intent(ACTION_KAI_APPEND_CHAT).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_APPEND_TEXT, safeText)
                putExtra(EXTRA_APPEND_ROLE, role)
            }
        )
    }

    fun shouldRebuildRecognizerOnError(error: Int): Boolean {
        return when (error) {
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER -> true
            else -> false
        }
    }

    fun rebuildRecognizerSoft() {
        scope.launch {
            delay(120)
            recognizerEpoch += 1
        }
    }

    fun sendKaiCmd(cmd: String, text: String = "", dir: String = "", times: Int = 1) {
        val intent = Intent(KaiAccessibilityService.ACTION_KAI_COMMAND).apply {
            setPackage(context.packageName)
            putExtra(KaiAccessibilityService.EXTRA_CMD, cmd)
            putExtra(KaiAccessibilityService.EXTRA_TEXT, text)
            if (dir.isNotBlank()) putExtra(KaiAccessibilityService.EXTRA_DIR, dir)
            putExtra(KaiAccessibilityService.EXTRA_TIMES, times.coerceIn(1, 10))
        }
        context.sendBroadcast(intent)
    }

    fun requestFreshDump(delayMs: Long = 120L) {
        scope.launch {
            delay(delayMs)
            KaiObservationRuntime.requestImmediateDump()
        }
    }

    fun startEyeWatching() {
        KaiObservationRuntime.ensureBridge(context.applicationContext)
        if (!KaiObservationRuntime.isWatching) {
            KaiObservationRuntime.startWatching(immediateDump = true)
        } else {
            KaiObservationRuntime.requestImmediateDump()
        }
        eyeWatching = KaiObservationRuntime.isWatching
        statusText = computedIdleStatus()
        appendToMainChat("Kai eye watching ON", "system")
    }

    fun stopEyeWatching() {
        KaiObservationRuntime.stopWatching()
        eyeWatching = false
        statusText = computedIdleStatus()
        appendToMainChat("Kai eye watching OFF", "system")
    }

    fun cancelPendingRestart() {
        restartListenJob?.cancel()
        restartListenJob = null
        restartListenGuard = false
    }

    fun stopBubbleListening() {
        cancelPendingRestart()
        listenSession += 1
        bubbleListening = false
        try {
            recognizer.stopListening()
        } catch (_: Exception) {
        }
        try {
            recognizer.cancel()
        } catch (_: Exception) {
        }
    }

    fun startBubbleListening() {
        if (!bubbleLoop) return
        if (bubbleListening) return
        if (loopRunning) return
        if (KaiVoice.speakingNow()) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            bubbleListening = false
            statusText = "Speech unavailable"
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastVoiceAt < 420L) return
        if (now - lastListenStartAt < 550L) return

        cancelPendingRestart()
        listenSession += 1
        val mySession = listenSession

        bubbleListening = true
        statusText = "Listening"
        lastListenStartAt = now

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 7)
        }

        try {
            recognizer.cancel()
            scope.launch {
                delay(70)
                if (mySession != listenSession) return@launch
                try {
                    recognizer.startListening(intent)
                } catch (_: Exception) {
                    if (mySession == listenSession) {
                        bubbleListening = false
                        statusText = computedIdleStatus()
                        rebuildRecognizerSoft()
                    }
                }
            }
        } catch (_: Exception) {
            bubbleListening = false
            statusText = computedIdleStatus()
            rebuildRecognizerSoft()
        }
    }

    fun resetBubbleRecognizerAfterAgentLoop() {
        scope.launch {
            stopBubbleListening()
            recognizerErrorCount = 0
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
            recognizerEpoch += 1
            delay(300)
            needsBubbleRecognizerRestart = true
        }
    }

    fun restartBubbleListening(delayMs: Long = 520L) {
        if (!bubbleLoop) return
        if (restartListenGuard) return
        restartListenGuard = true

        cancelPendingRestart()
        restartListenJob = scope.launch {
            try {
                delay(delayMs)
                if (bubbleLoop && !bubbleListening && !KaiVoice.speakingNow() && !loopRunning) {
                    startBubbleListening()
                }
            } finally {
                restartListenGuard = false
            }
        }
    }

    LaunchedEffect(needsBubbleRecognizerRestart, bubbleLoop) {
        if (needsBubbleRecognizerRestart && bubbleLoop) {
            needsBubbleRecognizerRestart = false
            startBubbleListening()
        }
    }

    fun resetAgentLoopUi(finalStatus: String? = null) {
        loopRunning = false
        statusText = finalStatus ?: computedIdleStatus()
    }

    fun softResetBubbleRuntime() {
        pendingAgentSilentDump = false
        eyeWatching = KaiObservationRuntime.isWatching
        KaiBubbleManager.releaseAllSuppression()
        KaiBubbleManager.softResetUiState()
        KaiAgentController.finishActionLoopSession()
        cancelPendingRestart()
        needsBubbleRecognizerRestart = true
        resetAgentLoopUi()
        requestFreshDump(140L)
    }

    fun cancelCurrentActionLoop(reason: String? = null) {
        actionRunToken += 1
        KaiRuntimeLoopCoordinator.cancelLoop(agentLoopEngine)
        agentLoopEngine = null
        softResetBubbleRuntime()
        if (reason != null) appendToMainChat(reason, "system")
    }

    fun saveMemoryDirect(rawText: String, value: String) {
        val cleanValue = value.trim().ifBlank { rawText.trim() }
        if (cleanValue.isBlank()) return

        scope.launch(Dispatchers.IO) {
            val ok = memoryRepo.upsertMemory(
                category = "user_memory",
                key = "mem_${System.currentTimeMillis()}",
                value = cleanValue,
                source = "bubble_voice",
                importance = 7
            )
            withContext(Dispatchers.Main) {
                appendToMainChat(
                    if (ok) "Memory saved: $cleanValue" else "Memory save failed",
                    "system"
                )
                statusText = computedIdleStatus()
                if (bubbleLoop) restartBubbleListening(520L)
            }
        }
    }

    fun stopBubbleTalk() {
        bubbleLoop = false
        stopBubbleListening()
        KaiVoice.stop()
        needsBubbleRecognizerRestart = true
        statusText = computedIdleStatus()
    }

    fun speakFromBubble(text: String, tone: KaiVoice.Tone = KaiVoice.Tone.KAI) {
        lastVoiceAt = System.currentTimeMillis()
        KaiVoice.speak(
            context = context,
            text = text,
            tone = tone,
            onDone = {
                lastVoiceAt = System.currentTimeMillis()
                statusText = computedIdleStatus()
                if (bubbleLoop) restartBubbleListening(480L)
            },
            onError = {
                lastVoiceAt = System.currentTimeMillis()
                statusText = computedIdleStatus()
                if (bubbleLoop) restartBubbleListening(560L)
            }
        )
    }

    fun askKaiFromBubble(text: String) {
        statusText = "Thinking"
        OpenAIClient.askStream(
            userText = text,
            history = emptyList(),
            onDelta = {},
            onDone = {},
            onError = {
                statusText = computedIdleStatus()
                restartBubbleListening(520L)
            },
            onFinalText = { finalText ->
                scope.launch {
                    statusText = "Speaking"
                    speakFromBubble(finalText)
                }
            }
        )
    }

    fun toggleAgent() {
        val running = KaiAgentController.toggleContinuousAnalysis(
            userGoal = "Observe the current screen quietly and keep building useful written context.",
            customPrompt = customPromptText,
            onRequestDump = {
                pendingAgentSilentDump = true
                KaiObservationRuntime.requestImmediateDump()
            },
            onInsight = { insight ->
                statusText = "Monitoring"
                appendToMainChat(insight, "assistant")
            }
        )
        agentRunning = running
        statusText = if (running) "Monitoring" else computedIdleStatus()
        appendToMainChat(if (running) "Agent active" else "Agent off", "system")
    }

    fun triggerBubbleAction(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank()) return
        if (!KaiBubbleManager.isShowing()) return

        cancelPendingRestart()
        stopBubbleListening()

        actionRunToken += 1
        val myRunToken = actionRunToken

        KaiObservationRuntime.ensureBridge(context.applicationContext)
        if (!KaiObservationRuntime.hasRecentUsefulObservation(1800L)) {
            KaiObservationRuntime.requestImmediateDump()
        }
        eyeWatching = KaiObservationRuntime.isWatching

        if (KaiAgentController.isRunning()) {
            agentRunning = false
            appendToMainChat("Monitoring paused before action loop", "system")
        }

        agentLoopEngine?.cancel()
        agentLoopEngine = null

        KaiBubbleManager.releaseAllSuppression()
        KaiBubbleManager.softResetUiState()

        loopRunning = true
        statusText = "Agent planning"
        customPromptText = clean
        expanded = true
        promptComposerOpen = true

        val engine = KaiRuntimeLoopCoordinator.startLoop(
            context = context.applicationContext,
            prompt = clean,
            appendLog = { role, text -> appendToMainChat(text, role) },
            onPhase = { phase ->
                if (myRunToken != actionRunToken) return@startLoop
                statusText = when (phase) {
                    KaiRuntimePhase.CANCELLED -> "Agent cancelled"
                    else -> "Agent working"
                }
            },
            onFinished = { result ->
                if (myRunToken != actionRunToken) return@startLoop
                agentLoopEngine = null
                softResetBubbleRuntime()
                appendToMainChat(result.finalMessage, "system")
                expanded = true
                promptComposerOpen = true
                if (bubbleLoop) restartBubbleListening(700L)
            }
        )

        agentLoopEngine = engine
    }

    LaunchedEffect(promptComposerOpen, expanded, loopRunning) {
        KaiBubbleManager.setInputModeEnabled(promptComposerOpen && expanded && !loopRunning)
    }

    DisposableEffect(Unit) {
        onDispose {
            cancelPendingRestart()
            try {
                agentLoopEngine?.cancel()
            } catch (_: Exception) {
            }
            KaiBubbleManager.setInputModeEnabled(false)
            KaiBubbleManager.softResetUiState()
        }
    }

    val bubbleListener = remember(recognizerEpoch) {
        object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                bubbleListening = false
                recognizerErrorCount = 0

                val raw = KaiCommandParser.pickBestRecognitionCandidate(
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                )

                when (val command = KaiCommandParser.parse(raw)) {
                    KaiParsedCommand.Stop -> {
                        cancelCurrentActionLoop("Agent loop cancelled")
                        stopBubbleTalk()
                    }

                    KaiParsedCommand.ToggleAgent -> {
                        toggleAgent()
                        restartBubbleListening()
                    }

                    KaiParsedCommand.Report -> {
                        expanded = true
                        promptComposerOpen = true
                        statusText = "Prompt ready"
                    }

                    KaiParsedCommand.ReadScreen,
                    KaiParsedCommand.AnalyzeScreen -> {
                        if (customPromptText.isNotBlank()) {
                            triggerBubbleAction(customPromptText)
                        } else {
                            expanded = true
                            promptComposerOpen = true
                            appendToMainChat("Open Custom Prompt and write the goal first.", "system")
                            statusText = "Prompt ready"
                        }
                    }

                    KaiParsedCommand.SoftReset -> {
                        softResetBubbleRuntime()
                        appendToMainChat("Soft reset completed", "system")
                        restartBubbleListening(700L)
                    }

                    KaiParsedCommand.Back -> {
                        sendKaiCmd(KaiAccessibilityService.CMD_BACK)
                        restartBubbleListening()
                    }

                    KaiParsedCommand.Home -> {
                        sendKaiCmd(KaiAccessibilityService.CMD_HOME)
                        restartBubbleListening()
                    }

                    KaiParsedCommand.Recents -> {
                        sendKaiCmd(KaiAccessibilityService.CMD_RECENTS)
                        restartBubbleListening()
                    }

                    is KaiParsedCommand.Scroll -> {
                        sendKaiCmd(
                            KaiAccessibilityService.CMD_SCROLL,
                            dir = command.dir,
                            times = command.times
                        )
                        restartBubbleListening()
                    }

                    is KaiParsedCommand.Click -> {
                        if (command.target.isNotBlank()) {
                            sendKaiCmd(KaiAccessibilityService.CMD_CLICK_TEXT, text = command.target)
                            restartBubbleListening()
                        } else {
                            askKaiFromBubble(raw)
                        }
                    }

                    is KaiParsedCommand.TypeText -> {
                        if (command.text.isNotBlank()) {
                            sendKaiCmd(KaiAccessibilityService.CMD_TYPE, text = command.text)
                            restartBubbleListening()
                        } else {
                            askKaiFromBubble(raw)
                        }
                    }

                    is KaiParsedCommand.OpenApp -> {
                        triggerBubbleAction("open ${command.appName}".trim())
                    }

                    is KaiParsedCommand.SaveMemory -> {
                        saveMemoryDirect(command.rawText, command.value)
                    }

                    is KaiParsedCommand.Ask -> askKaiFromBubble(command.text)
                }
            }

            override fun onError(error: Int) {
                bubbleListening = false
                statusText = computedIdleStatus()
                recognizerErrorCount = (recognizerErrorCount + 1).coerceAtMost(10)
                if (shouldRebuildRecognizerOnError(error)) {
                    rebuildRecognizerSoft()
                    recognizerErrorCount = 0
                }
                if (bubbleLoop && !KaiVoice.speakingNow()) {
                    restartBubbleListening(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 650L
                            else -> 850L
                        }
                    )
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(bubbleListener)
        onDispose {
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (pendingAgentSilentDump && KaiObservationRuntime.hasRecentUsefulObservation(1800L)) {
                pendingAgentSilentDump = false
            }
            eyeWatching = KaiObservationRuntime.isWatching
            agentRunning = KaiAgentController.isRunning()
            statusText = computedIdleStatus()
            delay(280L)
        }
    }

    val aurora = Color(0xFF56F0A6)
    val aurora2 = Color(0xFF2CE3FF)
    val aurora3 = Color(0xFF7CF7D0)
    val darkBg = Color(0xFF08111A)
    val darkBg2 = Color(0xFF0C1723)
    val textMain = Color(0xFFF4F8FF)
    val textDim = Color(0xFFB8C4D3)
    val danger = Color(0xFF2A1E1E)

    val infinite = rememberInfiniteTransition(label = "kai_island")
    val pulse by infinite.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "kai_island_pulse"
    )

    val eyeGlow = when {
        bubbleListening -> 1.18f
        loopRunning -> 1.10f
        eyeWatching -> 1.06f
        agentRunning -> 1.0f
        KaiBubbleManager.isStronglySuppressed() -> 0.35f
        KaiBubbleManager.isActionUiSuppressed() -> 0.62f
        else -> (0.88f + ((pulse - 0.95f) * 2f))
    }

    val islandAlpha = when {
        KaiBubbleManager.isStronglySuppressed() -> 0.60f
        KaiBubbleManager.isActionUiSuppressed() -> 0.84f
        else -> 1f
    }

    val islandScale = if (KaiBubbleManager.isActionUiSuppressed()) 0.992f else 1f

    @Composable
    fun IslandChip(
        label: String,
        icon: ImageVector? = null,
        dangerStyle: Boolean = false,
        onTap: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (dangerStyle) danger.copy(alpha = 0.92f) else darkBg2.copy(alpha = 0.96f)
                )
                .border(
                    1.dp,
                    if (dangerStyle) Color.White.copy(alpha = 0.16f) else aurora.copy(alpha = 0.44f),
                    RoundedCornerShape(18.dp)
                )
                .pointerInput(label, dangerStyle) {
                    detectTapGestures(
                        onTap = {
                            ClickSfx.play(context, 0.14f)
                            onTap()
                        }
                    )
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = label,
                color = textMain,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    @Composable
    fun CircleControl(
        icon: ImageVector,
        tint: Color,
        size: Int = 46,
        onTap: () -> Unit,
        onLongPress: (() -> Unit)? = null
    ) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            aurora.copy(alpha = 0.16f),
                            aurora2.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                .pointerInput(icon, onLongPress) {
                    detectTapGestures(
                        onTap = {
                            ClickSfx.play(context, 0.14f)
                            onTap()
                        },
                        onLongPress = { onLongPress?.invoke() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size((size * 0.42f).dp)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(288.dp)
                    .height(78.dp)
                    .scale(islandScale)
                    .alpha(islandAlpha)
                    .shadow(20.dp, RoundedCornerShape(32.dp))
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(darkBg.copy(alpha = 0.985f), darkBg2.copy(alpha = 0.985f))
                        )
                    )
                    .border(
                        1.35.dp,
                        Brush.horizontalGradient(
                            listOf(
                                aurora.copy(alpha = 0.88f),
                                aurora2.copy(alpha = 0.74f),
                                aurora3.copy(alpha = 0.56f)
                            )
                        ),
                        RoundedCornerShape(32.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CircleControl(
                        icon = Icons.Filled.Mic,
                        tint = if (bubbleLoop || bubbleListening) aurora3 else textMain,
                        size = 48,
                        onTap = {
                            if (bubbleLoop || bubbleListening) {
                                stopBubbleTalk()
                            } else {
                                bubbleLoop = true
                                startBubbleListening()
                            }
                        }
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Dynamic Island",
                            color = textMain,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                loopRunning -> statusText
                                bubbleListening -> "Listening"
                                bubbleLoop -> "Talk mode"
                                expanded -> "Expanded"
                                else -> statusText
                            },
                            color = if (bubbleListening || loopRunning) aurora3 else textDim,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    CircleControl(
                        icon = Icons.Filled.Home,
                        tint = textMain,
                        size = 40,
                        onTap = { KaiBubbleManager.onHomeAction(context) }
                    )

                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        aurora.copy(alpha = 0.34f * eyeGlow),
                                        aurora2.copy(alpha = 0.22f * eyeGlow),
                                        aurora3.copy(alpha = 0.12f * eyeGlow),
                                        Color.Transparent
                                    )
                                )
                            )
                            .border(1.dp, aurora.copy(alpha = 0.68f), CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (eyeWatching) stopEyeWatching() else startEyeWatching()
                                    },
                                    onLongPress = {
                                        expanded = true
                                        promptComposerOpen = true
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .scale(eyeGlow.coerceIn(0.94f, 1.28f))
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            aurora.copy(alpha = 0.16f * eyeGlow),
                                            aurora2.copy(alpha = 0.10f * eyeGlow),
                                            Color.Transparent
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.aurora_eye),
                                contentDescription = null,
                                modifier = Modifier.size(62.dp),
                                alpha = 0.995f
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    AnimatedVisibility(visible = promptComposerOpen) {
                        Column(
                            modifier = Modifier
                                .width(286.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(darkBg2.copy(alpha = 0.985f))
                                .border(1.dp, aurora.copy(alpha = 0.36f), RoundedCornerShape(24.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TextField(
                                value = customPromptText,
                                onValueChange = { customPromptText = it },
                                modifier = Modifier.width(262.dp),
                                placeholder = {
                                    Text(
                                        "Example: open notes and create a new note",
                                        color = Color.White.copy(alpha = 0.36f)
                                    )
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF0B0B12).copy(alpha = 0.58f),
                                    unfocusedContainerColor = Color(0xFF0B0B12).copy(alpha = 0.48f),
                                    disabledContainerColor = Color(0xFF0B0B12).copy(alpha = 0.48f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    disabledTextColor = Color.White.copy(alpha = 0.92f),
                                    focusedIndicatorColor = aurora.copy(alpha = 0.66f),
                                    unfocusedIndicatorColor = aurora.copy(alpha = 0.28f),
                                    disabledIndicatorColor = aurora.copy(alpha = 0.28f),
                                    cursorColor = aurora
                                ),
                                singleLine = false,
                                maxLines = 5,
                                readOnly = loopRunning,
                                enabled = true
                            )

                            Row(
                                modifier = Modifier.width(262.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IslandChip(
                                    label = if (loopRunning) "Stop" else "Make Action",
                                    icon = if (loopRunning) Icons.Filled.Close else Icons.Filled.Star,
                                    dangerStyle = loopRunning,
                                    onTap = {
                                        if (loopRunning) cancelCurrentActionLoop("Agent loop cancelled")
                                        else triggerBubbleAction(customPromptText)
                                    }
                                )
                                IslandChip(
                                    label = "Close",
                                    icon = Icons.Filled.Close,
                                    onTap = {
                                        expanded = false
                                        promptComposerOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = when {
                    loopRunning -> "Agent loop active"
                    agentRunning -> "Agent active"
                    eyeWatching -> "Kai eye active"
                    else -> ""
                },
                color = aurora3.copy(alpha = 0.86f),
                fontSize = 11.sp,
                modifier = Modifier.alpha(if (agentRunning || loopRunning || eyeWatching) 1f else 0f)
            )
        }
    }
}