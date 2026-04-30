// ════════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v7.0 — production-ready, multi-language
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslateScreen.kt
//
// УЛУЧШЕНИЯ v7.0 vs v6.0:
//   1. Полная локализация через stringResource (RU/UK/DE)
//   2. UA вместо UK (ISO правильно)
//   3. Убрана белорусская "ў" из украинского детектора
//   4. animateColorAsState для плавного перехода цвета mic кнопки
//   5. Glassmorphism с градиентным бордером для эффекта стекла
//   6. lineHeight явно для всего текста
//   7. Дышащая анимация альфы для live-пузыря
//   8. systemBarsPadding вместо ручного расчёта
//   9. Haptic feedback на mic
//  10. Тонкая зернистость на фоне
//  11. Tutorial-шаги в empty state
//  12. Усиленный shadow под пузырями для глубины
// ════════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.R
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.AudioParticleBox
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

// ═══════════════════════════════════════════════════════════
//  PALETTE — премиум, нейтральная для всех культур
// ═══════════════════════════════════════════════════════════
private object TranslatorPalette {
    val BgTop      = Color(0xFF0B0F1C)
    val BgBottom   = Color(0xFF03060D)

    val AccentIdle    = Color(0xFF4A5570)
    val AccentListen  = Color(0xFF00D4AA)
    val AccentSpeak   = Color(0xFF7B6FF7)
    val AccentDanger  = Color(0xFFFF5470)

    val TextPrimary   = Color(0xFFF2F4FA)
    val TextSecondary = Color(0xFFA8B0C4)
    val TextMuted     = Color(0xFF6B7388)

    val BubbleUserBgTop    = Color(0xFF1E2742).copy(alpha = 0.92f)
    val BubbleUserBgBottom = Color(0xFF161D33).copy(alpha = 0.78f)
    val BubbleModelBgTop   = Color(0xFF1A2138).copy(alpha = 0.92f)
    val BubbleModelBgBottom = Color(0xFF12182A).copy(alpha = 0.78f)
    val BubbleHighlight    = Color(0xFFFFFFFF).copy(alpha = 0.16f)
    val BubbleShade        = Color(0xFFFFFFFF).copy(alpha = 0.02f)

    val NoiseColor = Color(0xFFFFFFFF).copy(alpha = 0.012f)
}

