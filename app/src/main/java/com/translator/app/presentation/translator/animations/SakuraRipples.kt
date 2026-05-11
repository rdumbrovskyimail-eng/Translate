// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/SakuraRipples.kt
//
// АНИМАЦИЯ ДЛЯ "SAKURA" — расходящиеся кольца, как от капли.
//
// При тишине: одно тонкое кольцо медленно "дышит".
// При речи: каждые ~250мс рождается новое кольцо в центре,
// летит наружу, растёт и затухает. Частота рождения и размер
// зависят от RMS.
//
// Реализация: пул из 6 ring-слотов (zero-alloc), каждый имеет
// progress 0..1. Когда progress = 1 — слот свободен для нового кольца.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator.animations

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.translator.app.presentation.theme.AppPalette
import kotlinx.coroutines.flow.Flow

private const val SLOTS = 6

@Composable
fun SakuraRipples(
    palette: AppPalette,
    audioFlow: Flow<ByteArray>,
    isAiSpeaking: Boolean
) {
    val audioLevel by rememberAudioLevel(audioFlow, isAiSpeaking, attack = 0.4f, release = 0.06f)

    // progress[i] in [0..1]. 1 = свободно.
    val progress = remember { FloatArray(SLOTS) { 1f } }
    val amplitude = remember { FloatArray(SLOTS) } // снимок RMS на момент рождения
    val lastSpawnNanos = remember { longArrayOf(0L) }

    // Idle: одиночное медленное кольцо
    val idleTransition = rememberInfiniteTransition(label = "sakuraIdle")
    val idlePhase by idleTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sakuraIdlePhase"
    )

    LaunchedEffect(audioFlow, isAiSpeaking) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now

                // Скорость расширения зависит от среднего уровня — но не сильно.
                val expandSpeed = 0.42f + audioLevel * 0.25f
                for (i in 0 until SLOTS) {
                    if (progress[i] < 1f) {
                        progress[i] = (progress[i] + dt * expandSpeed).coerceAtMost(1f)
                    }
                }

                // Рождение новых колец только если есть свободные слоты
                // и интервал между спавнами зависит от RMS.
                if (isAiSpeaking && audioLevel > 0.04f) {
                    val intervalNs = (300_000_000L - (audioLevel * 200_000_000L).toLong())
                        .coerceAtLeast(120_000_000L)
                    if (now - lastSpawnNanos[0] > intervalNs) {
                        // Найти свободный слот
                        for (i in 0 until SLOTS) {
                            if (progress[i] >= 1f) {
                                progress[i] = 0f
                                amplitude[i] = (0.6f + audioLevel * 0.4f).coerceIn(0f, 1f)
                                lastSpawnNanos[0] = now
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    Canvas(modifier = Modifier.size(140.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val maxR = (w.coerceAtMost(h) / 2f) - 4f
        val minR = 4.dp.toPx()

        // Центральная "капля" — её размер пульсирует от RMS.
        val coreRadius = (8.dp.toPx() + audioLevel * 14.dp.toPx())
        val coreBrush = Brush.radialGradient(
            colors = listOf(palette.accentPrimary, palette.accentPrimary.copy(alpha = 0f)),
            center = Offset(cx, cy),
            radius = coreRadius * 2.2f
        )
        drawCircle(brush = coreBrush, radius = coreRadius * 2.2f, center = Offset(cx, cy))
        drawCircle(
            color = palette.accentPrimary,
            radius = coreRadius,
            center = Offset(cx, cy)
        )

        // Idle кольцо: пульсирующее, всегда есть.
        if (!isAiSpeaking || audioLevel < 0.05f) {
            val idleR = minR + idlePhase * (maxR - minR) * 0.6f
            val idleAlpha = (1f - idlePhase) * 0.32f
            drawCircle(
                color = palette.accentSecondary.copy(alpha = idleAlpha),
                radius = idleR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.4.dp.toPx())
            )
        }

        // Активные ripples
        for (i in 0 until SLOTS) {
            val p = progress[i]
            if (p >= 1f) continue

            // Easing — out cubic для естественной воды
            val eased = 1f - (1f - p) * (1f - p) * (1f - p)
            val r = minR + eased * (maxR - minR) * amplitude[i]
            val alpha = (1f - p) * 0.55f * amplitude[i]

            val ringColor = if (i % 2 == 0) palette.accentPrimary else palette.accentSecondary
            drawCircle(
                color = ringColor.copy(alpha = alpha),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = (2f - p * 1.2f).coerceAtLeast(0.5f).dp.toPx())
            )
        }
    }
}
