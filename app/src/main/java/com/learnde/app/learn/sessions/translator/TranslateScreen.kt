// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslateScreen.kt
package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.learn.core.TranslationPair
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════
//  PREMIUM PALETTE — Gemini Minimal Style
// ═══════════════════════════════════════════════════════════

private object GeminiPalette {
    val Background          = Color(0xFFF8FAFC) // Очень мягкий, почти белый синеватый фон
    val CardBackground      = Color(0xFFFFFFFF)
    
    val BrandBlue           = Color(0xFF1A73E8)
    val BrandCyan           = Color(0xFF12B5CB)
    val BrandPurple         = Color(0xFF9334E6)
    val BrandBlueSoft       = Color(0xFFE8F0FE)

    val TextPrimary         = Color(0xFF1E293B) // Глубокий сланцевый (Slate 800)
    val TextSecondary       = Color(0xFF64748B) // Мягкий серый (Slate 500)
    val TextMuted           = Color(0xFF94A3B8) // Slate 400

    val BorderLight         = Color(0xFFE2E8F0) // Slate 200
    val Divider             = Color(0xFFF1F5F9) // Slate 100

    val StatusListening     = BrandBlue
    val StatusSpeaking      = BrandPurple
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

            // Нижняя панель: Визуализатор + Кнопка
            BottomControlPanel(
                isActive = isActive,
                isAiSpeaking = learnState.isAiSpeaking,
                isMicActive = learnState.isMicActive,
                audioFlow = learnCoreViewModel.audioPlaybackFlow,
                onToggleMic = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isActive) {
                        learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                    } else {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
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
            .padding(horizontal = 8.dp, vertical = 12.dp),
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
                "Переводчик",
                fontSize = 20.sp,
                fontWeight = FontWeight.W600,
                color = GeminiPalette.TextPrimary,
                letterSpacing = (-0.5).sp,
            )

            val statusText = when {
                isActive && isAiSpeaking -> "Нейросеть говорит..."
                isActive && isMicActive -> "Слушаю вас..."
                isActive -> "Готов к переводу"
                else -> "Нажмите микрофон для старта"
            }
            
            AnimatedContent(
                targetState = statusText,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "statusText"
            ) { text ->
                Text(
                    text,
                    fontSize = 13.sp,
                    color = GeminiPalette.TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Элегантный бейдж языков
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .border(1.dp, GeminiPalette.BorderLight, RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .shadow(elevation = 2.dp, shape = RoundedCornerShape(24.dp), spotColor = Color(0x0D000000)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Русский", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GeminiPalette.TextPrimary)
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, // Можно заменить на иконку обмена
                    contentDescription = null,
                    tint = GeminiPalette.BrandCyan,
                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f }
                )
                Text("Deutsch", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GeminiPalette.TextPrimary)
            }

            Spacer(Modifier.height(8.dp))

            Text(
                if (isActive) "Говорите на любом языке" else "Синхронный перевод",
                fontSize = 22.sp,
                fontWeight = FontWeight.W600,
                color = GeminiPalette.TextPrimary,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )
            Text(
                if (isActive) "Нейросеть автоматически определит язык\nи мгновенно переведет фразу."
                else "Нажмите на микрофон внизу,\nчтобы начать общение.",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = GeminiPalette.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  СПИСОК ПАР (ЧАТ)
// ═══════════════════════════════════════════════════════════

@Composable
private fun PairsList(pairs: List<TranslationPair>) {
    val listState = rememberLazyListState()

    LaunchedEffect(pairs.size, pairs.lastOrNull()?.translationText?.length) {
        if (pairs.isNotEmpty()) {
            listState.animateScrollToItem(pairs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(pairs, key = { it.id }) { pair ->
            PairCard(pair = pair)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  КАРТОЧКА ПАРЫ (ПРЕМИУМ ДИЗАЙН)
// ═══════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════
//  КАРТОЧКА ПАРЫ (ПРЕМИУМ ДИЗАЙН И НОВАЯ ЛОГИКА ГАЛОЧЕК ✓✓)
// ═══════════════════════════════════════════════════════════

@Composable
private fun PairCard(pair: TranslationPair) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = Color(0x1A000000),
                ambientColor = Color(0x0A000000)
            )
            .clip(RoundedCornerShape(24.dp))
            .background(GeminiPalette.CardBackground)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Оригинал (Ввод Микрофоном / Локальный Vosk)
        TranscriptBlock(
            text = pair.originalText,
            lang = pair.originalLang,
            isOriginal = true,
            isFinal = pair.originalIsFinal,
            isRefined = pair.originalIsRefined // <- ВНИМАНИЕ ПРОКИДЫВАЕМ
        )

        // Разделитель
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GeminiPalette.Divider),
        )

        // Перевод (Live Web Socket -> Model Text + Flash)
        TranscriptBlock(
            text = pair.translationText,
            lang = pair.translationLang,
            isOriginal = false,
            isFinal = pair.translationIsFinal,
            isRefined = pair.translationIsRefined // <- ВНИМАНИЕ ПРОКИДЫВАЕМ
        )
    }
}

@Composable
private fun TranscriptBlock(
    text: String,
    lang: String,
    isOriginal: Boolean,
    isFinal: Boolean, 
    isRefined: Boolean 
) {
    Column(modifier = Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween // Разделяет статус с галочкой на разные концы карточки
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = if (isOriginal) GeminiPalette.TextMuted else GeminiPalette.BrandBlue
                Box(
                    modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor),
                )
                Spacer(Modifier.width(8.dp))

                val label = buildString {
                    append(if (isOriginal) "Распознано" else "Перевод")
                    if (lang.isNotEmpty()) append(" · $lang")
                }
                
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GeminiPalette.TextSecondary,
                    letterSpacing = 0.5.sp,
                )
            }
            
            // ПРОРИСОВКА ВЫЗОВ ЭФФЕКТОВ: ✓ ИЛИ ✓✓  
            StatusIndicator(isFinal = isFinal, isRefined = isRefined)
        }

