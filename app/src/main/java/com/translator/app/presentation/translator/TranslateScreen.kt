// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/translator/app/presentation/translator/TranslateScreen.kt
//
// Премиальный экран переводчика с поддержкой 4 тем.
//
// ОСОБЕННОСТИ:
//   • Все цвета — через LocalAppPalette.current (никаких хардкодов)
//   • Все строки — через stringResource (ru / de)
//   • Воices animation выбирается по themeId:
//       - AURORA      → AuroraAura (прямоугольная булька-градиент)
//       - BERLIN_MIST → BerlinWaveform (горизонтальный bar-graph)
//       - SAKURA      → SakuraRipples (расходящиеся кольца)
//       - OBSIDIAN    → ObsidianOrb (3D-сфера с орбитой)
//   • Минималистичная карточка перевода: флаг + текст + статус-точка
//   • Микрофонная кнопка с тонким наружным "halo" в активном состоянии
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility as AnimatedVisibilityCore
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.translator.app.R
import com.translator.app.presentation.theme.AppPalette
import com.translator.app.presentation.theme.AppThemeId
import com.translator.app.presentation.theme.LocalAppPalette
import com.translator.app.presentation.translator.animations.AuroraAura
import com.translator.app.presentation.translator.animations.BerlinWaveform
import com.translator.app.presentation.translator.animations.ObsidianOrb
import com.translator.app.presentation.translator.animations.SakuraRipples
import kotlinx.coroutines.flow.Flow

@Composable
fun TranslateScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onBack: () -> Unit,
    viewModel: TranslatorViewModel = hiltViewModel()
) {
    val palette = LocalAppPalette.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val isActive = state.connectionStatus != ConnectionStatus.Disconnected

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (state.connectionStatus == ConnectionStatus.Disconnected) {
                viewModel.startSession()
            } else {
                viewModel.onMicPermissionGranted()
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
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

    Box(modifier = Modifier.fillMaxSize().background(palette.background)) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(
                palette = palette,
                onBack = { viewModel.stopSession(); onBack() },
                onSettings = onNavigateToSettings,
                onLogs = onNavigateToLogs,
                isActive = isActive,
                isAiSpeaking = state.isAiSpeaking,
                isMicActive = state.isMicActive,
                connectionStatus = state.connectionStatus
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (state.pairs.isEmpty()) {
                    EmptyState(palette = palette, isActive = isActive)
                } else {
                    PairsList(palette = palette, pairs = state.pairs)
                }
            }

            BottomControlPanel(
                palette = palette,
                isActive = isActive,
                isAiSpeaking = state.isAiSpeaking,
                isMicActive = state.isMicActive,
                audioFlow = viewModel.audioPlaybackFlow,
                onToggleMic = {
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.toggleMic()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
//  TOP BAR
// ════════════════════════════════════════════════════════════

@Composable
private fun TopBar(
    palette: AppPalette,
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = palette.textPrimary
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.translator_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.W600,
                color = palette.textPrimary
            )
            val statusRes = when {
                connectionStatus == ConnectionStatus.Reconnecting -> R.string.status_reconnecting
                connectionStatus == ConnectionStatus.Connecting -> R.string.status_connecting
                isActive && isAiSpeaking -> R.string.status_ai_speaking
                isActive && isMicActive -> R.string.status_listening
                isActive -> R.string.status_ready
                else -> R.string.status_disconnected
            }
            val statusText = stringResource(statusRes)
            AnimatedContent(targetState = statusText, label = "statusText") { text ->
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        IconButton(onClick = onLogs) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = stringResource(R.string.cd_logs),
                tint = palette.textPrimary
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = palette.textPrimary
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
//  EMPTY STATE
// ════════════════════════════════════════════════════════════

@Composable
private fun EmptyState(palette: AppPalette, isActive: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Языковая капсула — флагообразные значки RU ⇄ DE
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.border, RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "RU",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
                Text(
                    text = "⇄",
                    fontSize = 14.sp,
                    color = palette.accentPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "DE",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            }

            Text(
                text = stringResource(
                    if (isActive) R.string.hint_active_title else R.string.hint_idle_title
                ),
                fontSize = 22.sp,
                fontWeight = FontWeight.W600,
                color = palette.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(
                    if (isActive) R.string.hint_active_subtitle else R.string.hint_idle_subtitle
                ),
                fontSize = 15.sp,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
//  PAIRS LIST + CARD
// ════════════════════════════════════════════════════════════

@Composable
private fun PairsList(palette: AppPalette, pairs: List<TranslationPair>) {
    val listState = rememberLazyListState()
    val lastPair = pairs.lastOrNull()

    androidx.compose.runtime.LaunchedEffect(
        pairs.size,
        lastPair?.translationText?.length,
        lastPair?.originalText?.length
    ) {
        if (pairs.isNotEmpty()) listState.animateScrollToItem(pairs.size - 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(pairs, key = { it.id }) { pair -> PairCard(palette, pair) }
    }
}

@Composable
private fun PairCard(palette: AppPalette, pair: TranslationPair) {
    val shadowSpot = if (palette.isDark) Color.Black.copy(alpha = 0.5f) else Color(0x14000000)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = if (palette.isDark) 0.dp else 6.dp, RoundedCornerShape(22.dp), spotColor = shadowSpot)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.surfaceElevated)
            .then(if (palette.isDark) Modifier.border(1.dp, palette.border, RoundedCornerShape(22.dp)) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TranscriptBlock(
            palette = palette,
            text = pair.originalText,
            lang = pair.originalLang,
            isOriginal = true,
            isFinal = pair.originalIsFinal,
            isRefined = pair.originalIsRefined
        )
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
        TranscriptBlock(
            palette = palette,
            text = pair.translationText,
            lang = pair.translationLang,
            isOriginal = false,
            isFinal = pair.translationIsFinal,
            isRefined = pair.translationIsRefined
        )
    }
}

@Composable
private fun TranscriptBlock(
    palette: AppPalette,
    text: String,
    lang: String,
    isOriginal: Boolean,
    isFinal: Boolean,
    isRefined: Boolean
) {
    Column(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isOriginal) palette.textMuted else palette.accentPrimary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = lang.ifBlank {
                        if (isOriginal) "—" else "—"
                    }.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = palette.textSecondary
                )
            }
            StatusDot(palette = palette, isFinal = isFinal, isRefined = isRefined)
        }
        Spacer(Modifier.height(6.dp))
        if (text.isBlank() && !isFinal) {
            ShimmerPlaceholder(palette = palette)
        } else {
            Text(
                text = text,
                fontSize = if (isOriginal) 15.sp else 18.sp,
                lineHeight = if (isOriginal) 22.sp else 26.sp,
                color = if (isOriginal) palette.textSecondary else palette.textPrimary,
                fontWeight = if (isOriginal) FontWeight.Normal else FontWeight.W500
            )
        }
    }
}

@Composable
private fun StatusDot(palette: AppPalette, isFinal: Boolean, isRefined: Boolean) {
    // Минималистичный индикатор: 1 точка серая (черновик), 2 точки в цвете (final),
    // 2 точки в цвете success (refined).
    val color = when {
        isRefined -> palette.statusOk
        isFinal -> palette.textMuted
        else -> palette.textMuted.copy(alpha = 0.5f)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(4.dp).clip(CircleShape).background(color))
        if (isFinal || isRefined) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun ShimmerPlaceholder(palette: AppPalette) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .height(14.dp).fillMaxWidth(0.75f)
                .clip(RoundedCornerShape(4.dp))
                .background(palette.border.copy(alpha = alpha))
        )
        Box(
            modifier = Modifier
                .height(14.dp).fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(4.dp))
                .background(palette.border.copy(alpha = alpha))
        )
    }
}

