// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
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
    @TranscriberScope private val transcriberClient: LiveClient,
    @LearnScope private val audioEngine: AudioEngine,
    private val settingsStore: DataStore<AppSettings>,
    private val logger: AppLogger,
    private val arbiter: ActiveClientArbiter,
    private val statusBus: LearnFunctionStatusBus,
    private val registry: LearnSessionRegistry,
    private val vocabularyEnforcer: com.learnde.app.learn.domain.VocabularyEnforcer,
    private val translatorSession: com.learnde.app.learn.sessions.translator.TranslatorSession,
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
        private const val AI_AUDIO_TAIL_INITIAL_MS = 1_500L
        private const val INITIAL_SESSION_GUARD_MS = 2_000L
        private const val STUCK_TURN_TIMEOUT_MS = 8_000L
        private const val STUCK_TURN_TIMEOUT_TRANSLATOR_MS = 2_000L
        private const val TEXT_WITHOUT_AUDIO_TIMEOUT_MS = 1_500L
        private const val SILENCE_PROMPT_COOLDOWN_MS = 30_000L
        private const val FINISH_SESSION_GRACE_MS = 5_000L
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

    private var stuckTurnWatchdogJob: Job? = null
    private var textWithoutAudioJob: Job? = null
    private var transcriberObserverJob: Job? = null
    @Volatile private var transcriberEnabled: Boolean = false

    // Буфер дельт текущего turn'а транскриптора (накапливается до TurnComplete).
    private val transcriberBuffer = StringBuilder()
    private val origRegex = Regex("""ORIG:\s*(.+?)(?=\n\s*TRANS:|$)""", RegexOption.DOT_MATCHES_ALL)
    private val transRegex = Regex("""TRANS:\s*(.+?)$""", RegexOption.DOT_MATCHES_ALL)

    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var modelStartedSpeakingThisTurn = false
    @Volatile private var awaitingInitialGreeting = false
    @Volatile private var lastAiAudioChunkAtMs: Long = 0L
    @Volatile private var sessionFinished: Boolean = false
    @Volatile private var lastSilencePromptAtMs: Long = 0L
    @Volatile private var droppedMicChunks: Int = 0

    @Volatile private var lastModelActivityAtMs: Long = 0L
    @Volatile private var sessionReadyAtMs: Long = 0L
    @Volatile private var hasModelOutputThisTurn: Boolean = false
    @Volatile private var translatorForceMicOpenUntilMs: Long = 0L

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

    @Volatile private var liveModelMessageTs: Long = 0L
    @Volatile private var translatorFunctionFinalizedThisTurn: Boolean = false

    init {
        observeSettings()
        observeGeminiEvents()
        observeArbiter()
        observeVocabularyViolations()
        // observeTranslatorFunctionTranscripts() — убрано: voice-only режим без функций
        startTranscriptProcessor()
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
                liveModelMessageTs = 0L
                translatorFunctionFinalizedThisTurn = false
                transcriptMutex.withLock {
                    transcriptBuffer = emptyList()
                }
                _state.update { it.copy(transcript = emptyList(), liveUserTranscript = "") }
            }
        }
    }

    private suspend fun handleUserDelta(text: String) {
        if (text.isEmpty()) return

        // Gemini Live шлёт incremental deltas (НЕ cumulative).
        // Просто аппендим, без перепроверок.
        userTurnBuffer.append(text)

        _state.update { it.copy(liveUserTranscript = userTurnBuffer.toString()) }

        lastInputTs = System.currentTimeMillis()
        silenceTimerJob?.cancel()
    }

    private suspend fun finalizeUserTurn() {
        val bufferedText = userTurnBuffer.toString().trim()
        userTurnBuffer.clear()

        _state.update { it.copy(liveUserTranscript = "") }

        if (bufferedText.isEmpty()) return

        // Для translator: если функция record_translation уже была вызвана,
        // в transcript уже есть финальное user-сообщение.
        // Не добавляем дубликат от inputAudioTranscription.
        if (activeSession?.id == "translator") {
            val waitStart = System.currentTimeMillis()
            val maxWait = 1_200L
            while (System.currentTimeMillis() - waitStart < maxWait) {
                val lastUser = transcriptBuffer.lastOrNull { it.role == ConversationMessage.ROLE_USER }
                // Окно 10 секунд: TurnComplete может прийти через 2-5 сек после функции,
                // и мы должны узнать что функция уже отработала.
                if (lastUser != null && (System.currentTimeMillis() - lastUser.timestamp) < 10_000L) {
                    // record_translation уже записал точную пару — выходим без дублирования
                    logger.d("finalizeUserTurn[translator]: skip dup, FN already wrote '${lastUser.text}'")
                    return
                }
                kotlinx.coroutines.delay(50)
            }
            transcriptMutex.withLock {
                val lastUser = transcriptBuffer.lastOrNull { it.role == ConversationMessage.ROLE_USER }
                if (lastUser != null && (System.currentTimeMillis() - lastUser.timestamp) < 10_000L) {
                    return@withLock
                }
                // Функция не пришла — это fallback, добавляем
                val newMsg = ConversationMessage.user(bufferedText)
                val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            }
            return
        }

        transcriptMutex.withLock {
            val newMsg = ConversationMessage.user(bufferedText)
            val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
            transcriptBuffer = next
            _state.update { it.copy(transcript = next) }
        }
    }

    private suspend fun handleModelDelta(text: String, source: String) {
        if (text.isEmpty()) return

        if (cachedSettings.outputTranscription && source == "ModelText") return
        if (!cachedSettings.outputTranscription && source == "OutputTranscript") return

        // Для translator: если record_translation уже завершил turn — игнорируем
        // поздние OutputTranscript-дельты, которые иначе создадут "хвостовой" пузырь.
        if (activeSession?.id == "translator" && translatorFunctionFinalizedThisTurn) {
            return
        }

        // Первая дельта модели = модель начала отвечать = user-turn закончен.
        // Финализируем user СНАЧАЛА, чтобы он попал в transcript ПЕРЕД model-bubble.
        if (modelTurnBuffer.isEmpty() && userTurnBuffer.isNotEmpty()) {
            finalizeUserTurn()
        }

        lastModelActivityAtMs = System.currentTimeMillis()
        hasModelOutputThisTurn = true
        startStuckTurnWatchdog()

        // Gemini Live шлёт incremental deltas (НЕ cumulative). Просто аппендим.
        modelTurnBuffer.append(text)

        upsertLiveModelBubble(modelTurnBuffer.toString())

        if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
            vocabularyEnforcer.analyze(text)
        }
    }

    private suspend fun finalizeModelTurn() {
        val finalText = modelTurnBuffer.toString().trim()
        modelTurnBuffer.clear()

        // Для translator: если record_translation уже сработал — он удалил
        // live-bubble и записал финальную пару, выставив liveModelMessageTs = 0L.
        // В этом случае пропускаем upsert.
        // Если функция НЕ пришла — fallback, оставляем upsert как есть.
        if (finalText.isNotEmpty() && liveModelMessageTs != 0L) {
            upsertLiveModelBubble(finalText)
        }

        liveModelMessageTs = 0L
        hasModelOutputThisTurn = false
        cancelStuckTurnWatchdog()
        cancelTextWithoutAudioWatchdog()
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

    private fun startStuckTurnWatchdog() {
        stuckTurnWatchdogJob?.cancel()
        val timeout = if (activeSession?.id == "translator") STUCK_TURN_TIMEOUT_TRANSLATOR_MS
                      else STUCK_TURN_TIMEOUT_MS
        stuckTurnWatchdogJob = viewModelScope.launch {
            delay(timeout)

            // Не паникуем если модель ВСЁ ЕЩЁ играет аудио (просто длинный ответ)
            val now = System.currentTimeMillis()
            val sinceLastAudio = now - lastAiAudioChunkAtMs
            if (lastAiAudioChunkAtMs > 0L && sinceLastAudio < 2_000L) {
                logger.d("Stuck-turn watchdog: model still playing audio (${sinceLastAudio}ms ago), restarting watchdog")
                startStuckTurnWatchdog()
                return@launch
            }

            if (hasModelOutputThisTurn) {
                logger.w("⚠ STUCK_TURN_DETECTED — force-finalizing")
                transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
                transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
                runCatching { audioEngine.flushPlayback() }
                runCatching { audioEngine.onTurnComplete() }
                modelStartedSpeakingThisTurn = false
                hasModelOutputThisTurn = false
                translatorFunctionFinalizedThisTurn = false
                _state.update { it.copy(isAiSpeaking = false) }
                lastAiAudioChunkAtMs = 0L
                // Принудительно открываем микрофон на 1 секунду — после watchdog
                // пользователь должен сразу мочь говорить, без задержек.
                if (activeSession?.id == "translator") {
                    translatorForceMicOpenUntilMs = System.currentTimeMillis() + 1000L
                }
                cancelTextWithoutAudioWatchdog()
            }
        }
    }

    private fun cancelStuckTurnWatchdog() {
        stuckTurnWatchdogJob?.cancel()
        stuckTurnWatchdogJob = null
    }

    private fun startTextWithoutAudioWatchdog() {
        textWithoutAudioJob?.cancel()
        textWithoutAudioJob = viewModelScope.launch {
            delay(TEXT_WITHOUT_AUDIO_TIMEOUT_MS)
            val now = System.currentTimeMillis()
            if (hasModelOutputThisTurn && (now - lastAiAudioChunkAtMs) > TEXT_WITHOUT_AUDIO_TIMEOUT_MS) {
                logger.w("⚠ TEXT_WITHOUT_AUDIO — opening mic gate proactively")
                lastAiAudioChunkAtMs = 0L
                _state.update { it.copy(isAiSpeaking = false) }
            }
        }
    }

    private fun cancelTextWithoutAudioWatchdog() {
        textWithoutAudioJob?.cancel()
        textWithoutAudioJob = null
    }

    // ════════════════════════════════════════════════════════════
    //  TRANSCRIBER CLIENT — параллельный text-only поток
    // ════════════════════════════════════════════════════════════

    /**
     * Запуск второго (текстового) клиента для translator-сессии.
     * Работает параллельно voice-клиенту, слушает тот же микрофон,
     * выдаёт ORIG/TRANS пары через GeminiEvent.ModelText.
     *
     * Полностью независим от voice-клиента: ошибки коннекта или
     * disconnect транскриптора НЕ влияют на основной аудио-поток.
     */
    private suspend fun startTranscriberClient() {
        if (transcriberEnabled) {
            logger.d("Transcriber: already enabled, skipping start")
            return
        }
        if (activeApiKey.isEmpty()) {
            logger.w("Transcriber: no API key, skipping")
            return
        }

        logger.d("▶ Transcriber.start")

        // Observer событий транскриптора — отдельный job чтобы
        // его можно было корректно остановить вместе с клиентом.
        transcriberObserverJob?.cancel()
        transcriberObserverJob = viewModelScope.launch {
            transcriberClient.events.collect { event ->
                handleTranscriberEvent(event)
            }
        }

        runCatching {
            transcriberClient.connect(
                apiKey = activeApiKey,
                config = buildTranscriberConfig(),
                logRaw = false,
            )
            transcriberEnabled = true
            logger.d("Transcriber: connect initiated")
        }.onFailure { e ->
            logger.e("Transcriber: connect failed: ${e.message}", e)
            transcriberObserverJob?.cancel()
            transcriberObserverJob = null
            transcriberEnabled = false
        }
    }

    /**
     * Корректное завершение транскриптора.
     * Сначала гасит observer (чтобы события disconnect не проходили
     * как полезные), потом закрывает WS.
     */
    private suspend fun stopTranscriberClient() {
        if (!transcriberEnabled && transcriberObserverJob == null) return

        logger.d("▶ Transcriber.stop")
        transcriberEnabled = false

        transcriberObserverJob?.cancel()
        transcriberObserverJob = null

        runCatching { transcriberClient.disconnect() }
            .onFailure { logger.w("Transcriber: disconnect error: ${it.message}") }

        logger.d("◀ Transcriber.stop done")
    }

    /**
     * Обработка событий второго клиента.
     * Пока — только логирование. UI и парсинг ORIG/TRANS подключим
     * на следующих шагах.
     */
    private fun handleTranscriberEvent(event: GeminiEvent) {
        when (event) {
            is GeminiEvent.Connected -> logger.d("Transcriber ← Connected")
            is GeminiEvent.SetupComplete -> logger.d("Transcriber ← ✓ SetupComplete")
            is GeminiEvent.ModelText -> logger.d("Transcriber ← TEXT: ${event.text}")
            is GeminiEvent.TurnComplete -> logger.d("Transcriber ← TurnComplete")
            is GeminiEvent.Disconnected -> {
                logger.d("Transcriber ← Disconnected: ${event.code} ${event.reason}")
                transcriberEnabled = false
            }
            is GeminiEvent.ConnectionError -> {
                logger.e("Transcriber ← Error: ${event.message}")
                transcriberEnabled = false
            }
            else -> { /* остальные события игнорируем */ }
        }
    }

    /**
     * Конфиг для текстового транскриптора.
     * Полностью independent от translator config — другие промпт,
     * modality, transcription-флаги.
     */
    private fun buildTranscriberConfig(): SessionConfig {
        val transcriberPrompt = """ru ↔ de

You are a qualified bilingual transcriber. You listen to speech in Russian or German and output a structured text record. Audio in, text out. No voice response.

For every utterance you hear, respond with EXACTLY this format:

ORIG: <verbatim text in the original language>
TRANS: <translation: ru→de or de→ru>

Strict rules:
- ORIG must contain the user's exact words, in the original language and script.
- If Russian was spoken — TRANS is in German.
- If German was spoken — TRANS is in Russian.
- You strictly use only Russian and German. No other languages.
- TRANSLATION ONLY. No own initiative. No questions. No commentary. No explanations.
- If the input is not Russian or German — output nothing at all.
- If you hear noise, silence, or mumbling — output nothing at all.
- Do not greet. Do not acknowledge. Do not repeat.
- One utterance = one ORIG/TRANS pair.""".trimIndent()

        return SessionConfig(
            model = cachedSettings.model,
            responseModality = "TEXT",
            temperature = 0.3f,
            topP = 0.9f,
            topK = 0,
            maxOutputTokens = 2048,
            voiceId = "",
            languageCode = "",
            latencyProfile = LatencyProfile.UltraLow,
            autoActivityDetection = true,
            vadStartSensitivity = "START_SENSITIVITY_HIGH",
            vadEndSensitivity = "END_SENSITIVITY_LOW",
            vadSilenceDurationMs = 500,
            vadPrefixPaddingMs = 150,
            systemInstruction = transcriberPrompt,
            inputTranscription = false,
            outputTranscription = false,
            transcriptionLanguageCodes = emptyList(),
            enableSessionResumption = false,
            sendSessionResumptionConfig = false,
            sessionHandle = null,
            enableContextCompression = false,
            sendContextCompressionConfig = false,
            enableGoogleSearch = false,
            functionDeclarations = emptyList(),
            sendAudioStreamEnd = false,
            sendThinkingConfig = true,
            sendTranscriptionConfig = false,
        )
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

    private fun observeTranslatorFunctionTranscripts() {
        viewModelScope.launch {
            translatorSession.functionTranscripts.collect { pair ->
                if (activeSession?.id != "translator") return@collect

                logger.d("Translator FN-transcript: [${pair.sourceLang}] '${pair.original}' → '${pair.translation}'")

                // ВАЖНО: все мутации делаем АТОМАРНО под одним withLock,
                // иначе race condition: между удалением live-bubble и сбросом
                // liveModelMessageTs = 0L успевает прилететь поздняя дельта,
                // и upsertLiveModelBubble создаст новый "хвостовой" пузырь.
                transcriptMutex.withLock {
                    // 1. Удаляем live-bubble модели (он мог быть создан outputTranscription'ом)
                    val filtered = if (liveModelMessageTs != 0L) {
                        transcriptBuffer.filterNot { it.timestamp == liveModelMessageTs }
                    } else {
                        transcriptBuffer
                    }

                    // 2. Добавляем точную пару USER + MODEL
                    val now = System.currentTimeMillis()
                    val userMsg = ConversationMessage(
                        role = ConversationMessage.ROLE_USER,
                        text = pair.original,
                        timestamp = now,
                    )
                    val modelMsg = ConversationMessage(
                        role = ConversationMessage.ROLE_MODEL,
                        text = pair.translation,
                        timestamp = now + 1,
                    )
                    val next = (filtered + userMsg + modelMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                    transcriptBuffer = next

                    // 3. Сбрасываем буферы дельт ВНУТРИ withLock — атомарно с обновлением transcript.
                    //    Это гарантирует что новый upsertLiveModelBubble увидит корректное состояние:
                    //    либо liveModelMessageTs = 0L (старый bubble уже удалён),
                    //    либо ещё старый ts (но тогда он находится в buffer и обновится корректно).
                    liveModelMessageTs = 0L
                    userTurnBuffer.clear()
                    modelTurnBuffer.clear()

                    // 4. Помечаем turn как завершённый функцией —
                    //    приходящие позже OutputTranscript-дельты должны игнорироваться,
                    //    иначе они создадут "хвостовой" пузырь типа "essen." или "ist.".
                    translatorFunctionFinalizedThisTurn = true

                    _state.update {
                        it.copy(transcript = next, liveUserTranscript = "")
                    }
                }

                // Для translator: после функции явно завершаем turn локально и отправляем
                // ToolResponse → сервер закончит генерацию и будет готов к следующей фразе.
                // Без этого сервер "залипает" в режиме генерации, и модель не реагирует
                // на новую речь пользователя в течение десятков секунд.
                kotlinx.coroutines.delay(50) // даём ToolResponse уйти на сервер первым
                runCatching { audioEngine.onTurnComplete() }
                _state.update { it.copy(isAiSpeaking = false) }
                modelStartedSpeakingThisTurn = false
                hasModelOutputThisTurn = false
                translatorFunctionFinalizedThisTurn = false
                cancelStuckTurnWatchdog()
                cancelTextWithoutAudioWatchdog()
                lastAiAudioChunkAtMs = 0L
                // Принудительно держим микрофон открытым 1500мс после функции —
                // пользователь должен иметь возможность сразу говорить следующую фразу
                // даже если модель ещё доигрывает остаток своего аудио.
                translatorForceMicOpenUntilMs = System.currentTimeMillis() + 1_500L
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

        val profile = if (isTranslator) {
            LatencyProfile.UltraLow
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
            "translator"   -> Triple(500, 150, 1.0f)  // быстрее реакция, ниже temp = меньше отсебятины
            "a1_situation" -> Triple(1000, 300, cachedSettings.temperature)
            "a1_review"    -> Triple(1000, 300, cachedSettings.temperature)
            else           -> Triple(1000, 300, cachedSettings.temperature)
        }

        val finalSilenceMs = if (cachedSettings.vadSilenceTimeoutMs > 0 && !isTranslator)
            maxOf(cachedSettings.vadSilenceTimeoutMs, 500)
        else silenceMs

        val finalLanguageCode = if (isTranslator) "" else cachedSettings.languageCode
        val finalVoiceId = if (isTranslator) "Puck" else cachedSettings.voiceId
        val finalMaxTokens = if (isTranslator) 8192 else cachedSettings.maxOutputTokens
        val finalTopP = if (isTranslator) 0.95f else cachedSettings.topP  // меньше отсебятины
        val finalTopK = if (isTranslator) 0 else cachedSettings.topK

        // Для translator транскрипция выключена — record_translation function call
        // даёт точный текст оригинала и перевода. ASR Gemini Live мисхёрит языки
        // ("preto sim" вместо "Привіт"), и эти данные нам не нужны.
        val inputTranscr = false
        val outputTranscr = false

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
            // Для переводчика на расстоянии: HIGH чувствительность старта (быстро реагирует на тихую речь)
            // и LOW конца (даём договорить до конца перед концом фразы)
            vadStartSensitivity = if (isTranslator) "START_SENSITIVITY_HIGH"
                else if (cachedSettings.vadStartOfSpeechSensitivity > 0.5f) "START_SENSITIVITY_HIGH"
                else "START_SENSITIVITY_LOW",
            vadEndSensitivity = if (isTranslator) "END_SENSITIVITY_LOW"
                else if (cachedSettings.vadEndOfSpeechSensitivity > 0.5f) "END_SENSITIVITY_HIGH"
                else "END_SENSITIVITY_LOW",
            vadSilenceDurationMs = finalSilenceMs,
            vadPrefixPaddingMs = prefixMs,
            systemInstruction = finalSystemInstruction,
            inputTranscription = inputTranscr,
            outputTranscription = outputTranscr,
            transcriptionLanguageCodes = emptyList(),
            enableSessionResumption = false,
            sendSessionResumptionConfig = if (isTranslator) false else true,
            sessionHandle = null,
            enableContextCompression = false,
            sendContextCompressionConfig = if (isTranslator) false else true,
            sendTranscriptionConfig = if (isTranslator) false else true,
            enableGoogleSearch = false,
            functionDeclarations = session.functionDeclarations,
            sendAudioStreamEnd = cachedSettings.sendAudioStreamEnd,
            sendThinkingConfig = true,
        )
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

        stopTranscriberClient()
        runCatching { liveClient.disconnect() }
        runCatching { session?.onExit() }

        transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
        transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
        transcriptChannel.trySend(TranscriptOp.Reset)

        activeSession = null
        pendingToolCalls.clear()
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

        // Translator: запускаем параллельный text-клиент для качественного транскрипта.
        // Failure здесь НЕ критичен — voice-клиент работает независимо.
        if (session.id == "translator" && activeSession != null) {
            startTranscriberClient()
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
                    val isTranslator = activeSession?.id == "translator"
                    val now = System.currentTimeMillis()
                    val sinceLastAi = now - lastAiAudioChunkAtMs

                    val effectiveTailMs: Long = when {
                        // Translator voice-only: микрофон закрыт минимально, чтобы избежать
                        // эха своего голоса, но не блокировать пользователя.
                        isTranslator -> 0L
                        sessionReadyAtMs > 0L && (now - sessionReadyAtMs) < INITIAL_SESSION_GUARD_MS ->
                            AI_AUDIO_TAIL_INITIAL_MS
                        else -> AI_AUDIO_TAIL_MS
                    }

                    // Принудительное открытие микрофона после record_translation —
                    // позволяет пользователю говорить сразу следующую фразу,
                    // не дожидаясь пока модель доиграет остаток своего аудио.
                    val forceOpen = isTranslator && now < translatorForceMicOpenUntilMs

                    val aiActuallyAudible =
                        !forceOpen &&
                        lastAiAudioChunkAtMs > 0L &&
                        sinceLastAi < effectiveTailMs

                    if (!aiActuallyAudible) {
                        liveClient.sendAudio(chunk)
                        if (droppedMicChunks > 0) {
                            logger.d("Mic: gate opened, dropped $droppedMicChunks chunks during AI tail")
                            droppedMicChunks = 0
                        }
                    } else {
                        droppedMicChunks++
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
            // Text-клиент отключён.
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

                        startStuckTurnWatchdog()
                        cancelTextWithoutAudioWatchdog()
                    }

                    is GeminiEvent.Interrupted -> {
                        // При barge-in: модель НЕ успеет вызвать record_translation.
                        // Финализируем то что собралось через input/outputTranscription
                        // как fallback — иначе transcript останется пустым.
                        transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
                        transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
                        audioEngine.flushPlayback()
                        _state.update { it.copy(isAiSpeaking = false) }
                        modelStartedSpeakingThisTurn = false
                        hasModelOutputThisTurn = false
                        translatorFunctionFinalizedThisTurn = false
                        cancelStuckTurnWatchdog()
                        cancelTextWithoutAudioWatchdog()
                    }

                    is GeminiEvent.TurnComplete -> {
                        // Для translator: если record_translation уже сработал —
                        // observeTranslatorFunctionTranscripts уже добавил финальную пару.
                        // Финализация буферов всё равно нужна — finalizeUserTurn/finalizeModelTurn
                        // знают про anti-duplicate и пропустят дубль.
                        transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
                        transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
                        modelStartedSpeakingThisTurn = false
                        hasModelOutputThisTurn = false
                        // Сбрасываем флаг — следующий turn ещё не финализирован функцией
                        translatorFunctionFinalizedThisTurn = false
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
                                    liveClient.sendRealtimeText(
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
                        cancelStuckTurnWatchdog()
                        cancelTextWithoutAudioWatchdog()
                    }

                    is GeminiEvent.InputTranscript -> {
                        // Для translator транскрипция отключена и не нужна
                        if (activeSession?.id != "translator") {
                            transcriptChannel.trySend(TranscriptOp.UserDelta(event.text))
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        // Для translator OutputTranscript игнорируем —
                        // record_translation function call даёт точный перевод.
                        if (activeSession?.id != "translator") {
                            if (lastAiAudioChunkAtMs == 0L
                                || (System.currentTimeMillis() - lastAiAudioChunkAtMs) > 500) {
                                startTextWithoutAudioWatchdog()
                            }
                            transcriptChannel.trySend(TranscriptOp.ModelDelta(event.text, "OutputTranscript"))
                        }
                    }

                    is GeminiEvent.ModelText -> {
                        if (activeSession?.id == "translator") return@collect
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        if (lastAiAudioChunkAtMs == 0L
                            || (System.currentTimeMillis() - lastAiAudioChunkAtMs) > 500) {
                            startTextWithoutAudioWatchdog()
                        }
                        if (activeSession?.id == "translator") {
                            lastModelActivityAtMs = System.currentTimeMillis()
                            hasModelOutputThisTurn = true
                            startStuckTurnWatchdog()
                        }
                        transcriptChannel.trySend(TranscriptOp.ModelDelta(event.text, "ModelText"))
                    }

                    is GeminiEvent.ToolCall -> {
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

            if (session.id == "translator" || session.initialUserMessage.isBlank()) {
                logger.d("Learn: no initial greeting → enabling mic only")
                delay(50)
                if (activeSession == session && !_state.value.isMicActive
                    && _state.value.connectionStatus == LearnConnectionStatus.Ready) {
                    startMic()
                }
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

                    liveClient.sendRealtimeText(
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
            liveClient.sendRealtimeText(prompt)
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
            runCatching { liveClient.sendRealtimeText(text) }
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
        transcriberObserverJob?.cancel()
        cancelStuckTurnWatchdog()
        cancelTextWithoutAudioWatchdog()
        statusBus.reset()
        safeStopForegroundService()

        cleanupScope.launch {
            runCatching { stopInternal() }
            runCatching { transcriptMutex.withLock { transcriptBuffer = emptyList() } }
            runCatching { audioEngine.releaseAll() }
            runCatching { transcriptChannel.close() }
            logger.d("LearnCoreViewModel cleanup complete")
        }
    }
}