@Composable
fun TranslatorScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
) {
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val isActive = learnState.sessionId == "translator" &&
        learnState.connectionStatus != LearnConnectionStatus.Disconnected

    val activity = context as? android.app.Activity
    var showRationaleDialog by remember { mutableStateOf(false) }
    var rationaleIsPermanent by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
        } else {
            rationaleIsPermanent = activity == null ||
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.RECORD_AUDIO,
                )
            showRationaleDialog = true
        }
    }

    if (showRationaleDialog) {
        com.learnde.app.presentation.learn.components.MicPermissionRationaleDialog(
            showSettingsButton = rationaleIsPermanent,
            onDismiss = { showRationaleDialog = false },
            onRequestAgain = {
                showRationaleDialog = false
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            context = context,
        )
    }

    androidx.activity.compose.BackHandler {
        if (isActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(TranslatorPalette.BgTop, TranslatorPalette.BgBottom)
                )
            )
    ) {
        // Тонкая зернистость для матового эффекта
        NoiseLayer()

        // Hero шарик во весь экран
        HeroParticleBackground(
            playbackSync = learnCoreViewModel.audioPlaybackFlow,
            isActive = isActive,
            isAiSpeaking = learnState.isAiSpeaking,
            isMicActive = learnState.isMicActive,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            TopBar(
                onBack = {
                    if (isActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                    onBack()
                },
                isActive = isActive,
                isAiSpeaking = learnState.isAiSpeaking,
                isMicActive = learnState.isMicActive,
            )

            LanguageIndicator(
                isActive = isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (learnState.transcript.isEmpty() && learnState.liveUserTranscript.isEmpty()) {
                    EmptyHint(
                        isActive = isActive,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    FloatingTranscript(
                        messages = learnState.transcript,
                        liveUserText = learnState.liveUserTranscript,
                        showThinking = isActive
                            && learnState.isMicActive
                            && !learnState.isAiSpeaking
                            && learnState.transcript.lastOrNull()?.role != ConversationMessage.ROLE_USER,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                FloatingMicButton(
                    isActive = isActive,
                    onStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val hasMic = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStop = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                    },
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  NOISE LAYER — едва заметная зернистость для матового эффекта
// ═══════════════════════════════════════════════════════════
@Composable
private fun NoiseLayer() {
    val noisePoints = remember {
        List(2000) {
            Offset(Random.nextFloat(), Random.nextFloat())
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        noisePoints.forEach { p ->
            drawCircle(
                color = TranslatorPalette.NoiseColor,
                radius = 0.6f,
                center = Offset(p.x * w, p.y * h),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  HERO PARTICLE BACKGROUND
// ═══════════════════════════════════════════════════════════
@Composable
private fun HeroParticleBackground(
    playbackSync: Flow<ByteArray>,
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
) {
    val targetSize = when {
        !isActive -> 220.dp
        isAiSpeaking -> 380.dp
        isMicActive -> 320.dp
        else -> 260.dp
    }

    val animatedSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "heroSize",
    )

    val pulseTransition = rememberInfiniteTransition(label = "heroPulse")
    val breathScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroBreath",
    )

    val targetGlowAlpha = when {
        !isActive -> 0.15f
        isAiSpeaking -> 0.55f
        isMicActive -> 0.45f
        else -> 0.25f
    }
    val glowAlpha by animateFloatAsState(
        targetValue = targetGlowAlpha,
        animationSpec = tween(600),
        label = "heroGlow",
    )

    val targetGlowColor = when {
        !isActive -> TranslatorPalette.AccentIdle
        isAiSpeaking -> TranslatorPalette.AccentSpeak
        isMicActive -> TranslatorPalette.AccentListen
        else -> TranslatorPalette.AccentIdle
    }
    val glowColor by animateColorAsState(
        targetValue = targetGlowColor,
        animationSpec = tween(600),
        label = "heroGlowColor",
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(animatedSize * 1.8f)
                .scale(breathScale)
                .blur(80.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = glowAlpha),
                            glowColor.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent,
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .size(animatedSize)
                .scale(breathScale)
                .alpha(if (isActive) 1f else 0.7f),
            contentAlignment = Alignment.Center,
        ) {
            AudioParticleBox(
                playbackSync = playbackSync,
                size = animatedSize,
            )
        }

        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(tween(600)),
            exit = fadeOut(tween(400)),
        ) {
            RotatingRing(
                size = animatedSize + 24.dp,
                color = glowColor,
                isFast = isAiSpeaking || isMicActive,
            )
        }
    }
}

@Composable
private fun RotatingRing(
    size: androidx.compose.ui.unit.Dp,
    color: Color,
    isFast: Boolean,
) {
    val rotation = rememberInfiniteTransition(label = "ringRotation")
    val angle by rotation.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isFast) 6000 else 18000, easing = LinearEasing),
        ),
        label = "ringAngle",
    )

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = angle }
            .clip(CircleShape)
            .border(
                width = 1.dp,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        color.copy(alpha = 0.6f),
                        color.copy(alpha = 0.2f),
                        Color.Transparent,
                        Color.Transparent,
                    )
                ),
                shape = CircleShape,
            )
            .alpha(0.7f),
    )
}

