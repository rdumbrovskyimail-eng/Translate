// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (v3.0)
// Путь: app/src/main/java/com/translator/app/MainActivity.kt
//
// Корень приложения. Читает AppSettings из ViewModel, строит:
//   • GeminiLiveTheme(themeId)            → LocalAppPalette с интерполяцией
//   • CompositionLocalProvider(LocalMessageReveal) → live-стиль сообщений
//   • AppNavGraph()                       → весь UI
//
// При смене темы или стиля в SettingsScreen — оба провайдера получают
// новые значения МГНОВЕННО (через collectAsStateWithLifecycle).
// UI не перемонтируется, а только перетекает в новое состояние.
// ═══════════════════════════════════════════════════════════
package com.translator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.translator.app.presentation.navigation.AppNavGraph
import com.translator.app.presentation.settings.SettingsViewModel
import com.translator.app.presentation.theme.AppThemeId
import com.translator.app.presentation.theme.GeminiLiveTheme
import com.translator.app.presentation.translator.reveal.LocalMessageReveal
import com.translator.app.presentation.translator.reveal.MessageRevealId
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val settings by settingsVm.settings.collectAsStateWithLifecycle()

            val themeId = AppThemeId.fromName(settings.themeId)
            val revealId = MessageRevealId.fromName(settings.messageRevealId)

            GeminiLiveTheme(themeId = themeId) {
                CompositionLocalProvider(LocalMessageReveal provides revealId) {
                    AppNavGraph()
                }
            }
        }
    }
}
