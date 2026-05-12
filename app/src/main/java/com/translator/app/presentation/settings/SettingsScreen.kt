// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (v3.0)
// Путь: app/src/main/java/com/translator/app/presentation/settings/SettingsScreen.kt
//
// Полный набор настроек + 2 новые секции вверху:
//   1. ThemePickerSection         — 6 тем с живой кроссфейд-сменой
//   2. MessageRevealPickerSection — 5 стилей появления карточек
//
// Все настройки применяются МГНОВЕННО через viewModel.update {}.
// DataStore сохраняет данные асинхронно (см. SettingsViewModel) —
// UI не блокируется и не ждёт записи.
//
// При смене темы: LocalAppPalette сразу начинает интерполировать (~620мс).
// При смене reveal: LocalMessageReveal обновляется немедленно.
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.translator.app.presentation.theme.AppThemeId
import com.translator.app.presentation.translator.reveal.MessageRevealId

private val AVAILABLE_VOICES = listOf(
    "Puck"   to "Puck ♂ (энергичный)",
    "Charon" to "Charon ♂ (серьёзный)",
    "Fenrir" to "Fenrir ♂ (низкий)",
    "Orus"   to "Orus ♂ (дружелюбный)",
    "Kore"   to "Kore ♀ (мягкий)",
    "Aoede"  to "Aoede ♀ (тёплый)",
    "Leda"   to "Leda ♀ (живой)",
    "Zephyr" to "Zephyr ♀ (спокойный)"
)

private val LATENCY_PROFILES = listOf(
    "Off"       to "Off — мгновенный ответ",
    "UltraLow"  to "Ultra Low — minimal thinking",
    "Low"       to "Low — light thinking",
    "Balanced"  to "Balanced — medium thinking",
    "Reasoning" to "Reasoning — deep thinking"
)

