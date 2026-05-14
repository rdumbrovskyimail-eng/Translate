package com.translator.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.translator.app.presentation.navigation.AppNavGraph
import com.translator.app.presentation.theme.GeminiLiveTheme
import com.translator.app.presentation.theme.ThemeViewModel
import com.translator.app.presentation.translator.reveal.LocalMessageReveal
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op, лучше иметь нотификацию, но без неё работаем */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val themeId by themeVm.themeId.collectAsStateWithLifecycle()
            val revealId by themeVm.revealId.collectAsStateWithLifecycle()

            GeminiLiveTheme(themeId = themeId) {
                CompositionLocalProvider(LocalMessageReveal provides revealId) {
                    AppNavGraph()
                }
            }
        }
    }
}