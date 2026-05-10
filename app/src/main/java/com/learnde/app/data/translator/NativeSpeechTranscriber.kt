// Путь: app/src/main/java/com/learnde/app/data/translator/NativeSpeechTranscriber.kt
package com.learnde.app.data.translator

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.learnde.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Транскриптор голоса пользователя на базе системного Android SpeechRecognizer.
 *
 * Преимущества над Gemini Live input ASR:
 *   • Точнее распознаёт русский и немецкий (на Samsung S23 Ultra — Samsung-Google engine)
 *   • Работает on-device при наличии моделей (Android 12+, FEATURE_ON_DEVICE_RECOGNITION)
 *   • Низкая задержка partial results (~100-300мс)
 *   • Свободная альтернатива Vosk без +50МБ моделей в APK
 *
 * Архитектура:
 *   • Один экземпляр на язык (RU и DE) — для авто-детекта переключаем по фразе
 *   • Партиалы (`onPartialResults`) → драфт пользователя в UI
 *   • Финал (`onResults`) → подтверждённый оригинал → триггер для перевода
 *
 * Использование на S23 Ultra (Android 16) — мик делится с AudioRecord для Gemini WS
 * через AudioPolicy: оба источника `VOICE_COMMUNICATION` корректно сосуществуют.
 */
