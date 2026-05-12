// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/translator/GemCapsuleButton.kt
//
// Премиальная pill-кнопка для темы GEM.
//
// • Форма: тонкий rounded rectangle (pill), НЕ круг
// • В покое: Gemini blue (#4285F4)
// • При записи/AI говорит: Google green (#34A853), пульсирует
// • Размер: ~120dp × 44dp (примерно в 2 раза меньше круглой 72dp)
// • Behind: мягкое размытое halo того же цвета
// • Текст/иконка: компактная (Mic 18dp / Stop 18dp), белая
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.translator.app.R
import com.translator.app.presentation.theme.AppPalette

@Composable
fun GemCapsuleButton(
    palette: AppPalette,
    isActive: Boolean,
    onClick: () -> Unit
) {
    // Анимированный цвет: blue → green
    val color by animateColorAsState(
        targetValue = if (isActive) palette.accentSecondary else palette.accentPrimary,
        animationSpec = tween(durationMillis = 280),
        label = "gemBtnColor"
    )

    // Пульсация при разговоре
    val pulseTr = rememberInfiniteTransition(label = "gemPulse")
    val pulse by pulseTr.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "gemPulseV"
    )
    val activeScale = if (isActive) pulse else 1f

    // Spring scale на press feedback
    val pressedScale by animateFloatAsState(
        targetValue = activeScale,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "gemPress"
    )

    // Halo alpha
    val haloAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.40f else 0.20f,
        animationSpec = tween(durationMillis = 300),
        label = "gemHalo"
    )

    Box(
        modifier = Modifier.size(width = 132.dp, height = 56.dp),
        contentAlignment = Alignment.Center
    ) {
        // ── Blurred halo behind (premium effect)
        Box(
            modifier = Modifier
                .size(width = 128.dp, height = 52.dp)
                .scale(if (isActive) 1.10f else 1.0f)
                .clip(RoundedCornerShape(28.dp))
                .blur(radius = 16.dp)
                .background(color.copy(alpha = haloAlpha))
        )

        // ── Solid pill button
        Box(
            modifier = Modifier
                .size(width = 112.dp, height = 44.dp)
                .scale(pressedScale)
                .shadow(
                    elevation = if (isActive) 14.dp else 6.dp,
                    shape = RoundedCornerShape(22.dp),
                    spotColor = color.copy(alpha = 0.55f)
                )
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.95f), color)
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(targetState = isActive, label = "gemIcon") { active ->
                Icon(
                    imageVector = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = stringResource(
                        if (active) R.string.cd_mic_stop else R.string.cd_mic_start
                    ),
                    tint = palette.textOnAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}