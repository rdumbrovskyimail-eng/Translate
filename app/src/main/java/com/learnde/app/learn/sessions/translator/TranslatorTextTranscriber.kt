// ════════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v2.0 — dual-output (original + translation)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorTextTranscriber.kt
//
// АРХИТЕКТУРА v2.0:
//   - Параллельная text-mode WS-сессия рядом с audio TranslatorSession.
//   - На каждое высказывание модель возвращает ДВЕ строки:
//       SRC|<src_lang>|<original>
//       DST|<dst_lang>|<translation>
//   - Для неподдерживаемых языков / шума:
//       SKIP|unknown|
//   - Направления идентичны audio-сессии: ru→de, uk→de, de→ru.
//   - UI берёт оригинал И перевод из ОДНОГО события — синхронность
//     гарантирована атомарностью.
//
// ПРОИЗВОДИТЕЛЬНОСТЬ:
//   - temperature=0, topP=0.3, topK=5 — детерминированный формат.
//   - maxOutputTokens=768 — запас на длинные фразы (две строки).
//   - VAD HIGH/HIGH с 400ms тишины — синхронно с audio-клиентом.
//   - Промпт английский, минимальный — быстрее первый токен.
// ════════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.learn.core.TranslatorTextScope
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Атомарная пара (оригинал, перевод) — единственный источник правды
 * для translator-UI.
 *
 * @param originalText распознанная речь пользователя
 * @param originalLang один из {"ru", "uk", "de"}
 * @param translatedText перевод; пусто → перевод ещё не пришёл (не должно
 *                       случаться при нормальном двухстрочном ответе)
 * @param translatedLang один из {"de", "ru", "unknown"};
 *                       "unknown" сигнализирует UI о direction mismatch
 */
