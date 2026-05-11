// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/BerlinWaveform.kt
//
// АНИМАЦИЯ ДЛЯ "BERLIN MIST" — горизонтальный waveform,
// в стиле Apple Voice Memos, но плавнее и тоньше.
//
// 48 вертикальных столбиков, каждый "запоминает" исторический RMS.
// При тишине — всё в линию (high < 2px), плавно дышат.
// Когда Gemini говорит — столбики ВПРАВО получают свежий RMS,
// а старые сдвигаются влево по rolling window.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator.animations

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.translator.app.presentation.theme.AppPalette
import kotlinx.coroutines.flow.Flow
import kotlin.math.sin

private const val BARS = 48

/**
 * Berlin Mist waveform — холодный минималистичный bar-graph.
 *
 * Каждый bar плавно интерполирует к новому таргету за ~80ms,
 * благодаря этому даже резкие всплески выглядят "шёлковыми".
 */
@Composable
fun BerlinWaveform(
    palette: AppPalette,
    audioFlow: Flow<ByteArray>,
    isAiSpeaking: Boolean
) {
    val audioLevel by rememberAudioLevel(audioFlow, isAiSpeaking, attack = 0.45f, release = 0.05f)

    // История уровней. Кольцевой буфер: индекс = (head + i) % BARS.
    // Сами значения — двойные: текущее и таргет. Каждый кадр интерполируем.
    val current = remember { FloatArray(BARS) }
    val target = remember { FloatArray(BARS) }
    val head = remember { intArrayOf(0) }
    val lastPushNanos = remember { longArrayOf(0L) }

    // "Дыхание" в idle режиме: микро-волна синусом.
    val breathTransition = rememberInfiniteTransition(label = "berlinBreath")
    val breathPhase by breathTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2f).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "berlinBreathPhase"
    )

    // Каждые ~30ms пушим новый сэмпл в кольцо. На каждый кадр — интерполяция.
    LaunchedEffect(audioFlow, isAiSpeaking) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now

                // Пушим новый бар каждые 30мс — даёт скорость 33 бар/сек.
                if (now - lastPushNanos[0] > 30_000_000L) {
                    lastPushNanos[0] = now
                    head[0] = (head[0] + 1) % BARS
                    target[head[0]] = if (isAiSpeaking) audioLevel else 0f
                }

                // Smoothing: current → target экспоненциально.
                val k = (1f - kotlin.math.exp(-dt * 18f)).coerceIn(0f, 1f)
                for (i in 0 until BARS) {
                    current[i] += (target[i] - current[i]) * k
                }
            }
        }
    }

    Canvas(modifier = Modifier.size(220.dp, 56.dp)) {
        val w = size.width
        val h = size.height
        val barW = w / (BARS * 1.45f)
        val gap = (w - barW * BARS) / (BARS - 1)
        val midY = h / 2f
        val maxHalf = h / 2f - 2f
        val minHalf = 1.2.dp.toPx()

        for (i in 0 until BARS) {
            // Читаем кольцо: bar 0 (слева) = самый старый, bar BARS-1 (справа) = свежий.
            val ringIdx = (head[0] + 1 + i) % BARS
            val raw = current[ringIdx]

            // Idle: микро-волна от синуса; speaking: реальный уровень.
            val idleAmp = if (!isAiSpeaking) {
                (sin(breathPhase + i * 0.18f) * 0.08f + 0.08f).toFloat()
            } else 0f
            val v = (raw + idleAmp).coerceIn(0f, 1f)

            // Применяем кривую — придаёт "профессиональный" вид (не плоско-линейный).
            val shaped = v * v * (3f - 2f * v) // smoothstep
            val half = (minHalf + shaped * (maxHalf - minHalf))

            val x = i * (barW + gap)
            val color = if (i > BARS - 10 && isAiSpeaking) {
                // последние 10 баров справа — акцентный цвет (свежий звук)
                lerpColor(palette.textMuted, palette.accentSecondary, ((i - (BARS - 10)) / 10f))
            } else {
                palette.textSecondary
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(x, midY - half),
                size = Size(barW, half * 2f),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}

private fun lerpColor(
    a: androidx.compose.ui.graphics.Color,
    b: androidx.compose.ui.graphics.Color,
    t: Float
): androidx.compose.ui.graphics.Color {
    val tt = t.coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt
    )
}
