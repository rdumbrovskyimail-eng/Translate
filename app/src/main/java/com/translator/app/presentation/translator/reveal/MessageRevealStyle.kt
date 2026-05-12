// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/translator/reveal/MessageRevealStyle.kt
//
// Пять production-grade стилей появления карточек перевода.
// Применяется одинаково ко ВСЕМ темам — независимый слой UX.
//
// Стили (от минимума до максимума):
//   1. INSTANT       — мгновенно, без анимации. Для скорости и accessibility.
//   2. SOFT_FADE     — fade-in + микро-translation, 240мс, FastOutSlowIn.
//   3. SPRING_SLIDE  — spring-снизу + scale 0.94→1, мягкая физика iOS.
//   4. LIQUID_MORPH  — clipPath расширяется (морфит из горизонтальной линии),
//                      одновременно blur 12dp → 0dp + fade-in.
//   5. TYPEWRITER    — карточка появляется сразу, текст постепенно
//                      раскрывается посимвольно (15 chars/sec).
//
// Любой стиль читает текущий MessageRevealId из настроек.
// CompositionLocal `LocalMessageReveal` пробрасывает выбранный стиль.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator.reveal

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// ───────────────────────────────────────────────────────────
//  Идентификатор стиля — сохраняется в AppSettings.messageRevealId
// ───────────────────────────────────────────────────────────
enum class MessageRevealId(val titleKey: String, val descKey: String) {
    INSTANT      ("reveal_instant",      "reveal_instant_desc"),
    SOFT_FADE    ("reveal_soft_fade",    "reveal_soft_fade_desc"),
    SPRING_SLIDE ("reveal_spring_slide", "reveal_spring_slide_desc"),
    LIQUID_MORPH ("reveal_liquid_morph", "reveal_liquid_morph_desc"),
    TYPEWRITER   ("reveal_typewriter",   "reveal_typewriter_desc");

    companion object {
        fun fromName(name: String?): MessageRevealId =
            entries.firstOrNull { it.name == name } ?: SOFT_FADE
    }
}

/**
 * Текущий выбранный стиль. По умолчанию SOFT_FADE (premium-default).
 * Реальное значение проставляется в TranslateScreen через
 * CompositionLocalProvider(LocalMessageReveal provides style) { ... }
 */
val LocalMessageReveal = staticCompositionLocalOf { MessageRevealId.SOFT_FADE }

/**
 * Удобный wrapper: оборачивает контент карточки в нужный reveal-эффект.
 * Вызывается один раз для каждой PairCard в TranslateScreen.
 *
 * @param itemKey стабильный ключ (pair.id) — определяет когда anim запускается.
 * @param content собственно содержимое карточки.
 */
@Composable
fun MessageReveal(
    itemKey: Any,
    style: MessageRevealId = LocalMessageReveal.current,
    content: @Composable () -> Unit
) {
    when (style) {
        MessageRevealId.INSTANT      -> InstantReveal(content)
        MessageRevealId.SOFT_FADE    -> SoftFadeReveal(itemKey, content)
        MessageRevealId.SPRING_SLIDE -> SpringSlideReveal(itemKey, content)
        MessageRevealId.LIQUID_MORPH -> LiquidMorphReveal(itemKey, content)
        MessageRevealId.TYPEWRITER   -> TypewriterReveal(itemKey, content)
    }
}

// ───────────────────────────────────────────────────────────
//  1. INSTANT — без обёрток
// ───────────────────────────────────────────────────────────
@Composable
private fun InstantReveal(content: @Composable () -> Unit) {
    content()
}

// ───────────────────────────────────────────────────────────
//  2. SOFT_FADE — fade-in + микро-сдвиг (8dp снизу) → 0
// ───────────────────────────────────────────────────────────
@Composable
private fun SoftFadeReveal(itemKey: Any, content: @Composable () -> Unit) {
    var mounted by remember(itemKey) { mutableStateOf(false) }
    LaunchedEffect(itemKey) { mounted = true }

    val alpha by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "softFadeAlpha"
    )
    val ty by animateFloatAsState(
        targetValue = if (mounted) 0f else 22f,
        animationSpec = tween(durationMillis = 320, easing = LinearOutSlowInEasing),
        label = "softFadeTy"
    )

    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = ty
        }
    ) { content() }
}

