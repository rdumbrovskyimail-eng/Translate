// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.8
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
//
//   v3.8 (function-based user transcription для translator):
//     - Транскрипция речи пользователя в режиме "translator" теперь
//       приходит ОТ САМОЙ МОДЕЛИ через function call submit_user_speech,
//       а не от ASR-канала InputTranscript.
//     - ASR Google по-прежнему отвечает за транскрипцию голоса Gemini
//       (OutputTranscript) — там он работает идеально.
//     - Решает проблему иероглифов и кривой транскрипции на коротких
//       фразах (1-3 слова) и при переключении укр↔рос.
//     - Новая зависимость: TranslatorSession инжектится напрямую,
//       чтобы подписаться на её userSpeechFlow.
//
// НОВОЕ В v3.7 (по результатам live-тестирования v3.6):
//
//   1. STUCK-TURN WATCHDOG (3500ms)
//      Лечит главную проблему сессии 2: модель присылает первую
//      дельту ("Hallo. Lass") и зависает на 10+ секунд. WS жив,
//      но генерация заморожена.
//      Решение: после первой model-дельты запускаем таймер. Если
//      3500ms не пришло НИ новой дельты, НИ AudioChunk, НИ
//      TurnComplete — принудительно финализируем turn локально.
//      Юзер видит частичный перевод и может продолжать.
//
//   2. TEXT-WITHOUT-AUDIO DETECTOR
//      Детектит когда OutputTranscript есть, а AudioChunk не идёт.
//      Не блокирует UI, просто логирует и не закрывает mic gate
//      (т.к. динамик не звучит — нет смысла гейтить мик).
//
//   3. ASR-GARBAGE SUPPRESSOR (опциональный, по флагу)
//      Если cachedSettings.translatorSuppressShortAsrGarbage==true
//      и в translator-режиме пришла КОРОТКАЯ латинская дельта
//      без немецких маркеров (Hola, Pesa la otra, cocktail) —
//      показываем пузырь как "…" с меткой ВЫ · ?
//
//   4. INITIAL-SESSION ECHO GUARD
//      На первые 2 секунды после SetupComplete расширяем AI tail
//      gate с 600ms до 1500ms. Защита от хвоста аудио прошлой
//      сессии (баг 16:26:36 где модель ответила сама себе).
//
// СОВМЕСТИМОСТЬ: требуется одно новое поле в AppSettings:
//   val translatorSuppressShortAsrGarbage: Boolean = true
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.ToolResponse
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.learn.domain.VocabularyViolation
import com.learnde.app.learn.sessions.translator.TranslationPair
import com.learnde.app.learn.sessions.translator.TranslationPairCodec
import com.learnde.app.util.AppLogger
import com.learnde.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class LearnCoreViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @LearnScope private val liveClient: LiveClient,
    @LearnScope private val audioEngine: AudioEngine,
    private val settingsStore: DataStore<AppSettings>,
    private val logger: AppLogger,
    private val arbiter: ActiveClientArbiter,
    private val statusBus: LearnFunctionStatusBus,
    private val registry: LearnSessionRegistry,
    private val vocabularyEnforcer: com.learnde.app.learn.domain.VocabularyEnforcer,
    private val translatorSession: com.learnde.app.learn.sessions.translator.TranslatorSession,
    private val translatorTextTranscriber: com.learnde.app.learn.sessions.translator.TranslatorTextTranscriber,
) : ViewModel() {

    companion object {
        private val cleanupScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

        private const val MAX_TRANSCRIPT_SIZE = 150
        private const val LEARNER_SILENCE_THRESHOLD_MS = 10_000L
        private const val SILENCE_CHECK_WINDOW_MS = 9_000L
        private const val GREETING_WARMUP_MS = 150L
        private const val SILENCE_WARMUP_MS = 400L
        private const val MIC_PREWARM_MS = 200L
        private const val GREETING_RETRY_MS = 4_000L
        private const val GREETING_FINAL_MS = 8_000L
        private const val SILENCE_PCM_BYTES = (2 * 16000 * 400) / 1000
        private const val AI_AUDIO_TAIL_MS = 600L

        // v3.7: расширенный AI tail на старте сессии для защиты от echo
        private const val AI_AUDIO_TAIL_INITIAL_MS = 1_500L
        private const val INITIAL_SESSION_GUARD_MS = 2_000L

        // v3.7: stuck-turn watchdog
        private const val STUCK_TURN_TIMEOUT_MS = 3_500L
        // v3.7: text-without-audio detector
        private const val TEXT_WITHOUT_AUDIO_TIMEOUT_MS = 1_500L

        private const val SILENCE_PROMPT_COOLDOWN_MS = 30_000L
        private const val FINISH_SESSION_GRACE_MS = 5_000L

        // v3.7: критерии "короткой латинской галлюцинации"
        private const val ASR_GARBAGE_MAX_WORDS = 2
        private const val ASR_GARBAGE_MAX_CHARS = 18

        /** Опциональный фильтр коротких ASR-галлюцинаций в translator-режиме.
         *  Если true — Hola/Pesa la otra/cocktail заменяются на "…" с меткой ВЫ · ?
         *  Чтобы сделать тоглом — замените на cachedSettings.<flag> в трёх местах ниже. */
        private const val TRANSLATOR_SUPPRESS_SHORT_ASR_GARBAGE = true
    }

    private val _state = MutableStateFlow(LearnCoreState())
    val state: StateFlow<LearnCoreState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LearnCoreEffect>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val effects: SharedFlow<LearnCoreEffect> = _effects.asSharedFlow()

    val audioPlaybackFlow get() = audioEngine.playbackSync
    val functionStatus: StateFlow<FunctionStatus> = statusBus.status

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    @Volatile private var activeApiKey: String = ""
    @Volatile private var activeSession: LearnSession? = null
    @Volatile private var contextSeeded = false

    private val pendingToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val toolCallJobs = ConcurrentHashMap<String, Job>()
    private val startStopMutex = Mutex()
    private val micOperationMutex = Mutex()
    private var micJob: Job? = null
    private var silenceTimerJob: Job? = null
    private var greetingFallbackJob: Job? = null
    private var setupJob: Job? = null
    private var finishGraceJob: Job? = null

    // v3.7: новые watchdog-джобы
    private var stuckTurnWatchdogJob: Job? = null
    private var textWithoutAudioJob: Job? = null

    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var modelStartedSpeakingThisTurn = false
    @Volatile private var awaitingInitialGreeting = false
    @Volatile private var lastAiAudioChunkAtMs: Long = 0L
    @Volatile private var sessionFinished: Boolean = false
    @Volatile private var lastSilencePromptAtMs: Long = 0L
    @Volatile private var droppedMicChunks: Int = 0

    // v3.7: timestamp последней любой активности от модели в текущем turn'е
    // (audio ИЛИ output transcript). Используется watchdog'ом.
    @Volatile private var lastModelActivityAtMs: Long = 0L
    // v3.7: timestamp когда сессия стала Ready (для initial echo guard)
    @Volatile private var sessionReadyAtMs: Long = 0L
    // v3.7: модель уже прислала ХОТЯ БЫ что-то (audio или text) в этом turn'е
    @Volatile private var hasModelOutputThisTurn: Boolean = false

    private val transcriptMutex = Mutex()
    @Volatile private var transcriptBuffer: List<ConversationMessage> = emptyList()
    @Volatile private var pendingVocabViolation: VocabularyViolation? = null

    private val transcriptChannel = Channel<TranscriptOp>(Channel.UNLIMITED)

    private sealed class TranscriptOp {
        data class UserDelta(val text: String) : TranscriptOp()
        data class ModelDelta(val text: String, val source: String) : TranscriptOp()
        object UserTurnComplete : TranscriptOp()
        object ModelTurnComplete : TranscriptOp()
        object ModelInterrupted : TranscriptOp()
        object Reset : TranscriptOp()
    }

    private val userTurnBuffer = StringBuilder()
    private val modelTurnBuffer = StringBuilder()

    @Volatile private var lastRejectedUserDelta: String = ""

    @Volatile private var liveUserMessageTs: Long = 0L
    @Volatile private var liveModelMessageTs: Long = 0L

    init {
        observeSettings()
        observeGeminiEvents()
        observeArbiter()
        observeVocabularyViolations()
        startTranscriptProcessor()
        observeTranslatorUserSpeech()
        observeTranslatorTextTranscripts()
        viewModelScope.launch { audioEngine.initPlayback() }
    }

    private fun startTranscriptProcessor() {
        viewModelScope.launch {
            transcriptChannel.consumeAsFlow().collect { op ->
                runCatching { processTranscriptOp(op) }
                    .onFailure { logger.e("Transcript op failed: ${it.message}", it) }
            }
        }
    }

    private suspend fun processTranscriptOp(op: TranscriptOp) {
        when (op) {
            is TranscriptOp.UserDelta -> handleUserDelta(op.text)
            is TranscriptOp.ModelDelta -> handleModelDelta(op.text, op.source)
            is TranscriptOp.UserTurnComplete -> finalizeUserTurn()
            is TranscriptOp.ModelTurnComplete -> finalizeModelTurn()
            is TranscriptOp.ModelInterrupted -> finalizeModelTurn()
            is TranscriptOp.Reset -> {
                userTurnBuffer.clear()
                modelTurnBuffer.clear()
                lastRejectedUserDelta = ""
                liveUserMessageTs = 0L
                liveModelMessageTs = 0L
            }
        }
    }

    private suspend fun handleUserDelta(text: String) {
        if (text.isEmpty()) return

        val current = userTurnBuffer.toString()
        when {
            current.isEmpty() -> userTurnBuffer.append(text)
            text.startsWith(current) -> {
                userTurnBuffer.clear()
                userTurnBuffer.append(text)
            }
            current.contains(text) -> { 
            }
            else -> {
                userTurnBuffer.append(" ").append(text)
            }
        }

        _state.update { it.copy(liveUserTranscript = userTurnBuffer.toString()) }

        lastInputTs = System.currentTimeMillis()
        silenceTimerJob?.cancel()
    }

    private suspend fun finalizeUserTurn() {
        val bufferedText = userTurnBuffer.toString().trim()
        userTurnBuffer.clear()

        // 1. Очищаем "живой" текст с экрана
        _state.update { it.copy(liveUserTranscript = "") }

        // 2. Если текст был, сохраняем его как финальное сообщение в историю чата
        if (bufferedText.isNotEmpty()) {
            transcriptMutex.withLock {
                val newMsg = ConversationMessage.user(bufferedText)
                val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            }
        }
        liveUserMessageTs = 0L
    }

    private suspend fun handleModelDelta(text: String, source: String) {
        if (text.isEmpty()) return

        if (cachedSettings.outputTranscription && source == "ModelText") return
        if (!cachedSettings.outputTranscription && source == "OutputTranscript") return

        // v3.7: фиксируем активность для watchdog
        lastModelActivityAtMs = System.currentTimeMillis()
        hasModelOutputThisTurn = true
        startStuckTurnWatchdog()

        val current = modelTurnBuffer.toString()
        when {
            current.isEmpty() -> modelTurnBuffer.append(text)
            text == current -> return
            text.startsWith(current) && text.length > current.length -> {
                modelTurnBuffer.clear()
                modelTurnBuffer.append(text)
            }
            current.endsWith(text) -> return
            else -> modelTurnBuffer.append(text)
        }

        upsertLiveModelBubble(modelTurnBuffer.toString())

        if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
            vocabularyEnforcer.analyze(text)
        }
    }



    private suspend fun finalizeModelTurn() {
        val finalText = modelTurnBuffer.toString().trim()
        modelTurnBuffer.clear()

        when {
            finalText.isNotEmpty() && liveModelMessageTs != 0L -> {
                updateBubbleByTs(liveModelMessageTs, finalText, ConversationMessage.ROLE_MODEL)
            }
            finalText.isNotEmpty() && liveModelMessageTs == 0L -> {
                upsertLiveModelBubble(finalText)
            }
        }

        liveModelMessageTs = 0L
        hasModelOutputThisTurn = false
        cancelStuckTurnWatchdog()
        cancelTextWithoutAudioWatchdog()
    }

    private suspend fun upsertLiveUserBubble(text: String) {
        transcriptMutex.withLock {
            if (liveUserMessageTs == 0L) {
                val newMsg = ConversationMessage.user(text)
                liveUserMessageTs = newMsg.timestamp
                val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            } else {
                val idx = transcriptBuffer.indexOfLast { it.timestamp == liveUserMessageTs }
                if (idx >= 0) {
                    val updated = transcriptBuffer[idx].copy(text = text)
                    val next = transcriptBuffer.toMutableList().apply { set(idx, updated) }
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                } else {
                    val newMsg = ConversationMessage.user(text)
                    liveUserMessageTs = newMsg.timestamp
                    val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                }
            }
        }
    }

    private suspend fun upsertLiveModelBubble(text: String) {
        transcriptMutex.withLock {
            if (liveModelMessageTs == 0L) {
                val newMsg = ConversationMessage(
                    role = ConversationMessage.ROLE_MODEL,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
                liveModelMessageTs = newMsg.timestamp
                val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            } else {
                val idx = transcriptBuffer.indexOfLast { it.timestamp == liveModelMessageTs }
                if (idx >= 0) {
                    val updated = transcriptBuffer[idx].copy(text = text)
                    val next = transcriptBuffer.toMutableList().apply { set(idx, updated) }
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                } else {
                    val newMsg = ConversationMessage(
                        role = ConversationMessage.ROLE_MODEL,
                        text = text,
                        timestamp = System.currentTimeMillis()
                    )
                    liveModelMessageTs = newMsg.timestamp
                    val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                }
            }
        }
    }

    private suspend fun updateBubbleByTs(ts: Long, finalText: String, role: String) {
        transcriptMutex.withLock {
            val idx = transcriptBuffer.indexOfLast { it.timestamp == ts && it.role == role }
            if (idx >= 0) {
                val updated = transcriptBuffer[idx].copy(text = finalText)
                val next = transcriptBuffer.toMutableList().apply { set(idx, updated) }
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  v3.7: STUCK-TURN WATCHDOG
    // ═══════════════════════════════════════════════════════

    /**
     * Запускается на КАЖДОЙ дельте от модели (audio или text).
     * Если за STUCK_TURN_TIMEOUT_MS не пришло ничего нового и
     * не пришёл TurnComplete — принудительно финализируем turn.
     *
     * Это решает баг native-audio модели Gemini Live где она
     * иногда зависает в середине генерации (видели "Hallo. Lass"
     * и тишину 10+ секунд).
     */
    private fun startStuckTurnWatchdog() {
        stuckTurnWatchdogJob?.cancel()
        stuckTurnWatchdogJob = viewModelScope.launch {
            delay(STUCK_TURN_TIMEOUT_MS)
            // Проверяем что watchdog ещё актуален
            if (!hasModelOutputThisTurn) return@launch

            val sinceLastActivity = System.currentTimeMillis() - lastModelActivityAtMs
            if (sinceLastActivity >= STUCK_TURN_TIMEOUT_MS) {
                logger.w("⚠ STUCK_TURN_DETECTED — no model activity for ${sinceLastActivity}ms, " +
                    "force-finalizing")

                // 1. Финализируем буферы (сохраняем то что успело прийти)
                transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
                transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)

                // 2. Чистим аудио (если что-то висит в очереди)
                runCatching { audioEngine.flushPlayback() }
                runCatching { audioEngine.onTurnComplete() }

                // 3. Сбрасываем UI-состояние
                modelStartedSpeakingThisTurn = false
                hasModelOutputThisTurn = false
                _state.update { it.copy(isAiSpeaking = false) }

                // 4. Открываем mic gate (если был закрыт по AI tail)
                lastAiAudioChunkAtMs = 0L

                cancelTextWithoutAudioWatchdog()
            }
        }
    }

    private fun cancelStuckTurnWatchdog() {
        stuckTurnWatchdogJob?.cancel()
        stuckTurnWatchdogJob = null
    }

    /**
     * Запускается когда пришёл OutputTranscript но AudioChunk не пришёл.
     * Если за TEXT_WITHOUT_AUDIO_TIMEOUT_MS аудио так и не появилось —
     * считаем что модель залипла в text-only режиме. Логируем и
     * сбрасываем lastAiAudioChunkAtMs чтобы mic gate не блокировал.
     */
    private fun startTextWithoutAudioWatchdog() {
        textWithoutAudioJob?.cancel()
        textWithoutAudioJob = viewModelScope.launch {
            delay(TEXT_WITHOUT_AUDIO_TIMEOUT_MS)
            val now = System.currentTimeMillis()
            // Если модель всё ещё якобы "говорит", но AudioChunk
            // не приходил — открываем mic gate.
            if (hasModelOutputThisTurn && (now - lastAiAudioChunkAtMs) > TEXT_WITHOUT_AUDIO_TIMEOUT_MS) {
                logger.w("⚠ TEXT_WITHOUT_AUDIO — model outputs text but no audio, " +
                    "opening mic gate proactively")
                lastAiAudioChunkAtMs = 0L
                _state.update { it.copy(isAiSpeaking = false) }
            }
        }
    }

    private fun cancelTextWithoutAudioWatchdog() {
        textWithoutAudioJob?.cancel()
        textWithoutAudioJob = null
    }



    private fun observeVocabularyViolations() {
        viewModelScope.launch {
            vocabularyEnforcer.violations.collect { violation ->
                if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
                    pendingVocabViolation = violation
                    logger.d("Learn: vocab violation buffered (${violation.violatingWords})")
                }
            }
        }
    }

    /**
     * v3.9: function call submit_user_speech больше НЕ создаёт пузырь.
     * Источник user-пузырей в translator — только TranslatorTextTranscriber
     * (через observeTranslatorTextTranscripts). Это исключает дубли.
     *
     * Метод оставлен для логирования и совместимости с TranslatorSession,
     * который может по-прежнему слать function call (мы его игнорируем).
     */
    private fun observeTranslatorUserSpeech() {
        viewModelScope.launch {
            translatorSession.userSpeechFlow.collect { event ->
                if (activeSession?.id != "translator") return@collect
                logger.d(
                    "Learn: ignoring submit_user_speech function call " +
                        "[${event.language}] \"${event.text}\" (transcriber is source of truth)"
                )
            }
        }
    }

    /**
     * v3.9: транскрибер теперь даёт пару (оригинал, перевод) одним событием.
     * Каждое событие — новый пузырь, без обновления предыдущих. Пара
     * хранится сериализованно в ConversationMessage.text — UI расшифрует.
     *
     * Это единственный источник user-пузырей в translator-режиме:
     * - InputTranscript игнорируется (см. observeGeminiEvents)
     * - submit_user_speech function call игнорируется (см. observeTranslatorUserSpeech)
     * - OutputTranscript / ModelText игнорируются (см. observeGeminiEvents)
     */
    private fun observeTranslatorTextTranscripts() {
        viewModelScope.launch {
            translatorTextTranscriber.transcripts.collect { event ->
                if (activeSession?.id != "translator") return@collect

                logger.d(
                    "Learn: translator pair [${event.originalLang}→${event.translatedLang}] " +
                        "src=\"${event.originalText}\" dst=\"${event.translatedText}\""
                )

                val encoded = TranslationPairCodec.encode(
                    TranslationPair(
                        originalText = event.originalText,
                        originalLang = event.originalLang,
                        translatedText = event.translatedText,
                        translatedLang = event.translatedLang,
                    )
                )

                transcriptMutex.withLock {
                    val newMsg = ConversationMessage(
                        role = ConversationMessage.ROLE_USER,
                        text = encoded,
                        timestamp = event.timestamp,
                    )
                    val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                    transcriptBuffer = next
                    _state.update {
                        it.copy(transcript = next, liveUserTranscript = "")
                    }
                }
            }
        }
    }



    fun onIntent(intent: LearnCoreIntent) {
        when (intent) {
            is LearnCoreIntent.Start     -> handleStart(intent.sessionId)
            is LearnCoreIntent.Stop      -> handleStop()
            is LearnCoreIntent.ToggleMic -> handleToggleMic()
            is LearnCoreIntent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data
                .catch { e ->
                    logger.e("Learn: DataStore read error: ${e.message}")
                    emit(AppSettings())
                }
                .collect { settings ->
                    cachedSettings = settings
                    activeApiKey = settings.apiKey
                    _state.update { it.copy(apiKeySet = settings.apiKey.isNotEmpty()) }
                    audioEngine.setPlaybackVolume(settings.playbackVolume / 100f)
                    audioEngine.setMicGain(settings.micGain / 100f)
                    audioEngine.setSpeakerRouting(settings.forceSpeakerOutput)
                }
        }
    }

    private fun buildLearnSessionConfig(session: LearnSession): SessionConfig {
        val isTranslator = session.id == "translator"

        // ═══ THINKING ═══
        // Translator — ВСЕГДА Off (хардкод, иначе перевод тормозит).
        // Остальные сессии используют выбор пользователя из настроек.
        val profile = if (isTranslator) {
            LatencyProfile.Off
        } else {
            runCatching { enumValueOf<LatencyProfile>(cachedSettings.latencyProfile) }
                .getOrDefault(LatencyProfile.UltraLow)
        }

        val userInfo = buildString {
            if (cachedSettings.userName.isNotBlank()) append("Имя ученика: ${cachedSettings.userName}. ")
            if (cachedSettings.learningGoals.isNotBlank()) append("Цель изучения: ${cachedSettings.learningGoals}. ")
            if (cachedSettings.learningTopics.isNotBlank()) append("Интересные темы: ${cachedSettings.learningTopics}. ")
        }

        val finalSystemInstruction = if (userInfo.isNotBlank() && !isTranslator) {
            "${session.systemInstruction}\n\n[ДАННЫЕ ПОЛЬЗОВАТЕЛЯ]:\n" +
                "Обращайся к ученику по имени. Учитывай эти данные: $userInfo"
        } else {
            session.systemInstruction
        }

        val (silenceMs, prefixMs, temp) = when (session.id) {
            "translator"   -> Triple(350, 80, 0.05f)
            "a1_situation" -> Triple(1000, 300, cachedSettings.temperature)
            "a1_review"    -> Triple(1000, 300, cachedSettings.temperature)
            else           -> Triple(1000, 300, cachedSettings.temperature)
        }

        val finalSilenceMs = if (cachedSettings.vadSilenceTimeoutMs > 0 && !isTranslator)
            maxOf(cachedSettings.vadSilenceTimeoutMs, 500)
        else silenceMs

        val finalLanguageCode = if (isTranslator) "" else cachedSettings.languageCode

        val finalVoiceId = if (isTranslator) "Puck" else cachedSettings.voiceId
        val finalMaxTokens = if (isTranslator) 512 else cachedSettings.maxOutputTokens
        val finalTopP = if (isTranslator) 0.8f else cachedSettings.topP
        val finalTopK = if (isTranslator) 20 else cachedSettings.topK

        return SessionConfig(
            model = cachedSettings.model,
            temperature = temp,
            topP = finalTopP,
            topK = finalTopK,
            maxOutputTokens = finalMaxTokens,
            presencePenalty = cachedSettings.presencePenalty,
            frequencyPenalty = cachedSettings.frequencyPenalty,
            voiceId = finalVoiceId,
            languageCode = finalLanguageCode,
            latencyProfile = profile,
            autoActivityDetection = cachedSettings.enableServerVad,
            vadStartSensitivity = if (isTranslator) "START_SENSITIVITY_HIGH"
                else if (cachedSettings.vadStartOfSpeechSensitivity > 0.5f) "START_SENSITIVITY_HIGH"
                else "START_SENSITIVITY_LOW",
            vadEndSensitivity = if (isTranslator) "END_SENSITIVITY_HIGH"
                else if (cachedSettings.vadEndOfSpeechSensitivity > 0.5f) "END_SENSITIVITY_HIGH"
                else "END_SENSITIVITY_LOW",
            vadSilenceDurationMs = finalSilenceMs,
            vadPrefixPaddingMs = prefixMs,
            systemInstruction = finalSystemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = false,
            sessionHandle = null,
            enableContextCompression = false,
            enableGoogleSearch = false,
            functionDeclarations = session.functionDeclarations,
            sendAudioStreamEnd = cachedSettings.sendAudioStreamEnd,
        ).also {
            logger.d(
                "Learn: config for ${session.id}: profile=$profile " +
                    "(thinking=${profile.thinkingLevel ?: "OFF"}), " +
                    "silence=${finalSilenceMs}ms, prefix=${prefixMs}ms, temp=$temp, " +
                    "voice=$finalVoiceId, maxTok=$finalMaxTokens"
            )
        }
    }

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                val owned = owner == ClientOwner.LEARN
                _state.update { it.copy(arbiterOwned = owned) }
                val isConnected = _state.value.connectionStatus != LearnConnectionStatus.Disconnected
                if (!owned && activeSession != null && isConnected) {
                    logger.w("Learn: lost arbiter ownership → stopping")
                    handleStop()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  START / STOP
    // ══════════════════════════════════════════════════════

    private suspend fun stopInternal() = startStopMutex.withLock {
        logger.d("▶ Learn.stopInternal")
        val session = activeSession
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()
        finishGraceJob?.cancel()
        finishGraceJob = null
        cancelStuckTurnWatchdog()
        cancelTextWithoutAudioWatchdog()

        micOperationMutex.withLock {
            audioEngine.stopCapture()
        }
        safeStopForegroundService()

        runCatching { liveClient.disconnect() }
        // Останавливаем параллельный text-транскриптер (если был активен)
        runCatching { translatorTextTranscriber.stop() }
        runCatching { session?.onExit() }

        transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
        transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
        transcriptChannel.trySend(TranscriptOp.Reset)

        activeSession = null
        pendingToolCalls.clear()
        transcriptMutex.withLock { transcriptBuffer = emptyList() }
        statusBus.reset()
        vocabularyEnforcer.reset()
        contextSeeded = false
        pendingVocabViolation = null
        modelStartedSpeakingThisTurn = false
        awaitingInitialGreeting = false
        sessionFinished = false
        lastAiAudioChunkAtMs = 0L
        lastModelActivityAtMs = 0L
        sessionReadyAtMs = 0L
        hasModelOutputThisTurn = false
        lastSilencePromptAtMs = 0L
        droppedMicChunks = 0
        setupJob?.cancel()
        setupJob = null

        _state.update {
            it.copy(
                sessionId = null,
                connectionStatus = LearnConnectionStatus.Disconnected,
                isMicActive = false,
                isAiSpeaking = false,
                isPreparingSession = false,
                isFinishingSession = false,
            )
        }
        arbiter.release(ClientOwner.LEARN)
        logger.d("◀ Learn.stopInternal — arbiter released")
    }

    private suspend fun startInternal(sessionId: String) = startStopMutex.withLock {
        val session = registry.get(sessionId) ?: run {
            logger.e("Learn: unknown session id: $sessionId")
            _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain("Unknown session: $sessionId")))
            return@withLock
        }
        if (activeApiKey.isEmpty()) {
            _state.update { it.copy(error = UiText.Plain("API ключ не задан. Задайте его в Настройках.")) }
            return@withLock
        }

        logger.d("▶ Learn.startInternal(${session.id})")

        arbiter.acquire(ClientOwner.LEARN)
        runCatching { liveClient.disconnect() }

        pendingToolCalls.clear()
        contextSeeded = false
        statusBus.reset()
        pendingVocabViolation = null
        transcriptChannel.trySend(TranscriptOp.Reset)
        modelStartedSpeakingThisTurn = false
        awaitingInitialGreeting = false
        sessionFinished = false
        lastAiAudioChunkAtMs = 0L
        lastModelActivityAtMs = 0L
        sessionReadyAtMs = 0L
        hasModelOutputThisTurn = false
        lastSilencePromptAtMs = 0L
        droppedMicChunks = 0
        cancelStuckTurnWatchdog()
        cancelTextWithoutAudioWatchdog()
        greetingFallbackJob?.cancel()
        setupJob?.cancel()
        setupJob = null
        finishGraceJob?.cancel()
        finishGraceJob = null

        session.onEnter()
        activeSession = session
        if (session.id == "a1_situation" || session.id == "a1_review") {
            vocabularyEnforcer.warmUp()
        }

        _state.update {
            it.copy(
                sessionId = session.id,
                connectionStatus = LearnConnectionStatus.Connecting,
                error = null,
                isMicActive = false,
                isAiSpeaking = false,
                isPreparingSession = true,
                isFinishingSession = false,
            )
        }

        runCatching {
            liveClient.connect(
                apiKey = activeApiKey,
                config = buildLearnSessionConfig(session),
                logRaw = cachedSettings.logRawWebSocketFrames
            )
        }.onFailure { e ->
            logger.e("Learn: connect failed: ${e.message}", e)
            _state.update {
                it.copy(
                    connectionStatus = LearnConnectionStatus.Disconnected,
                    isPreparingSession = false,
                    error = UiText.Plain("Не удалось подключиться: ${e.message}")
                )
            }
            arbiter.release(ClientOwner.LEARN)
            activeSession = null
        }

        // Параллельный transcriber только для translator-сессии
        if (session.id == "translator") {
            runCatching {
                translatorTextTranscriber.start(
                    apiKey = activeApiKey,
                    model = cachedSettings.model,
                    logRaw = false,
                )
            }.onFailure { e ->
                logger.w("Learn: translator text-transcriber start failed: ${e.message}")
                // Не критично: audio перевод продолжит работать без транскрипции
            }
        }

        logger.d("◀ Learn.startInternal — awaiting SetupComplete")
    }

    private fun handleStart(sessionId: String) {
        viewModelScope.launch {
            transcriptMutex.withLock { transcriptBuffer = emptyList() }
            _state.update { it.copy(transcript = emptyList()) }
            startInternal(sessionId)
        }
    }

    private fun handleStop() {
        viewModelScope.launch {
            stopInternal()
        }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == LearnConnectionStatus.Ready
            || _state.value.connectionStatus == LearnConnectionStatus.Negotiating) {
            startMic()
        }
    }

    private fun startMic() {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            logger.w("Learn.startMic: no RECORD_AUDIO permission")
            return
        }

        val fgsOk = safeStartForegroundService()
        if (!fgsOk) {
            _effects.tryEmit(LearnCoreEffect.ShowToast(
                UiText.Plain("Запусти обучение когда приложение на переднем плане")
            ))
        }

        _state.update {
            it.copy(isMicActive = true, connectionStatus = LearnConnectionStatus.Recording)
        }

        micJob = viewModelScope.launch {
            launch {
                audioEngine.micOutput.collect { chunk ->
                    val now = System.currentTimeMillis()
                    val sinceLastAi = now - lastAiAudioChunkAtMs

                    val effectiveTailMs = if (sessionReadyAtMs > 0L
                        && (now - sessionReadyAtMs) < INITIAL_SESSION_GUARD_MS) {
                        AI_AUDIO_TAIL_INITIAL_MS
                    } else {
                        AI_AUDIO_TAIL_MS
                    }

                    val aiActuallyAudible = lastAiAudioChunkAtMs > 0L &&
                                            sinceLastAi < effectiveTailMs

                    // Audio-клиент: с gate против echo
                    if (!aiActuallyAudible) {
                        liveClient.sendAudio(chunk)
                        if (droppedMicChunks > 0) {
                            logger.d("Mic: gate opened, dropped $droppedMicChunks chunks during AI tail")
                            droppedMicChunks = 0
                        }
                    } else {
                        droppedMicChunks++
                    }

                    // Text-транскриптер: без gate — он не воспроизводит звук, echo не страшен.
                    // Получает каждый PCM-чанк независимо от состояния audio-клиента.
                    if (activeSession?.id == "translator") {
                        translatorTextTranscriber.sendAudio(chunk)
                    }
                }
            }
            micOperationMutex.withLock {
                audioEngine.startCapture()
            }
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        silenceTimerJob?.cancel()

        viewModelScope.launch {
            micOperationMutex.withLock {
                audioEngine.stopCapture()
            }
            when {
                cachedSettings.sendAudioStreamEnd -> liveClient.sendAudioStreamEnd()
                else -> liveClient.sendTurnComplete()
            }
            // Также пробрасываем audioStreamEnd в text-транскриптер
            if (activeSession?.id == "translator") {
                runCatching { translatorTextTranscriber.sendAudioStreamEnd() }
            }
            _state.update {
                it.copy(
                    isMicActive = false,
                    connectionStatus = if (liveClient.isReady) LearnConnectionStatus.Ready
                    else LearnConnectionStatus.Disconnected
                )
            }
        }
    }

    private fun safeStartForegroundService(): Boolean {
        return try {
            appContext.startForegroundService(
                com.learnde.app.GeminiLiveForegroundService.startIntent(
                    appContext, cachedSettings.forceSpeakerOutput
                )
            )
            true
        } catch (e: IllegalStateException) {
            logger.w("FGS not allowed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } catch (e: SecurityException) {
            logger.e("FGS permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            logger.e("FGS unexpected error: ${e.message}", e)
            false
        }
    }

    private fun safeStopForegroundService() {
        try {
            appContext.startService(
                com.learnde.app.GeminiLiveForegroundService.stopIntent(appContext)
            )
        } catch (_: Exception) { }
    }

    // ══════════════════════════════════════════════════════
    //  GEMINI EVENTS
    // ══════════════════════════════════════════════════════

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected ->
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Negotiating) }

                    is GeminiEvent.SetupComplete -> handleSetupComplete()

                    is GeminiEvent.AudioChunk -> {
                        val now = System.currentTimeMillis()
                        lastAiAudioChunkAtMs = now
                        // v3.7: фиксируем активность для watchdog
                        lastModelActivityAtMs = now
                        hasModelOutputThisTurn = true

                        if (!modelStartedSpeakingThisTurn) {
                            modelStartedSpeakingThisTurn = true
                            if (awaitingInitialGreeting) {
                                awaitingInitialGreeting = false
                                greetingFallbackJob?.cancel()
                                logger.d("Learn: model started greeting ✓")
                            }
                        }
                        _state.update { it.copy(isAiSpeaking = true, isPreparingSession = false) }
                        audioEngine.enqueuePlayback(event.pcmData)

                        // Перезапускаем watchdog
                        startStuckTurnWatchdog()
                        // Audio пришёл — text-without-audio watchdog не нужен
                        cancelTextWithoutAudioWatchdog()
                    }

                    is GeminiEvent.Interrupted -> {
                        transcriptChannel.trySend(TranscriptOp.ModelInterrupted)
                        audioEngine.flushPlayback()
                        _state.update { it.copy(isAiSpeaking = false) }
                        hasModelOutputThisTurn = false
                        cancelStuckTurnWatchdog()
                        cancelTextWithoutAudioWatchdog()
                    }

                    is GeminiEvent.TurnComplete -> {
                        transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
                        transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
                        modelStartedSpeakingThisTurn = false
                        hasModelOutputThisTurn = false
                        cancelStuckTurnWatchdog()
                        cancelTextWithoutAudioWatchdog()

                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }

                        flushPendingVocabViolation()

                        lastInputTs = System.currentTimeMillis()
                        val now = System.currentTimeMillis()
                        val cooldownPassed = (now - lastSilencePromptAtMs) > SILENCE_PROMPT_COOLDOWN_MS

                        if (_state.value.isMicActive && !sessionFinished && cooldownPassed
                            && activeSession?.id != "translator"
                        ) {
                            silenceTimerJob?.cancel()
                            silenceTimerJob = viewModelScope.launch {
                                delay(LEARNER_SILENCE_THRESHOLD_MS)
                                val quietFor = System.currentTimeMillis() - lastInputTs
                                if (quietFor > SILENCE_CHECK_WINDOW_MS
                                    && liveClient.isReady
                                    && _state.value.isMicActive
                                    && !sessionFinished
                                    && activeSession?.id != "translator"
                                ) {
                                    logger.d("Learn: silence detected (${quietFor}ms), prompting AI")
                                    lastSilencePromptAtMs = System.currentTimeMillis()
                                    liveClient.sendText(
                                        "[СИСТЕМА]: Ученик молчит. Коротко подбодри его по-русски, " +
                                            "дай подсказку или назови правильный ответ и попроси повторить."
                                    )
                                    lastInputTs = System.currentTimeMillis()
                                }
                            }
                        }
                    }

                    is GeminiEvent.GenerationComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        // v3.8: для translator-сессии ASR пользователя не используем —
                        // транскрипцию даст сама модель через function call submit_user_speech.
                        // Это решает проблему иероглифов на коротких фразах укр/рос/нем.
                        if (activeSession?.id != "translator") {
                            transcriptChannel.trySend(TranscriptOp.UserDelta(event.text))
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        if (lastAiAudioChunkAtMs == 0L
                            || (System.currentTimeMillis() - lastAiAudioChunkAtMs) > 500) {
                            startTextWithoutAudioWatchdog()
                        }
                        // v3.9: для translator пузыри строит TranslatorTextTranscriber.
                        // Output аудио-модели в UI не пускаем — был бы дубль перевода.
                        if (activeSession?.id != "translator") {
                            transcriptChannel.trySend(TranscriptOp.ModelDelta(event.text, "OutputTranscript"))
                        }
                    }

                    is GeminiEvent.ModelText -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        if (lastAiAudioChunkAtMs == 0L
                            || (System.currentTimeMillis() - lastAiAudioChunkAtMs) > 500) {
                            startTextWithoutAudioWatchdog()
                        }
                        // v3.9: см. комментарий в OutputTranscript ветке
                        if (activeSession?.id != "translator") {
                            transcriptChannel.trySend(TranscriptOp.ModelDelta(event.text, "ModelText"))
                        }
                    }

                    is GeminiEvent.ToolCall -> {
                        // ВАЖНО: Вызов функции — это тоже активность модели! Сбрасываем таймер зависания.
                        lastModelActivityAtMs = System.currentTimeMillis()
                        hasModelOutputThisTurn = true
                        startStuckTurnWatchdog()

                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        handleToolCalls(event)
                    }

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) {
                            toolCallJobs[id]?.cancel()
                        }
                    }

                    is GeminiEvent.Disconnected -> {
                        greetingFallbackJob?.cancel()
                        cancelStuckTurnWatchdog()
                        cancelTextWithoutAudioWatchdog()
                        val isAbnormal = event.code != 1000 && event.code != 1001
                        val errorMsg = if (isAbnormal) "Соединение закрыто: ${event.reason} (Код: ${event.code}). Проверьте API-ключ." else null

                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
                                error = if (isAbnormal) UiText.Plain(errorMsg!!) else it.error
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        silenceTimerJob?.cancel()

                        if (isAbnormal && activeSession != null) {
                            _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain(errorMsg!!)))
                        }
                    }

                    is GeminiEvent.ConnectionError -> {
                        greetingFallbackJob?.cancel()
                        cancelStuckTurnWatchdog()
                        cancelTextWithoutAudioWatchdog()
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
                                error = UiText.Plain(event.message),
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        silenceTimerJob?.cancel()
                        _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain(event.message)))
                    }

                    is GeminiEvent.SessionHandleUpdate,
                    is GeminiEvent.GoAway,
                    is GeminiEvent.UsageMetadata,
                    is GeminiEvent.GroundingMetadata -> { }
                }
            }
        }
    }

    private fun handleSetupComplete() {
        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Ready) }
        sessionReadyAtMs = System.currentTimeMillis()
        val session = activeSession ?: return
        contextSeeded = true
        modelStartedSpeakingThisTurn = false

        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            delay(GREETING_WARMUP_MS)

            if (!liveClient.isReady || activeSession != session) {
                logger.w("Learn: WS not ready or session changed after warmup, aborting greeting flow")
                return@launch
            }

            if (session.initialUserMessage.isBlank()) {
                logger.d("Learn: no initial greeting → enabling mic only")
                if (!_state.value.isMicActive) startMic()
                return@launch
            }

            logger.d("Learn: starting greeting sequence (silence-first → mic → text)")
            awaitingInitialGreeting = true

            runCatching { sendSilenceWarmup() }
                .onFailure { logger.w("Learn: silence warmup failed: ${it.message}") }

            if (!liveClient.isReady || activeSession != session) {
                awaitingInitialGreeting = false
                return@launch
            }

            if (!_state.value.isMicActive) startMic()
            delay(MIC_PREWARM_MS)

            if (!liveClient.isReady || activeSession != session) {
                awaitingInitialGreeting = false
                return@launch
            }

            logger.d("Learn: sending initial greeting trigger")
            liveClient.sendText(session.initialUserMessage)

            greetingFallbackJob?.cancel()
            greetingFallbackJob = viewModelScope.launch {
                delay(GREETING_RETRY_MS)
                if (awaitingInitialGreeting && liveClient.isReady && activeSession == session) {
                    logger.w("Learn: no audio from model in ${GREETING_RETRY_MS}ms — retrying")
                    runCatching { sendSilenceWarmup() }
                    if (activeSession != session) return@launch

                    liveClient.sendText(
                        "Ты меня слышишь? Поприветствуй ученика сейчас по-русски и " +
                            "задай первый вопрос."
                    )

                    delay(GREETING_FINAL_MS - GREETING_RETRY_MS)
                    if (awaitingInitialGreeting && activeSession == session) {
                        logger.w("Learn: model stayed silent, giving up greeting flow")
                        awaitingInitialGreeting = false
                    }
                }
            }
        }
    }

    private suspend fun sendSilenceWarmup() {
        val silence = ByteArray(SILENCE_PCM_BYTES)
        logger.d("Learn: injecting ${SILENCE_PCM_BYTES}B of silence (${SILENCE_WARMUP_MS}ms)")
        val chunkSize = 1280
        var offset = 0
        while (offset < silence.size) {
            val end = minOf(offset + chunkSize, silence.size)
            liveClient.sendAudio(silence.copyOfRange(offset, end))
            offset = end
            delay(40)
        }
    }

    private fun flushPendingVocabViolation() {
        val violation = pendingVocabViolation ?: return
        pendingVocabViolation = null
        if ((activeSession?.id == "a1_situation" || activeSession?.id == "a1_review")
            && liveClient.isReady) {
            val prompt = vocabularyEnforcer.buildCorrectionPrompt(violation)
            logger.d("Learn: sending buffered vocab correction (${violation.violatingWords})")
            liveClient.sendText(prompt)
        }
    }

    private fun handleToolCalls(event: GeminiEvent.ToolCall) {
        val session = activeSession
        val responses = java.util.concurrent.ConcurrentLinkedQueue<ToolResponse>()

        for (call in event.calls) {
            pendingToolCalls.add(call.id)
            statusBus.onDetected(call.name, call.id)
        }

        val children = event.calls.map { call ->
            viewModelScope.launch {
                var success = true
                try {
                    if (call.id !in pendingToolCalls) {
                        responses.add(ToolResponse(call.name, call.id, """{"status":"cancelled"}"""))
                        success = false
                        return@launch
                    }

                    statusBus.onExecuting(call.name, call.id)

                    val result = try {
                        session?.handleToolCall(call) ?: """{"error":"no active session"}"""
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            responses.add(ToolResponse(call.name, call.id, """{"status":"cancelled"}"""))
                            success = false
                            throw e
                        }
                        logger.e("Learn.toolCall threw: ${e.message}", e)
                        success = false
                        """{"error":"${e.message?.replace("\"", "'")}"}"""
                    }

                    if (result.contains("\"error\"")) success = false
                    responses.add(ToolResponse(call.name, call.id, result))
                } finally {
                    statusBus.onCompleted(call.name, call.id, success = success)
                    pendingToolCalls.remove(call.id)
                    toolCallJobs.remove(call.id)
                }
            }.also { toolCallJobs[call.id] = it }
        }

        viewModelScope.launch {
            children.joinAll()
            if (responses.isNotEmpty() && liveClient.isReady) {
                runCatching { liveClient.sendToolResponse(responses.toList()) }
                    .onFailure { logger.e("Learn: failed to send ToolResponse: ${it.message}") }

                // ВАЖНО: Мы ответили модели, сейчас она начнет генерировать голос.
                // Даем ей свежие 3.5 секунды на старт аудио.
                lastModelActivityAtMs = System.currentTimeMillis()
                startStuckTurnWatchdog()
            }

            if (event.calls.any { it.name == "finish_session" }) {
                sessionFinished = true
                silenceTimerJob?.cancel()
                logger.d("Learn: finish_session → grace ${FINISH_SESSION_GRACE_MS}ms")

                _state.update { it.copy(isFinishingSession = true) }

                finishGraceJob?.cancel()
                finishGraceJob = viewModelScope.launch {
                    delay(FINISH_SESSION_GRACE_MS)
                    if (activeSession != null && sessionFinished) {
                        stopInternal()
                    }
                }
            }
        }
    }

    fun sendSystemText(text: String) {
        if (!liveClient.isReady) {
            logger.w("Learn.sendSystemText: liveClient not ready, dropping: $text")
            return
        }
        viewModelScope.launch {
            if (_state.value.isAiSpeaking) {
                var waited = 0L
                val maxWaitMs = 4_000L
                while (_state.value.isAiSpeaking && waited < maxWaitMs) {
                    delay(120)
                    waited += 120
                }
            }
            runCatching { liveClient.sendText(text) }
                .onFailure { logger.e("Learn.sendSystemText failed: ${it.message}") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()
        setupJob?.cancel()
        finishGraceJob?.cancel()
        cancelStuckTurnWatchdog()
        cancelTextWithoutAudioWatchdog()
        statusBus.reset()
        safeStopForegroundService()

        cleanupScope.launch {
            runCatching { stopInternal() }
            runCatching { transcriptMutex.withLock { transcriptBuffer = emptyList() } }
            runCatching { audioEngine.releaseAll() }
            runCatching { transcriptChannel.close() }
            runCatching { translatorTextTranscriber.shutdown() }
            logger.d("LearnCoreViewModel cleanup complete")
        }
    }

}

