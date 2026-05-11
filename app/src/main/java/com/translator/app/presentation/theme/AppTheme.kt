// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/translator/app/presentation/theme/AppTheme.kt
//
// Четыре production-grade темы:
//   1. AURORA      — премиум light, navy→amber градиенты (default)
//   2. BERLIN_MIST — холодный nordic light, anthracite минимализм
//   3. SAKURA      — тёплый light, dusty rose + teal
//   4. OBSIDIAN    — глубокий dark, electric teal + lavender (OLED)
//
// Каждая тема — полный набор семантических цветов.
// Через CompositionLocal любой Composable получает AppPalette.current.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Идентификатор темы — сохраняется в AppSettings.
 * Порядок важен: используется как index в settings.
 */
enum class AppThemeId(val displayKey: String) {
    AURORA("theme_aurora"),
    BERLIN_MIST("theme_berlin"),
    SAKURA("theme_sakura"),
    OBSIDIAN("theme_obsidian");

    companion object {
        fun fromName(name: String?): AppThemeId =
            entries.firstOrNull { it.name == name } ?: AURORA
    }
}

/**
 * Полная семантическая палитра — единственный источник цветов в UI.
 * Любой Composable читает её через AppPalette.current.
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
    val accentSoft: Color,         // фон для chip / soft container

    // ── Borders / dividers ──
    val border: Color,
    val divider: Color,

    // ── Status ──
    val statusRecording: Color,
    val statusOk: Color,
    val statusWarning: Color,

    // ── Aura colors (для voice animation) ──
    // Используются всеми 4 анимациями для градиентов / свечения.
    val auraGradient: List<Color>, // минимум 3, лучше 4-5
    val auraGlow: Color
) {
    companion object {
        val Aurora = AppPalette(
            id = AppThemeId.AURORA,
            isDark = false,
            background = Color(0xFFFAFAF7),
            surface = Color(0xFFFFFFFF),
            surfaceElevated = Color(0xFFFFFEFB),
            surfaceHigh = Color(0xFFF5F4EE),
            textPrimary = Color(0xFF1A1F2E),
            textSecondary = Color(0xFF4F5566),
            textMuted = Color(0xFF8B91A1),
            textOnAccent = Color(0xFFFFFFFF),
            accentPrimary = Color(0xFF1E3A8A),    // deep navy
            accentSecondary = Color(0xFFC2691A),  // burnt amber
            accentSoft = Color(0xFFE9EEF8),
            border = Color(0xFFE6E4DE),
            divider = Color(0xFFF0EEE8),
            statusRecording = Color(0xFFD64545),
            statusOk = Color(0xFF2E7D5B),
            statusWarning = Color(0xFFC2691A),
            auraGradient = listOf(
                Color(0xFF1E3A8A),  // navy
                Color(0xFF5B4FC4),  // indigo
                Color(0xFFC2691A),  // amber
                Color(0xFFD64545),  // rose
                Color(0xFF1E3A8A)   // loop back
            ),
            auraGlow = Color(0xFF1E3A8A)
        )

        val BerlinMist = AppPalette(
            id = AppThemeId.BERLIN_MIST,
            isDark = false,
            background = Color(0xFFF4F5F7),
            surface = Color(0xFFFFFFFF),
            surfaceElevated = Color(0xFFFFFFFF),
            surfaceHigh = Color(0xFFEBEDF0),
            textPrimary = Color(0xFF111827),
            textSecondary = Color(0xFF4B5563),
            textMuted = Color(0xFF9CA3AF),
            textOnAccent = Color(0xFFFFFFFF),
            accentPrimary = Color(0xFF1F2937),    // anthracite
            accentSecondary = Color(0xFF2563EB),  // crisp blue
            accentSoft = Color(0xFFE5E7EB),
            border = Color(0xFFE5E7EB),
            divider = Color(0xFFEEF0F3),
            statusRecording = Color(0xFFDC2626),
            statusOk = Color(0xFF16A34A),
            statusWarning = Color(0xFFD97706),
            auraGradient = listOf(
                Color(0xFF1F2937),
                Color(0xFF374151),
                Color(0xFF2563EB),
                Color(0xFF1F2937)
            ),
            auraGlow = Color(0xFF2563EB)
        )

        val Sakura = AppPalette(
            id = AppThemeId.SAKURA,
            isDark = false,
            background = Color(0xFFFCF8F3),
            surface = Color(0xFFFFFCF7),
            surfaceElevated = Color(0xFFFFFFFF),
            surfaceHigh = Color(0xFFF6EFE5),
            textPrimary = Color(0xFF3A2E26),
            textSecondary = Color(0xFF6B5A4F),
            textMuted = Color(0xFFA89589),
            textOnAccent = Color(0xFFFFFFFF),
            accentPrimary = Color(0xFFB45F8F),    // muted rose
            accentSecondary = Color(0xFF5B8C8C),  // dusty teal
            accentSoft = Color(0xFFF5E0EA),
            border = Color(0xFFEADDD0),
            divider = Color(0xFFF1E8DC),
            statusRecording = Color(0xFFC04A6A),
            statusOk = Color(0xFF5B8C5B),
            statusWarning = Color(0xFFC68642),
            auraGradient = listOf(
                Color(0xFFB45F8F),
                Color(0xFFD58BB0),
                Color(0xFF5B8C8C),
                Color(0xFF8FB5B5),
                Color(0xFFB45F8F)
            ),
            auraGlow = Color(0xFFB45F8F)
        )

        val Obsidian = AppPalette(
            id = AppThemeId.OBSIDIAN,
            isDark = true,
            background = Color(0xFF0A0A0B),         // true black для OLED
            surface = Color(0xFF111114),
            surfaceElevated = Color(0xFF15151A),
            surfaceHigh = Color(0xFF1B1C22),
            textPrimary = Color(0xFFE5E5E5),
            textSecondary = Color(0xFFA1A1AA),
            textMuted = Color(0xFF6B7280),
            textOnAccent = Color(0xFF0A0A0B),
            accentPrimary = Color(0xFF14B8A6),      // electric teal
            accentSecondary = Color(0xFFA78BFA),    // lavender
            accentSoft = Color(0xFF14B8A622),
            border = Color(0xFF1F1F26),
            divider = Color(0xFF1A1A1F),
            statusRecording = Color(0xFFF87171),
            statusOk = Color(0xFF34D399),
            statusWarning = Color(0xFFFBBF24),
            auraGradient = listOf(
                Color(0xFF14B8A6),
                Color(0xFF06B6D4),
                Color(0xFFA78BFA),
                Color(0xFF8B5CF6),
                Color(0xFF14B8A6)
            ),
            auraGlow = Color(0xFF14B8A6)
        )

        fun byId(id: AppThemeId): AppPalette = when (id) {
            AppThemeId.AURORA -> Aurora
            AppThemeId.BERLIN_MIST -> BerlinMist
            AppThemeId.SAKURA -> Sakura
            AppThemeId.OBSIDIAN -> Obsidian
        }
    }
}

/** CompositionLocal — текущая палитра в любом Composable. */
val LocalAppPalette = staticCompositionLocalOf { AppPalette.Aurora }

/** Удобный аксессор: AppPalette.current */
object LocalPaletteAccessor {
    val current: AppPalette
        @Composable
        @ReadOnlyComposable
        get() = androidx.compose.runtime.currentComposer.consume(LocalAppPalette)
}
