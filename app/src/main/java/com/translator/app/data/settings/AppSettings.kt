// ═══════════════════════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/translator/app/data/settings/AppSettings.kt
//
// ПОЛНАЯ ЗАМЕНА (v5.0 — дефолты уровня production-переводчика)
//
// Что изменено в дефолтах и почему:
//   • voiceId = "Charon" — самый ровный, чёткий и нейтральный голос Live API:
//     спокойный информативный тембр, который одинаково естественно звучит
//     и по-русски, и по-немецки, без «игривости» Aoede/Puck. Подходит для
//     любых ситуаций: от бытового разговора до врача/официальных учреждений.
//   • maxOutputTokens = 8192 — аудио-выход расходует токены ответа,
//     512 обрезали озвучку длинных фраз.
//   • VAD: end=LOW, silence=800, prefix=300, activityHandling=NO_INTERRUPTION —
//     переводчика не перебивают, длинные фразы не режутся.
//   • latencyProfile = "Low" — thinking минимум low (Off рвал длинные фразы).
//   • translatorLayoutId = "MINIMAL" — минималистичный экран по умолчанию.
//   • transcriptionLanguageCodes = emptyList() — native-audio модель сама
//     определяет язык; для bidirectional пары передавать коды вредно.
//
// ВАЖНО: сериализатор хранит настройки с encodeDefaults=true, поэтому новые
// дефолты применятся к СВЕЖЕЙ установке/сбросу настроек. На существующей
// установке старые сохранённые значения останутся (но TranslatorSession v5.0
// всё критичное — VAD, barge-in, токены — всё равно фиксирует жёстко).
// ═══════════════════════════════════════════════════════════════════════════
package com.translator.app.data.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { AUTO, LIGHT, DARK }

@Serializable
data class AppSettings(

    // ═══════════════════ 1. AUTH ═══════════════════
    val apiKey: String = "",
    val apiKeyBackup: String = "",
    val autoRotateKeys: Boolean = false,

    // ═══════════════════ 2. MODEL ═══════════════════
    // Короткий id модели. Префикс "models/" добавляется в GeminiLiveClient.buildFullSetup().
    val model: String = "gemini-3.1-flash-live-preview",

    // Translator-режим: низкая temperature для детерминированного перевода.
    val temperature: Float = 0.2f,
    val topP: Float = 0.95f,
    // Аудио-выход расходует токены ответа: 8192 — безопасный потолок для
    // озвучки длинной фразы (TranslatorSession дополнительно coerceAtLeast).
    val maxOutputTokens: Int = 8192,

    // ═══════════════════ 3. VOICE ═══════════════════
    // Charon: ровный, чёткий, нейтральный — лучший универсал для перевода
    // RU↔DE. Альтернативы: Kore (твёрже), Aoede (легче), Puck (живее).
    val voiceId: String = "Charon",

    // ═══════════════════ 4. AUDIO ═══════════════════
    val useAec: Boolean = true,
    val jitterPreBufferChunks: Int = 3,
    val jitterTimeoutMs: Long = 150L,
    val playbackQueueCapacity: Int = 256,
    val sendAudioStreamEnd: Boolean = true,

    val playbackVolume: Int = 100,
    val micGain: Int = 100,
    val forceSpeakerOutput: Boolean = true,
    val playbackBoost: Float = 1.4f,

    // ═══════════════════ 5. SESSION / RECONNECT ═══════════════════
    val enableSessionResumption: Boolean = true,
    val enableContextCompression: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseDelayMs: Long = 2000L,
    val reconnectMaxDelayMs: Long = 30000L,
    val compressionTriggerTokens: Long = 25_600L,
    val compressionTargetTokens: Long = 12_800L,

    // ═══════════════════ 6. VAD ═══════════════════
    val enableServerVad: Boolean = true,
    val vadStartSensitivity: String = "START_SENSITIVITY_HIGH",
    // LOW на конце речи: естественные паузы внутри фразы не закрывают ход.
    val vadEndSensitivity: String = "END_SENSITIVITY_LOW",
    val vadSilenceDurationMs: Int = 800,
    // 300ms — защита первой фонемы коротких слов при START_SENSITIVITY_HIGH.
    val vadPrefixPaddingMs: Int = 300,
    val turnCoverage: String = "TURN_INCLUDES_ONLY_ACTIVITY",
    // Переводчика не перебивают: исключает обрыв генерации эхом динамика.
    val activityHandling: String = "NO_INTERRUPTION",

    // ═══════════════════ 7. TRANSCRIPTION ═══════════════════
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,
    // Пусто: native-audio модель сама детектирует язык (bidirectional).
    val transcriptionLanguageCodes: List<String> = emptyList(),

    // ═══════════════════ 7.1 TRANSLATION PAIR ═══════════════════
    val sourceLanguageCode: String = "ru",
    val targetLanguageCode: String = "de",

    // ═══════════════════ 8. THINKING / LATENCY ═══════════════════
    // Low: лёгкое планирование — обязательное для целостности длинных фраз
    // (финальный глагол в русском/немецком уходит в конец предложения).
    val latencyProfile: String = "Low",
    val includeThoughts: Boolean = false,

    // ═══════════════════ 9. UI / THEME ═══════════════════
    val themeMode: ThemeMode = ThemeMode.AUTO,
    // Дефолтная тема — GEM.
    val themeId: String = "GEM",
    val messageRevealId: String = "SOFT_FADE",
    // Минималистичный экран переводчика — основной по умолчанию.
    val translatorLayoutId: String = "MINIMAL",   // "CLASSIC" | "MINIMAL"
    val longPhraseMode: Boolean = false,

    // ═══════════════════ 10. DEBUG ═══════════════════
    val showDebugLog: Boolean = false,
    val logRawWebSocketFrames: Boolean = false,
    val showUsageMetadata: Boolean = false
)
