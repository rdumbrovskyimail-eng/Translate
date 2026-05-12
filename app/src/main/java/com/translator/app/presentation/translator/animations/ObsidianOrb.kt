// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (v2.0 — Deep Orb)
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/ObsidianOrb.kt
//
// Глянцевая 3D-сфера для тёмной темы. Многослойные радиальные градиенты
// дают полную иллюзию объёма. Вокруг — 2 кольца орбитальных частиц
// в противоположных направлениях.
//
// Pulse: scale × 1.25 на громких звуках (с pq spring).
// Glow: внешняя аура расширяется при peak, мерцает в такт level.
// Particles: 5 + 4 точек, у каждой свой trail из 3 «уходящих» копий.
// Surface highlight: дополнительный glass-reflection arc сверху.
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
import androidx.compose.ui.graphics.Color
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
    val m = rememberAudioMetrics(audioFlow, isAiSpeaking, attack = 0.5f, release = 0.07f)
    val level by m.level
    val peak by m.peak

    val pulseScale by animateFloatAsState(
        targetValue = if (isAiSpeaking) 1f + level * 0.20f + peak * 0.10f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "obsidianPulse"
    )

    val breathTr = rememberInfiniteTransition(label = "obsidianBreath")
    val breath by breathTr.animateFloat(
        initialValue = 0.93f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "obsidianBreathV"
    )

    val rotTr = rememberInfiniteTransition(label = "obsidianRot")
    val orb1 by rotTr.animateFloat(
        initialValue = 0f, targetValue = (Math.PI * 2f).toFloat(),
        animationSpec = infiniteRepeatable(tween(5500, easing = LinearEasing), RepeatMode.Restart),
        label = "orb1"
    )
    val orb2 by rotTr.animateFloat(
        initialValue = 0f, targetValue = -(Math.PI * 2f).toFloat(),
        animationSpec = infiniteRepeatable(tween(8200, easing = LinearEasing), RepeatMode.Restart),
        label = "orb2"
    )

    val effectiveAngle1 = orb1 + level * orb1 * 0.6f

    Canvas(modifier = Modifier.size(190.dp)) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val baseR = (w.coerceAtMost(h) / 2f) * 0.30f
        val orbR = baseR * pulseScale * breath

        // ── (1) Outer aura — 4 концентрических слоя
        val glowR = orbR * (3.8f + level * 1.8f + peak * 0.8f)
        val auraBrush = Brush.radialGradient(
            colors = listOf(
                palette.accentPrimary.copy(alpha = 0.40f + level * 0.30f),
                palette.accentPrimary.copy(alpha = 0.15f),
                palette.accentSecondary.copy(alpha = 0.08f),
                Color.Transparent
            ),
            center = Offset(cx, cy),
            radius = glowR
        )
        drawCircle(brush = auraBrush, radius = glowR, center = Offset(cx, cy))

        // ── (2) Outer orbit ring — 5 particles, primary direction
        val orbitR1 = orbR * 1.9f
        val pSize1 = 3.4.dp.toPx() + level * 1.4.dp.toPx()
        for (k in 0 until 5) {
            val aOff = (Math.PI * 2f / 5f * k).toFloat()
            val ang = effectiveAngle1 + aOff
            val px = cx + cos(ang) * orbitR1
            val py = cy + sin(ang) * orbitR1
            // Trail: 3 уходящие копии
            for (t in 0..2) {
                val tAng = ang - 0.08f * (t + 1)
                val tpx = cx + cos(tAng) * orbitR1
                val tpy = cy + sin(tAng) * orbitR1
                drawCircle(
                    color = palette.accentPrimary.copy(alpha = 0.18f / (t + 1)),
                    radius = pSize1 * (1f - t * 0.18f),
                    center = Offset(tpx, tpy)
                )
            }
            drawCircle(
                color = palette.accentPrimary.copy(alpha = 0.5f),
                radius = pSize1 * 2.6f,
                center = Offset(px, py)
            )
            drawCircle(color = palette.accentPrimary, radius = pSize1, center = Offset(px, py))
        }

        // ── (3) Inner orbit ring — 4 particles, reverse, accentSecondary
        val orbitR2 = orbR * 2.45f
        val pSize2 = pSize1 * 0.85f
        for (k in 0 until 4) {
            val aOff = (Math.PI * 2f / 4f * k).toFloat()
            val ang = orb2 + aOff
            val px = cx + cos(ang) * orbitR2
            val py = cy + sin(ang) * orbitR2
            drawCircle(
                color = palette.accentSecondary.copy(alpha = 0.42f),
                radius = pSize2 * 2.4f,
                center = Offset(px, py)
            )
            drawCircle(color = palette.accentSecondary, radius = pSize2, center = Offset(px, py))
        }

        // ── (4) Orb body — глубокий радиальный градиент
        val bodyBrush = Brush.radialGradient(
            colors = listOf(
                palette.accentSecondary,
                palette.accentPrimary,
                palette.accentPrimary.copy(alpha = 0.7f),
                palette.accentPrimary.copy(alpha = 0.12f)
            ),
            center = Offset(cx - orbR * 0.32f, cy - orbR * 0.42f),
            radius = orbR * 1.5f
        )
        drawCircle(brush = bodyBrush, radius = orbR, center = Offset(cx, cy))

        // ── (5) Glass highlight — белый блик
        val hiR = orbR * 0.58f
        val hiBrush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.62f),
                Color.White.copy(alpha = 0.12f),
                Color.Transparent
            ),
            center = Offset(cx - orbR * 0.34f, cy - orbR * 0.42f),
            radius = hiR
        )
        drawCircle(
            brush = hiBrush,
            radius = hiR,
            center = Offset(cx - orbR * 0.34f, cy - orbR * 0.42f)
        )
    }
}
