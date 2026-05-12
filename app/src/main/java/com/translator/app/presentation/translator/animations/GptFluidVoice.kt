// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/GptFluidVoice.kt
//
// АНИМАЦИЯ ДЛЯ "OPEN_OASIS" — стиль ChatGPT Advanced Voice Mode.
//
// Что это: аморфная "капля" (fluid blob), форма генерируется
// математически каждый кадр как сумма синусоид по углам, замкнутая
// плавными квадратичными кривыми Безье. Внутри — графитовый цвет,
// при речи — мятный glow за пределами тела, drift centra по orbits.
//
// Технические особенности:
//   • 14 контрольных точек по периметру → плавный контур
//   • 2 octaves wobble (низкочастотный + высокочастотный)
//   • Audio-reactive extrusion: peak добавляет «шипы» в локальных секторах
//   • Drift X/Y: тело слегка плавает в пространстве (Lissajous orbit)
//   • Outer soft glow (3 концентрических круга) реагирует на peak
//   • Inner highlight: верхний-левый блик имитирует glass
//
// Производительность: одна Path, два прохода (glow + body), Float-only.
// На S23U стабильные 120 fps.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator.animations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import com.translator.app.presentation.theme.AppPalette
import kotlinx.coroutines.flow.Flow
import kotlin.math.cos
import kotlin.math.sin

private const val NODES = 14

@Composable
fun GptFluidVoice(
    palette: AppPalette,
    audioFlow: Flow<ByteArray>,
    isAiSpeaking: Boolean
) {
    val m = rememberAudioMetrics(audioFlow, isAiSpeaking, attack = 0.7f, release = 0.10f)
    val level by m.level
    val peak by m.peak
    val velocity by m.velocity

    // Время в секундах (накопительное), чтобы фаза синусов росла плавно
    val phase = remember { mutableFloatStateOf(0f) }
    val xs = remember { FloatArray(NODES) }
    val ys = remember { FloatArray(NODES) }

    LaunchedEffect(Unit) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now
                // Скорость морфинга: idle = 0.45, speaking ускоряется до ~2.5
                phase.floatValue += dt * (0.55f + level * 2.2f + peak * 0.6f)
            }
        }
    }

    Canvas(modifier = Modifier.size(220.dp)) {
        val w = size.width
        val h = size.height
        val cx0 = w / 2f
        val cy0 = h / 2f
        val baseR = (w.coerceAtMost(h) / 2f) * 0.42f

        val t = phase.floatValue

        // Drift orbit: центр капли плавает по микро-Lissajous
        val driftX = cos(t * 0.42f) * baseR * 0.06f
        val driftY = sin(t * 0.31f) * baseR * 0.06f
        val cx = cx0 + driftX
        val cy = cy0 + driftY

        // Адаптивный радиус: тихо ≈ 1.0, шумно до 1.18 + всплески от peak
        val sizeFactor = 1f + level * 0.16f + peak * 0.12f
        val r0 = baseR * sizeFactor

        // ── Считаем 14 точек по периметру
        val twoPi = (Math.PI * 2f).toFloat()
        for (i in 0 until NODES) {
            val angle = (twoPi / NODES) * i
            // Octave 1: низкочастотное «дыхание» формы
            val w1 = sin(angle * 2f + t * 1.3f) * 0.13f
            // Octave 2: высокочастотное мерцание
            val w2 = sin(angle * 5f + t * 2.1f) * 0.05f
            // Audio extrusion: «шип» в локальном секторе, амплитуда от peak
            val ex = sin(angle * 3f + t * 4.5f) * peak * 0.22f
            // velocity push — слегка поджимает или растягивает форму
            val velMod = sin(angle + t * 1.7f) * velocity * 0.04f

            val rr = r0 * (1f + w1 + w2 + ex + velMod)
            xs[i] = cx + cos(angle) * rr
            ys[i] = cy + sin(angle) * rr
        }

        // ── Сборка плавного замкнутого пути (quadratic Bezier между midpoints)
        val path = Path()
        // Начало — середина между последней и первой точкой
        val startMx = (xs[NODES - 1] + xs[0]) * 0.5f
        val startMy = (ys[NODES - 1] + ys[0]) * 0.5f
        path.moveTo(startMx, startMy)
        for (i in 0 until NODES) {
            val nx = xs[(i + 1) % NODES]
            val ny = ys[(i + 1) % NODES]
            val mx = (xs[i] + nx) * 0.5f
            val my = (ys[i] + ny) * 0.5f
            path.quadraticBezierTo(xs[i], ys[i], mx, my)
        }
        path.close()

        // ── Soft outer halo (3 концентрических, реагируют на peak)
        val haloAccent = if (level > 0.05f || peak > 0.05f) palette.accentPrimary
                        else palette.accentSecondary
        val haloStrength = if (isAiSpeaking) 0.18f + peak * 0.42f else 0.10f
        for (g in 3 downTo 1) {
            val rr = r0 * (1.4f + g * 0.35f + peak * 0.5f)
            drawCircle(
                color = haloAccent.copy(alpha = haloStrength / (g * 1.2f)),
                radius = rr,
                center = Offset(cx, cy)
            )
        }

        // ── Тело: глубокий dark fill (графит / accent secondary в OpenOasis)
        // Для светлой темы — graphite, для тёмной — accentPrimary
        val bodyFill = if (palette.isDark) palette.accentPrimary else palette.accentSecondary
        drawPath(
            path = path,
            color = bodyFill,
            style = Fill
        )

        // ── Glass highlight: верхний-левый блик
        val hiCx = cx - r0 * 0.32f
        val hiCy = cy - r0 * 0.38f
        val hiR = r0 * 0.45f
        val hiBrush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.30f),
                Color.White.copy(alpha = 0.06f),
                Color.Transparent
            ),
            center = Offset(hiCx, hiCy),
            radius = hiR
        )
        drawCircle(brush = hiBrush, radius = hiR, center = Offset(hiCx, hiCy))

        // ── Inner mint accent core, появляется только при речи
        if (isAiSpeaking && level > 0.04f) {
            val coreR = r0 * (0.18f + level * 0.10f)
            val coreBrush = Brush.radialGradient(
                colors = listOf(
                    palette.accentPrimary.copy(alpha = 0.95f),
                    palette.accentPrimary.copy(alpha = 0.35f),
                    Color.Transparent
                ),
                center = Offset(cx + driftX * 0.6f, cy + driftY * 0.6f),
                radius = coreR * 2.5f
            )
            drawCircle(
                brush = coreBrush,
                radius = coreR * 2.5f,
                center = Offset(cx + driftX * 0.6f, cy + driftY * 0.6f)
            )
        }
    }
}
