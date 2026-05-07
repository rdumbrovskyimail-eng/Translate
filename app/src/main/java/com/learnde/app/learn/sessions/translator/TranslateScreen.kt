// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslateScreen.kt
package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.TranslationPair
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel

// ═══════════════════════════════════════════════════════════
//  ПАЛИТРА — Gemini light style
// ═══════════════════════════════════════════════════════════

private object GeminiPalette {
    // Backgrounds
    val Background          = Color(0xFFFFFFFF)
    val SurfaceTint         = Color(0xFFF7FAFE)
    val CardBackground      = Color(0xFFFFFFFF)

    // Borders & dividers
    val BorderLight         = Color(0xFFE3EAF5)
    val BorderAccent        = Color(0xFFCFE0FA)
    val Divider             = Color(0xFFEFF3FA)

    // Brand blues
    val BrandBlue           = Color(0xFF1A73E8)
    val BrandBlueLight      = Color(0xFF4285F4)
    val BrandBlueSoft       = Color(0xFFD2E3FC)

    // Text
    val TextPrimary         = Color(0xFF1F1F1F)   // финал Gemini — чёрный
    val TextFinalGray       = Color(0xFF5F6368)   // финал Vosk — тёмно-серый
    val TextPartialGray     = Color(0xFFAAB1BE)   // partial Vosk — светло-серый
    val TextLabel           = Color(0xFF5F6368)
    val TextMuted           = Color(0xFF9AA0A6)

    // Status
    val StatusListening     = Color(0xFF34A853)   // Google green
    val StatusSpeaking      = BrandBlue
    val StatusIdle          = Color(0xFFBDC1C6)
    val Danger              = Color(0xFFEA4335)
}



// ═══════════════════════════════════════════════════════════
//  MAIN SCREEN
// ═══════════════════════════════════════════════════════════

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

    // Пары приходят из state.translatorPairs (формируются в LearnCoreViewModel
    // при получении событий VoskTranscriber).
    val pairs = learnState.translatorPairs

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
            .background(GeminiPalette.Background),
    ) {
        Column(modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        ) {
            // Top stripe (микро-анимация в самом верху, тонкий прямоугольник)
            TopActivityStripe(
                isActive = isActive,
                isAiSpeaking = learnState.isAiSpeaking,
                isMicActive = learnState.isMicActive,
            )

            // Top bar — кнопка назад, заголовок, статус
            TopBar(
                onBack = {
                    if (isActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                    onBack()
                },
                isActive = isActive,
                isAiSpeaking = learnState.isAiSpeaking,
                isMicActive = learnState.isMicActive,
            )

            // Чат / содержимое
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (pairs.isEmpty()) {
                    EmptyState(isActive = isActive)
                } else {
                    PairsList(pairs = pairs)
                }
            }

            // Кнопка микрофона
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp, top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                MicButton(
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
//  ВЕРХНЯЯ ПОЛОСА АКТИВНОСТИ
// ═══════════════════════════════════════════════════════════

@Composable
private fun TopActivityStripe(
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
) {
    val targetColor = when {
        !isActive -> GeminiPalette.BorderLight
        isAiSpeaking -> GeminiPalette.BrandBlue
        isMicActive -> GeminiPalette.StatusListening
        else -> GeminiPalette.BrandBlueSoft
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(500),
        label = "stripeColor",
    )

    val transition = rememberInfiniteTransition(label = "stripeFlow")
    val flow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "stripeFlowOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(GeminiPalette.SurfaceTint),
    ) {
        if (isActive) {
            // Бегущая полоска
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .graphicsLayer { alpha = 0.95f }
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                (flow * 0.4f).coerceIn(0f, 0.99f) to Color.Transparent,
                                (flow * 0.4f + 0.05f).coerceIn(0f, 0.99f) to animatedColor,
                                (flow * 0.4f + 0.25f).coerceIn(0.01f, 1f) to animatedColor,
                                (flow * 0.4f + 0.30f).coerceIn(0.02f, 1f) to Color.Transparent,
                                1f to Color.Transparent,
                            )
                        )
                    ),
            )
        } else {
            // Idle — статичная тонкая полоса
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(animatedColor),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  ВЕРХНЯЯ ПАНЕЛЬ
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = GeminiPalette.TextPrimary,
            )
        }

        Spacer(Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.translator_title),
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeminiPalette.TextPrimary,
                letterSpacing = 0.1.sp,
            )

            val statusText = when {
                isActive && isAiSpeaking -> stringResource(R.string.translator_status_speaking)
                isActive && isMicActive -> stringResource(R.string.translator_status_listening)
                isActive -> stringResource(R.string.translator_status_ready)
                else -> stringResource(R.string.translator_status_idle)
            }
            val targetStatusColor = when {
                isActive && isAiSpeaking -> GeminiPalette.StatusSpeaking
                isActive && isMicActive -> GeminiPalette.StatusListening
                else -> GeminiPalette.TextMuted
            }
            val statusColor by animateColorAsState(
                targetValue = targetStatusColor,
                animationSpec = tween(300),
                label = "statusColor",
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    PulseDot(color = statusColor)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    statusText,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                )
            }
        }

        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun PulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulseDot")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseDotAnim",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(color),
    )
}

// ═══════════════════════════════════════════════════════════
//  EMPTY STATE
// ═══════════════════════════════════════════════════════════