        Spacer(Modifier.height(8.dp))

        if (text.isBlank() && !isFinal) {
            // Профессиональный скелетон-шиммер
            ShimmerPlaceholder()
        } else {
            // Анимация бесшовной смены текста от Арбитра!
            Text(
                text = text,
                fontSize = if (isOriginal) 16.sp else 18.sp,
                lineHeight = if (isOriginal) 24.sp else 26.sp,
                color = if (isOriginal) GeminiPalette.TextSecondary else GeminiPalette.TextPrimary,
                fontWeight = if (isOriginal) FontWeight.Normal else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusIndicator(isFinal: Boolean, isRefined: Boolean) {
    val text = when {
        isRefined -> "✓✓"
        isFinal -> "✓"
        else -> "..."
    }
    val color = when {
        isRefined -> Color(0xFF1A73E8) // BrandBlue для финального уточнения
        isFinal -> Color(0xFF4CAF50)   // Зеленый для подтвержденного
        else -> GeminiPalette.TextMuted
    }

    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (scaleIn(animationSpec = spring(dampingRatio = 0.6f)) + fadeIn()) togetherWith fadeOut()
        },
        label = "statusIndicator"
    ) { targetText ->
        Text(
            text = targetText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun ShimmerPlaceholder() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.height(14.dp).fillMaxWidth(0.8f).clip(RoundedCornerShape(4.dp)).background(GeminiPalette.BorderLight.copy(alpha = alpha)))
        Box(modifier = Modifier.height(14.dp).fillMaxWidth(0.5f).clip(RoundedCornerShape(4.dp)).background(GeminiPalette.BorderLight.copy(alpha = alpha)))
    }
}

// ═══════════════════════════════════════════════════════════
//  НИЖНЯЯ ПАНЕЛЬ: ВИЗУАЛИЗАТОР + КНОПКА
// ═══════════════════════════════════════════════════════════

@Composable
private fun BottomControlPanel(
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    audioFlow: Flow<ByteArray>,
    onToggleMic: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp, top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Визуализатор аудио (Gemini Voice Aura)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            contentAlignment = Alignment.Center
        ) {
            // ИСПОЛЬЗУЕМ ПОЛНЫЙ ПУТЬ, чтобы избежать конфликта с ColumnScope
            androidx.compose.animation.AnimatedVisibility(
                visible = isActive,
                enter = fadeIn(tween(500)) + scaleIn(tween(500, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(300)) + scaleOut(tween(300))
            ) {
                GeminiVoiceAura(
                    audioFlow = audioFlow,
                    isAiSpeaking = isAiSpeaking,
                    isMicActive = isMicActive
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Премиальная кнопка микрофона
        MicButton(isActive = isActive, onClick = onToggleMic)
    }
}

// ═══════════════════════════════════════════════════════════
//  GEMINI VOICE AURA (НОВЫЙ ВИЗУАЛИЗАТОР)
// ═══════════════════════════════════════════════════════════

@Composable
private fun GeminiVoiceAura(
    audioFlow: Flow<ByteArray>,
    isAiSpeaking: Boolean,
    isMicActive: Boolean
) {
    var rms by remember { mutableFloatStateOf(0f) }
    var lastDecayNanos by remember { mutableLongStateOf(0L) }

    // Плавное сглаживание RMS
    val animatedRms by animateFloatAsState(
        targetValue = if (isAiSpeaking || isMicActive) rms.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "rmsAnim"
    )

    // Подписка на аудио (только когда говорит ИИ, для микрофона можно добавить свой поток, 
    // но пока симулируем легкое дыхание, если просто слушаем)
    LaunchedEffect(audioFlow, isAiSpeaking) {
        if (isAiSpeaking) {
            audioFlow.collect { pcm ->
                rms = computeRms16(pcm).coerceIn(0f, 1f)
                lastDecayNanos = System.nanoTime()
            }
        }
    }

    // Затухание
    LaunchedEffect(isAiSpeaking) {
        var prevFrame = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prevFrame).coerceAtLeast(0L) / 1_000_000_000f
                prevFrame = now
                if (now - lastDecayNanos > 100_000_000L) {
                    rms = max(0f, rms * Math.pow(0.05, dt.toDouble()).toFloat())
                }
            }
        }
    }

