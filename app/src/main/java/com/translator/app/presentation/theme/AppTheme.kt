// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (v3.0 — Live Interpolation Engine)
// Путь: app/src/main/java/com/translator/app/presentation/theme/AppTheme.kt
//
// Шесть production-grade тем + бесшовная live-смена.
//
// Темы:
//   1. AURORA       — премиум light, navy↔amber градиент (default)
//   2. BERLIN_MIST  — холодный nordic light, anthracite + crisp blue
//   3. SAKURA       — тёплый light, dusty rose + teal
//   4. OBSIDIAN     — глубокий OLED dark, teal + lavender (true-black)
//   5. OPEN_OASIS   — стиль ChatGPT Voice: мятный + графит, минимализм
//   6. GEMINI_NEXUS — стиль Google Gemini: cosmic mesh, blue→magenta→amber
//
// Live-интерполяция:
//   AnimatedAppPalette() оборачивает целевую палитру и анимирует
//   КАЖДОЕ цветовое поле через animateColorAsState. Смена темы в
//   настройках мгновенно перетекает в UI — без перерисовки графа,
//   без рестарта. Длительность 600 мс с FastOutSlowIn.
//
// Каждое поле палитры — Color (для прямого чтения в Composable).
// CompositionLocal `LocalAppPalette` пробрасывает интерполированную
// палитру в любой Composable дерева.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ───────────────────────────────────────────────────────────
//  Идентификатор темы — сериализуется как String в AppSettings.themeId
// ───────────────────────────────────────────────────────────
enum class AppThemeId(val displayKey: String) {
    AURORA("theme_aurora"),
    BERLIN_MIST("theme_berlin"),
    SAKURA("theme_sakura"),
    OBSIDIAN("theme_obsidian"),
    OPEN_OASIS("theme_open_oasis"),
    GEMINI_NEXUS("theme_gemini_nexus"),
    GEM("theme_gem");

    companion object {
        fun fromName(name: String?): AppThemeId =
            entries.firstOrNull { it.name == name } ?: AURORA
    }
}

/**
 * Полная семантическая палитра — единственный источник цветов в UI.
 * Любой Composable читает её через LocalAppPalette.current.
 */
