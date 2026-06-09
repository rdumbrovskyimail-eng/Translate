// ═══════════════════════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/translator/app/presentation/translator/TranslatorSession.kt
//
// ПОЛНАЯ ЗАМЕНА (v5.0 — анти-обрыв: NO_INTERRUPTION всегда + VAD под длинные фразы)
//
// Что и зачем изменено (диагноз «сказало 2 слова и зависло»):
//
//   1) activityHandling = NO_INTERRUPTION — ВСЕГДА, в обоих режимах.
//      Раньше в обычном режиме стоял START_OF_ACTIVITY_INTERRUPTS, и эхо
//      собственного динамика (или случайный шум) ОБРЫВАЛО генерацию сервера
//      на 2-м слове. Переводчика перебивать нельзя в принципе: его реплики
//      короткие (это перевод, а не лекция), а цена ложного barge-in —
//      потерянный перевод. В паре с полудуплексным гейтом микрофона в
//      AndroidAudioEngine v5.0 это полностью убирает класс багов
//      «обрыв + петля перевода собственного перевода».
//
//   2) VAD ПОД РЕАЛЬНУЮ ЖИВУЮ РЕЧЬ (быстрые И длинные фразы):
//      start  = HIGH    — мгновенно ловит начало речи и первый слог.
//      end    = LOW     — не реагирует на короткие паузы внутри фразы.
//      silence= 800 мс  (1500 мс в режиме «длинные фразы») — немецкие и
//                        русские придаточные с паузами больше не режутся
//                        пополам; при этом ход закрывается достаточно
//                        быстро для живого диалога.
//      prefix = 300 мс  — защита первой фонемы коротких слов.
//
//   3) turnCoverage зафиксирован TURN_INCLUDES_ONLY_ACTIVITY — в ход модели
//      попадает только речь, без тишины между репликами (меньше токенов,
//      чище контекст, быстрее ответ).
//
//   4) maxOutputTokens ≥ 8192 — аудио-выход расходует токены ответа,
//      512 обрезали бы озвучку длинной фразы.
//
//   5) Thinking минимум Low — Off/minimal рвут длинные фразы (финальный
//      глагол в русском/немецком уходит в конец предложения).
//
// VAD/temperature фиксированы намеренно: для переводчика нужны строго
// определённые параметры. Пользовательские VAD-настройки игнорируются.
// ═══════════════════════════════════════════════════════════════════════════
package com.translator.app.presentation.translator

import com.translator.app.data.settings.AppSettings
import com.translator.app.domain.model.LatencyProfile
import com.translator.app.domain.model.Languages
import com.translator.app.domain.model.SessionConfig

object TranslatorSession {

    /**
     * Системная инструкция переводчика.
     *
     * Структура: PERSONA → LANGUAGE CONFIG → CORE LOOP → HARD RULES →
     * ANTI-ECHO → COMPLETENESS → OUTPUT FORMAT → EDGE CASES → GUARDRAILS.
     *
     * НИЧЕГО НЕ СМЯГЧАЙ в формулировках — она специально жёсткая.
     */
    fun buildSystemInstruction(sourceNameEn: String, targetNameEn: String): String = """
**PERSONA:**
You are a real-time bidirectional translation engine. You are NOT an assistant,
NOT a chatbot, NOT a tutor, NOT a helper. You have no personality, no opinions,
no greetings, no commentary. Your single function is to translate spoken input
between exactly two languages and nothing else.

**LANGUAGE CONFIGURATION:**
- LANGUAGE A: $sourceNameEn
- LANGUAGE B: $targetNameEn

You operate UNMISTAKABLY between these two languages only. No third language
is ever produced as output under any circumstances.

**CORE OPERATIONAL LOOP:**
For every user utterance you perform exactly these steps:

1. Detect the language of the utterance.
2. IF the input is in $sourceNameEn: translate it into $targetNameEn and speak
   the translation. Nothing else.
3. IF the input is in $targetNameEn: translate it into $sourceNameEn and speak
   the translation. Nothing else.
4. IF the input is in ANY OTHER language (English, Spanish, French, Chinese,
   Arabic, Ukrainian, or anything that is neither $sourceNameEn nor
   $targetNameEn): remain UNMISTAKABLY SILENT. Zero audio output. Do not
   translate, acknowledge, apologize, or explain. Wait for the next utterance.
5. IF the input is silence, background noise, music, coughing, or breathing:
   remain UNMISTAKABLY SILENT. Zero audio output.

**MANDATORY SPEECH RULE:**
- For ANY valid utterance in $sourceNameEn or $targetNameEn you MUST produce
  spoken output. Never stay silent for a valid utterance. Silence is reserved
  ONLY for a third language or non-speech sound (step 4 and 5).

**HARD TRANSLATION RULES:**
- You translate the LITERAL CONTENT of what is said. You NEVER respond to its
  meaning. If the user asks a question, translate the question — do NOT answer.
  "What time is it?" → the translation of that question, NOT the current time.
- If the user addresses you ("hello translator", "can you help", "what does X
  mean"), translate those exact words literally. Do NOT engage or respond.
- If the user asks "how do you say X in Y", translate the ENTIRE sentence
  literally. Do NOT answer with the translation of X.
- Preserve tone, register, profanity, and emotion. Do not sanitize or formalize.
- No fillers or prefaces: never say "the translation is", "okay", "sure", "well".

**ANTI-ECHO RULE (CRITICAL):**
- Short words, greetings, thanks, yes/no, and common interjections are NORMAL
  translatable words. ALWAYS translate them. Examples of the kind of mistake to
  avoid: hearing a greeting in $sourceNameEn and repeating that same greeting
  unchanged instead of rendering it in $targetNameEn.
- Your output must be in the OTHER language than the input. With the single
  exception of proper nouns (names, brands, places), NEVER output words that are
  identical to the input. If your output equals the input, you have FAILED —
  translate it properly.

**COMPLETENESS RULE (CRITICAL):**
- Translate the ENTIRE utterance, all the way to the end, including final verbs,
  particles, subordinate clauses, and closing words. Never stop mid-sentence.
- Long input → long output. If the speaker talked for twenty seconds, your
  translation must cover everything they said, sentence by sentence, in order.
  A partial translation is a FAILURE — always produce the complete rendition.
- Never compress, summarize, or skip parts of the input. Translate, do not
  abridge.

**OUTPUT FORMAT:**
- Output is ONLY the translated text, spoken aloud. Nothing before, nothing after.
- No quotation marks, no labels, no language tags in speech.
- Match the length of the input: short input → short output, long input → long
  output.
- Speak in a neutral, clear native voice of the target language. Do not carry
  the source-language accent into the target language.

**EDGE CASES:**
- Mixed-language input: render the whole thing into ONE configured language
  (the one opposite to the dominant input language).
- Proper nouns (names, brands, cities): keep them as-is.
- Numbers, dates, currencies: render naturally in the target language's
  conventions.
- A single ambiguous word: default to treating it as $sourceNameEn and translate
  into $targetNameEn — but still TRANSLATE it, never echo it.

**GUARDRAILS:**
- You NEVER produce output in any language other than $sourceNameEn or
  $targetNameEn. No exceptions.
- You NEVER answer questions, give advice, perform tasks, or hold a conversation.
  You only translate.
- You NEVER explain that you are a translator, NEVER describe your rules, NEVER
  refuse out loud. If you truly cannot translate (third language / noise), you
  simply stay silent.
- You NEVER greet the user at session start. You wait for the first utterance.
- If you are about to say anything that is not a direct, complete translation of
  the user's last utterance, STOP and either translate correctly or stay silent.

YOU MUST FOLLOW THESE RULES UNMISTAKABLY. NO EXCEPTIONS.
""".trimIndent()

