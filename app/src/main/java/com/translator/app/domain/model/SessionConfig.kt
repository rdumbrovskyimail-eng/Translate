package com.translator.app.domain.model

data class FunctionDeclarationConfig(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterConfig> = emptyMap(),
    val required: List<String> = emptyList()
)

data class ParameterConfig(
    val type: String = "STRING",
    val description: String = "",
    val enumValues: List<String> = emptyList(),
    val items: ParameterConfig? = null,
    val properties: Map<String, ParameterConfig> = emptyMap(),
    val required: List<String> = emptyList()
)

data class SessionConfig(
    val model: String = DEFAULT_MODEL,

    // Generation
    val responseModality: String = "AUDIO",
    val temperature: Float = 0.2f,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = 512,

    // Voice
    val voiceId: String = "Aoede",

    // Thinking
    val latencyProfile: LatencyProfile = LatencyProfile.Off,
    val thinkingIncludeThoughts: Boolean = false,

    // VAD
    val autoActivityDetection: Boolean = true,
    val vadStartSensitivity: String = "START_SENSITIVITY_HIGH",
    val vadEndSensitivity: String = "END_SENSITIVITY_HIGH",
    val vadPrefixPaddingMs: Int = 120,
    val vadSilenceDurationMs: Int = 400,
    val activityHandling: String = "START_OF_ACTIVITY_INTERRUPTS",
    val turnCoverage: String = "TURN_INCLUDES_ONLY_ACTIVITY",

    // System instruction
    val systemInstruction: String = "",

    // Transcription
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,
    val transcriptionLanguageCodes: List<String> = emptyList(),

    // Session management
    val enableSessionResumption: Boolean = true,
    val sessionHandle: String? = null,
    val enableContextCompression: Boolean = true,
    val compressionTriggerTokens: Long = 25_600L,
    val compressionTargetTokens: Long = 12_800L,

    // Setup timeout
    val setupTimeoutMs: Long = 10_000L
) {
    companion object {
        // Короткий id модели. Префикс "models/" добавляется при сборке setup-фрейма.
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}

enum class LatencyProfile(val thinkingLevel: String?, val displayName: String) {
    Off       (null,      "Off — мгновенный ответ"),
    UltraLow  ("minimal", "Ultra Low — minimal"),
    Low       ("low",     "Low — light"),
    Balanced  ("medium",  "Balanced — medium"),
    Reasoning ("high",    "Reasoning — deep")
}