package com.example.reply.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.reply.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

private enum class PresenceState {
    GREETING,
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    DEFENSE
}

@Composable
fun PresenceScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    SideEffect {
        try {
            view.isSoundEffectsEnabled = false
            view.rootView?.isSoundEffectsEnabled = false
        } catch (_: Exception) {
        }
    }

    var state by remember { mutableStateOf(PresenceState.GREETING) }
    var previewVisible by remember { mutableStateOf(false) }
    var cycle by remember { mutableIntStateOf(0) }
    var defenseUntil by remember { mutableLongStateOf(0L) }
    var isListening by remember { mutableStateOf(false) }
    var micRms by remember { mutableFloatStateOf(0f) }
    var heardText by remember { mutableStateOf("") }
    var openConversationLoop by remember { mutableStateOf(false) }

    val hasCameraPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission.value = granted
        if (!granted) previewVisible = false
    }

    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 7)
            putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
            putExtra(
                "android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES",
                arrayListOf("ar", "en-US", "uk-UA")
            )
        }

    fun stopPresenceListening() {
        isListening = false
        try {
            recognizer.stopListening()
        } catch (_: Exception) {
        }
        try {
            recognizer.cancel()
        } catch (_: Exception) {
        }
    }

    fun startPresenceListening() {
        if (KaiVoice.speakingNow()) return
        isListening = true
        state = PresenceState.LISTENING

        try {
            recognizer.cancel()
            scope.launch {
                delay(70)
                recognizer.startListening(recognizerIntent())
            }
        } catch (_: Exception) {
            isListening = false
            state = PresenceState.IDLE
        }
    }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                micRms = 0f

                val heard = KaiCommandParser.pickBestRecognitionCandidate(
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                ).trim()

                heardText = heard

                if (heard.isBlank()) {
                    state = PresenceState.IDLE
                    if (openConversationLoop) {
                        scope.launch {
                            delay(500)
                            startPresenceListening()
                        }
                    }
                    return
                }

                state = PresenceState.THINKING

                OpenAIClient.askStream(
                    userText = heard,
                    history = emptyList(),
                    onDelta = {},
                    onDone = {},
                    onError = {
                        KaiVoice.speak(
                            context = context,
                            text = "I heard you, but something went wrong.",
                            tone = KaiVoice.Tone.KAI,
                            onStart = { state = PresenceState.SPEAKING },
                            onDone = {
                                state = PresenceState.IDLE
                                if (openConversationLoop) {
                                    scope.launch {
                                        delay(520)
                                        startPresenceListening()
                                    }
                                }
                            },
                            onError = { state = PresenceState.IDLE }
                        )
                    },
                    onFinalText = { finalText ->
                        scope.launch(Dispatchers.Main) {
                            KaiVoice.speak(
                                context = context,
                                text = finalText,
                                tone = KaiVoice.Tone.KAI,
                                onStart = { state = PresenceState.SPEAKING },
                                onDone = {
                                    state = PresenceState.IDLE
                                    if (openConversationLoop) {
                                        scope.launch {
                                            delay(520)
                                            startPresenceListening()
                                        }
                                    }
                                },
                                onError = { state = PresenceState.IDLE }
                            )
                        }
                    }
                )
            }

            override fun onError(error: Int) {
                isListening = false
                micRms = 0f
                if (!KaiVoice.speakingNow()) {
                    state = PresenceState.IDLE
                }
                if (openConversationLoop) {
                    scope.launch {
                        delay(650)
                        startPresenceListening()
                    }
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                micRms = (rmsdB / 10f).coerceIn(0f, 1f)
            }

            override fun onReadyForSpeech(params: Bundle?) {
                state = PresenceState.LISTENING
            }

            override fun onBeginningOfSpeech() {
                state = PresenceState.LISTENING
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        recognizer.setRecognitionListener(listener)

        onDispose {
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
            KaiVoice.stop()
        }
    }

    val infinite = rememberInfiniteTransition(label = "presence_motion")

    val breathe by infinite.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "presence_breathe"
    )

    val drift by infinite.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "presence_drift"
    )

    val sway by infinite.animateFloat(
        initialValue = -1.8f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "presence_sway"
    )

    val auraShift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(9200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "presence_aura_shift"
    )

    val speakPulse by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "presence_speaking_pulse"
    )

    LaunchedEffect(Unit) {
        KaiVoice.speak(
            context = context,
            text = "Hello. What's up?",
            tone = KaiVoice.Tone.KAI,
            onStart = { state = PresenceState.SPEAKING },
            onDone = { state = PresenceState.IDLE },
            onError = { state = PresenceState.IDLE }
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(220)
            val now = System.currentTimeMillis()

            if (defenseUntil > now) {
                state = PresenceState.DEFENSE
                continue
            }

            if (KaiVoice.speakingNow()) {
                state = PresenceState.SPEAKING
                continue
            }

            if (isListening) {
                state = PresenceState.LISTENING
                continue
            }

            if (
                state == PresenceState.GREETING ||
                state == PresenceState.SPEAKING ||
                state == PresenceState.DEFENSE
            ) {
                continue
            }

            cycle = (cycle + 1) % 42
            state = when {
                cycle in 0..18 -> PresenceState.IDLE
                cycle in 19..28 -> PresenceState.LISTENING
                cycle in 29..36 -> PresenceState.THINKING
                else -> PresenceState.IDLE
            }
        }
    }

    val auroraA = Color(0xFF56F0A6)
    val auroraB = Color(0xFF28D7C7)
    val auroraC = Color(0xFF7CF7D0)
    val defense = Color(0xFFFF5C6C)
    val bgTop = Color(0xFF03050A)
    val bgBottom = Color(0xFF091222)

    val stateColor = when (state) {
        PresenceState.GREETING -> auroraC
        PresenceState.IDLE -> auroraB
        PresenceState.LISTENING -> auroraA
        PresenceState.THINKING -> Color(0xFF8EF7E0)
        PresenceState.SPEAKING -> Color(0xFFA3FFE7)
        PresenceState.DEFENSE -> defense
    }

    val stateLabel = when (state) {
        PresenceState.GREETING -> "Kai is arriving"
        PresenceState.IDLE -> "Presence ready"
        PresenceState.LISTENING -> "Kai is listening"
        PresenceState.THINKING -> "Kai is thinking"
        PresenceState.SPEAKING -> "Kai is speaking"
        PresenceState.DEFENSE -> "Defense mode"
    }

    val avatarScale by animateFloatAsState(
        targetValue = when (state) {
            PresenceState.SPEAKING -> 1.05f
            PresenceState.LISTENING -> 1.03f
            PresenceState.THINKING -> 1.015f
            PresenceState.DEFENSE -> 1.07f
            else -> 1f
        },
        animationSpec = tween(320),
        label = "presence_avatar_scale"
    )

    val auraAlpha by animateFloatAsState(
        targetValue = when (state) {
            PresenceState.SPEAKING -> 0.82f * speakPulse
            PresenceState.LISTENING -> (0.72f + micRms * 0.25f).coerceAtMost(1f)
            PresenceState.THINKING -> 0.78f
            PresenceState.DEFENSE -> 0.88f
            else -> 0.64f
        },
        animationSpec = tween(260),
        label = "presence_aura_alpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.aurora_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.96f
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            bgTop.copy(alpha = 0.30f),
                            bgBottom.copy(alpha = 0.66f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.44f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            stateColor.copy(alpha = 0.18f),
                            auroraA.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        radius = 1450f
                    )
                )
        )

        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .size(42.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onBack() })
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.size(18.dp))

            Text(
                text = "Kai Presence",
                color = Color.White,
                fontSize = 26.sp
            )

            Spacer(modifier = Modifier.size(8.dp))

            Text(
                text = stateLabel,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp
            )

            if (heardText.isNotBlank()) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = heardText.take(52),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(380.dp)
                    .scale(breathe)
                    .padding(top = if (drift > 0f) drift.dp else 0.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(320.dp)
                        .alpha(auraAlpha)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    stateColor.copy(alpha = 0.22f),
                                    auroraB.copy(alpha = 0.10f),
                                    Color.Transparent
                                ),
                                radius = 900f
                            ),
                            shape = CircleShape
                        )
                )

                Box(
                    modifier = Modifier
                        .padding(top = 232.dp)
                        .width(228.dp)
                        .height(54.dp)
                        .alpha(
                            when (state) {
                                PresenceState.SPEAKING -> 0.80f
                                PresenceState.DEFENSE -> 0.88f
                                else -> 0.64f
                            }
                        )
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    stateColor.copy(alpha = 0.58f),
                                    Color.Transparent
                                ),
                                radius = 300f
                            ),
                            shape = RoundedCornerShape(999.dp)
                        )
                )

                Image(
                    painter = painterResource(id = R.drawable.kai_avatar_cute),
                    contentDescription = null,
                    modifier = Modifier
                        .size(290.dp)
                        .padding(top = 2.dp)
                        .scale(avatarScale)
                        .graphicsLayer {
                            rotationZ = sway
                            translationY =
                                if (state == PresenceState.SPEAKING || state == PresenceState.LISTENING) {
                                    sin(auraShift.toDouble()).toFloat() * 4f
                                } else {
                                    0f
                                }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { startPresenceListening() },
                                onLongPress = {
                                    defenseUntil = System.currentTimeMillis() + 3200L
                                }
                            )
                        },
                    contentScale = ContentScale.Fit
                )

                if (state == PresenceState.THINKING) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 52.dp, end = 34.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0B1624).copy(alpha = 0.88f))
                            .border(
                                1.dp,
                                stateColor.copy(alpha = 0.50f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("Thinking…", color = Color.White, fontSize = 13.sp)
                    }
                }

                if (previewVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 6.dp, top = 24.dp)
                            .size(116.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = 0.36f))
                            .border(
                                1.dp,
                                stateColor.copy(alpha = 0.45f),
                                RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasCameraPermission.value) {
                            CameraPreviewSurface(
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "No camera\npermission",
                                color = Color.White.copy(alpha = 0.82f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (isListening) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 34.dp)
                            .size(54.dp)
                            .shadow(10.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color(0xFF08111A).copy(alpha = 0.88f))
                            .border(
                                1.dp,
                                auroraA.copy(alpha = 0.72f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🎤",
                            color = auroraC,
                            fontSize = 22.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PresenceChip(
                    text = if (openConversationLoop) "Stop Loop" else "Talk Loop",
                    icon = {
                        Text(
                            text = "🎤",
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                ) {
                    openConversationLoop = !openConversationLoop
                    if (openConversationLoop) {
                        startPresenceListening()
                    } else {
                        stopPresenceListening()
                        state = PresenceState.IDLE
                    }
                }

                PresenceChip(
                    text = "Preview",
                    icon = {
                        Text(
                            text = "👁",
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                ) {
                    if (!previewVisible) {
                        if (!hasCameraPermission.value) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        if (hasCameraPermission.value) {
                            previewVisible = true
                        }
                    } else {
                        previewVisible = false
                    }
                }
            }

            Spacer(modifier = Modifier.size(10.dp))
        }
    }
}

@Composable
private fun PresenceChip(
    text: String,
    icon: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF0B0F14).copy(alpha = 0.56f))
            .border(
                1.dp,
                Color(0xFF38F2D8).copy(alpha = 0.34f),
                RoundedCornerShape(50)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onClick() }
                )
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon?.invoke()
        Text(text = text, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun CameraPreviewSurface(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                bindCameraUseCase(context, lifecycleOwner, this)
            }
        },
        update = { previewView ->
            bindCameraUseCase(context, lifecycleOwner, previewView)
        }
    )
}

private fun bindCameraUseCase(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener(
        {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val selector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview
                )
            } catch (_: Exception) {
            }
        },
        ContextCompat.getMainExecutor(context)
    )
}