// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (v2.0 — Water Ripples)
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/SakuraRipples.kt
//
// Расходящиеся кольца как от капли в воде.
// Пул из 8 ring-слотов (zero-alloc). При peak — рождается новое кольцо.
// При тишине — два медленных «дыхательных» кольца + центральная капля.
//
// Физика:
//   • spawnInterval ∝ 1/(peak)  → чем громче, тем чаще
//   • amplitude   ∝ peak        → громкий = большой радиус
//   • easing      = out-cubic   → естественная вода
//   • strokeWidth уменьшается с прогрессом → finished ring почти невидим
//   • alpha       = (1-p)² * amp → плавный fade-out
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.translator.app.presentation.theme.AppPalette
import kotlinx.coroutines.flow.Flow

private const val SLOTS = 8

@Composable
fun SakuraRipples(
    palette: AppPalette,
    audioFlow: Flow<ByteArray>,
    isAiSpeaking: Boolean
) {
    val m = rememberAudioMetrics(audioFlow, isAiSpeaking, attack = 0.55f, release = 0.06f)
    val level by m.level
    val peak by m.peak

    val progress = remember { FloatArray(SLOTS) { 1f } }
    val amplitude = remember { FloatArray(SLOTS) }
    val lastSpawn = remember { longArrayOf(0L) }

    val idleTr = rememberInfiniteTransition(label = "sakuraIdle")
    val idlePhase by idleTr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "sakuraIdlePh"
    )

    LaunchedEffect(audioFlow, isAiSpeaking) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now

                val expandSpeed = 0.44f + level * 0.32f
                for (i in 0 until SLOTS) {
                    if (progress[i] < 1f) {
                        progress[i] = (progress[i] + dt * expandSpeed).coerceAtMost(1f)
                    }
                }

                if (isAiSpeaking && peak > 0.06f) {
                    val intervalNs = (320_000_000L - (peak * 240_000_000L).toLong())
                        .coerceAtLeast(110_000_000L)
                    if (now - lastSpawn[0] > intervalNs) {
                        for (i in 0 until SLOTS) {
                            if (progress[i] >= 1f) {
                                progress[i] = 0f
                                amplitude[i] = (0.55f + peak * 0.45f).coerceIn(0f, 1f)
                                lastSpawn[0] = now
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    Canvas(modifier = Modifier.size(160.dp)) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val maxR = (w.coerceAtMost(h) / 2f) - 4f
        val minR = 4.dp.toPx()

        // ── Центральная капля
        val coreRadius = 7.dp.toPx() + level * 18.dp.toPx() + peak * 6.dp.toPx()
        val coreBrush = Brush.radialGradient(
            colors = listOf(palette.accentPrimary, palette.accentPrimary.copy(alpha = 0f)),
            center = Offset(cx, cy),
            radius = coreRadius * 2.4f
        )
        drawCircle(brush = coreBrush, radius = coreRadius * 2.4f, center = Offset(cx, cy))
        drawCircle(color = palette.accentPrimary, radius = coreRadius, center = Offset(cx, cy))

        // ── Idle двойное дыхание
        if (!isAiSpeaking || level < 0.05f) {
            for (k in 0..1) {
                val ph = (idlePhase + k * 0.5f) % 1f
                val r = minR + ph * (maxR - minR) * 0.62f
                val a = (1f - ph) * (1f - ph) * 0.35f
                drawCircle(
                    color = palette.accentSecondary.copy(alpha = a),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }

        // ── Active ripples
        for (i in 0 until SLOTS) {
            val p = progress[i]
            if (p >= 1f) continue
            val eased = 1f - (1f - p) * (1f - p) * (1f - p)         // out-cubic
            val r = minR + eased * (maxR - minR) * amplitude[i]
            val a = (1f - p) * (1f - p) * 0.65f * amplitude[i]
            val ringColor = if (i % 2 == 0) palette.accentPrimary else palette.accentSecondary
            val strokePx = (2.4f - p * 1.6f).coerceAtLeast(0.4f).dp.toPx()
            drawCircle(
                color = ringColor.copy(alpha = a),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = strokePx)
            )
        }
    }
}
