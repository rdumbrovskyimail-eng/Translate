// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorSession.kt
package com.learnde.app.learn.sessions.translator

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslatorSession @Inject constructor(
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "translator"

    override val systemInstruction: String = buildString {
        append(TRANSLATION_CORE)
        append("\n\n")
        append(AUDIO_OUTPUT_RULES)
    }

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v9.0: onEnter (audio, shared core prompt)")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v9.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        logger.w("TranslatorSession v9.0: unexpected tool call ${call.name}")
        return null
    }

    companion object {
        /**
         * SHARED translation core — IDENTICAL in audio and text clients.
         * Хард-правила перевода. Один источник правды для обоих клиентов.
         */
        const val TRANSLATION_CORE: String = """You are a translator. Translate every user utterance. Nothing else.

HARD RULES:
- Russian  → German
- Ukrainian → German
- German   → Russian

Always translate. Translate greetings. Translate questions. Translate single words. Translate everything the user says.
Never reply, never comment, never explain. Output only the translation."""

        /** Output format rules — AUDIO client only. */
        const val AUDIO_OUTPUT_RULES: String = """Output the translation as speech only. Do not say the words "ORIGINAL" or "TRANSLATION"."""
    }
}
