package com.translator.app.presentation.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.sqrt

private object GeminiPalette {
    val Background          = Color(0xFFF8FAFC)
    val CardBackground      = Color(0xFFFFFFFF)
    val BrandBlue           = Color(0xFF1A73E8)
    val BrandCyan           = Color(0xFF12B5CB)
    val BrandPurple         = Color(0xFF9334E6)
    val TextPrimary         = Color(0xFF1E293B)
    val TextSecondary       = Color(0xFF64748B)
    val TextMuted           = Color(0xFF94A3B8)
    val BorderLight         = Color(0xFFE2E8F0)
    val Divider             = Color(0xFFF1F5F9)
}

@Composable
fun TranslateScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onBack: () -> Unit,
    viewModel: TranslatorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val isActive = state.connectionStatus != ConnectionStatus.Disconnected

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            // permission получен — стартуем сессию (или, если уже подключены, мик)
            if (state.connectionStatus == ConnectionStatus.Disconnected) {
                viewModel.startSession()
            } else {
                viewModel.onMicPermissionGranted()
            }
        }
    }

    // КРИТИЧНО: запрашиваем permission ДО startSession.
    // Если запустить сессию без RECORD_AUDIO, AudioRecord падает с UOE
    // на первой же попытке startCapture.
    LaunchedEffect(Unit) {
        if (!isActive) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.startSession()
            } else {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    androidx.activity.compose.BackHandler {
        viewModel.stopSession()
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(GeminiPalette.Background)) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(
                onBack = { viewModel.stopSession(); onBack() },
                onSettings = onNavigateToSettings,
                onLogs = onNavigateToLogs,
                isActive = isActive,
                isAiSpeaking = state.isAiSpeaking,
                isMicActive = state.isMicActive,
                connectionStatus = state.connectionStatus,
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (state.pairs.isEmpty()) {
                    EmptyState(isActive = isActive)
                } else {
                    PairsList(pairs = state.pairs)
                }
            }

            BottomControlPanel(
                isActive = isActive,
                isAiSpeaking = state.isAiSpeaking,
                isMicActive = state.isMicActive,
                audioFlow = viewModel.audioPlaybackFlow,
                onToggleMic = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.toggleMic()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onLogs: () -> Unit,
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    connectionStatus: ConnectionStatus
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = GeminiPalette.TextPrimary)
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Gemini Translate", fontSize = 20.sp, fontWeight = FontWeight.W600, color = GeminiPalette.BrandBlue)
            val statusText = when {
                connectionStatus == ConnectionStatus.Reconnecting -> "Переподключение..."
                connectionStatus == ConnectionStatus.Connecting -> "Подключение..."
                isActive && isAiSpeaking -> "Нейросеть говорит..."
                isActive && isMicActive -> "Слушаю вас..."
                isActive -> "Готов к переводу"
                else -> "Отключено"
            }
            AnimatedContent(targetState = statusText, label = "statusText") { text ->
                Text(text, fontSize = 13.sp, color = GeminiPalette.TextSecondary, fontWeight = FontWeight.Medium)
            }
        }
        IconButton(onClick = onLogs) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Логи", tint = GeminiPalette.TextPrimary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = GeminiPalette.TextPrimary)
        }
    }
}

@Composable
private fun EmptyState(isActive: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).border(1.dp, GeminiPalette.BorderLight, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 10.dp).shadow(2.dp, RoundedCornerShape(24.dp)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Русский", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GeminiPalette.TextPrimary)
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = GeminiPalette.BrandCyan, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f })
                Text("Deutsch", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GeminiPalette.TextPrimary)
            }
            Text(if (isActive) "Говорите на любом языке" else "Синхронный перевод", fontSize = 22.sp, fontWeight = FontWeight.W600, color = GeminiPalette.TextPrimary, textAlign = TextAlign.Center)
            Text(if (isActive) "Нейросеть автоматически определит язык\nи мгновенно переведет фразу." else "Подключение к серверу...", fontSize = 15.sp, color = GeminiPalette.TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PairsList(pairs: List<TranslationPair>) {
    val listState = rememberLazyListState()
    val lastPair = pairs.lastOrNull()

    LaunchedEffect(pairs.size, lastPair?.translationText?.length, lastPair?.originalText?.length) {
        if (pairs.isNotEmpty()) listState.animateScrollToItem(pairs.size - 1)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(pairs, key = { it.id }) { pair -> PairCard(pair) }
    }
}

@Composable
private fun PairCard(pair: TranslationPair) {
    Column(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(24.dp), spotColor = Color(0x1A000000)).clip(RoundedCornerShape(24.dp)).background(GeminiPalette.CardBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TranscriptBlock(text = pair.originalText, lang = pair.originalLang, isOriginal = true, isFinal = pair.originalIsFinal, isRefined = pair.originalIsRefined)
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GeminiPalette.Divider))
        TranscriptBlock(text = pair.translationText, lang = pair.translationLang, isOriginal = false, isFinal = pair.translationIsFinal, isRefined = pair.translationIsRefined)
    }
}