private val VAD_START_SENS = listOf("START_SENSITIVITY_LOW", "START_SENSITIVITY_HIGH")
private val VAD_END_SENS   = listOf("END_SENSITIVITY_LOW", "END_SENSITIVITY_HIGH")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLogs: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var backupKeyVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.W700) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.AutoMirrored.Filled.List, "Логи")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ─── ВНЕШНИЙ ВИД ────────────────────────────────────────
            ThemePickerSection(
                selected = AppThemeId.fromName(s.themeId),
                onSelect = { id -> viewModel.update { copy(themeId = id.name) } }
            )
            MessageRevealPickerSection(
                selected = MessageRevealId.fromName(s.messageRevealId),
                onSelect = { id -> viewModel.update { copy(messageRevealId = id.name) } }
            )

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ─── 1. AUTH ───
                SectionTitle("Авторизация")

                OutlinedTextField(
                    value = s.apiKey,
                    onValueChange = { viewModel.update { copy(apiKey = it) } },
                    label = { Text("API Ключ Gemini") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { keyVisible = !keyVisible }) {
                            Text(if (keyVisible) "скрыть" else "показать", fontSize = 12.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = s.apiKeyBackup,
                    onValueChange = { viewModel.update { copy(apiKeyBackup = it) } },
                    label = { Text("Резервный API ключ (опционально)") },
                    singleLine = true,
                    visualTransformation = if (backupKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { backupKeyVisible = !backupKeyVisible }) {
                            Text(if (backupKeyVisible) "скрыть" else "показать", fontSize = 12.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                SwitchRow(
                    title = "Авто-ротация ключей при rate-limit",
                    checked = s.autoRotateKeys,
                    onChange = { v -> viewModel.update { copy(autoRotateKeys = v) } }
                )

                // ─── 2. VOICE ───
                SectionTitle("Голос")
                DropdownSetting(
                    label = "Голос ИИ",
                    value = s.voiceId,
                    options = AVAILABLE_VOICES,
                    onSelect = { v -> viewModel.update { copy(voiceId = v) } }
                )

                // ─── 3. AUDIO ───
                SectionTitle("Аудио")
                SliderRow(
                    title = "Громкость ИИ: ${s.playbackVolume}%",
                    value = s.playbackVolume.toFloat(),
                    range = 0f..100f,
                    onChange = { v -> viewModel.update { copy(playbackVolume = v.toInt()) } }
                )
                SliderRow(
                    title = "Усиление динамика (boost): ${"%.2f".format(s.playbackBoost)}x",
                    value = s.playbackBoost,
                    range = 1.0f..2.0f,
                    onChange = { v -> viewModel.update { copy(playbackBoost = v) } }
                )
                Text(
                    "Программный boost после AudioTrack. Speaker звучит заметно громче. По умолчанию 1.6x.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SliderRow(
                    title = "Чувствительность микрофона: ${s.micGain}%",
                    value = s.micGain.toFloat(),
                    range = 50f..200f,
                    onChange = { v -> viewModel.update { copy(micGain = v.toInt()) } }
                )
                SwitchRow(
                    title = "Громкоговоритель (Speaker)",
                    checked = s.forceSpeakerOutput,
                    onChange = { v -> viewModel.update { copy(forceSpeakerOutput = v) } }
                )
                SwitchRow(
                    title = "Эхоподавление (AEC)",
                    checked = s.useAec,
                    onChange = { v -> viewModel.update { copy(useAec = v) } }
                )
                SwitchRow(
                    title = "Отправлять audio_stream_end",
                    checked = s.sendAudioStreamEnd,
                    onChange = { v -> viewModel.update { copy(sendAudioStreamEnd = v) } }
                )

                // ─── 4. JITTER ───
                SectionTitle("Jitter buffer")
                SliderRow(
                    title = "Pre-buffer chunks: ${s.jitterPreBufferChunks}",
                    value = s.jitterPreBufferChunks.toFloat(),
                    range = 1f..10f, steps = 8,
                    onChange = { v -> viewModel.update { copy(jitterPreBufferChunks = v.toInt()) } }
                )
                SliderRow(
                    title = "Timeout: ${s.jitterTimeoutMs}ms",
                    value = s.jitterTimeoutMs.toFloat(),
                    range = 50f..500f,
                    onChange = { v -> viewModel.update { copy(jitterTimeoutMs = v.toLong()) } }
                )
                SliderRow(
                    title = "Queue capacity: ${s.playbackQueueCapacity}",
                    value = s.playbackQueueCapacity.toFloat(),
                    range = 64f..512f,
                    onChange = { v -> viewModel.update { copy(playbackQueueCapacity = v.toInt()) } }
                )

                // ─── 5. VAD ───
                SectionTitle("VAD (Voice Activity Detection)")
                SwitchRow(
                    title = "Серверный VAD",
                    checked = s.enableServerVad,
                    onChange = { v -> viewModel.update { copy(enableServerVad = v) } }
                )
                DropdownSetting(
                    label = "Чувствительность начала речи",
                    value = s.vadStartSensitivity,
                    options = VAD_START_SENS.map { it to it },
                    onSelect = { v -> viewModel.update { copy(vadStartSensitivity = v) } }
                )
                DropdownSetting(
                    label = "Чувствительность конца речи",
                    value = s.vadEndSensitivity,
                    options = VAD_END_SENS.map { it to it },
                    onSelect = { v -> viewModel.update { copy(vadEndSensitivity = v) } }
                )
                SliderRow(
                    title = "Тишина для конца фразы: ${s.vadSilenceDurationMs}ms",
                    value = s.vadSilenceDurationMs.toFloat(),
                    range = 100f..2000f,
                    onChange = { v -> viewModel.update { copy(vadSilenceDurationMs = v.toInt()) } }
                )

                // ─── 6. TRANSCRIPTION ───
                SectionTitle("Транскрипция")
                SwitchRow(
                    title = "Транскрипция входа (что вы сказали)",
                    checked = s.inputTranscription,
                    onChange = { v -> viewModel.update { copy(inputTranscription = v) } }
                )
                SwitchRow(
                    title = "Транскрипция выхода (что сказала модель)",
                    checked = s.outputTranscription,
                    onChange = { v -> viewModel.update { copy(outputTranscription = v) } }
                )

                // ─── 7. LATENCY ───
                SectionTitle("Латентность / Thinking")
                DropdownSetting(
                    label = "Профиль латентности",
                    value = s.latencyProfile,
                    options = LATENCY_PROFILES,
                    onSelect = { v -> viewModel.update { copy(latencyProfile = v) } }
                )

                // ─── 8. SESSION ───
                SectionTitle("Сессия")
                SwitchRow(
                    title = "Возобновление сессии (resumption)",
                    checked = s.enableSessionResumption,
                    onChange = { v -> viewModel.update { copy(enableSessionResumption = v) } }
                )
                SwitchRow(
                    title = "Сжатие контекста",
                    checked = s.enableContextCompression,
                    onChange = { v -> viewModel.update { copy(enableContextCompression = v) } }
                )

                // ─── 9. RECONNECT ───
                SectionTitle("Переподключение")
                SliderRow(
                    title = "Макс. попыток: ${s.maxReconnectAttempts}",
                    value = s.maxReconnectAttempts.toFloat(),
                    range = 1f..10f, steps = 8,
                    onChange = { v -> viewModel.update { copy(maxReconnectAttempts = v.toInt()) } }
                )
                SliderRow(
                    title = "Базовая задержка: ${s.reconnectBaseDelayMs}ms",
                    value = s.reconnectBaseDelayMs.toFloat(),
                    range = 500f..10000f,
                    onChange = { v -> viewModel.update { copy(reconnectBaseDelayMs = v.toLong()) } }
                )
                SliderRow(
                    title = "Макс. задержка: ${s.reconnectMaxDelayMs}ms",
                    value = s.reconnectMaxDelayMs.toFloat(),
                    range = 5000f..60000f,
                    onChange = { v -> viewModel.update { copy(reconnectMaxDelayMs = v.toLong()) } }
                )

                // ─── 10. DEBUG ───
                SectionTitle("Отладка")
                SwitchRow(
                    title = "Логировать сырые WebSocket-кадры",
                    checked = s.logRawWebSocketFrames,
                    onChange = { v -> viewModel.update { copy(logRawWebSocketFrames = v) } }
                )
                SwitchRow(
                    title = "Показывать счётчик токенов",
                    checked = s.showUsageMetadata,
                    onChange = { v -> viewModel.update { copy(showUsageMetadata = v) } }
                )

                OutlinedButton(
                    onClick = onNavigateToLogs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть логи")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column {
        Text(title, fontSize = 13.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (k, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = { onSelect(k); expanded = false }
                )
            }
        }
    }
}