@Immutable
data class AppPalette(
    val id: AppThemeId,
    val isDark: Boolean,

    // ── Surfaces ──
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHigh: Color,

    // ── Text ──
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textOnAccent: Color,

    // ── Accent ──
    val accentPrimary: Color,
    val accentSecondary: Color,
    val accentSoft: Color,

    // ── Borders / dividers ──
    val border: Color,
    val divider: Color,

    // ── Status ──
    val statusRecording: Color,
    val statusOk: Color,
    val statusWarning: Color,

    // ── Aura (для voice animation) — ровно 5 цветов для предсказуемой интерполяции ──
    val aura0: Color,
    val aura1: Color,
    val aura2: Color,
    val aura3: Color,
    val aura4: Color,
    val auraGlow: Color
) {
    /** Удобный доступ для анимаций, читающих градиент целиком. */
    val auraGradient: List<Color> get() = listOf(aura0, aura1, aura2, aura3, aura4)

    companion object {
        // ─── 1. AURORA ──────────────────────────────────────
        val Aurora = AppPalette(
            id = AppThemeId.AURORA, isDark = false,
            background       = Color(0xFFFAF9F6),
            surface          = Color(0xFFFFFFFF),
            surfaceElevated  = Color(0xFFFFFEFD),
            surfaceHigh      = Color(0xFFF3F2EB),
            textPrimary      = Color(0xFF141724),
            textSecondary    = Color(0xFF51556B),
            textMuted        = Color(0xFF9094A8),
            textOnAccent     = Color(0xFFFFFFFF),
            accentPrimary    = Color(0xFF1E3A8A),
            accentSecondary  = Color(0xFFC2691A),
            accentSoft       = Color(0xFFE9EEF8),
            border           = Color(0xFFE3DED8),
            divider          = Color(0xFFEFEEE8),
            statusRecording  = Color(0xFFD64545),
            statusOk         = Color(0xFF2E7D5B),
            statusWarning    = Color(0xFFC2691A),
            aura0 = Color(0xFF1E3A8A),
            aura1 = Color(0xFF5B4FC4),
            aura2 = Color(0xFFC2691A),
            aura3 = Color(0xFFD64545),
            aura4 = Color(0xFF1E3A8A),
            auraGlow = Color(0xFF335AC0)
        )

        // ─── 2. BERLIN MIST ─────────────────────────────────
        val BerlinMist = AppPalette(
            id = AppThemeId.BERLIN_MIST, isDark = false,
            background       = Color(0xFFF4F5F7),
            surface          = Color(0xFFFFFFFF),
            surfaceElevated  = Color(0xFFFFFFFF),
            surfaceHigh      = Color(0xFFEBEDF0),
            textPrimary      = Color(0xFF111827),
            textSecondary    = Color(0xFF4B5563),
            textMuted        = Color(0xFF9CA3AF),
            textOnAccent     = Color(0xFFFFFFFF),
            accentPrimary    = Color(0xFF1F2937),
            accentSecondary  = Color(0xFF2563EB),
            accentSoft       = Color(0xFFE5E7EB),
            border           = Color(0xFFE5E7EB),
            divider          = Color(0xFFEEF0F3),
            statusRecording  = Color(0xFFDC2626),
            statusOk         = Color(0xFF16A34A),
            statusWarning    = Color(0xFFD97706),
            aura0 = Color(0xFF1F2937),
            aura1 = Color(0xFF374151),
            aura2 = Color(0xFF2563EB),
            aura3 = Color(0xFF60A5FA),
            aura4 = Color(0xFF1F2937),
            auraGlow = Color(0xFF2563EB)
        )

        // ─── 3. SAKURA ──────────────────────────────────────
        val Sakura = AppPalette(
            id = AppThemeId.SAKURA, isDark = false,
            background       = Color(0xFFFCF8F3),
            surface          = Color(0xFFFFFCF7),
            surfaceElevated  = Color(0xFFFFFFFF),
            surfaceHigh      = Color(0xFFF6EFE5),
            textPrimary      = Color(0xFF3A2E26),
            textSecondary    = Color(0xFF6B5A4F),
            textMuted        = Color(0xFFA89589),
            textOnAccent     = Color(0xFFFFFFFF),
            accentPrimary    = Color(0xFFB45F8F),
            accentSecondary  = Color(0xFF5B8C8C),
            accentSoft       = Color(0xFFF5E0EA),
            border           = Color(0xFFEADDD0),
            divider          = Color(0xFFF1E8DC),
            statusRecording  = Color(0xFFC04A6A),
            statusOk         = Color(0xFF5B8C5B),
            statusWarning    = Color(0xFFC68642),
            aura0 = Color(0xFFB45F8F),
            aura1 = Color(0xFFD58BB0),
            aura2 = Color(0xFF5B8C8C),
            aura3 = Color(0xFF8FB5B5),
            aura4 = Color(0xFFB45F8F),
            auraGlow = Color(0xFFB45F8F)
        )

        // ─── 4. OBSIDIAN ────────────────────────────────────
        val Obsidian = AppPalette(
            id = AppThemeId.OBSIDIAN, isDark = true,
            background       = Color(0xFF000000),
            surface          = Color(0xFF0B0D0F),
            surfaceElevated  = Color(0xFF131519),
            surfaceHigh      = Color(0xFF1A1D23),
            textPrimary      = Color(0xFFF0F1F5),
            textSecondary    = Color(0xFFA5A9B8),
            textMuted        = Color(0xFF5D6171),
            textOnAccent     = Color(0xFF0B0D0F),
            accentPrimary    = Color(0xFF14B8A6),
            accentSecondary  = Color(0xFFA78BFA),
            accentSoft       = Color(0x2214B8A6),
            border           = Color(0xFF1F1F26),
            divider          = Color(0xFF1A1A1F),
            statusRecording  = Color(0xFFF87171),
            statusOk         = Color(0xFF34D399),
            statusWarning    = Color(0xFFFBBF24),
            aura0 = Color(0xFF0F766E),
            aura1 = Color(0xFF14B8A6),
            aura2 = Color(0xFF22D3EE),
            aura3 = Color(0xFFA78BFA),
            aura4 = Color(0xFF7C3AED),
            auraGlow = Color(0xFF22D3EE)
        )

        // ─── 5. OPEN OASIS (ChatGPT-style) ──────────────────
        val OpenOasis = AppPalette(
            id = AppThemeId.OPEN_OASIS, isDark = false,
            background       = Color(0xFFFFFFFF),
            surface          = Color(0xFFFDFDFD),
            surfaceElevated  = Color(0xFFF7F7F8),
            surfaceHigh      = Color(0xFFEAECEE),
            textPrimary      = Color(0xFF0D0D0D),
            textSecondary    = Color(0xFF606164),
            textMuted        = Color(0xFFACAEB3),
            textOnAccent     = Color(0xFFFFFFFF),
            accentPrimary    = Color(0xFF18A07A),
            accentSecondary  = Color(0xFF0D0D0D),
            accentSoft       = Color(0xFFEDF8F5),
            border           = Color(0xFFE8EAEE),
            divider          = Color(0xFFF1F2F4),
            statusRecording  = Color(0xFFEA545C),
            statusOk         = Color(0xFF18A07A),
            statusWarning    = Color(0xFFF89539),
            // Графитовая монохромная аура с мятным акцентом — фирменный ChatGPT-look
            aura0 = Color(0xFF0D0D0D),
            aura1 = Color(0xFF2D2D30),
            aura2 = Color(0xFF505055),
            aura3 = Color(0xFF18A07A),
            aura4 = Color(0xFF0D0D0D),
            auraGlow = Color(0xFF18A07A)
        )

        // ─── 6. GEMINI NEXUS (Google Gemini-style) ──────────
        val GeminiNexus = AppPalette(
            id = AppThemeId.GEMINI_NEXUS, isDark = false,
            background       = Color(0xFFF5F7FA),
            surface          = Color(0xFFFFFFFF),
            surfaceElevated  = Color(0xFFFFFFFF),
            surfaceHigh      = Color(0xFFE9EEF4),
            textPrimary      = Color(0xFF1C2235),
            textSecondary    = Color(0xFF5B6275),
            textMuted        = Color(0xFF9095A6),
            textOnAccent     = Color(0xFFFFFFFF),
            accentPrimary    = Color(0xFF1760E4),
            accentSecondary  = Color(0xFFB52382),
            accentSoft       = Color(0xFFDEE9FC),
            border           = Color(0xFFE0E5EE),
            divider          = Color(0xFFEEF1F6),
            statusRecording  = Color(0xFFDE2938),
            statusOk         = Color(0xFF229048),
            statusWarning    = Color(0xFFDC731B),
            // Signature Gemini mesh: blue → violet → magenta → coral → amber
            aura0 = Color(0xFF4579F2),
            aura1 = Color(0xFF7D20BE),
            aura2 = Color(0xFFB52382),
            aura3 = Color(0xFFF65B65),
            aura4 = Color(0xFFFABF51),
            auraGlow = Color(0xFF7D20BE)
        )

        // ─── 7. GEM (Apple-clean + Gemini Sparkle) ──────────
        val Gem = AppPalette(
            id = AppThemeId.GEM, isDark = false,
            background       = Color(0xFFFFFFFF),
            surface          = Color(0xFFFFFFFF),
            surfaceElevated  = Color(0xFFFFFFFF),
            surfaceHigh      = Color(0xFFF5F8FC),
            textPrimary      = Color(0xFF0A0A0A),
            textSecondary    = Color(0xFF6E6E73),
            textMuted        = Color(0xFFAEAEB2),
            textOnAccent     = Color(0xFFFFFFFF),
            accentPrimary    = Color(0xFF4285F4),
            accentSecondary  = Color(0xFF34A853),
            accentSoft       = Color(0xFFE8F0FE),
            border           = Color(0xFFBAE6FD),
            divider          = Color(0xFF7DD3FC),
            statusRecording  = Color(0xFF34A853),
            statusOk         = Color(0xFF34A853),
            statusWarning    = Color(0xFFFBBC04),
            aura0 = Color(0xFF34A853),
            aura1 = Color(0xFF06B6D4),
            aura2 = Color(0xFF4285F4),
            aura3 = Color(0xFFFBBC04),
            aura4 = Color(0xFFEA4335),
            auraGlow = Color(0xFF34A853)
        )

        fun byId(id: AppThemeId): AppPalette = when (id) {
            AppThemeId.AURORA       -> Aurora
            AppThemeId.BERLIN_MIST  -> BerlinMist
            AppThemeId.SAKURA       -> Sakura
            AppThemeId.OBSIDIAN     -> Obsidian
            AppThemeId.OPEN_OASIS   -> OpenOasis
            AppThemeId.GEMINI_NEXUS -> GeminiNexus
            AppThemeId.GEM          -> Gem
        }
    }
}