// ───────────────────────────────────────────────────────────
//  3. SPRING_SLIDE — упругое появление снизу, scale 0.94→1
// ───────────────────────────────────────────────────────────
@Composable
private fun SpringSlideReveal(itemKey: Any, content: @Composable () -> Unit) {
    var mounted by remember(itemKey) { mutableStateOf(false) }
    LaunchedEffect(itemKey) { mounted = true }

    val ty by animateFloatAsState(
        targetValue = if (mounted) 0f else 60f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "springTy"
    )
    val scale by animateFloatAsState(
        targetValue = if (mounted) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "springScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "springAlpha"
    )

    Box(
        modifier = Modifier.graphicsLayer {
            this.translationY = ty
            this.scaleX = scale
            this.scaleY = scale
            this.alpha = alpha
            this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        }
    ) { content() }
}

// ───────────────────────────────────────────────────────────
//  4. LIQUID_MORPH — blur 14→0 + fade + лёгкий vertical squash
// ───────────────────────────────────────────────────────────
@Composable
private fun LiquidMorphReveal(itemKey: Any, content: @Composable () -> Unit) {
    var mounted by remember(itemKey) { mutableStateOf(false) }
    LaunchedEffect(itemKey) { mounted = true }

    val progress by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "liquidProg"
    )

    val blurDp = (14f * (1f - progress))
    val scaleY = 0.86f + 0.14f * progress
    val scaleX = 1.02f - 0.02f * progress
    val alpha = progress.coerceAtMost(1f)

    Box(
        modifier = Modifier
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
                this.alpha = alpha
            }
            .blur(radius = blurDp.dp)
    ) { content() }
}

// ───────────────────────────────────────────────────────────
//  5. TYPEWRITER — карточка появляется сразу, текст пишется
//                  посимвольно. Эффект достигается через
//                  CompositionLocal `LocalTypewriterProgress`
//                  и хелпер typewriterText() для строк.
//
//  Карточка просто фейдится за 120мс, а текст внутри сам себя
//  «обрезает» через прогресс (см. typewriterText() ниже).
// ───────────────────────────────────────────────────────────
@Composable
private fun TypewriterReveal(itemKey: Any, content: @Composable () -> Unit) {
    var mounted by remember(itemKey) { mutableStateOf(false) }
    LaunchedEffect(itemKey) { mounted = true }

    val alpha by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "typeAlpha"
    )

    Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
        // Внутренний CompositionLocal: сигнализирует Text-блокам,
        // что они должны раскрываться посимвольно.
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTypewriterEnabled provides true
        ) { content() }
    }
}

/** Сигнал «включён typewriter» — потребители (TranscriptBlock) обрезают строку. */
val LocalTypewriterEnabled = staticCompositionLocalOf { false }

/**
 * Хелпер: возвращает прогрессивно «открывающийся» текст.
 * Если typewriter выключен — возвращает [text] как есть.
 * Если включён — открывает символы со скоростью [charsPerSec] (по умолчанию 32).
 *
 * Использование:
 *   val displayed = typewriterText(pair.translationText)
 *   Text(displayed, ...)
 */
@Composable
fun typewriterText(text: String, charsPerSec: Float = 32f): String {
    val enabled = LocalTypewriterEnabled.current
    if (!enabled || text.isEmpty()) return text

    val progress = remember(text) { mutableFloatStateOf(0f) }

    LaunchedEffect(text) {
        var prev = System.nanoTime()
        // Считаем целевую длительность от длины строки
        val durSec = text.length / charsPerSec
        while (progress.floatValue < 1f) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now
                progress.floatValue =
                    (progress.floatValue + dt / durSec.coerceAtLeast(0.1f))
                        .coerceAtMost(1f)
            }
        }
    }

    val take = (text.length * progress.floatValue).toInt().coerceAtMost(text.length)
    // Курсор «▌» в конце пока идёт печать
    return if (take < text.length) text.substring(0, take) + "▌" else text
}
