// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/ObsidianOrb.kt
//
// АНИМАЦИЯ ДЛЯ "OBSIDIAN" — премиальная "wow"-сфера для тёмной темы.
//
// Что это: глянцевая 3D-сфера (имитация через многослойные радиальные
// градиенты), вокруг неё медленно вращаются 4 орбитальных частицы.
// При тишине: сфера дышит, частицы плавно текут.
// При речи Gemini:
//   • сфера увеличивается, "пульсирует" в такт RMS
//   • частицы ускоряются, появляются "следы"
//   • вокруг — мягкая аура свечения, расширяющаяся при громких звуках
//
// На AMOLED S23 Ultra: фон true-black гасит пиксели → сфера светится
// как настоящий энергошар.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator.animations

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.translator.app.presentation.theme.AppPalette
import kotlinx.coroutines.flow.Flow
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ObsidianOrb(
    palette: AppPalette,
    audioFlow: Flow<ByteArray>,
    isAiSpeaking: Boolean
) {
    val audioLevel by rememberAudioLevel(audioFlow, isAiSpeaking, attack = 0.4f, release = 0.07f)

    // Размер сферы — пульсирует на громких звуках.
    val pulseScale by animateFloatAsState(
        targetValue = if (isAiSpeaking) 1f + audioLevel * 0.22f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "obsidianPulse"
    )

    // Idle дыхание (всегда работает).
    val breathTransition = rememberInfiniteTransition(label = "obsidianBreath")
    val breath by breathTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "obsidianBreathValue"
    )

    // Орбитальное вращение: 1 оборот за 6 секунд idle, ускоряется при речи.
    val orbitalTransition = rememberInfiniteTransition(label = "obsidianOrbital")
    val orbitalBase by orbitalTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2f).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "obsidianOrbitalBase"
    )

    // Фазовое смещение (второй слой орбиты, обратное направление)
    val orbitalReverse by orbitalTransition.animateFloat(
        initialValue = 0f,
        targetValue = -(Math.PI * 2f).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "obsidianOrbitalReverse"
    )

    val effectiveAngle = orbitalBase + audioLevel * orbitalBase * 0.8f

    Canvas(modifier = Modifier.size(180.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val baseRadius = (w.coerceAtMost(h) / 2f) * 0.32f
        val orbRadius = baseRadius * pulseScale * breath

        // 1. Внешняя аура (3 концентрических слоя)
        val glowRadius = orbRadius * (3.5f + audioLevel * 1.5f)
        val auraBrush = Brush.radialGradient(
            colors = listOf(
                palette.accentPrimary.copy(alpha = 0.35f + audioLevel * 0.25f),
                palette.accentPrimary.copy(alpha = 0.12f),
                palette.accentSecondary.copy(alpha = 0.06f),
                androidx.compose.ui.graphics.Color.Transparent
            ),
            center = Offset(cx, cy),
            radius = glowRadius
        )
        drawCircle(brush = auraBrush, radius = glowRadius, center = Offset(cx, cy))

        // 2. Орбитальные частицы (4 точки, 2 направления)
        val orbitR = orbRadius * 1.85f
        val particleR = 3.dp.toPx()
        for (k in 0 until 4) {
            val angleOffset = (Math.PI / 2 * k).toFloat()
            val angle = effectiveAngle + angleOffset
            val px = cx + cos(angle) * orbitR
            val py = cy + sin(angle) * orbitR
            // Glow вокруг частицы (трейл)
            drawCircle(
                color = palette.accentPrimary.copy(alpha = 0.4f),
                radius = particleR * 3f,
                center = Offset(px, py)
            )
            drawCircle(
                color = palette.accentPrimary,
                radius = particleR,
                center = Offset(px, py)
            )
        }

        // 3. Орбитальные частицы (второй слой, обратное направление, lavender)
        val orbitR2 = orbRadius * 2.35f
        for (k in 0 until 3) {
            val angleOffset = (Math.PI * 2 / 3 * k).toFloat()
            val angle = orbitalReverse + angleOffset
            val px = cx + cos(angle) * orbitR2
            val py = cy + sin(angle) * orbitR2
            drawCircle(
                color = palette.accentSecondary.copy(alpha = 0.35f),
                radius = particleR * 2.2f,
                center = Offset(px, py)
            )
            drawCircle(
                color = palette.accentSecondary,
                radius = particleR * 0.85f,
                center = Offset(px, py)
            )
        }

        // 4. Тело сферы — радиальный градиент даёт ощущение объёма (3D).
        val orbBrush = Brush.radialGradient(
            colors = listOf(
                palette.accentSecondary,                            // ярко-фиолетовый центр
                palette.accentPrimary,                              // teal
                palette.accentPrimary.copy(alpha = 0.75f),
                palette.accentPrimary.copy(alpha = 0.15f)
            ),
            center = Offset(cx - orbRadius * 0.3f, cy - orbRadius * 0.4f),  // смещение центра = блик
            radius = orbRadius * 1.4f
        )
        drawCircle(brush = orbBrush, radius = orbRadius, center = Offset(cx, cy))

        // 5. Глянцевый highlight — белый блик в верхней левой части.
        val highlightR = orbRadius * 0.55f
        val highlightBrush = Brush.radialGradient(
            colors = listOf(
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
                androidx.compose.ui.graphics.Color.Transparent
            ),
            center = Offset(cx - orbRadius * 0.32f, cy - orbRadius * 0.4f),
            radius = highlightR
        )
        drawCircle(
            brush = highlightBrush,
            radius = highlightR,
            center = Offset(cx - orbRadius * 0.32f, cy - orbRadius * 0.4f)
        )
    }
}