// ═══════════════════════════════════════════════════════════
//  TOP BAR
// ═══════════════════════════════════════════════════════════
@Composable
private fun TopBar(
    onBack: () -> Unit,
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = TranslatorPalette.TextPrimary,
            )
        }

        Spacer(Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.translator_title),
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = TranslatorPalette.TextPrimary,
                letterSpacing = 0.2.sp,
            )

            val statusText = when {
                isActive && isAiSpeaking -> stringResource(R.string.translator_status_speaking)
                isActive && isMicActive -> stringResource(R.string.translator_status_listening)
                isActive -> stringResource(R.string.translator_status_ready)
                else -> stringResource(R.string.translator_status_idle)
            }
            val targetStatusColor = when {
                isActive && isAiSpeaking -> TranslatorPalette.AccentSpeak
                isActive && isMicActive -> TranslatorPalette.AccentListen
                else -> TranslatorPalette.TextMuted
            }
            val statusColor by animateColorAsState(
                targetValue = targetStatusColor,
                animationSpec = tween(400),
                label = "statusColor",
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    statusText,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                )
            }
        }

        Spacer(Modifier.width(8.dp))
    }
}

// ═══════════════════════════════════════════════════════════
//  LANGUAGE INDICATOR
// ═══════════════════════════════════════════════════════════
@Composable
private fun LanguageIndicator(
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.02f),
                    )
                ),
                shape = RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangChip(
            code = stringResource(R.string.translator_lang_codes_user),
            label = stringResource(R.string.translator_lang_user),
        )

        AnimatedDivider(isActive = isActive)

        LangChip(
            code = stringResource(R.string.translator_lang_code_target),
            label = stringResource(R.string.translator_lang_target),
        )
    }
}

