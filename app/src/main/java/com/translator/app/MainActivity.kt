package com.translator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.translator.app.presentation.navigation.AppNavGraph
import com.translator.app.presentation.settings.SettingsViewModel
import com.translator.app.presentation.theme.GeminiLiveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            val themeId = com.translator.app.presentation.theme.AppThemeId.fromName(settings.themeId)
            GeminiLiveTheme(themeId = themeId) {
                AppNavGraph()
            }
        }
    }
}