@Composable
private fun TranscriptBlock(text: String, lang: String, isOriginal: Boolean, isFinal: Boolean, isRefined: Boolean) {
    Column(modifier = Modifier.animateContentSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isOriginal) GeminiPalette.TextMuted else GeminiPalette.BrandBlue))
                Spacer(Modifier.width(8.dp))
                Text(buildString { append(if (isOriginal) "Распознано" else "Перевод"); if (lang.isNotEmpty()) append(" · $lang") }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GeminiPalette.TextSecondary)
            }
            StatusIndicator(isFinal = isFinal, isRefined = isRefined)
        }
        Spacer(Modifier.height(8.dp))
        if (text.isBlank() && !isFinal) {
            ShimmerPlaceholder()
        } else {
            Text(text = text, fontSize = if (isOriginal) 16.sp else 18.sp, color = if (isOriginal) GeminiPalette.TextSecondary else GeminiPalette.TextPrimary, fontWeight = if (isOriginal) FontWeight.Normal else FontWeight.Medium)
        }
    }
}

@Composable
private fun StatusIndicator(isFinal: Boolean, isRefined: Boolean) {
    val text = when {
        isRefined -> "✓✓"
        isFinal -> "✓"
        else -> "···"
    }
    val color = when {
        isRefined -> Color(0xFF4CAF50)
        isFinal -> GeminiPalette.TextMuted
        else -> GeminiPalette.TextSecondary
    }
    AnimatedContent(targetState = text, label = "status") { currentText ->
        Text(currentText, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
private fun ShimmerPlaceholder() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(0.3f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "shimmerAlpha")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.height(14.dp).fillMaxWidth(0.8f).clip(RoundedCornerShape(4.dp)).background(GeminiPalette.BorderLight.copy(alpha = alpha)))
        Box(modifier = Modifier.height(14.dp).fillMaxWidth(0.5f).clip(RoundedCornerShape(4.dp)).background(GeminiPalette.BorderLight.copy(alpha = alpha)))
    }
}

@Composable
private fun BottomControlPanel(isActive: Boolean, isAiSpeaking: Boolean, isMicActive: Boolean, audioFlow: Flow<ByteArray>, onToggleMic: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp, top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(visible = isActive, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                GeminiVoiceAura(audioFlow = audioFlow, isAiSpeaking = isAiSpeaking, isMicActive = isMicActive)
            }
        }
        Spacer(Modifier.height(16.dp))
        MicButton(isActive = isMicActive, onClick = onToggleMic)
    }
}

@Composable
private fun GeminiVoiceAura(audioFlow: Flow<ByteArray>, isAiSpeaking: Boolean, isMicActive: Boolean) {
    var rms by remember { mutableFloatStateOf(0f) }
    var lastDecayNanos by remember { mutableLongStateOf(0L) }

    val animatedRms by animateFloatAsState(if (isAiSpeaking || isMicActive) rms.coerceIn(0f, 1f) else 0f, spring(dampingRatio = 0.8f, stiffness = 200f), label = "rms")

    LaunchedEffect(audioFlow, isAiSpeaking) {
        if (isAiSpeaking) {
            audioFlow.collect { pcm ->
                rms = computeRms16(pcm).coerceIn(0f, 1f)
                lastDecayNanos = System.nanoTime()
            }
        }
    }

    LaunchedEffect(isAiSpeaking) {
        var prevFrame = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prevFrame).coerceAtLeast(0L) / 1_000_000_000f
                prevFrame = now
                if (now - lastDecayNanos > 100_000_000L) rms = max(0f, rms * Math.pow(0.05, dt.toDouble()).toFloat())
            }
        }
    }

    val breath by rememberInfiniteTransition(label = "breath").animateFloat(0f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "b")

    Canvas(modifier = Modifier.size(120.dp, 40.dp)) {
        val colors = listOf(GeminiPalette.BrandBlue, GeminiPalette.BrandPurple, GeminiPalette.BrandCyan)
        val pillWidth = 12.dp.toPx()
        val spacing = 16.dp.toPx()
        val startX = (size.width - (3 * pillWidth + 2 * spacing)) / 2f

        for (i in 0 until 3) {
            val h = if (isAiSpeaking) pillWidth + (animatedRms * (size.height - pillWidth) * (1f - i * 0.1f)) else pillWidth + (breath * pillWidth * 0.5f)
            drawRoundRect(color = colors[i], topLeft = Offset(startX + i * (pillWidth + spacing), (size.height - h) / 2f), size = Size(pillWidth, h), cornerRadius = CornerRadius(pillWidth / 2f))
        }
    }
}

private fun computeRms16(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    var sum = 0.0
    for (i in 0 until pcm.size - 1 step 2) {
        val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
        val s = if (sample >= 0x8000) sample - 0x10000 else sample
        sum += (s * s).toDouble()
    }
    return ((sqrt(sum / (pcm.size / 2)) / 32768.0) * 3.5).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun MicButton(isActive: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (isActive) Color(0xFFE53935) else GeminiPalette.BrandBlue, label = "c")
    val scale by animateFloatAsState(if (isActive) 1.05f else 1f, spring(dampingRatio = 0.6f, stiffness = 200f), label = "s")

    Box(
        modifier = Modifier.size(80.dp).scale(scale).shadow(if (isActive) 16.dp else 8.dp, CircleShape, spotColor = color.copy(alpha = 0.5f)).clip(CircleShape).background(Brush.linearGradient(listOf(color.copy(alpha = 0.9f), color))).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(targetState = isActive, label = "icon") { active ->
            Icon(if (active) Icons.Filled.Stop else Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}