// ───────────────────────────────────────────────────────────
//  Live interpolation engine
// ───────────────────────────────────────────────────────────
private const val THEME_TRANSITION_MS = 620

/**
 * Принимает целевую палитру (мгновенную, по themeId) и возвращает
 * палитру, в которой КАЖДОЕ цветовое поле плавно интерполируется
 * через animateColorAsState. При смене темы все цвета UI «перетекают»
 * за ~620 мс.
 */
@Composable
fun rememberAnimatedPalette(target: AppPalette): AppPalette {
    val spec = tween<Color>(durationMillis = THEME_TRANSITION_MS, easing = FastOutSlowInEasing)

    return AppPalette(
        id = target.id,                 // id меняем мгновенно — для логики, не для рисования
        isDark = target.isDark,         // флаг — мгновенно
        background       = animateColorAsState(target.background,      spec, label = "bg").value,
        surface          = animateColorAsState(target.surface,         spec, label = "sf").value,
        surfaceElevated  = animateColorAsState(target.surfaceElevated, spec, label = "sfE").value,
        surfaceHigh      = animateColorAsState(target.surfaceHigh,     spec, label = "sfH").value,
        textPrimary      = animateColorAsState(target.textPrimary,     spec, label = "tP").value,
        textSecondary    = animateColorAsState(target.textSecondary,   spec, label = "tS").value,
        textMuted        = animateColorAsState(target.textMuted,       spec, label = "tM").value,
        textOnAccent     = animateColorAsState(target.textOnAccent,    spec, label = "tA").value,
        accentPrimary    = animateColorAsState(target.accentPrimary,   spec, label = "aP").value,
        accentSecondary  = animateColorAsState(target.accentSecondary, spec, label = "aS").value,
        accentSoft       = animateColorAsState(target.accentSoft,      spec, label = "aSf").value,
        border           = animateColorAsState(target.border,          spec, label = "br").value,
        divider          = animateColorAsState(target.divider,         spec, label = "dv").value,
        statusRecording  = animateColorAsState(target.statusRecording, spec, label = "sR").value,
        statusOk         = animateColorAsState(target.statusOk,        spec, label = "sO").value,
        statusWarning    = animateColorAsState(target.statusWarning,   spec, label = "sW").value,
        aura0            = animateColorAsState(target.aura0,           spec, label = "g0").value,
        aura1            = animateColorAsState(target.aura1,           spec, label = "g1").value,
        aura2            = animateColorAsState(target.aura2,           spec, label = "g2").value,
        aura3            = animateColorAsState(target.aura3,           spec, label = "g3").value,
        aura4            = animateColorAsState(target.aura4,           spec, label = "g4").value,
        auraGlow         = animateColorAsState(target.auraGlow,        spec, label = "gl").value
    )
}

/** CompositionLocal — текущая интерполированная палитра. */
val LocalAppPalette = staticCompositionLocalOf { AppPalette.Aurora }
