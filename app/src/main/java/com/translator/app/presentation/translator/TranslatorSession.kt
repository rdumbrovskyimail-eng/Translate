package com.translator.app.presentation.translator

import com.translator.app.data.settings.AppSettings
import com.translator.app.domain.model.LatencyProfile
import com.translator.app.domain.model.SessionConfig

object TranslatorSession {

    /**
     * Промпт переводчика. Только конкретные правила, без отрицаний.
     * Краткость критична — длинный prompt замедляет первый токен.
     */
    const val SYSTEM_INSTRUCTION =
        "You are a real-time voice translator between Russian and German. " +
        "Translate input to the target language immediately. " +
        "Output ONLY the translation. " +
        "NEVER add conversational filler, greetings, explanations, or meta-commentary. " +
        "If input is ambiguous, translate literally. " +
        "Preserve numbers, names, and technical terms exactly as provided."

    fun buildConfig(settings: AppSettings): SessionConfig {
        val latencyProfile = runCatching {
            LatencyProfile.valueOf(settings.latencyProfile)
        }.getOrDefault(LatencyProfile.Off)

        return SessionConfig(
            model = settings.model,

            temperature = settings.temperature,
            topP = settings.topP,
            maxOutputTokens = settings.maxOutputTokens,

            voiceId = settings.voiceId,
            responseModality = "AUDIO",
            latencyProfile = latencyProfile,
            thinkingIncludeThoughts = settings.includeThoughts,

            autoActivityDetection = settings.enableServerVad,
            vadStartSensitivity = settings.vadStartSensitivity,
            vadEndSensitivity = settings.vadEndSensitivity,
            vadSilenceDurationMs = settings.vadSilenceDurationMs,
            vadPrefixPaddingMs = settings.vadPrefixPaddingMs,
            activityHandling = settings.activityHandling,
            turnCoverage = settings.turnCoverage,

            systemInstruction = SYSTEM_INSTRUCTION,

            inputTranscription = settings.inputTranscription,
            outputTranscription = settings.outputTranscription,
            transcriptionLanguageCodes = settings.transcriptionLanguageCodes,

            enableSessionResumption = settings.enableSessionResumption,
            enableContextCompression = settings.enableContextCompression,
            compressionTriggerTokens = settings.compressionTriggerTokens,
            compressionTargetTokens = settings.compressionTargetTokens
        )
    }
}