@Composable
private fun EmptyState(isActive: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Иконка-квадрат с лёгким градиентом
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                GeminiPalette.BrandBlueLight,
                                GeminiPalette.BrandBlue,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Text(
                if (isActive)
                    stringResource(R.string.translator_hint_active_title)
                else
                    stringResource(R.string.translator_hint_idle_title),
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeminiPalette.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                if (isActive)
                    stringResource(R.string.translator_hint_active_subtitle)
                else
                    stringResource(R.string.translator_hint_idle_subtitle),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = GeminiPalette.TextLabel,
                textAlign = TextAlign.Center,
            )

            if (!isActive) {
                Spacer(Modifier.height(8.dp))
                LanguageBadge()
            }
        }
    }
}

@Composable
private fun LanguageBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GeminiPalette.SurfaceTint)
            .border(1.dp, GeminiPalette.BorderLight, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "RU",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = GeminiPalette.BrandBlue,
            letterSpacing = 0.8.sp,
        )
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 1.dp)
                .background(GeminiPalette.BorderAccent)
        )
        Text(
            "DE",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = GeminiPalette.BrandBlue,
            letterSpacing = 0.8.sp,
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  СПИСОК ПАР
// ═══════════════════════════════════════════════════════════

@Composable
private fun PairsList(pairs: List<TranslationPair>) {
    val listState = rememberLazyListState()

    LaunchedEffect(pairs.size, pairs.lastOrNull()) {
        if (pairs.isNotEmpty()) {
            listState.animateScrollToItem(pairs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(pairs, key = { it.id }) { pair ->
            PairCard(pair = pair)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  КАРТОЧКА ПАРЫ — оригинал + перевод вместе
// ═══════════════════════════════════════════════════════════

@Composable
private fun PairCard(pair: TranslationPair) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GeminiPalette.CardBackground)
            .border(
                width = 1.dp,
                color = GeminiPalette.BorderAccent,
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Оригинал
        TranscriptLine(
            text = pair.originalText,
            isFinal = pair.originalIsFinal,
            isRefined = pair.originalIsRefined,
            lang = pair.originalLang,
            isOriginal = true,
        )

        // Тонкая разделительная линия
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GeminiPalette.Divider),
        )

        // Перевод
        TranscriptLine(
            text = pair.translationText,
            isFinal = pair.translationIsFinal,
            isRefined = pair.translationIsRefined,
            lang = pair.translationLang,
            isOriginal = false,
        )
    }
}

@Composable
private fun TranscriptLine(
    text: String,
    isFinal: Boolean,
    isRefined: Boolean,
    lang: String,
    isOriginal: Boolean,
) {
    // Цвет текста в три состояния:
    // partial → светло-серый
    // final (Vosk) → серый
    // refined (Gemini REST) → чёрный
    val targetColor = when {
        isRefined -> GeminiPalette.TextPrimary
        isFinal -> GeminiPalette.TextFinalGray
        else -> GeminiPalette.TextPartialGray
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(450),
        label = "lineColor",
    )

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Маленький индикатор: точка + лейбл "Вы" / "Перевод"
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOriginal) GeminiPalette.StatusListening
                        else GeminiPalette.BrandBlue
                    ),
            )
            Spacer(Modifier.width(8.dp))

            val label = buildString {
                append(if (isOriginal) "Вы" else "Перевод")
                if (lang.isNotEmpty()) append(" · $lang")
            }
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeminiPalette.TextLabel,
                letterSpacing = 1.sp,
            )

            // Бейдж "уточнено" — мини-индикатор что Gemini подтвердил
            if (isRefined) {
                Spacer(Modifier.width(8.dp))
                RefinedBadge()
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = if (text.isBlank()) "…" else text,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            color = animatedColor,
            fontWeight = if (isRefined) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun RefinedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(GeminiPalette.BrandBlueSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "AI",
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = GeminiPalette.BrandBlue,
            letterSpacing = 0.8.sp,
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  МИКРОФОННАЯ КНОПКА
// ═══════════════════════════════════════════════════════════

@Composable
private fun MicButton(
    isActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val targetColor = if (isActive) GeminiPalette.Danger else GeminiPalette.BrandBlue
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(350),
        label = "btnColor",
    )

    val pulse = rememberInfiniteTransition(label = "btnPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isActive) 1.15f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.18f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(1300)),
        label = "pulseAlpha",
    )

    val startCd = stringResource(R.string.cd_mic_start)
    val stopCd = stringResource(R.string.cd_mic_stop)

    Box(
        modifier = Modifier.size(86.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Внешняя пульсация
        Box(
            modifier = Modifier
                .size(86.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(animatedColor.copy(alpha = pulseAlpha)),
        )
        // Основа кнопки
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            animatedColor,
                            if (isActive) animatedColor
                            else GeminiPalette.BrandBlueLight,
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    color = Color.White,
                    shape = CircleShape,
                )
                .clickable {
                    if (isActive) onStop() else onStart()
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isActive,
                transitionSpec = {
                    (scaleIn(tween(220)) + fadeIn(tween(220))) togetherWith
                        (scaleOut(tween(220)) + fadeOut(tween(220)))
                },
                label = "iconSwap",
            ) { active ->
                Icon(
                    if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (active) stopCd else startCd,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}