data class UserTranscriptEvent(
    val originalText: String,
    val originalLang: String,
    val translatedText: String,
    val translatedLang: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Singleton
class TranslatorTextTranscriber @Inject constructor(
    @TranslatorTextScope private val client: LiveClient,
    private val logger: AppLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _transcripts = MutableSharedFlow<UserTranscriptEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val transcripts: SharedFlow<UserTranscriptEvent> = _transcripts.asSharedFlow()

    @Volatile private var eventJob: Job? = null
    @Volatile private var isActive: Boolean = false

    private val turnBuffer = StringBuilder()

    val isReady: Boolean get() = client.isReady

    // ═══════════════════════════════════════════════════════════
    //  SYSTEM INSTRUCTION — dual-output, strict format
    // ═══════════════════════════════════════════════════════════
    private val systemInstruction = """
You are a dual-output speech processor. For each user utterance you transcribe the original AND produce the translation, following STRICT direction rules.

DIRECTIONS — IDENTICAL to the audio translator:
- ru input → de translation
- uk input → de translation
- de input → ru translation
- any other language → SKIP

OUTPUT FORMAT — exactly two lines, no exceptions:
SRC|<src_lang>|<original_transcript>
DST|<dst_lang>|<translation>

For unsupported languages or unintelligible audio, output ONE line:
SKIP|unknown|

FIELD RULES:
- <src_lang> ∈ {ru, uk, de}
- <dst_lang> derived deterministically: ru→de, uk→de, de→ru
- <original_transcript> — exact words, original script (Cyrillic for ru/uk, Latin with umlauts for de). No paraphrasing.
- <translation> — natural, idiomatic, matches register (Sie/du, Вы/ты). No English loanwords in German output. No German calques in Russian output.

RU vs UK DISAMBIGUATION:
- Ukrainian markers: ї, і, є, ґ, апостроф, words "що", "як", "ти", "дякую", "привіт", "будь ласка", "немає".
- Russian markers: ё, ы, words "что", "как", "ты", "спасибо", "привет", "пожалуйста", "нет".
- If both Cyrillic but neither marker dominates → ru.

ABSOLUTE RULES:
- Exactly 2 lines for ru/uk/de input. Exactly 1 line for SKIP.
- No explanations. No alternatives. No commentary. No third line.
- Never use voice. Text only.
- Never output empty SRC line. If transcript is empty → SKIP.
""".trimIndent()

    suspend fun start(apiKey: String, model: String, logRaw: Boolean) {
        if (isActive) {
            logger.d("TranslatorTextTranscriber v2.0: already active, skipping start")
            return
        }
        isActive = true
        turnBuffer.clear()

        val config = SessionConfig(
            model = model,
            responseModality = "TEXT",
            temperature = 0.0f,
            topP = 0.3f,
            topK = 5,
            maxOutputTokens = 768,
            voiceId = "Aoede",
            languageCode = "",
            latencyProfile = LatencyProfile.Off,
            autoActivityDetection = true,
            vadStartSensitivity = "START_SENSITIVITY_HIGH",
            vadEndSensitivity = "END_SENSITIVITY_HIGH",
            vadPrefixPaddingMs = 80,
            vadSilenceDurationMs = 400,
            systemInstruction = systemInstruction,
            inputTranscription = false,
            outputTranscription = false,
            enableSessionResumption = false,
            enableContextCompression = false,
            enableGoogleSearch = false,
            functionDeclarations = emptyList(),
            sendAudioStreamEnd = true,
            setupTimeoutMs = 8_000L,
            sendThinkingConfig = false,
        )

        logger.d("TranslatorTextTranscriber v2.0: starting (TEXT mode, dual-output, model=$model)")

        eventJob = scope.launch {
            client.events.collect { event ->
                handleEvent(event)
            }
        }

        runCatching { client.connect(apiKey, config, logRaw) }
            .onFailure { e ->
                logger.e("TranslatorTextTranscriber v2.0: connect failed: ${e.message}")
                isActive = false
            }
    }

    suspend fun stop() {
        if (!isActive) return
        isActive = false
        runCatching { client.disconnect() }
        eventJob?.cancel()
        eventJob = null
        turnBuffer.clear()
        logger.d("TranslatorTextTranscriber v2.0: stopped")
    }

    /**
     * PCM-чанк ретранслируется в text-сессию параллельно с audio-сессией.
     * Один и тот же byte[] передаётся обоим клиентам — без копирования.
     */
    fun sendAudio(pcm: ByteArray) {
        if (!isActive || !client.isReady) return
        client.sendAudio(pcm)
    }

    fun sendAudioStreamEnd() {
        if (!isActive || !client.isReady) return
        runCatching { client.sendAudioStreamEnd() }
    }

    private fun handleEvent(event: GeminiEvent) {
        when (event) {
            is GeminiEvent.SetupComplete -> {
                logger.d("TranslatorTextTranscriber v2.0: ready")
            }
            is GeminiEvent.ModelText -> {
                turnBuffer.append(event.text)
            }
            is GeminiEvent.TurnComplete, is GeminiEvent.GenerationComplete -> {
                val raw = turnBuffer.toString().trim()
                turnBuffer.clear()
                if (raw.isNotEmpty()) {
                    parseAndEmit(raw)
                }
            }
            is GeminiEvent.Interrupted -> {
                turnBuffer.clear()
            }
            is GeminiEvent.ConnectionError -> {
                logger.e("TranslatorTextTranscriber v2.0: connection error: ${event.message}")
            }
            is GeminiEvent.Disconnected -> {
                logger.d("TranslatorTextTranscriber v2.0: disconnected ${event.code}")
            }
            else -> { /* ignore */ }
        }
    }

    /**
     * Парсит ответ модели по строкам, ищет SRC и DST маркеры.
     * Direction mismatch не блокирует эмит — UI получит unknown
     * и покажет visual warning.
     */
    private fun parseAndEmit(raw: String) {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) {
            logger.d("TranslatorTextTranscriber v2.0: empty response, skipping")
            return
        }

        // SKIP-путь: модель явно отказалась обрабатывать.
        if (lines.first().startsWith("SKIP|", ignoreCase = true)) {
            logger.d("TranslatorTextTranscriber v2.0: SKIP (unsupported/unintelligible)")
            return
        }

        val srcLine = lines.firstOrNull { it.startsWith("SRC|", ignoreCase = true) }
        val dstLine = lines.firstOrNull { it.startsWith("DST|", ignoreCase = true) }

        if (srcLine == null) {
            logger.w("TranslatorTextTranscriber v2.0: malformed response (no SRC line): $raw")
            return
        }

        val (rawSrcLang, srcText) = parseLine(srcLine) ?: run {
            logger.w("TranslatorTextTranscriber v2.0: cannot parse SRC: $srcLine")
            return
        }

        if (srcText.isBlank()) {
            logger.d("TranslatorTextTranscriber v2.0: empty original text, skipping")
            return
        }

        val srcLang = when (rawSrcLang) {
            "ru", "uk", "de" -> rawSrcLang
            else -> {
                logger.w("TranslatorTextTranscriber v2.0: unsupported src lang '$rawSrcLang', dropping")
                return
            }
        }

        val expectedDst = when (srcLang) {
            "ru", "uk" -> "de"
            "de"       -> "ru"
            else       -> "unknown"
        }

        val (dstLangRaw, dstText) = if (dstLine != null) {
            parseLine(dstLine) ?: ("unknown" to "")
        } else {
            logger.w("TranslatorTextTranscriber v2.0: no DST line for src=$srcLang")
            "unknown" to ""
        }

        val dstLang = when (dstLangRaw) {
            "de", "ru" -> dstLangRaw
            else       -> "unknown"
        }

        if (dstLang != expectedDst && dstText.isNotBlank()) {
            logger.w(
                "TranslatorTextTranscriber v2.0: direction mismatch — src=$srcLang " +
                    "expected=$expectedDst got=$dstLang. Emitting with unknown marker."
            )
        }

        val finalDstLang = if (dstLang == expectedDst) dstLang else "unknown"

        logger.d(
            "TranslatorTextTranscriber v2.0: [$srcLang→$finalDstLang] " +
                "src=\"$srcText\" dst=\"$dstText\""
        )

        _transcripts.tryEmit(
            UserTranscriptEvent(
                originalText = srcText,
                originalLang = srcLang,
                translatedText = dstText,
                translatedLang = finalDstLang,
            )
        )
    }

    /**
     * Разбирает строку формата "PREFIX|lang|text" на (lang, text).
     * Использует limit=3, чтобы '|' внутри текста перевода не ломал парсинг.
     */
    private fun parseLine(line: String): Pair<String, String>? {
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val lang = parts[1].trim().lowercase()
        val text = parts[2].trim()
        return lang to text
    }

    fun shutdown() {
        scope.cancel()
    }
}