@Composable
private fun LangChip(code: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            code,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TranslatorPalette.TextPrimary,
            letterSpacing = 1.2.sp,
        )
        Text(
            label,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = TranslatorPalette.TextMuted,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun AnimatedDivider(isActive: Boolean) {
    val transition = rememberInfiniteTransition(label = "divider")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
        ),
        label = "dividerOffset",
    )

    Row(
        modifier = Modifier.width(80.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(5) { i ->
            val alpha = if (isActive) {
                val phase = (offset + i * 0.2f) % 1f
                (kotlin.math.sin(phase * Math.PI).toFloat() * 0.7f + 0.3f).coerceIn(0.2f, 1f)
            } else 0.3f

            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(TranslatorPalette.TextSecondary.copy(alpha = alpha))
            )
            if (i < 4) Spacer(Modifier.width(3.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  EMPTY STATE — с tutorial-шагами
// ═══════════════════════════════════════════════════════════
@Composable
private fun EmptyHint(
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Tutorial steps — только когда idle
            if (!isActive) {
                TutorialSteps()
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (isActive) stringResource(R.string.translator_hint_active_title)
                    else stringResource(R.string.translator_hint_idle_title),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TranslatorPalette.TextPrimary.copy(alpha = 0.95f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isActive) stringResource(R.string.translator_hint_active_subtitle)
                    else stringResource(R.string.translator_hint_idle_subtitle),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = TranslatorPalette.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TutorialSteps() {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        TutorialStep(num = "1", text = stringResource(R.string.translator_tutorial_step1))
        TutorialStep(num = "2", text = stringResource(R.string.translator_tutorial_step2))
        TutorialStep(num = "3", text = stringResource(R.string.translator_tutorial_step3))
    }
}

@Composable
private fun TutorialStep(num: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                num,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TranslatorPalette.TextSecondary,
            )
        }
        Text(
            text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = TranslatorPalette.TextSecondary,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  FLOATING TRANSCRIPT
// ═══════════════════════════════════════════════════════════
@Composable
private fun FloatingTranscript(
    messages: List<ConversationMessage>,
    liveUserText: String,
    showThinking: Boolean,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text, liveUserText, showThinking) {
        val extra = when {
            liveUserText.isNotEmpty() -> 1
            showThinking -> 1
            else -> 0
        }
        val totalItems = messages.size + extra
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { msg -> "${msg.timestamp}_${msg.role}" }) { msg ->
            FloatingBubble(message = msg, isLive = false)
        }

        if (liveUserText.isNotEmpty()) {
            item(key = "live_user") {
                val liveMsg = ConversationMessage(
                    role = ConversationMessage.ROLE_USER,
                    text = liveUserText,
                    timestamp = System.currentTimeMillis()
                )
                FloatingBubble(message = liveMsg, isLive = true)
            }
        }

        if (showThinking && liveUserText.isEmpty()) {
            item(key = "thinking") {
                ThinkingDots()
            }
        }
    }
}

@Composable
private fun FloatingBubble(message: ConversationMessage, isLive: Boolean) {
    val isUser = message.role == ConversationMessage.ROLE_USER
    val text = message.text.trim()
    if (text.isEmpty()) return

    val lang = detectLang(text)
    val langCode = when (lang) {
        DetectedLang.DE -> "DE"
        DetectedLang.RU -> "RU"
        DetectedLang.UK -> "UA"
        DetectedLang.UNKNOWN -> ""
    }

    val (bgTop, bgBottom) = if (isUser)
        TranslatorPalette.BubbleUserBgTop to TranslatorPalette.BubbleUserBgBottom
    else
        TranslatorPalette.BubbleModelBgTop to TranslatorPalette.BubbleModelBgBottom

    val accentDot = if (isUser) TranslatorPalette.AccentListen else TranslatorPalette.AccentSpeak

    // Дышащая анимация для live-пузыря
    val breathTransition = rememberInfiniteTransition(label = "liveBreath")
    val breathAlpha by breathTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveAlpha",
    )

    val finalAlpha by animateFloatAsState(
        targetValue = if (isLive) breathAlpha else 1f,
        animationSpec = tween(300),
        label = "bubbleAlpha",
    )

    val youSpeakingLabel = stringResource(R.string.translator_bubble_you_speaking)
    val youLabel = stringResource(R.string.translator_bubble_you)
    val translationLabel = stringResource(R.string.translator_bubble_translation)

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isUser) 18.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 18.dp,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(finalAlpha),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(bubbleShape)
                // Glassmorphism: вертикальный градиент имитирует свет сверху
                .background(
                    Brush.verticalGradient(
                        colors = listOf(bgTop, bgBottom)
                    )
                )
                // Двойной градиентный бордер: блик сверху + тень снизу
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleHighlight,
                            TranslatorPalette.BubbleShade,
                        )
                    ),
                    shape = bubbleShape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accentDot)
                )
                Spacer(Modifier.width(6.dp))

                val labelText = when {
                    isLive -> youSpeakingLabel
                    isUser -> if (langCode.isNotEmpty()) "$youLabel · $langCode" else youLabel
                    else -> if (langCode.isNotEmpty()) "$translationLabel · $langCode" else translationLabel
                }

                Text(
                    labelText.uppercase(),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TranslatorPalette.TextSecondary,
                    letterSpacing = 1.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = TranslatorPalette.TextPrimary,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dot1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "d1",
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse),
        label = "d2",
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse),
        label = "d3",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleUserBgTop,
                            TranslatorPalette.BubbleUserBgBottom,
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleHighlight,
                            TranslatorPalette.BubbleShade,
                        )
                    ),
                    shape = RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(dot1, dot2, dot3).forEach { alpha ->
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(TranslatorPalette.AccentListen),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  FLOATING MIC BUTTON
// ═══════════════════════════════════════════════════════════
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun FloatingMicButton(
    isActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val targetColor = if (isActive)
        TranslatorPalette.AccentDanger
    else
        TranslatorPalette.AccentListen

    val animatedButtonColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(400),
        label = "btnColor",
    )

    val pulse = rememberInfiniteTransition(label = "btnPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.18f else 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(1400)),
        label = "pulseAlpha",
    )

    val startCd = stringResource(R.string.cd_mic_start)
    val stopCd = stringResource(R.string.cd_mic_stop)

    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(animatedButtonColor.copy(alpha = pulseAlpha)),
        )
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(animatedButtonColor.copy(alpha = 0.18f)),
        )
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            animatedButtonColor,
                            animatedButtonColor.copy(alpha = 0.85f),
                        )
                    )
                )
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.2f),
                    CircleShape,
                )
                .clickable {
                    if (isActive) onStop() else onStart()
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isActive,
                transitionSpec = {
                    (scaleIn(tween(200)) + fadeIn(tween(200))) togetherWith
                        (scaleOut(tween(200)) + fadeOut(tween(200)))
                },
                label = "iconSwap",
            ) { active ->
                Icon(
                    if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (active) stopCd else startCd,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Language detection — UA fixed, Belarusian removed
// ═══════════════════════════════════════════════════════════
private enum class DetectedLang { DE, RU, UK, UNKNOWN }

// Только украинские специфические буквы. ў/Ў белорусские — удалены.
private val UKR_SPECIFIC_LETTERS = "ієґїІЄҐЇ"

private val UKR_MARKER_WORDS = setOf(
    "що", "щоб", "чому", "як", "де", "коли",
    "ти", "ви", "він", "вона", "ми", "вони",
    "буде", "будеш", "будемо", "будуть", "буду",
    "робити", "робиш", "роблю", "робимо",
    "створювати", "створюєш",
    "розумію", "розумієш", "розуміти",
    "хочу", "хочеш", "хоче", "хочемо",
    "почати", "починати",
    "здобути", "здобуваю",
    "професію", "професія",
    "вчитися", "вчуся", "вчишся", "вчимося",
    "є", "немає", "не", "так", "ні",
    "дуже", "трохи",
    "сьогодні", "завтра", "вчора",
    "привіт", "дякую", "будь",
    "навіщо", "невже",
)

private val GERMAN_FUNCTION_WORDS = setOf(
    "der","die","das","den","dem","des","ein","eine","einen","einem","einer","eines",
    "ich","du","er","sie","es","wir","ihr","mich","dich","ihn","uns","euch","ihnen",
    "und","oder","aber","weil","wenn","dass","ob","sondern","denn","doch",
    "ist","sind","war","waren","habe","hat","haben","wird","werden",
    "nicht","kein","keine","mit","ohne","für","gegen","über","unter","auf","aus",
    "bei","nach","seit","vor","durch","zu","in","an","im","am","ins","ans",
    "ja","nein","auch","schon","noch","mehr","sehr","gut","heute","morgen","gestern",
    "hallo","danke","bitte","tschüss","toll","klasse","super","wunderbar",
    "wie","was","wo","wer","wann","warum","welcher","welche","welches",
    "geht","gehst","gehen","macht","machst","machen",
    "kosten","möglich","ändern","wirklich","funktionieren","jetzt"
)

private fun detectLang(text: String): DetectedLang {
    val cleaned = text.trim()
    if (cleaned.isBlank() || cleaned == "..." || cleaned == "…" || cleaned == "?") {
        return DetectedLang.UNKNOWN
    }

    val hasCyrillic = cleaned.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    val hasUkrSpecific = cleaned.any { it in UKR_SPECIFIC_LETTERS }
    val hasUmlauts = cleaned.any { it in "äöüßÄÖÜ" }
    val hasLatinLetters = cleaned.any { it in 'a'..'z' || it in 'A'..'Z' }

    return when {
        hasUkrSpecific -> DetectedLang.UK
        hasCyrillic -> {
            val cyrillicTokens = cleaned.lowercase()
                .replace(Regex("[^а-яё ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            val ukrHits = cyrillicTokens.count { it in UKR_MARKER_WORDS }
            if (ukrHits > 0) DetectedLang.UK else DetectedLang.RU
        }
        hasUmlauts -> DetectedLang.DE
        hasLatinLetters -> {
            val tokens = cleaned.lowercase()
                .replace(Regex("[^a-zäöüß]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return DetectedLang.UNKNOWN
            val deHits = tokens.count { it in GERMAN_FUNCTION_WORDS }
            if (deHits > 0) DetectedLang.DE else DetectedLang.UNKNOWN
        }
        else -> DetectedLang.UNKNOWN
    }
}