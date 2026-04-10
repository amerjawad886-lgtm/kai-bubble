package com.example.reply.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reply.R
import com.example.reply.agent.KaiAgentController
import com.example.reply.agent.KaiAgentLoopEngine
import com.example.reply.data.supabase.KaiChatRepository
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

private enum class MsgRole { SYSTEM, USER, KAI }

private data class ChatMsg(
    val id: Long,
    val role: MsgRole,
    val text: String
)

private const val MAX_RUNTIME_LOG_MESSAGES = 240

private val ChatMsgListSaver = listSaver<List<ChatMsg>, String>(
    save = { list ->
        list.map { msg ->
            val safeText = msg.text.replace("|||", " ")
            "${msg.id}|||${msg.role.name}|||$safeText"
        }
    },
    restore = { restored ->
        restored.mapNotNull { row ->
            val parts = row.split("|||", limit = 3)
            if (parts.size < 3) null
            else ChatMsg(
                id = parts[0].toLongOrNull() ?: System.nanoTime(),
                role = runCatching { MsgRole.valueOf(parts[1]) }.getOrDefault(MsgRole.SYSTEM),
                text = parts[2]
            )
        }
    }
)

@Composable
fun KaiHomeScreen(
    startMode: String = "",
    loadSessionId: Long? = null,
    onEyeTap: () -> Unit = {},
    onOpenHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val chatRepo = remember { KaiChatRepository(context.applicationContext) }
    val memoryRepo = remember { KaiMemoryRepository(context.applicationContext) }

    var recognizerEpoch by remember { mutableIntStateOf(0) }
    val recognizer = remember(recognizerEpoch) {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

    SideEffect {
        view.isSoundEffectsEnabled = false
        try {
            view.rootView?.isSoundEffectsEnabled = false
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(Unit) {
        ClickSfx.preload(context)
    }

    LaunchedEffect(Unit) {
        KaiAgentController.ensureRuntimeObservationBridge(context.applicationContext)
    }

    var messages by rememberSaveable(stateSaver = ChatMsgListSaver) {
        mutableStateOf<List<ChatMsg>>(emptyList())
    }
    var msgSeed by rememberSaveable { mutableLongStateOf(System.nanoTime()) }

    fun nextMsgId(): Long {
        msgSeed += 1L
        return msgSeed
    }

    fun shouldRebuildRecognizerOnError(error: Int): Boolean {
        return when (error) {
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER -> true
            else -> false
        }
    }

    var chatInput by rememberSaveable { mutableStateOf("") }
    var currentSessionId by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var currentSessionTitle by rememberSaveable { mutableStateOf("New Chat") }
    var hasInitialized by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var customPromptText by rememberSaveable { mutableStateOf("") }

    val listState = rememberLazyListState()

    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var isExecutingAction by remember { mutableStateOf(false) }

    var requestId by remember { mutableIntStateOf(0) }
    var streamingText by remember { mutableStateOf("") }
    var micRms by remember { mutableFloatStateOf(0f) }

    var voiceLoop by rememberSaveable { mutableStateOf(false) }
    var muteSpeakUntilMs by remember { mutableLongStateOf(0L) }
    var speechSessionId by remember { mutableIntStateOf(0) }
    var listenSessionId by remember { mutableIntStateOf(0) }
    var lastListenStartAt by remember { mutableLongStateOf(0L) }
    var recognizerErrorCount by remember { mutableIntStateOf(0) }

    var agentLoopEngine by remember { mutableStateOf<KaiAgentLoopEngine?>(null) }
    var agentRunToken by remember { mutableIntStateOf(0) }
    var restartVoiceJob by remember { mutableStateOf<Job?>(null) }
    var restartVoiceGuard by remember { mutableStateOf(false) }
    var needsVoiceRecognizerRestart by remember { mutableStateOf(false) }
    var recognizerDirty by remember { mutableStateOf(false) }

    fun remoteSessionId(): String = "session_$currentSessionId"

    fun recentHistory(): List<OpenAIHistoryItem> =
        messages.takeLast(12).map {
            OpenAIHistoryItem(
                role = when (it.role) {
                    MsgRole.USER -> "user"
                    MsgRole.KAI -> "assistant"
                    MsgRole.SYSTEM -> "system"
                },
                text = it.text
            )
        }

    fun push(role: MsgRole, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        var titleForRemote = currentSessionTitle
        if (role == MsgRole.USER && currentSessionTitle == "New Chat") {
            titleForRemote = OpenAIClient.titleFromText(clean)
            currentSessionTitle = titleForRemote
        }

        val next = ChatMsg(id = nextMsgId(), role = role, text = clean)
        messages = (messages + next).takeLast(MAX_RUNTIME_LOG_MESSAGES)

        val remoteRole = when (role) {
            MsgRole.USER -> "user"
            MsgRole.KAI -> "assistant"
            MsgRole.SYSTEM -> "system"
        }

        val summary = when {
            role == MsgRole.USER -> clean.take(160)
            else -> messages.firstOrNull { it.role == MsgRole.USER }?.text?.take(160).orEmpty()
        }

        scope.launch(Dispatchers.IO) {
            chatRepo.upsertSession(
                sessionId = remoteSessionId(),
                title = titleForRemote,
                summary = summary
            )
            chatRepo.insertMessage(
                sessionId = remoteSessionId(),
                role = remoteRole,
                message = clean
            )
        }

        scope.launch {
            delay(60)
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
            }
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
            sendKaiCmd(KaiAccessibilityService.CMD_DUMP)
        }
    }

    fun cancelRestartVoiceLoop() {
        restartVoiceJob?.cancel()
        restartVoiceJob = null
        restartVoiceGuard = false
    }

    fun prepareFreshRecognizerForNextTurn(autoRestart: Boolean = false) {
        cancelRestartVoiceLoop()
        listenSessionId += 1
        isListening = false
        micRms = 0f
        recognizerErrorCount = 0
        try { recognizer.stopListening() } catch (_: Exception) {}
        try { recognizer.cancel() } catch (_: Exception) {}
        try { recognizer.destroy() } catch (_: Exception) {}
        recognizerEpoch += 1
        if (autoRestart && voiceLoop) {
            needsVoiceRecognizerRestart = true
        }
    }

    fun rebuildRecognizerSoft() {
        scope.launch {
            delay(120)
            prepareFreshRecognizerForNextTurn(autoRestart = false)
        }
    }

    fun clearTransientUiState() {
        requestId += 1
        streamingText = ""
        isThinking = false
        isAnalyzing = false
        isExecutingAction = false
        KaiBubbleManager.releaseAllSuppression()
    }

    fun softAgentRefreshAfterRun() {
        clearTransientUiState()
        requestFreshDump(140L)
        requestFreshDump(380L)
    }

    fun startNewChat() {
        currentSessionId = System.currentTimeMillis()
        currentSessionTitle = "New Chat"
        messages = emptyList()
        clearTransientUiState()
        scope.launch(Dispatchers.IO) {
            chatRepo.upsertSession(
                sessionId = remoteSessionId(),
                title = currentSessionTitle,
                summary = ""
            )
        }
    }

    fun stopAgentLoop(notify: Boolean = false) {
        agentRunToken += 1
        KaiRuntimeLoopCoordinator.cancelLoop(agentLoopEngine)
        agentLoopEngine = null
        clearTransientUiState()
        if (notify) push(MsgRole.SYSTEM, "Agent loop cancelled")
    }

    fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 7)
        }

    fun stopListening() {
        cancelRestartVoiceLoop()
        listenSessionId += 1
        isListening = false
        micRms = 0f
        try { recognizer.stopListening() } catch (_: Exception) {}
        try { recognizer.cancel() } catch (_: Exception) {}
    }

    fun startListening() {
        if (!voiceLoop) return
        if (isListening || isThinking || isAnalyzing || isExecutingAction) return
        if (KaiVoice.speakingNow()) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

        val now = System.currentTimeMillis()
        if (now - lastListenStartAt < 550L) return

        cancelRestartVoiceLoop()
        streamingText = ""
        micRms = 0f
        isListening = true
        lastListenStartAt = now

        listenSessionId += 1
        val myListenSession = listenSessionId

        try { recognizer.cancel() } catch (_: Exception) {}

        scope.launch {
            delay(70)
            if (myListenSession != listenSessionId) return@launch
            try {
                recognizer.startListening(recognizerIntent())
            } catch (_: Exception) {
                if (myListenSession == listenSessionId) {
                    isListening = false
                    recognizerDirty = true
                    prepareFreshRecognizerForNextTurn(autoRestart = true)
                    recognizerDirty = false
                }
            }
        }
    }

    fun resetVoiceRecognizerAfterAgentLoop() {
        scope.launch {
            stopListening()
            recognizerErrorCount = 0
            try { recognizer.destroy() } catch (_: Exception) {}
            recognizerEpoch += 1
            delay(300)
            needsVoiceRecognizerRestart = true
        }
    }

    fun restartVoiceLoop(delayMs: Long = 520L) {
        if (!voiceLoop) return
        if (restartVoiceGuard) return
        restartVoiceGuard = true

        cancelRestartVoiceLoop()
        restartVoiceJob = scope.launch {
            try {
                delay(delayMs)
                if (voiceLoop && !isListening && !KaiVoice.speakingNow() &&
                    !isAnalyzing && !isExecutingAction && !isThinking) {
                    startListening()
                }
            } finally {
                restartVoiceGuard = false
            }
        }
    }

    fun restartListeningAfterTts(delayMs: Long = 520L) {
        if (!voiceLoop) return
        if (isListening || isThinking || isAnalyzing || isExecutingAction) return
        if (!KaiVoice.speechFullyCompleted()) return

        if (recognizerDirty) {
            prepareFreshRecognizerForNextTurn(autoRestart = true)
            recognizerDirty = false
        } else {
            restartVoiceLoop(delayMs)
        }
    }

    fun attemptHandoffAfterTts() {
        if (voiceLoop && !isListening && KaiVoice.speechFullyCompleted() &&
            !isAnalyzing && !isExecutingAction && !isThinking) {
            restartListeningAfterTts(520L)
        }
    }

    LaunchedEffect(needsVoiceRecognizerRestart, voiceLoop, isAnalyzing, isExecutingAction, isThinking) {
        if (needsVoiceRecognizerRestart && voiceLoop && KaiVoice.speechFullyCompleted() &&
            !isAnalyzing && !isExecutingAction && !isThinking) {
            needsVoiceRecognizerRestart = false
            startListening()
        }
    }

    fun saveMemoryDirect(rawText: String, value: String) {
        val cleanValue = value.trim().ifBlank { rawText.trim() }
        if (cleanValue.isBlank()) return

        scope.launch(Dispatchers.IO) {
            val ok = memoryRepo.upsertMemory(
                category = "user_memory",
                key = "mem_${System.currentTimeMillis()}",
                value = cleanValue,
                source = "home_voice",
                importance = 7
            )
            withContext(Dispatchers.Main) {
                push(MsgRole.SYSTEM, if (ok) "Memory saved: $cleanValue" else "Memory save failed")
                if (voiceLoop && !KaiVoice.speakingNow()) restartVoiceLoop(520L)
            }
        }
    }

    fun hardStopSpeech() {
        speechSessionId += 1
        muteSpeakUntilMs = System.currentTimeMillis() + 1200L
        KaiVoice.stop()
        OpenAIClient.cancelActiveStream()
    }

    fun performSoftReset(notify: Boolean = true) {
        cancelRestartVoiceLoop()
        hardStopSpeech()
        stopListening()
        try { agentLoopEngine?.cancel() } catch (_: Exception) {}
        agentLoopEngine = null
        KaiAgentController.finishActionLoopSession("Manual soft reset")
        KaiBubbleManager.releaseAllSuppression()
        KaiBubbleManager.softResetUiState()
        clearTransientUiState()
        recognizerDirty = true
        requestFreshDump(120L)
        requestFreshDump(360L)
        if (notify) push(MsgRole.SYSTEM, "Soft reset completed")
        if (voiceLoop && !KaiVoice.speakingNow()) restartVoiceLoop(650L)
    }

    fun stopAll() {
        voiceLoop = false
        stopListening()
        stopAgentLoop()
        hardStopSpeech()
        push(MsgRole.SYSTEM, "Talk stopped")
    }

    fun speakKai(text: String, afterDone: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (now < muteSpeakUntilMs) return

        val mySpeechSession = ++speechSessionId
        if (isListening) stopListening()

        try {
            KaiVoice.speak(
                context = context,
                text = text,
                tone = KaiVoice.Tone.KAI,
                onDone = {
                    if (mySpeechSession != speechSessionId) return@speak
                    afterDone?.invoke()
                    attemptHandoffAfterTts()
                },
                onError = {
                    if (mySpeechSession != speechSessionId) return@speak
                    afterDone?.invoke()
                    attemptHandoffAfterTts()
                }
            )
        } catch (_: Exception) {
        }
    }

    fun askOpenAIStream(text: String, speakAfter: Boolean) {
        val clean = text.trim()
        if (clean.isBlank()) return

        val myId = ++requestId
        isThinking = true
        streamingText = ""
        push(MsgRole.USER, clean)

        OpenAIClient.askStream(
            userText = clean,
            history = recentHistory(),
            onDelta = { delta ->
                scope.launch(Dispatchers.Main) {
                    if (myId != requestId) return@launch
                    streamingText += delta
                }
            },
            onDone = {},
            onFinalText = { finalText ->
                scope.launch(Dispatchers.Main) {
                    if (myId != requestId) return@launch
                    isThinking = false
                    streamingText = ""
                    push(MsgRole.KAI, finalText)
                    if (speakAfter || voiceLoop) speakKai(finalText)
                }
            },
            onError = {
                scope.launch(Dispatchers.Main) {
                    if (myId != requestId) return@launch
                    isThinking = false
                    streamingText = ""
                    push(MsgRole.SYSTEM, "Connection error")
                    restartVoiceLoop(520L)
                }
            }
        )
    }

    fun triggerMakeAction(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank()) return

        cancelRestartVoiceLoop()
        hardStopSpeech()

        if (isListening) {
            listenSessionId += 1
            isListening = false
            try { recognizer.stopListening() } catch (_: Exception) {}
            try { recognizer.cancel() } catch (_: Exception) {}
        }

        stopAgentLoop()

        if (KaiAgentController.isRunning()) {
            push(MsgRole.SYSTEM, "Monitoring carried into action loop")
        }

        val myRunToken = agentRunToken + 1
        agentRunToken = myRunToken

        customPromptText = clean
        isExecutingAction = true
        isAnalyzing = true

        val engine = KaiRuntimeLoopCoordinator.startLoop(
            context = context.applicationContext,
            prompt = clean,
            appendLog = { role, text ->
                when (role) {
                    "system" -> push(MsgRole.SYSTEM, text)
                    "user" -> push(MsgRole.USER, text)
                    else -> push(MsgRole.KAI, text)
                }
            },
            onPhase = { phase ->
                if (myRunToken != agentRunToken) return@startLoop
                when (phase) {
                    KaiRuntimePhase.PLANNING,
                    KaiRuntimePhase.OBSERVING -> {
                        isAnalyzing = true
                        isExecutingAction = false
                    }
                    KaiRuntimePhase.EXECUTING -> {
                        isAnalyzing = false
                        isExecutingAction = true
                    }
                    else -> {
                        isAnalyzing = false
                        isExecutingAction = false
                    }
                }
            },
            onFinished = { result ->
                if (myRunToken != agentRunToken) return@startLoop
                agentLoopEngine = null
                softAgentRefreshAfterRun()
                push(MsgRole.SYSTEM, result.finalMessage)
                if (voiceLoop && !KaiVoice.speakingNow()) restartVoiceLoop(700L)
            }
        )

        agentLoopEngine = engine
    }

    fun toggleTalk() {
        if (isListening || voiceLoop) {
            stopAll()
        } else {
            hardStopSpeech()
            voiceLoop = true
            push(MsgRole.SYSTEM, "Talk started")
            ClickSfx.play(context, 0.14f)
            startListening()
        }
    }

    fun toggleIsland() {
        // FIX: Always close the settings panel first before toggling the bubble.
        // Without this, simultaneous recomposition (panel closing) + overlay window attach
        // caused focus disruption on MIUI devices, making it appear the app was exited.
        settingsOpen = false

        if (!Settings.canDrawOverlays(context)) {
            try {
                val uri = Uri.parse("package:${context.packageName}")
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
            } catch (_: Exception) {
            }
            push(MsgRole.SYSTEM, "Enable overlay permission then try again.")
            return
        }

        if (KaiBubbleManager.isShowing()) {
            KaiBubbleManager.hide(context)
            push(MsgRole.SYSTEM, "Dynamic Island OFF")
        } else {
            KaiBubbleManager.show(context, onReady = {})
            push(MsgRole.SYSTEM, "Dynamic Island ON")
        }
    }

    fun handleParsedCommand(raw: String) {
        when (val cmd = KaiCommandParser.parse(raw)) {
            KaiParsedCommand.Stop -> stopAll()

            KaiParsedCommand.ReadScreen -> {
                push(MsgRole.SYSTEM, "Read was removed from the main flow. Use Custom Prompt.")
            }

            KaiParsedCommand.AnalyzeScreen -> {
                if (customPromptText.isNotBlank()) {
                    triggerMakeAction(customPromptText)
                } else {
                    push(MsgRole.SYSTEM, "Custom Prompt not set. Use Dynamic Island to set prompt.")
                }
            }

            KaiParsedCommand.Report -> {
                push(MsgRole.SYSTEM, "Report feature available in Dynamic Island.")
            }

            KaiParsedCommand.ToggleAgent -> {
                val running = KaiAgentController.toggleContinuousAnalysis(
                    userGoal = "Observe the current screen quietly and build written context.",
                    customPrompt = customPromptText,
                    onRequestDump = { },
                    onInsight = { insight -> push(MsgRole.KAI, insight) }
                )
                push(MsgRole.SYSTEM, if (running) "Agent active" else "Agent off")
                restartVoiceLoop()
            }

            KaiParsedCommand.SoftReset -> {
                performSoftReset(notify = true)
            }

            KaiParsedCommand.Back -> {
                sendKaiCmd(KaiAccessibilityService.CMD_BACK)
                restartVoiceLoop(700L)
            }

            KaiParsedCommand.Home -> {
                sendKaiCmd(KaiAccessibilityService.CMD_HOME)
                restartVoiceLoop(700L)
            }

            KaiParsedCommand.Recents -> {
                sendKaiCmd(KaiAccessibilityService.CMD_RECENTS)
                restartVoiceLoop(700L)
            }

            is KaiParsedCommand.Scroll -> {
                sendKaiCmd(KaiAccessibilityService.CMD_SCROLL, dir = cmd.dir, times = cmd.times)
                restartVoiceLoop(720L)
            }

            is KaiParsedCommand.Click -> {
                if (cmd.target.isNotBlank()) {
                    push(MsgRole.USER, raw)
                    sendKaiCmd(KaiAccessibilityService.CMD_CLICK_TEXT, text = cmd.target)
                    restartVoiceLoop(720L)
                } else {
                    askOpenAIStream(raw, speakAfter = true)
                }
            }

            is KaiParsedCommand.TypeText -> {
                if (cmd.text.isNotBlank()) {
                    push(MsgRole.USER, raw)
                    sendKaiCmd(KaiAccessibilityService.CMD_TYPE, text = cmd.text)
                    restartVoiceLoop(720L)
                } else {
                    askOpenAIStream(raw, speakAfter = true)
                }
            }

            is KaiParsedCommand.OpenApp -> {
                push(MsgRole.USER, raw)
                triggerMakeAction("open ${cmd.appName}".trim())
            }

            is KaiParsedCommand.SaveMemory -> {
                push(MsgRole.USER, raw)
                saveMemoryDirect(cmd.rawText, cmd.value)
            }

            is KaiParsedCommand.Ask -> {
                if (cmd.text.isBlank()) restartVoiceLoop(520L)
                else askOpenAIStream(cmd.text, speakAfter = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (hasInitialized) return@LaunchedEffect
        hasInitialized = true
        if (loadSessionId == null) startNewChat()
    }

    LaunchedEffect(loadSessionId) {
        val target = loadSessionId ?: return@LaunchedEffect
        val loaded = KaiChatHistoryStore.loadSession(context, target) ?: return@LaunchedEffect
        currentSessionId = loaded.id
        currentSessionTitle = loaded.title
        messages = loaded.messages.map {
            ChatMsg(
                id = nextMsgId(),
                role = when (it.role) {
                    "user" -> MsgRole.USER
                    "assistant" -> MsgRole.KAI
                    else -> MsgRole.SYSTEM
                },
                text = it.text
            )
        }
        delay(80)
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    LaunchedEffect(messages, currentSessionId, currentSessionTitle) {
        if (messages.isNotEmpty()) {
            KaiChatHistoryStore.saveSession(
                context = context,
                id = currentSessionId,
                title = currentSessionTitle,
                messages = messages.map {
                    KaiHistoryMessage(
                        role = when (it.role) {
                            MsgRole.USER -> "user"
                            MsgRole.KAI -> "assistant"
                            MsgRole.SYSTEM -> "system"
                        },
                        text = it.text
                    )
                }
            )
        }
    }

    LaunchedEffect(startMode) {
        when (startMode) {
            "talk" -> {
                voiceLoop = true
                startListening()
            }
            "prompt" -> {
                push(MsgRole.SYSTEM, "Custom Prompt UI moved to Dynamic Island.")
            }
        }
    }

    val listener = remember(recognizerEpoch) {
        object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                micRms = 0f
                recognizerErrorCount = 0
                val candidates = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val raw = KaiCommandParser.pickBestRecognitionCandidate(candidates)
                handleParsedCommand(raw)
                recognizerDirty = true
            }

            override fun onError(error: Int) {
                isListening = false
                micRms = 0f
                recognizerErrorCount = (recognizerErrorCount + 1).coerceAtMost(10)
                if (shouldRebuildRecognizerOnError(error)) {
                    recognizerDirty = true
                    if (voiceLoop && !KaiVoice.speakingNow() && !isAnalyzing && !isExecutingAction) {
                        prepareFreshRecognizerForNextTurn(autoRestart = true)
                        recognizerDirty = false
                    }
                    recognizerErrorCount = 0
                } else if (voiceLoop && !KaiVoice.speakingNow() && !isAnalyzing && !isExecutingAction) {
                    restartVoiceLoop(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 650L
                            else -> 850L
                        }
                    )
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                micRms = (rmsdB / 10f).coerceIn(0f, 1f)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(listener)
        onDispose {
            try { recognizer.destroy() } catch (_: Exception) {}
        }
    }

    DisposableEffect(Unit) {
        // FIX: dumpReceiver body was empty — removed it entirely and replaced with a no-op
        // registration only. The runtime bridge in KaiAgentController is the sole
        // authoritative observation owner; KaiHomeScreen does not need to mirror dumps.
        val appendReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != ACTION_KAI_APPEND_CHAT) return
                val text = i.getStringExtra(EXTRA_APPEND_TEXT).orEmpty()
                val role = i.getStringExtra(EXTRA_APPEND_ROLE).orEmpty()
                when (role) {
                    "system" -> push(MsgRole.SYSTEM, text)
                    "user" -> push(MsgRole.USER, text)
                    else -> push(MsgRole.KAI, text)
                }
            }
        }

        val appendFilter = IntentFilter(ACTION_KAI_APPEND_CHAT)

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(appendReceiver, appendFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(appendReceiver, appendFilter)
        }

        onDispose {
            try { context.unregisterReceiver(appendReceiver) } catch (_: Exception) {}
            try { agentLoopEngine?.cancel() } catch (_: Exception) {}
            KaiBubbleManager.releaseAllSuppression()
        }
    }

    val bgTop = Color(0xFF05060B)
    val bgBottom = Color(0xFF091222)
    val auroraA = Color(0xFF56F0A6)
    val auroraB = Color(0xFF28D7C7)
    val auroraC = Color(0xFF7CF7D0)
    val textPrimary = Color.White
    val textMuted = Color(0xFF9AA4B2)
    val panelShape = RoundedCornerShape(22.dp)

    val infinite = rememberInfiniteTransition(label = "kai_anim")
    val pulse by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(9500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val reactive = if (isListening) (0.35f + micRms) else 0f
    val glow = when {
        isListening -> reactive.coerceIn(0.45f, 1.2f)
        isThinking -> 0.95f
        isExecutingAction -> 1.08f
        isAnalyzing -> 1.0f
        else -> (0.75f + 0.35f * pulse)
    }

    @Composable
    fun CircleIconButton(
        icon: ImageVector? = null,
        tint: Color = Color.White,
        onClick: () -> Unit,
        playSound: Boolean = true
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xFF0B0B12).copy(alpha = 0.64f))
                .border(1.dp, auroraB.copy(alpha = 0.48f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (playSound) ClickSfx.play(context, 0.14f)
                            onClick()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.aurora_bg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.94f
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(bgTop.copy(alpha = 0.72f), bgBottom.copy(alpha = 0.92f))
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.38f)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                auroraB.copy(alpha = 0.22f),
                                auroraA.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            center = Offset(
                                x = 160f + (900f * ((kotlin.math.cos(phase) + 1f) * 0.5f)),
                                y = 520f + (140f * kotlin.math.sin(phase * 0.7f))
                            ),
                            radius = 1200f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kai OS",
                        color = textPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Copy Log",
                            color = auroraB.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .pointerInput(messages, streamingText) {
                                    detectTapGestures(
                                        onTap = {
                                            val copied = buildString {
                                                messages.takeLast(180).forEach { msg ->
                                                    append(
                                                        when (msg.role) {
                                                            MsgRole.SYSTEM -> "[system] "
                                                            MsgRole.USER -> "[user] "
                                                            MsgRole.KAI -> "[kai] "
                                                        }
                                                    )
                                                    append(msg.text)
                                                    append('\n')
                                                }
                                                if (isThinking && streamingText.isNotBlank()) {
                                                    append("[kai-stream] ")
                                                    append(streamingText)
                                                }
                                            }.trim()
                                            if (copied.isNotBlank()) {
                                                clipboard.setText(AnnotatedString(copied))
                                                push(MsgRole.SYSTEM, "Copied recent log safely")
                                            }
                                        }
                                    )
                                }
                        )

                        CircleIconButton(
                            icon = Icons.Filled.Menu,
                            onClick = { settingsOpen = !settingsOpen }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = when {
                        isExecutingAction -> "Agent executing…"
                        isAnalyzing -> "Agent planning…"
                        isThinking -> "Thinking…"
                        voiceLoop && isListening -> "Listening"
                        voiceLoop -> "Talk mode"
                        else -> currentSessionTitle
                    },
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .width(180.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    auroraB.copy(alpha = 0.28f * glow),
                                    auroraC.copy(alpha = 0.22f * glow),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(panelShape)
                        .background(Color(0xFF070E18).copy(alpha = 0.82f))
                        .border(1.dp, auroraB.copy(alpha = 0.14f), panelShape)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            val (bgColor, textColor, align) = when (msg.role) {
                                MsgRole.USER -> Triple(
                                    auroraB.copy(alpha = 0.18f),
                                    Color.White,
                                    Alignment.CenterEnd
                                )
                                MsgRole.KAI -> Triple(
                                    Color(0xFF0C1A28).copy(alpha = 0.92f),
                                    auroraC.copy(alpha = 0.96f),
                                    Alignment.CenterStart
                                )
                                MsgRole.SYSTEM -> Triple(
                                    Color(0xFF0A1520).copy(alpha = 0.74f),
                                    textMuted,
                                    Alignment.CenterStart
                                )
                            }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .align(align)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(bgColor)
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        color = textColor,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        if (isThinking && streamingText.isNotBlank()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color(0xFF0C1A28).copy(alpha = 0.92f))
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = streamingText,
                                            color = auroraC.copy(alpha = 0.96f),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Ask Kai…",
                                color = Color.White.copy(alpha = 0.32f),
                                fontSize = 13.sp
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF0B0B12).copy(alpha = 0.72f),
                            unfocusedContainerColor = Color(0xFF0B0B12).copy(alpha = 0.58f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = auroraB.copy(alpha = 0.52f),
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = auroraB
                        ),
                        singleLine = true,
                        enabled = !isThinking && !isExecutingAction
                    )

                    CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.Send,
                        tint = auroraB,
                        onClick = {
                            val q = chatInput.trim()
                            if (q.isNotBlank()) {
                                chatInput = ""
                                askOpenAIStream(q, speakAfter = false)
                            }
                        }
                    )

                    CircleIconButton(
                        icon = if (voiceLoop || isListening) Icons.Filled.Close else Icons.Filled.Mic,
                        tint = if (voiceLoop || isListening) Color(0xFFFF6B6B) else auroraA,
                        onClick = { toggleTalk() }
                    )
                }
            }

            if (settingsOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.22f))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { settingsOpen = false })
                        }
                )
            }

            AnimatedVisibility(
                visible = settingsOpen,
                enter = slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(280)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(220)
                ),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .background(Color(0xFF071018).copy(alpha = 0.985f))
                            .border(1.dp, auroraB.copy(alpha = 0.26f))
                            .padding(horizontal = 18.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.aurora_eye),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Kai OS",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = "Control panel",
                            color = Color.White.copy(alpha = 0.62f),
                            style = MaterialTheme.typography.labelMedium
                        )

                        Spacer(Modifier.height(6.dp))

                        SettingsRow("Presence") {
                            settingsOpen = false
                            onEyeTap()
                        }

                        SettingsRow(
                            if (KaiBubbleManager.isShowing()) "Dynamic Island · On"
                            else "Dynamic Island · Off"
                        ) {
                            toggleIsland()
                        }

                        SettingsRow("New Chat") {
                            settingsOpen = false
                            startNewChat()
                        }

                        SettingsRow("History") {
                            settingsOpen = false
                            onOpenHistory()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0C1622).copy(alpha = 0.94f))
            .border(1.dp, Color(0x3328D7C7), RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, color = Color.White)
    }
}
