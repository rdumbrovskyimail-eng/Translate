package com.translator.app.presentation.translator

import com.translator.app.data.settings.AppSettings
import com.translator.app.domain.model.LatencyProfile
import com.translator.app.domain.model.Languages
import com.translator.app.domain.model.SessionConfig

object TranslatorSession {

    /**
     * Промт переводчика. Пары x ↔ y подставляются динамически.
     */
    fun buildSystemInstruction(sourceNameEn: String, targetNameEn: String): String =
        "YOU ARE A PROFESSIONAL TRANSLATOR AND NOTHING MORE. " +
        "You provide high-quality translations from \"$sourceNameEn\" into \"$targetNameEn\" " +
        "and from \"$targetNameEn\" into \"$sourceNameEn\". " +
        "You respond instantly. You ignore other languages. " +
        "You do not respond to any additional questions from the person, etc. " +
        "ONLY TRANSLATION."

    fun buildConfig(settings: AppSettings): SessionConfig {
        val latencyProfile = runCatching {
            LatencyProfile.valueOf(settings.latencyProfile)
        }.getOrDefault(LatencyProfile.Off)

        val source = Languages.byCode(settings.sourceLanguageCode)
        val target = Languages.byCode(settings.targetLanguageCode)

        return SessionConfig(
            model = settings.model,

            temperature = settings.temperature,
            topP = settings.topP,
            maxOutputTokens = settings.maxOutputTokens,

            voiceId = settings.voiceId,
            latencyProfile = latencyProfile,
            thinkingIncludeThoughts = settings.includeThoughts,

            autoActivityDetection = settings.enableServerVad,
            vadStartSensitivity = settings.vadStartSensitivity,
            vadEndSensitivity = settings.vadEndSensitivity,
            vadSilenceDurationMs = settings.vadSilenceDurationMs,
            vadPrefixPaddingMs = settings.vadPrefixPaddingMs,
            activityHandling = settings.activityHandling,
            turnCoverage = settings.turnCoverage,

            systemInstruction = buildSystemInstruction(source.nameEn, target.nameEn),

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