// ════════════════════════════════════════════════════════════
//  BOTTOM PANEL — voice anim + mic button
// ════════════════════════════════════════════════════════════

@Composable
private fun BottomControlPanel(
    palette: AppPalette,
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    audioFlow: Flow<ByteArray>,
    onToggleMic: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Слот анимации — фиксированной высоты, чтобы layout не дёргался.
        Box(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibilityCore(
                visible = isActive,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f)
            ) {
                when (palette.id) {
                    AppThemeId.AURORA -> AuroraAura(
                        palette = palette,
                        audioFlow = audioFlow,
                        isAiSpeaking = isAiSpeaking || isMicActive
                    )
                    AppThemeId.BERLIN_MIST -> BerlinWaveform(
                        palette = palette,
                        audioFlow = audioFlow,
                        isAiSpeaking = isAiSpeaking || isMicActive
                    )
                    AppThemeId.SAKURA -> SakuraRipples(
                        palette = palette,
                        audioFlow = audioFlow,
                        isAiSpeaking = isAiSpeaking || isMicActive
                    )
                    AppThemeId.OBSIDIAN -> ObsidianOrb(
                        palette = palette,
                        audioFlow = audioFlow,
                        isAiSpeaking = isAiSpeaking || isMicActive
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MicButton(palette = palette, isActive = isMicActive, onClick = onToggleMic)
    }
}

@Composable
private fun MicButton(palette: AppPalette, isActive: Boolean, onClick: () -> Unit) {
    val haloAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.35f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "halo"
    )
    val color by animateColorAsState(
        targetValue = if (isActive) palette.statusRecording else palette.accentPrimary,
        animationSpec = tween(durationMillis = 200),
        label = "micColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "micScale"
    )

    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center
    ) {
        // Тонкое halo вокруг кнопки в активном состоянии
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = haloAlpha))
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .shadow(
                    elevation = if (isActive) 14.dp else 6.dp,
                    shape = CircleShape,
                    spotColor = color.copy(alpha = 0.5f)
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(color.copy(alpha = 0.92f), color)
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(targetState = isActive, label = "micIcon") { active ->
                Icon(
                    imageVector = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = stringResource(
                        if (active) R.string.cd_mic_stop else R.string.cd_mic_start
                    ),
                    tint = palette.textOnAccent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}
