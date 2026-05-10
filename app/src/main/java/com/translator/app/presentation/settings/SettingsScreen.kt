package com.translator.app.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = s.apiKey,
                onValueChange = { viewModel.update { copy(apiKey = it) } },
                label = { Text("API Ключ") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("Аудио", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Громкость ИИ: ${s.playbackVolume}%")
                Slider(
                    value = s.playbackVolume.toFloat(),
                    onValueChange = { v -> viewModel.update { copy(playbackVolume = v.toInt()) } },
                    valueRange = 0f..100f,
                    modifier = Modifier.width(200.dp)
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Чувствительность микрофона: ${s.micGain}%")
                Slider(
                    value = s.micGain.toFloat(),
                    onValueChange = { v -> viewModel.update { copy(micGain = v.toInt()) } },
                    valueRange = 50f..200f,
                    modifier = Modifier.width(200.dp)
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Громкоговоритель (Speaker)")
                Switch(checked = s.forceSpeakerOutput, onCheckedChange = { v -> viewModel.update { copy(forceSpeakerOutput = v) } })
            }
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Эхоподавление (AEC)")
                Switch(checked = s.useAec, onCheckedChange = { v -> viewModel.update { copy(useAec = v) } })
            }
        }
    }
}