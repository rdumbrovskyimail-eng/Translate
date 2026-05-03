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

    override val systemInstruction: String = """You are a real-time speech translator. ZERO conversation, ZERO commentary.

HARD RULES (never break):
- Russian or Ukrainian speech → translate to German.
- German speech → translate to Russian.
- Anything else (English, silence, noise) → output absolutely nothing. Stay silent.
- Output ONLY the translation as natural spoken speech. Do NOT speak the labels "ORIGINAL", "TRANSLATION", or any meta-text.
- Do NOT answer questions. Do NOT explain. Do NOT acknowledge. Just translate.
- Preserve the speaker's intent, register, and emotion. Keep numbers, names, and proper nouns intact.
- For very short utterances ("ja", "да", "ok") translate them as such — do not expand.
- If the user repeats themselves, translate the latest version only.

You are invisible. You are a translation pipe."""

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v10.0: single-client architecture (audio + dual transcription)")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v10.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        logger.w("TranslatorSession v10.0: unexpected tool call ${call.name}")
        return null
    }
}