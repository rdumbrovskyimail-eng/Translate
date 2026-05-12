// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/settings/MessageRevealPickerSection.kt
//
// Секция «Появление сообщений» для SettingsScreen.
// Показывает 5 стилей карточками с живым превью (миниатюра).
// Tap → сохранение в AppSettings.messageRevealId → инъекция в
// LocalMessageReveal через корень навигации.
//
// Каждая карточка анимирует своё превью — пользователь видит
// «как будет выглядеть» прежде чем нажать.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translator.app.presentation.theme.AppPalette
import com.translator.app.presentation.theme.LocalAppPalette
import com.translator.app.presentation.translator.reveal.MessageRevealId

@Composable
fun MessageRevealPickerSection(
    selected: MessageRevealId,
    onSelect: (MessageRevealId) -> Unit
) {
    val palette = LocalAppPalette.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "ПОЯВЛЕНИЕ СООБЩЕНИЙ",
            fontSize = 11.sp, fontWeight = FontWeight.W700,
            letterSpacing = 1.5.sp, color = palette.textMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RevealOption(
                id = MessageRevealId.INSTANT,
                isSelected = selected == MessageRevealId.INSTANT,
                title = "Мгновенно",
                desc = "Без анимации. Максимум скорости.",
                onClick = { onSelect(MessageRevealId.INSTANT) }
            )
            RevealOption(
                id = MessageRevealId.SOFT_FADE,
                isSelected = selected == MessageRevealId.SOFT_FADE,
                title = "Мягкое появление",
                desc = "Плавный fade + микро-сдвиг. Деликатно.",
                onClick = { onSelect(MessageRevealId.SOFT_FADE) }
            )
            RevealOption(
                id = MessageRevealId.SPRING_SLIDE,
                isSelected = selected == MessageRevealId.SPRING_SLIDE,
                title = "Spring снизу",
                desc = "Упругий iOS-стиль с пружинкой и масштабом.",
                onClick = { onSelect(MessageRevealId.SPRING_SLIDE) }
            )
            RevealOption(
                id = MessageRevealId.LIQUID_MORPH,
                isSelected = selected == MessageRevealId.LIQUID_MORPH,
                title = "Liquid morph",
                desc = "Размытие → чёткость с лёгким сжатием.",
                onClick = { onSelect(MessageRevealId.LIQUID_MORPH) }
            )
            RevealOption(
                id = MessageRevealId.TYPEWRITER,
                isSelected = selected == MessageRevealId.TYPEWRITER,
                title = "Печатная машинка",
                desc = "Текст «печатается» посимвольно. ChatGPT-стиль.",
                onClick = { onSelect(MessageRevealId.TYPEWRITER) }
            )
        }
    }
}

@Composable
private fun RevealOption(
    id: MessageRevealId,
    isSelected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    val palette = LocalAppPalette.current
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "rOptBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) palette.accentSoft else palette.surface)
            .border(
                width = borderWidth,
                color = if (isSelected) palette.accentPrimary else palette.border,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Превью самой анимации в миниатюре
        RevealPreview(id = id, palette = palette)

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp, fontWeight = FontWeight.W700,
                color = palette.textPrimary, letterSpacing = (-0.2).sp
            )
            Text(
                text = desc,
                fontSize = 12.sp, color = palette.textSecondary, lineHeight = 16.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        // Selected indicator
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (isSelected) palette.accentPrimary else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (isSelected) palette.accentPrimary else palette.border,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(palette.textOnAccent)
                )
            }
        }
    }
}

/**
 * Живое превью каждого стиля — миниатюрная карточка 70×42 dp,
 * которая бесконечно повторяет соответствующую анимацию.
 */
@Composable
private fun RevealPreview(id: MessageRevealId, palette: AppPalette) {
    val transition = rememberInfiniteTransition(label = "previewT_$id")
    // Прогресс 0..1, повторяющийся с паузой 600 мс на «открытом» состоянии.
    val raw by transition.animateFloat(
        initialValue = 0f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rawT"
    )
    val p = (raw - 0.2f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .size(width = 70.dp, height = 42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.background)
            .border(0.5.dp, palette.border, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        when (id) {
            MessageRevealId.INSTANT -> RevealMini(p = 1f, alpha = 1f, ty = 0f, blurRadius = 0f, scale = 1f, palette = palette)
            MessageRevealId.SOFT_FADE -> RevealMini(
                p = p, alpha = p, ty = (1f - p) * 8f, blurRadius = 0f, scale = 1f, palette = palette
            )
            MessageRevealId.SPRING_SLIDE -> {
                val sp = if (p > 0.7f) {
                    val ph = (p - 0.7f) / 0.3f
                    1f + 0.05f * kotlin.math.sin(ph * Math.PI * 4).toFloat() * (1f - ph)
                } else p / 0.7f
                RevealMini(
                    p = p,
                    alpha = (p * 2f).coerceAtMost(1f),
                    ty = (1f - sp.coerceIn(0f, 1f)) * 16f,
                    blurRadius = 0f,
                    scale = 0.94f + 0.06f * sp.coerceIn(0f, 1f),
                    palette = palette
                )
            }
            MessageRevealId.LIQUID_MORPH -> RevealMini(
                p = p,
                alpha = p,
                ty = 0f,
                blurRadius = (1f - p) * 6f,
                scale = 0.92f + 0.08f * p,
                palette = palette
            )
            MessageRevealId.TYPEWRITER -> {
                // Полоса «текста» постепенно заполняется
                Box(
                    modifier = Modifier
                        .size(width = 50.dp, height = 28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.surfaceHigh)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .height(2.dp)
                            .fillMaxWidth(p.coerceAtMost(1f))
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.accentPrimary)
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp, top = 11.dp)
                            .height(2.dp)
                            .fillMaxWidth((p * 0.85f).coerceAtMost(1f) * 0.7f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.textMuted)
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp, top = 18.dp)
                            .height(2.dp)
                            .fillMaxWidth((p * 0.7f).coerceAtMost(1f) * 0.55f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.textMuted)
                    )
                }
            }
        }
    }
}

@Composable
private fun RevealMini(
    p: Float,
    alpha: Float,
    ty: Float,
    blurRadius: Float,
    scale: Float,
    palette: AppPalette
) {
    Box(
        modifier = Modifier
            .size(width = 50.dp, height = 28.dp)
            .graphicsLayer {
                this.alpha = alpha
                this.translationY = ty
                this.scaleX = scale
                this.scaleY = scale
            }
            .blur(radius = blurRadius.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(listOf(palette.surfaceHigh, palette.surfaceElevated))
            )
            .border(0.5.dp, palette.border, RoundedCornerShape(6.dp))
    )
}
