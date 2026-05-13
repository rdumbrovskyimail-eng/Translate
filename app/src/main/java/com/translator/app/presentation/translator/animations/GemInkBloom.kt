// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (v2.0 — Rainbow Cloud Field)
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/GemInkBloom.kt
//
// АНИМАЦИЯ ДЛЯ "GEM" — поле радужных облаков в стиле Gemini Sparkle.
//
// 6 огромных мягких radial-облаков плавают по Lissajous-орбитам.
// Цвета: ЗЕЛЁНЫЙ (доминанта) + бирюза + синий + янтарь + красный + фиолетовый.
// BlendMode.Plus → смешиваются как свет, Modifier.blur 36dp → воздушные.
// Зелёное облако приклеено к верху-центру, всегда самое большое и плотное.
// Idle ≈ 0.35 скорость, speaking → ×4-5 + амплитуда +30%, peak → яркая вспышка.
// Vertical/horizontal fade-маски на границах растворяют облака в белом.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator.animations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.translator.app.presentation.theme.AppPalette
import kotlinx.coroutines.flow.Flow
import kotlin.math.cos
import kotlin.math.sin

private data class CloudSpec(
    val color: Color,
    val freqX: Float,
    val freqY: Float,
    val phaseOff: Float,
    val sizeMul: Float,
    val alphaMul: Float
)

@Composable
fun GemInkBloom(
    palette: AppPalette,
    audioFlow: Flow<ByteArray>,
    isAiSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val m = rememberAudioMetrics(audioFlow, isAiSpeaking, attack = 0.55f, release = 0.08f)
    val level by m.level
    val peak by m.peak

    val phase = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now
                phase.floatValue += dt * (0.35f + level * 3.5f + peak * 1.4f)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ── СЛОЙ 1: цветные облака, всё под массивным blur'ом
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(radius = 36.dp)
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            val orbitRx = w * 0.42f
            val orbitRy = h * 0.42f
            val baseR = (w * 0.56f) * (1f + level * 0.18f + peak * 0.12f)

            val t = phase.floatValue

            // Зелёный — доминанта, фиолетовый — добавка к 5 цветам палитры
            val clouds = listOf(
                CloudSpec(palette.aura0, 0.42f, 0.55f, 0.0f, 1.30f, 1.00f),  // зелёный
                CloudSpec(palette.aura1, 0.78f, 0.61f, 1.1f, 0.95f, 0.85f),  // бирюза
                CloudSpec(palette.aura2, 0.55f, 0.92f, 2.3f, 1.05f, 0.85f),  // синий
                CloudSpec(palette.aura3, 0.71f, 0.48f, 3.4f, 0.90f, 0.78f),  // янтарь
                CloudSpec(palette.aura4, 0.88f, 0.73f, 4.6f, 0.80f, 0.72f),  // красный
                CloudSpec(Color(0xFF9C5BFF), 0.65f, 0.85f, 5.5f, 0.85f, 0.70f) // фиолетовый
            )

            clouds.forEachIndexed { i, c ->
                val orbitX: Float
                val orbitY: Float
                if (i == 0) {
                    // Зелёное облако — приклеено к верху-центру, лёгкий микро-дрейф
                    orbitX = cx + sin(t * 0.45f) * orbitRx * 0.20f
                    orbitY = cy - h * 0.08f + cos(t * 0.38f) * orbitRy * 0.18f
                } else {
                    orbitX = cx + cos(t * c.freqX + c.phaseOff) * orbitRx
                    orbitY = cy + sin(t * c.freqY + c.phaseOff) * orbitRy * 0.85f
                }

                val pulseR = 0.85f + 0.20f * sin(t * 1.3f + i * 0.7f).toFloat()
                val cloudR = baseR * c.sizeMul * pulseR

                val baseAlpha = c.alphaMul * (0.55f + level * 0.25f + peak * 0.15f)
                val cloudAlpha = (if (i == 0) baseAlpha * 1.15f else baseAlpha).coerceAtMost(1f)

                val brush = Brush.radialGradient(
                    colors = listOf(
                        c.color.copy(alpha = cloudAlpha),
                        c.color.copy(alpha = cloudAlpha * 0.55f),
                        c.color.copy(alpha = 0f)
                    ),
                    center = Offset(orbitX, orbitY),
                    radius = cloudR
                )
                drawCircle(
                    brush = brush,
                    radius = cloudR,
                    center = Offset(orbitX, orbitY),
                    blendMode = BlendMode.Plus
                )
            }
        }

        // ── СЛОЙ 2: fade-маски (top/bottom/left/right) — растворяют облака
        // в чистом белом по краям. Без них облака резко обрезались бы.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // TOP fade
            val topBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 1f),
                    Color.White.copy(alpha = 0.6f),
                    Color.White.copy(alpha = 0f)
                ),
                startY = 0f,
                endY = h * 0.28f
            )
            drawRect(brush = topBrush, topLeft = Offset.Zero, size = Size(w, h * 0.28f))

            // BOTTOM fade
            val bottomBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0f),
                    Color.White.copy(alpha = 0.5f),
                    Color.White.copy(alpha = 1f)
                ),
                startY = h * 0.78f,
                endY = h
            )
            drawRect(
                brush = bottomBrush,
                topLeft = Offset(0f, h * 0.78f),
                size = Size(w, h * 0.22f)
            )

            // LEFT fade
            val leftBrush = Brush.horizontalGradient(
                colors = listOf(Color.White, Color.Transparent),
                startX = 0f, endX = w * 0.08f
            )
            drawRect(brush = leftBrush, topLeft = Offset.Zero, size = Size(w * 0.08f, h))

            // RIGHT fade
            val rightBrush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.White),
                startX = w * 0.92f, endX = w
            )
            drawRect(
                brush = rightBrush,
                topLeft = Offset(w * 0.92f, 0f),
                size = Size(w * 0.08f, h)
            )
        }
    }
}