    // Дыхание (idle animation), когда просто слушаем
    val infiniteTransition = rememberInfiniteTransition(label = "idleBreath")
    val breath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    Canvas(modifier = Modifier.size(width = 120.dp, height = 40.dp)) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        
        // 3 пилюли: Синяя, Фиолетовая, Голубая
        val colors = listOf(GeminiPalette.BrandBlue, GeminiPalette.BrandPurple, GeminiPalette.BrandCyan)
        val pillCount = 3
        val spacing = 16.dp.toPx()
        val pillWidth = 12.dp.toPx()
        
        val totalWidth = (pillCount * pillWidth) + ((pillCount - 1) * spacing)
        val startX = (w - totalWidth) / 2f

        for (i in 0 until pillCount) {
            // Смещение фазы для каждой пилюли, чтобы они двигались органично
            val phaseOffset = i * 0.5f
            
            // Базовая высота (точка) + дыхание + реакция на голос
            val idleHeight = pillWidth + (breath * pillWidth * 0.5f)
            val activeHeight = pillWidth + (animatedRms * (h - pillWidth) * (1f - phaseOffset * 0.2f))
            
            // Если ИИ говорит - реагируем на звук. Если слушаем - просто дышим.
            val currentHeight = if (isAiSpeaking) activeHeight else idleHeight
            
            val x = startX + i * (pillWidth + spacing)
            val y = centerY - (currentHeight / 2f)

            drawRoundRect(
                color = colors[i],
                topLeft = Offset(x, y),
                size = Size(pillWidth, currentHeight),
                cornerRadius = CornerRadius(pillWidth / 2f, pillWidth / 2f)
            )
        }
    }
}

private fun computeRms16(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    val n = pcm.size / 2
    var sum = 0.0
    var i = 0
    while (i + 1 < pcm.size) {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt()
        val sample = (hi shl 8) or lo
        val s = if (sample >= 0x8000) sample - 0x10000 else sample
        sum += (s * s).toDouble()
        i += 2
    }
    val rms = sqrt(sum / n) / 32768.0
    return (rms * 3.5).toFloat().coerceIn(0f, 1f) // Усиление для наглядности
}

// ═══════════════════════════════════════════════════════════
//  КНОПКА МИКРОФОНА
// ═══════════════════════════════════════════════════════════

@Composable
private fun MicButton(
    isActive: Boolean,
    onClick: () -> Unit
) {
    val targetColor = if (isActive) Color(0xFFE53935) else GeminiPalette.BrandBlue
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "btnColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
        label = "btnScale"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .shadow(
                elevation = if (isActive) 16.dp else 8.dp,
                shape = CircleShape,
                spotColor = animatedColor.copy(alpha = 0.5f),
                ambientColor = animatedColor.copy(alpha = 0.2f)
            )
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        animatedColor.copy(alpha = 0.9f),
                        animatedColor
                    )
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isActive,
            transitionSpec = {
                (scaleIn(tween(250)) + fadeIn(tween(250))) togetherWith
                    (scaleOut(tween(250)) + fadeOut(tween(250)))
            },
            label = "iconSwap"
        ) { active ->
            Icon(
                if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}