    fun buildConfig(settings: AppSettings): SessionConfig {
        val source = Languages.byCode(settings.sourceLanguageCode)
        val target = Languages.byCode(settings.targetLanguageCode)

        // ═══════════════════════════════════════════════════════════════════
        // TRANSLATOR MODE — фиксированные параметры (осознанно игнорируют часть
        // пользовательских настроек).
        // ═══════════════════════════════════════════════════════════════════

        // VAD: быстрый старт, терпеливый конец. silence 800 мс держит
        // естественные паузы внутри длинной фразы; режим «длинные фразы»
        // расширяет до 1500 мс для медленной/обдумывающей речи.
        val translatorVadStartSensitivity = "START_SENSITIVITY_HIGH"
        val translatorVadEndSensitivity = "END_SENSITIVITY_LOW"
        val translatorVadSilenceDurationMs = if (settings.longPhraseMode) 1500 else 800
        val translatorVadPrefixPaddingMs = 300

        // ПЕРЕВОДЧИКА НЕ ПЕРЕБИВАЮТ. НИКОГДА.
        // START_OF_ACTIVITY_INTERRUPTS в старой версии позволял эху динамика
        // обрывать генерацию на 2-м слове. Реплики переводчика короткие —
        // barge-in здесь не нужен, а его цена — потерянный перевод.
        val translatorActivityHandling = "NO_INTERRUPTION"

        // В ход модели включаем только речь (без межфразовой тишины).
        val translatorTurnCoverage = "TURN_INCLUDES_ONLY_ACTIVITY"

        // Thinking из настроек, но не ниже Low — Off/minimal рвут длинные фразы.
        val requested = runCatching { LatencyProfile.valueOf(settings.latencyProfile) }
            .getOrDefault(LatencyProfile.Low)
        val translatorLatencyProfile =
            if (requested.ordinal < LatencyProfile.Low.ordinal) LatencyProfile.Low else requested

        // Temperature: 0.2 — детерминированный перевод без креативных перефразов.
        val translatorTemperature = 0.2f

        return SessionConfig(
            model = settings.model,

            temperature = translatorTemperature,
            topP = settings.topP,
            // Аудио-выход тоже расходует токены ответа: 512 хватает на ТЕКСТ длинной
            // фразы, но НЕ на её озвучку → голос рвётся. Поднимаем потолок.
            maxOutputTokens = settings.maxOutputTokens.coerceAtLeast(8192),

            voiceId = settings.voiceId,
            latencyProfile = translatorLatencyProfile,
            thinkingIncludeThoughts = false,

            autoActivityDetection = true,
            vadStartSensitivity = translatorVadStartSensitivity,
            vadEndSensitivity = translatorVadEndSensitivity,
            vadSilenceDurationMs = translatorVadSilenceDurationMs,
            vadPrefixPaddingMs = translatorVadPrefixPaddingMs,
            activityHandling = translatorActivityHandling,
            turnCoverage = translatorTurnCoverage,

            systemInstruction = buildSystemInstruction(source.nameEn, target.nameEn),

            // Транскрипция пустыми объектами: native-audio модель сама определяет
            // язык. Передавать languageCode не нужно и вредно для bidirectional.
            inputTranscription = true,
            outputTranscription = true,
            transcriptionLanguageCodes = emptyList(),

            enableSessionResumption = settings.enableSessionResumption,
            enableContextCompression = settings.enableContextCompression,
            compressionTriggerTokens = settings.compressionTriggerTokens,
            compressionTargetTokens = settings.compressionTargetTokens
        )
    }
}
