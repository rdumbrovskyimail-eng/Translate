// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorTextTranscriber.kt
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed class TranscriberEvent {
    data class LiveUpdate(val original: String, val translation: String) : TranscriberEvent()
    data class FinalTurn(val original: String, val translation: String, val lang: String) : TranscriberEvent()
}

@Singleton
class TranslatorTextTranscriber @Inject constructor(
    @TranslatorTextScope private val client: LiveClient,
    private val logger: AppLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<TranscriberEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<TranscriberEvent> = _events.asSharedFlow()

    @Volatile private var eventJob: Job? = null
    @Volatile private var isActive: Boolean = false

    private val turnBuffer = StringBuilder()
    private val pcmBufferMutex = kotlinx.coroutines.sync.Mutex()
    private val pcmBuffer = java.util.ArrayDeque<ByteArray>()
    @Volatile private var pcmBufferBytes: Int = 0
    @Volatile private var bufferFlushed: Boolean = false

    val isReady: Boolean get() = client.isReady

    companion object {
        private val ORIGINAL_REGEX = Regex("ORIGINAL:(.*?)(?:\\nTRANSLATION:|$)", RegexOption.DOT_MATCHES_ALL)
        private val TRANSLATION_REGEX = Regex("TRANSLATION:(.*)", RegexOption.DOT_MATCHES_ALL)
        private const val MAX_PCM_BUFFER_BYTES = 320_000
    }

    private val systemInstruction: String = buildString {
        append(TranslatorSession.TRANSLATION_CORE)
        append("\n\n")
        append("""OUTPUT FORMAT (TEXT) — exactly two lines, no exceptions:
ORIGINAL: <exact transcript of what the user said, in the original language and script>
TRANSLATION: <the translation, following the direction rules above>

ORIGINAL TRANSCRIPT RULES:
- Use Cyrillic for Russian and Ukrainian. Use Latin with umlauts for German (ä, ö, ü, ß).
- Distinguish Russian vs Ukrainian by markers: ї, і, є, ґ, "що", "як", "ти", "дякую", "привіт" → Ukrainian; otherwise Russian.
- Write what the user actually said, verbatim. Do not paraphrase. Do not fix grammar.

ABSOLUTE OUTPUT RULES:
- Exactly 2 lines: one ORIGINAL line, one TRANSLATION line. Nothing else.
- No explanations. No alternatives. No commentary. No labels other than ORIGINAL: and TRANSLATION:.
- Text only. Never speak.
- If the language is not Russian, Ukrainian or German, or audio is unintelligible — output nothing.""")
    }

    suspend fun start(apiKey: String, model: String, logRaw: Boolean) {
        if (isActive) {
            logger.d("TranslatorTextTranscriber: already active, skipping start")
            return
        }
        bufferFlushed = false
        isActive = true
        turnBuffer.clear()

        // ВАЖНО: gemini-3.1-flash-live-preview НЕ поддерживает responseModality=TEXT
        // (закрывается с 1011 Internal Error). Официальный workaround Google — работать
        // в AUDIO modality и получать текст через outputAudioTranscription.
        // Голос модели просто игнорируем (не воспроизводим в этом клиенте).
        val config = SessionConfig(
            model = model,
            responseModality = "AUDIO",
            temperature = 0.05f,
            topP = 0.8f,
            topK = 20,
            maxOutputTokens = 512,
            voiceId = "Puck",
            languageCode = "",
            latencyProfile = LatencyProfile.Off,
            autoActivityDetection = true,
            vadStartSensitivity = "START_SENSITIVITY_HIGH",
            vadEndSensitivity = "END_SENSITIVITY_HIGH",
            vadPrefixPaddingMs = 80,
            vadSilenceDurationMs = 350,
            systemInstruction = systemInstruction,
            inputTranscription = false,
            outputTranscription = true,
            enableSessionResumption = false,
            enableContextCompression = false,
            enableGoogleSearch = false,
            functionDeclarations = emptyList(),
            sendAudioStreamEnd = true,
            setupTimeoutMs = 10_000L,
            sendThinkingConfig = false,
        )

        logger.d("TranslatorTextTranscriber: starting (AUDIO+outputTranscription mode, model=$model)")

        eventJob = scope.launch {
            client.events.collect { event ->
                handleEvent(event)
            }
        }

        runCatching { client.connect(apiKey, config, logRaw) }
            .onSuccess {
                logger.d("TranslatorTextTranscriber: connected successfully")
            }
            .onFailure { e ->
                logger.e("TranslatorTextTranscriber: connect failed: ${e.message}")
                isActive = false
                eventJob?.cancel()
            }
    }

    suspend fun stop() {
        if (!isActive) return
        isActive = false
        runCatching { client.disconnect() }
        eventJob?.cancel()
        eventJob = null
        turnBuffer.clear()
        scope.launch {
            pcmBufferMutex.withLock {
                pcmBuffer.clear()
                pcmBufferBytes = 0
                bufferFlushed = false
            }
        }
        logger.d("TranslatorTextTranscriber: stopped")
    }

    fun sendAudio(pcm: ByteArray) {
        if (!isActive) return
        if (!client.isReady || !bufferFlushed) {
            scope.launch {
                pcmBufferMutex.withLock {
                    while (pcmBufferBytes + pcm.size > MAX_PCM_BUFFER_BYTES && pcmBuffer.isNotEmpty()) {
                        val dropped = pcmBuffer.pollFirst()
                        pcmBufferBytes -= dropped.size
                    }
                    pcmBuffer.addLast(pcm)
                    pcmBufferBytes += pcm.size
                }
            }
            return
        }
        client.sendAudio(pcm)
    }

    fun sendAudioStreamEnd() {
        if (!isActive || !client.isReady) return
        runCatching { client.sendAudioStreamEnd() }
    }

    private fun handleEvent(event: GeminiEvent) {
        when (event) {
            is GeminiEvent.SetupComplete -> {
                logger.d("TranslatorTextTranscriber: ready")
                scope.launch {
                    pcmBufferMutex.withLock {
                        if (!bufferFlushed) {
                            while (pcmBuffer.isNotEmpty()) {
                                val chunk = pcmBuffer.pollFirst()
                                pcmBufferBytes -= chunk.size
                                runCatching { client.sendAudio(chunk) }
                            }
                            bufferFlushed = true
                        }
                    }
                }
            }
            is GeminiEvent.OutputTranscript -> {
                turnBuffer.append(event.text)
                parseAndEmitLive(turnBuffer.toString())
            }
            is GeminiEvent.ModelText -> {
                // В AUDIO modality модель не должна слать чистый текст,
                // но если вдруг прилетит — учитываем его в буфере.
                turnBuffer.append(event.text)
                parseAndEmitLive(turnBuffer.toString())
            }
            is GeminiEvent.AudioChunk -> {
                // Голос модели в этом клиенте мы игнорируем — нам нужен только текст.
            }
            is GeminiEvent.TurnComplete, is GeminiEvent.GenerationComplete -> {
                val raw = turnBuffer.toString().trim()
                turnBuffer.clear()
                if (raw.isNotEmpty()) {
                    parseAndEmitFinal(raw)
                }
            }
            is GeminiEvent.Interrupted -> {
                turnBuffer.clear()
            }
            is GeminiEvent.ConnectionError -> {
                logger.e("TranslatorTextTranscriber: connection error: ${event.message}")
            }
            is GeminiEvent.Disconnected -> {
                logger.d("TranslatorTextTranscriber: disconnected ${event.code}")
            }
            else -> { /* ignore */ }
        }
    }

    private fun parseAndEmitLive(raw: String) {
        val orig = ORIGINAL_REGEX.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val trans = TRANSLATION_REGEX.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        if (orig.isEmpty() && trans.isEmpty()) return
        _events.tryEmit(TranscriberEvent.LiveUpdate(orig, trans))
    }

    private fun parseAndEmitFinal(raw: String) {
        val originalMatch = ORIGINAL_REGEX.find(raw)
        val translationMatch = TRANSLATION_REGEX.find(raw)
        val orig = originalMatch?.groupValues?.get(1)?.trim().orEmpty()
        val trans = translationMatch?.groupValues?.get(1)?.trim().orEmpty()

        if (orig.isBlank() && trans.isBlank()) {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                logger.d("TranslatorTextTranscriber: empty/unintelligible, skipping")
                return
            }
            val fallbackOrig = lines.getOrNull(0).orEmpty()
            val fallbackTrans = lines.getOrNull(1).orEmpty()
            if (fallbackOrig.isBlank() && fallbackTrans.isBlank()) {
                logger.d("TranslatorTextTranscriber: empty fallback, skipping")
                return
            }
            val lang = detectLang(fallbackOrig)
            logger.d("TranslatorTextTranscriber:[$lang fallback] $fallbackOrig -> $fallbackTrans")
            _events.tryEmit(TranscriberEvent.FinalTurn(fallbackOrig, fallbackTrans, lang))
            return
        }

        val lang = detectLang(orig)
        logger.d("TranslatorTextTranscriber:[$lang] $orig -> $trans")
        _events.tryEmit(TranscriberEvent.FinalTurn(orig, trans, lang))
    }

    private fun detectLang(text: String): String {
        if (text.isBlank()) return "unknown"
        val hasUkrSpecific = text.any { it in "ієґїІЄҐЇ" }
        if (hasUkrSpecific) return "uk"
        val hasCyrillic = text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        if (hasCyrillic) {
            val lower = text.lowercase()
            val ukrMarkers = listOf("що", "як", "ти", "дякую", "привіт", "є", "вона", "вони")
            if (ukrMarkers.any { lower.contains(it) }) return "uk"
            return "ru"
        }
        val hasUmlauts = text.any { it in "äöüßÄÖÜ" }
        if (hasUmlauts) return "de"
        val hasLatin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
        if (hasLatin) {
            val lower = text.lowercase()
            if (Regex("\\b(der|die|das|und|ist|ich|nicht|haben|wie|geht)\\b").containsMatchIn(lower)) return "de"
        }
        return "unknown"
    }

    fun shutdown() {
        isActive = false
        runCatching { eventJob?.cancel() }
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                    client.disconnect()
                }
            }
        }
        scope.cancel()
    }
}
