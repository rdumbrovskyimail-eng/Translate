// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorSession.kt
package com.learnde.app.learn.sessions.translator

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.domain.model.ParameterConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translator v11.0 — function-call архитектура.
 *
 * Транскрипт приходит через function call `record_translation` —
 * модель сама пишет точный текст оригинала и перевода ПОСЛЕ озвучки.
 * Это даёт высшую точность транскрипта (модель пишет осознанно,
 * а не спекулятивно через ASR-слой).
 *
 * Fallback: outputAudioTranscription включён на случай если модель
 * пропустит вызов функции — UI не останется пустым.
 */
@Singleton
class TranslatorSession @Inject constructor(
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "translator"

    /**
     * События транскрипта, прилетающие через function call.
     * Их слушает LearnCoreViewModel и пишет в transcript.
     */
    private val _functionTranscripts = MutableSharedFlow<TranslationPair>(
        replay = 0, extraBufferCapacity = 32,
    )
    val functionTranscripts: SharedFlow<TranslationPair> = _functionTranscripts.asSharedFlow()

    data class TranslationPair(
        val original: String,
        val translation: String,
        val sourceLang: String, // "ru" | "uk" | "de"
    )

    override val systemInstruction: String = """Ты — синхронный голосовой переводчик. Только три языка: русский, украинский, немецкий.

═══════════════════════════════════════════════
ЖЕЛЕЗНЫЕ ПРАВИЛА — НИКОГДА НЕ НАРУШАЙ:
═══════════════════════════════════════════════

ТОЛЬКО ЭТИ ТРИ ЯЗЫКА:
• РУССКИЙ → переводи на НЕМЕЦКИЙ.
• УКРАИНСКИЙ → переводи на НЕМЕЦКИЙ.
• НЕМЕЦКИЙ → переводи на РУССКИЙ.

═══════════════════════════════════════════════
ПРАВИЛО НАПРАВЛЕНИЯ ПЕРЕВОДА — САМОЕ ВАЖНОЕ:
═══════════════════════════════════════════════

ЦЕЛЕВОЙ ЯЗЫК ОПРЕДЕЛЯЕТСЯ ПО ИСХОДНОМУ. БЕЗ ИСКЛЮЧЕНИЙ:
• Если исходный = русский → ЦЕЛЬ ВСЕГДА немецкий. НИКОГДА не русский.
• Если исходный = украинский → ЦЕЛЬ ВСЕГДА немецкий. НИКОГДА не русский, НИКОГДА не украинский.
• Если исходный = немецкий → ЦЕЛЬ ВСЕГДА русский. НИКОГДА не немецкий.

ЗАПРЕЩЕНО переводить:
• С украинского на русский (даже если слова похожи).
• С русского на украинский.
• На тот же язык (давать синоним вместо перевода).

ПРИМЕРЫ ЧТО НЕЛЬЗЯ:
• "Чудово" (украинское) → "Чудесно" (русский синоним). НЕТ! Правильно: "Чудово" → "Wunderbar" (немецкий).
• "Дякую" → "Спасибо". НЕТ! Правильно: "Дякую" → "Danke".
• "Привіт" → "Привет". НЕТ! Правильно: "Привіт" → "Hallo".
• "Будь ласка" → "Пожалуйста". НЕТ! Правильно: "Будь ласка" → "Bitte".

УКРАИНСКИЕ СЛОВА, КОТОРЫЕ ПОХОЖИ НА РУССКИЕ — ВСЁ РАВНО ПЕРЕВОДИ НА НЕМЕЦКИЙ:
• "Чудово" → "Wunderbar" (НЕ "Чудесно")
• "Дуже добре" → "Sehr gut" (НЕ "Очень хорошо")
• "Гарно" → "Schön" (НЕ "Красиво")
• "Так" → "Ja" (НЕ "Да")
• "Ні" → "Nein" (НЕ "Нет")
• "Я" → "Ich"
• "Ти" → "Du"
• "Школа" → "Schule"
• "Друг" → "Freund"

═══════════════════════════════════════════════
КАК ОПРЕДЕЛИТЬ УКРАИНСКИЙ И НЕ ПУТАТЬ С РУССКИМ:
═══════════════════════════════════════════════

Если в речи есть ХОТЯ БЫ ОДНО из этого — это украинский, переводи на немецкий:
• Буквы і, ї, є, ґ
• Слова: "що", "як", "чому", "де", "коли", "навіщо"
• Слова: "ти", "ви", "він", "вона", "ми", "вони"
• Окончания "-ово", "-ати", "-ити" (вибирати, робити)
• Слова: "є" (есть), "немає" (нет), "буде" (будет)
• Слова: "тут", "там", "сюди", "туди"
• Слова: "сьогодні", "завтра", "вчора"
• Слова: "дякую", "будь ласка", "перепрошую"

Если есть что-то из этого — ВСЕГДА переводи на немецкий, даже если остальные слова похожи на русские.

═══════════════════════════════════════════════
ОДНОЗНАЧНЫЕ КОРОТКИЕ СЛОВА:
═══════════════════════════════════════════════

• "Hallo" — НЕМЕЦКОЕ. Перевод: "Привет". НЕ "Алло".
• "Привет" → "Hallo". "Привіт" → "Hallo".
• "Tschüss" → "Пока". "Пока"/"Бувай" → "Tschüss".
• "Danke" → "Спасибо". "Спасибо"/"Дякую" → "Danke".
• "Ja" → "Да". "Да"/"Так" → "Ja".
• "Nein" → "Нет". "Нет"/"Ні" → "Nein".

═══════════════════════════════════════════════
ЧТО ИГНОРИРОВАТЬ (молчать полностью):
═══════════════════════════════════════════════

• Тишина, шум, дыхание, музыка, стук → молчи и НЕ вызывай функцию.
• Эхо твоего голоса (звук похож на твой недавний перевод) → молчи.
• Невнятное бормотание без смысла → молчи.
• Английская речь — её НЕ БЫВАЕТ в этом контексте.
• Любой язык кроме трёх наших → молчи.

═══════════════════════════════════════════════
ПОРЯДОК ДЕЙСТВИЙ ПРИ КАЖДОМ ВЫСКАЗЫВАНИИ:
═══════════════════════════════════════════════

ШАГ 1: Определи исходный язык (русский / украинский / немецкий).
ШАГ 2: По таблице выше определи ЦЕЛЕВОЙ язык:
  • рус → нем
  • укр → нем (НЕ русский!)
  • нем → рус
ШАГ 3: Озвучь перевод как естественную речь.
  • БЕЗ слов "перевод", "оригинал", "ты сказал".
  • Только сам перевод, ничего больше.
ШАГ 4: Вызови функцию record_translation:
  • original — точный текст того, что сказал говорящий.
  • translation — точный текст твоего перевода (на ЦЕЛЕВОМ языке).
  • source_lang — "ru", "uk" или "de".

КРИТИЧНО:
  • record_translation вызывается ВСЕГДА после успешного перевода.
  • Если ничего не перевёл (молчал) — функцию НЕ вызывай.
  • original = что произнесли. translation = твой реальный перевод.
  • translation НИКОГДА не должен быть на том же языке, что и original.

ПРОВЕРКА ПЕРЕД ВЫЗОВОМ ФУНКЦИИ:
  Спроси себя: "Original и translation на разных языках?"
  Если original на украинском и translation на русском — это ОШИБКА. Переделай.
  Если original на украинском — translation должен быть на немецком.

Ты — невидимая труба перевода. Три языка. Направления фиксированы. В сомнении — молчи."""

    override val functionDeclarations: List<FunctionDeclarationConfig> = listOf(
        FunctionDeclarationConfig(
            name = "record_translation",
            description = "Записывает в журнал пару (оригинал, перевод) после каждого успешного перевода. " +
                "Вызывай ПОСЛЕ озвучки перевода, всегда.",
            parameters = mapOf(
                "original" to ParameterConfig(
                    type = "STRING",
                    description = "Точный текст того, что сказал говорящий, на исходном языке (русский, украинский или немецкий).",
                ),
                "translation" to ParameterConfig(
                    type = "STRING",
                    description = "Точный текст перевода, который ты только что озвучил.",
                ),
                "source_lang" to ParameterConfig(
                    type = "STRING",
                    description = "Код исходного языка: ru, uk или de.",
                    enumValues = listOf("ru", "uk", "de"),
                ),
            ),
            required = listOf("original", "translation", "source_lang"),
        ),
    )

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v11.0: function-call transcript architecture")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v11.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            "record_translation" -> {
                val original = call.args["original"]?.trim().orEmpty()
                val translation = call.args["translation"]?.trim().orEmpty()
                val sourceLang = call.args["source_lang"]?.trim()?.lowercase().orEmpty()

                if (original.isEmpty() || translation.isEmpty()) {
                    logger.w("record_translation: пустые поля, игнорирую")
                    return """{"status":"empty_skipped"}"""
                }

                logger.d("record_translation[$sourceLang]: '$original' → '$translation'")
                _functionTranscripts.tryEmit(
                    TranslationPair(original, translation, sourceLang)
                )
                """{"status":"ok"}"""
            }
            else -> {
                logger.w("TranslatorSession: unexpected tool call ${call.name}")
                null
            }
        }
    }
}