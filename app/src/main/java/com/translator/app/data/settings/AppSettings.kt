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
    // ВАЖНО: для WS BidiGenerateContent v1beta модель шлётся БЕЗ префикса "models/".
    val model: String = "gemini-3.1-flash-live-preview",

    // Translator-режим: низкая temperature для детерминированного перевода.
    val temperature: Float = 0.2f,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = 512,
    val responseModality: String = "AUDIO",

    // ═══════════════════ 3. VOICE ═══════════════════
    val voiceId: String = "Aoede",

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
    val vadEndSensitivity: String = "END_SENSITIVITY_HIGH",
    val vadSilenceDurationMs: Int = 400,
    // 120ms — рекомендация Google для HIGH чувствительности (защита первой фонемы).
    val vadPrefixPaddingMs: Int = 120,
    val turnCoverage: String = "TURN_INCLUDES_ONLY_ACTIVITY",
    val activityHandling: String = "START_OF_ACTIVITY_INTERRUPTS",

    // ═══════════════════ 7. TRANSCRIPTION ═══════════════════
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,
    // ASR-подсказка реально отправляется в setup (см. GeminiLiveClient).
    val transcriptionLanguageCodes: List<String> = listOf("ru-RU", "de-DE"),

    // ═══════════════════ 8. THINKING / LATENCY ═══════════════════
    val latencyProfile: String = "Off",
    val includeThoughts: Boolean = false,

    // ═══════════════════ 9. UI / THEME ═══════════════════
    val themeMode: ThemeMode = ThemeMode.AUTO,
    // Дефолтная тема — GEM.
    val themeId: String = "GEM",
    val messageRevealId: String = "SOFT_FADE",

    // ═══════════════════ 10. DEBUG ═══════════════════
    val showDebugLog: Boolean = false,
    val logRawWebSocketFrames: Boolean = false,
    val showUsageMetadata: Boolean = false
)