@Singleton
class NativeSpeechTranscriber @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val logger: AppLogger,
) {

    sealed class TranscriptEvent {
        data class Partial(val text: String, val lang: String) : TranscriptEvent()
        data class Final(val text: String, val lang: String) : TranscriptEvent()
        data class Error(val code: Int, val lang: String) : TranscriptEvent()
        data object ReadyForSpeech : TranscriptEvent()
        data object EndOfSpeech : TranscriptEvent()
    }

    private val _events = MutableSharedFlow<TranscriptEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<TranscriptEvent> = _events.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var recognizerRu: SpeechRecognizer? = null
    @Volatile private var recognizerDe: SpeechRecognizer? = null

    @Volatile private var activeLang: String = "ru-RU"
    @Volatile private var running: Boolean = false
    @Volatile private var lastFinalText: String = ""
    @Volatile private var restartScheduled: Boolean = false
    @Volatile private var ruSupported: Boolean = true
    @Volatile private var deSupported: Boolean = true

    /**
     * Запускает транскрипцию. Поднимает оба recognizer (RU + DE),
     * стартует с RU. При длинной паузе или ошибке "no match" — переключается на DE.
     * Авто-перезапуск после каждой финальной фразы для continuous listening.
     */
    fun start() {
        if (running) {
            logger.d("NativeSpeech: already running, ignoring start()")
            return
        }
        running = true
        restartScheduled = false

        mainHandler.post {
            val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
                }.getOrDefault(false)
            } else false

            logger.d("NativeSpeech: on-device available=$onDeviceAvailable, SDK=${Build.VERSION.SDK_INT}")

            // Создаём оба recognizer'а; если on-device недоступен — оба будут сетевые.
            recognizerRu = createRecognizer(onDeviceAvailable)
            recognizerDe = createRecognizer(onDeviceAvailable)

            // По умолчанию доступны оба языка; флаги выключаются при первой ошибке 12/13.
            ruSupported = true
            deSupported = true

            activeLang = "ru-RU"
            startListeningInternal(activeLang)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        restartScheduled = false

        mainHandler.post {
            runCatching { recognizerRu?.cancel() }
            runCatching { recognizerRu?.destroy() }
            recognizerRu = null

            runCatching { recognizerDe?.cancel() }
            runCatching { recognizerDe?.destroy() }
            recognizerDe = null

            logger.d("NativeSpeech: stopped")
        }
    }

    private fun createRecognizer(preferOnDevice: Boolean): SpeechRecognizer {
        return if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
    }

    private fun startListeningInternal(lang: String) {
        val recognizer = if (lang == "ru-RU") recognizerRu else recognizerDe
        if (recognizer == null) {
            logger.w("NativeSpeech: recognizer for $lang is null, cannot start")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Длинные паузы между словами при разговоре через переводчик
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        recognizer.setRecognitionListener(makeListener(lang))
        runCatching { recognizer.startListening(intent) }
            .onFailure { logger.e("NativeSpeech: startListening($lang) failed: ${it.message}") }
    }

    private fun makeListener(lang: String) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            logger.d("NativeSpeech[$lang]: ready")
            _events.tryEmit(TranscriptEvent.ReadyForSpeech)
        }

        override fun onBeginningOfSpeech() { /* no-op */ }
        override fun onRmsChanged(rmsdB: Float) { /* no-op */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* no-op */ }

        override fun onEndOfSpeech() {
            _events.tryEmit(TranscriptEvent.EndOfSpeech)
        }

        override fun onError(error: Int) {
            val errName = errorToString(error)
            logger.d("NativeSpeech[$lang]: error $error ($errName)")

            // Помечаем язык как неподдерживаемый и больше к нему не возвращаемся
            val unsupported = when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,           // 13 (API 31+)
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> true       // 12 (API 31+)
                else -> false
            }

            if (unsupported) {
                when (lang) {
                    "ru-RU" -> ruSupported = false
                    "de-DE" -> deSupported = false
                }
                logger.w("NativeSpeech: $lang marked as unsupported (ru=$ruSupported, de=$deSupported)")

                if (!ruSupported && !deSupported) {
                    logger.e("NativeSpeech: NO supported languages, stopping recognition loop")
                    running = false
                    _events.tryEmit(TranscriptEvent.Error(error, lang))
                    return
                }
            }

            val shouldSwitchLang = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> true
                else -> false
            } || unsupported

            _events.tryEmit(TranscriptEvent.Error(error, lang))

            if (!running) return
            scheduleRestart(switchLang = shouldSwitchLang, errorBackoff = unsupported)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty() && text != lastFinalText) {
                lastFinalText = text
                logger.d("NativeSpeech[$lang] FINAL: $text")
                _events.tryEmit(TranscriptEvent.Final(text, langCodeShort(lang)))
            }
            scheduleRestart(switchLang = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty()) {
                _events.tryEmit(TranscriptEvent.Partial(text, langCodeShort(lang)))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* no-op */ }
    }

    private fun scheduleRestart(switchLang: Boolean, errorBackoff: Boolean = false) {
        if (!running || restartScheduled) return
        restartScheduled = true

        // При unsupported-language даём больший backoff чтобы не молотить логи
        val delayMs = if (errorBackoff) 500L else 200L

        mainHandler.postDelayed({
            restartScheduled = false
            if (!running) return@postDelayed

            if (switchLang) {
                // Переключаемся только на ПОДДЕРЖИВАЕМЫЙ язык
                val candidate = if (activeLang == "ru-RU") "de-DE" else "ru-RU"
                val candidateSupported = if (candidate == "ru-RU") ruSupported else deSupported
                activeLang = if (candidateSupported) candidate else activeLang
                logger.d("NativeSpeech: lang → $activeLang (ru=$ruSupported, de=$deSupported)")
            } else {
                // Если текущий язык вдруг стал unsupported — мигрируем на оставшийся
                val currentSupported = if (activeLang == "ru-RU") ruSupported else deSupported
                if (!currentSupported) {
                    activeLang = if (ruSupported) "ru-RU" else "de-DE"
                    logger.d("NativeSpeech: forced lang → $activeLang")
                }
            }

            startListeningInternal(activeLang)
        }, delayMs)
    }

    private fun langCodeShort(full: String): String = when {
        full.startsWith("ru") -> "RU"
        full.startsWith("de") -> "DE"
        else -> full.uppercase()
    }

    private fun errorToString(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NET_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        else -> "UNKNOWN($code)"
    }
}