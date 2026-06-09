// ═══════════════════════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/translator/app/presentation/translator/TranslatorViewModel.kt
//
// ПОЛНАЯ ЗАМЕНА (v5.0 — беспроигрышный turn-taking)
//
// Что и зачем изменено (диагноз «сказало 2 слова и зависло»):
//
//   1) УБРАН РУЧНОЙ ПОЛУДУПЛЕКС ИЗ VIEWMODEL (источник «зависания»).
//      Раньше в longPhraseMode на первом аудио-чанке вызывался
//      audioEngine.stopCapture(), а startCapture() — ТОЛЬКО по TurnComplete
//      и только при status == Recording. Если вместо TurnComplete приходил
//      Interrupted, рвалась сеть или ход «застревал», микрофон не включался
//      больше НИКОГДА → приложение глохло («висит»). Теперь полудуплекс
//      реализован внутри AndroidAudioEngine v5.0 (гейт отправки чанков,
//      AudioRecord не останавливается) и открывается автоматически по факту
//      окончания звука — ни одна ветка событий не может оставить его
//      закрытым.
//
//   2) Interrupted БЕЗ ЭВРИСТИКИ «looksLikeEcho».
//      Эвристика прятала событие, но не возвращала звук: сервер уже прервал
//      генерацию. Теперь (с NO_INTERRUPTION в TranslatorSession v5.0 + гейт
//      микрофона) ложных Interrupted от эха не бывает в принципе, а если
//      событие всё же пришло — обрабатываем честно: flush, финализация пары,
//      сброс состояния хода.
//
//   3) УБРАНЫ сбросы lastSeenTurnId = Long.MIN_VALUE на границах хода.
//      turnId монотонно растёт на стороне клиента, поэтому аудио нового хода
//      ВСЕГДА проходит фильтр. Сброс же позволял «хвостовым» чанкам
//      завершённого хода проигрываться в следующем. Сброс остаётся только
//      при stop/hard-reset (где клиентский счётчик всё равно монотонен).
//
//   4) GenerationComplete больше не гасит isAiSpeaking — звук в этот момент
//      ещё дозвучивает из буфера; индикатор гасится по TurnComplete.
//
// Из v4.0 сохранено: «лёгкий» обработчик аудио-чанка (никаких корутин и
// _state.update на каждый чанк), смена языковой пары через HARD reset,
// реконнект, мониторинг сети, foreground-сервис, пары транскриптов.
// ═══════════════════════════════════════════════════════════════════════════
package com.translator.app.presentation.translator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translator.app.GeminiLiveForegroundService
import com.translator.app.data.NetworkMonitor
import com.translator.app.data.settings.AppSettings
import com.translator.app.domain.AudioEngine
import com.translator.app.domain.LiveClient
import com.translator.app.domain.model.GeminiEvent
import com.translator.app.domain.model.Language
import com.translator.app.domain.model.Languages
import com.translator.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

enum class ConnectionStatus { Disconnected, Connecting, Reconnecting, Ready, Recording }

data class TranslatorState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val pairs: List<TranslationPair> = emptyList(),
    val error: String? = null,
    val promptTokens: Int = 0,
    val responseTokens: Int = 0,
    val totalTokens: Int = 0,
    val sourceLanguage: Language = Languages.DEFAULT_SOURCE,
    val targetLanguage: Language = Languages.DEFAULT_TARGET,
    val lastInputTranscript: String = "",
    val lastOutputTranscript: String = "",
    val transcriptLog: List<TranscriptEntry> = emptyList()
)

data class TranscriptEntry(
    val id: Long = System.currentTimeMillis(),
    val direction: TranscriptDirection,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun formatted(): String {
        val arrow = if (direction == TranscriptDirection.INPUT) "🎙 IN " else "🔊 OUT"
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
            .format(java.util.Date(timestampMs))
        return "[$time] $arrow | $text"
    }
}

enum class TranscriptDirection { INPUT, OUTPUT }

@HiltViewModel
class TranslatorViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val liveClient: LiveClient,
    private val audioEngine: AudioEngine,
    private val networkMonitor: NetworkMonitor,
    private val settingsStore: DataStore<AppSettings>,
    private val logger: AppLogger
) : ViewModel() {

    private val _state = MutableStateFlow(TranslatorState())
    val state = _state.asStateFlow()
    val audioPlaybackFlow = audioEngine.playbackSync

    private var micJob: Job? = null
    private var stuckTurnWatchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkJob: Job? = null

    private val reconnectAttempt = AtomicLong(0L)

    @Volatile private var nextPairId: Long = 1L
    @Volatile private var currentOpenPairId: Long? = null

    private val lastAiAudioChunkAtMs = AtomicLong(0L)
    private val hasModelOutputThisTurn = AtomicBoolean(false)
    private val lastSeenTurnId = AtomicLong(Long.MIN_VALUE)

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    @Volatile private var activeApiKey: String = ""
    @Volatile private var fgsStarted: Boolean = false

    private val networkLost = AtomicBoolean(false)

    // Сериализует операции смены сессии (язык/реконнект), исключая гонки.
    private val sessionMutex = Mutex()

    init {
        viewModelScope.launch { audioEngine.initPlayback() }
        observeGeminiEvents()
        observeNetwork()
        observeLanguageSettings()
    }

    // ════════════════════════════════════════════════════════════════════
    //  LANGUAGE
    // ════════════════════════════════════════════════════════════════════

    private fun observeLanguageSettings() {
        viewModelScope.launch {
            settingsStore.data
                .map { it.sourceLanguageCode to it.targetLanguageCode }
                .distinctUntilChanged()
                .collect { (srcCode, tgtCode) ->
                    _state.update {
                        it.copy(
                            sourceLanguage = Languages.byCode(srcCode),
                            targetLanguage = Languages.byCode(tgtCode)
                        )
                    }
                }
        }
    }

    fun setSourceLanguage(language: Language) {
        if (language.code == _state.value.targetLanguage.code) return
        viewModelScope.launch {
            val updated = settingsStore.updateData { it.copy(sourceLanguageCode = language.code) }
            hardResetForNewLanguagePair(updated)
        }
    }

    fun setTargetLanguage(language: Language) {
        if (language.code == _state.value.sourceLanguage.code) return
        viewModelScope.launch {
            val updated = settingsStore.updateData { it.copy(targetLanguageCode = language.code) }
            hardResetForNewLanguagePair(updated)
        }
    }

    fun swapLanguages() {
        viewModelScope.launch {
            val updated = settingsStore.updateData {
                it.copy(
                    sourceLanguageCode = it.targetLanguageCode,
                    targetLanguageCode = it.sourceLanguageCode
                )
            }
            hardResetForNewLanguagePair(updated)
        }
    }

    /** Режим "Длинные фразы" — для UI. */
    val longPhraseMode = settingsStore.data
        .map { it.longPhraseMode }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** Переключает режим и переподключает сессию с новым VAD. */
    fun setLongPhraseMode(on: Boolean) {
        viewModelScope.launch {
            val updated = settingsStore.updateData { it.copy(longPhraseMode = on) }
            if (_state.value.connectionStatus != ConnectionStatus.Disconnected) {
                hardResetForNewLanguagePair(updated)
            }
        }
    }

    /**
     * ЖЁСТКИЙ сброс при смене языковой пары: при смене языков нельзя
     * переиспользовать sessionHandle, иначе Gemini восстановит старый
     * system instruction/историю и продолжит переводить на старый язык.
     */
    private suspend fun hardResetForNewLanguagePair(freshSettings: AppSettings) = sessionMutex.withLock {
        val status = _state.value.connectionStatus
        if (status == ConnectionStatus.Disconnected) {
            cachedSettings = freshSettings
            return@withLock
        }
        logger.d("🔄 Language pair changed → ${freshSettings.sourceLanguageCode}↔${freshSettings.targetLanguageCode}, HARD reset")

        reconnectJob?.cancelAndJoin(); reconnectJob = null
        reconnectAttempt.set(0L)
        stuckTurnWatchdogJob?.cancelAndJoin(); stuckTurnWatchdogJob = null
        micJob?.cancelAndJoin(); micJob = null

        runCatching { audioEngine.stopCapture() }
        runCatching { audioEngine.flushPlayback() }
        runCatching { audioEngine.onTurnComplete() }

        runCatching { liveClient.disconnect() }
        runCatching { liveClient.resetSession() }

        hasModelOutputThisTurn.set(false)
        lastAiAudioChunkAtMs.set(0L)
        lastSeenTurnId.set(Long.MIN_VALUE)
        currentOpenPairId = null
        nextPairId = 1L

        _state.update {
            it.copy(
                pairs = emptyList(),
                isMicActive = false,
                isAiSpeaking = false,
                connectionStatus = ConnectionStatus.Connecting,
                error = null
            )
        }

        // Пауза, чтобы старый WebSocket гарантированно закрылся.
        delay(150)

        cachedSettings = freshSettings
        connectInternal(freshSession = true)
    }

    // ════════════════════════════════════════════════════════════════════
    //  SESSION
    // ════════════════════════════════════════════════════════════════════

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun toggleMic() { if (_state.value.isMicActive) stopMic() else startMic() }

    fun startSession() {
        if (_state.value.connectionStatus != ConnectionStatus.Disconnected) {
            logger.d("startSession ignored — already ${_state.value.connectionStatus}")
            return
        }
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            cachedSettings = settings
            if (settings.apiKey.isEmpty()) {
                _state.update { it.copy(error = "API ключ не задан") }
                return@launch
            }
            activeApiKey = settings.apiKey
            _state.update {
                it.copy(connectionStatus = ConnectionStatus.Connecting, error = null, pairs = emptyList())
            }
            currentOpenPairId = null
            reconnectAttempt.set(0L)
            runCatching { liveClient.resetSession() }

            audioEngine.updateJitterConfig(
                preBufferChunks = settings.jitterPreBufferChunks,
                timeoutMs = settings.jitterTimeoutMs,
                queueCapacity = settings.playbackQueueCapacity
            )
            audioEngine.setPlaybackVolume(settings.playbackVolume / 100f)
            audioEngine.setMicGain(settings.micGain / 100f)
            audioEngine.setSpeakerRouting(settings.forceSpeakerOutput)
            audioEngine.setPlaybackBoost(settings.playbackBoost)
            audioEngine.setUseAec(settings.useAec)
            // Полудуплекс: пока перевод звучит — микрофонные чанки не уходят.
            // Это полностью исключает обрыв перевода эхом собственного динамика.
            audioEngine.setMicGateDuringPlayback(true)

            startForegroundServiceSafe(settings.forceSpeakerOutput)
            connectInternal(freshSession = true)
        }
    }

    private fun startForegroundServiceSafe(forceSpeaker: Boolean) {
        if (!hasMicPermission()) {
            logger.w("FGS not started — RECORD_AUDIO not granted")
            return
        }
        runCatching {
            val intent = GeminiLiveForegroundService.startIntent(appContext, forceSpeaker)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            fgsStarted = true
        }.onFailure { fgsStarted = false; logger.e("FGS start failed: ${it.message}") }
    }

    private fun stopForegroundServiceSafe() {
        if (!fgsStarted) return
        runCatching {
            appContext.startService(GeminiLiveForegroundService.stopIntent(appContext))
        }
        fgsStarted = false
    }

    /**
     * @param freshSession если true — игнорируем sessionHandle и стартуем
     *                     с чистого листа (нужно при смене языковой пары).
     */
    private suspend fun connectInternal(freshSession: Boolean = false) {
        val baseConfig = TranslatorSession.buildConfig(cachedSettings)
        val handle = if (freshSession) null else liveClient.sessionHandle
        val config = baseConfig.copy(sessionHandle = handle)

        if (freshSession) logger.d("🆕 connectInternal: FRESH session (no handle)")
        else logger.d("connectInternal: resumable=${handle != null}")

        runCatching {
            liveClient.connect(activeApiKey, config, logRaw = cachedSettings.logRawWebSocketFrames)
        }.onFailure { e ->
            logger.e("connect failed: ${e.message}", e)
            _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, error = e.message) }
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            reconnectJob?.cancelAndJoin(); reconnectJob = null
            reconnectAttempt.set(0L)
            stuckTurnWatchdogJob?.cancelAndJoin(); stuckTurnWatchdogJob = null
            micJob?.cancelAndJoin(); micJob = null

            runCatching { audioEngine.stopCapture() }
            runCatching { audioEngine.flushPlayback() }
            runCatching { liveClient.disconnect() }
            runCatching { liveClient.resetSession() }
            stopForegroundServiceSafe()

            hasModelOutputThisTurn.set(false)
            lastAiAudioChunkAtMs.set(0L)
            lastSeenTurnId.set(Long.MIN_VALUE)
            currentOpenPairId = null

            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive = false,
                    isAiSpeaking = false
                )
            }
        }
    }

    fun onMicPermissionGranted() {
        if (!fgsStarted) startForegroundServiceSafe(cachedSettings.forceSpeakerOutput)
        if (!_state.value.isMicActive &&
            (_state.value.connectionStatus == ConnectionStatus.Ready ||
             _state.value.connectionStatus == ConnectionStatus.Recording)
        ) startMic()
    }

    private fun startMic() {
        if (!hasMicPermission()) { logger.w("startMic — no permission"); return }
        if (_state.value.isMicActive) return
        if (!fgsStarted) startForegroundServiceSafe(cachedSettings.forceSpeakerOutput)

        _state.update { it.copy(isMicActive = true, connectionStatus = ConnectionStatus.Recording) }
        micJob = viewModelScope.launch {
            audioEngine.startCapture()
            // sendAudio сам освобождает chunk в finally → здесь release не нужен.
            audioEngine.micOutput.collect { chunk -> liveClient.sendAudio(chunk) }
        }
    }

    private fun stopMic() {
        micJob?.cancel(); micJob = null
        viewModelScope.launch {
            audioEngine.stopCapture()
            if (cachedSettings.sendAudioStreamEnd) liveClient.sendAudioStreamEnd()
            _state.update { it.copy(isMicActive = false, connectionStatus = ConnectionStatus.Ready) }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  EVENTS  (коллектор на главном потоке; обработчики «лёгкие»)
    // ════════════════════════════════════════════════════════════════════

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.SetupComplete -> {
                        reconnectAttempt.set(0L)
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Ready) }
                        viewModelScope.launch {
                            delay(150)
                            if (hasMicPermission() &&
                                !_state.value.isMicActive &&
                                _state.value.connectionStatus == ConnectionStatus.Ready
                            ) startMic()
                        }
                    }

                    is GeminiEvent.AudioChunk -> {
                        // Отсекаем stale-чанки старых ходов (turnId монотонный).
                        if (event.turnId < lastSeenTurnId.get()) return@collect
                        if (event.turnId > lastSeenTurnId.get()) lastSeenTurnId.set(event.turnId)

                        lastAiAudioChunkAtMs.set(System.currentTimeMillis())
                        // ЛЁГКО: только передаём PCM движку (trySend, не блокирует).
                        // Полудуплексный гейт микрофона движок включает САМ —
                        // никаких stopCapture/startCapture и связанных дедлоков.
                        audioEngine.enqueuePlayback(event.pcmData)

                        // Состояние и вотчдог — ОДИН раз на ход, не на каждый чанк.
                        if (hasModelOutputThisTurn.compareAndSet(false, true)) startStuckTurnWatchdog()
                        if (!_state.value.isAiSpeaking) _state.update { it.copy(isAiSpeaking = true) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        logger.i("📝 IN : ${event.text}")
                        val entry = TranscriptEntry(direction = TranscriptDirection.INPUT, text = event.text)
                        val pairId = currentOpenPairId ?: openNewPair()
                        updatePair(pairId) {
                            val nt = it.originalText + event.text
                            it.copy(
                                originalText = nt,
                                originalLang = pickLangLabel(nt, _state.value.sourceLanguage, _state.value.targetLanguage)
                            )
                        }
                        _state.update {
                            it.copy(
                                lastInputTranscript = event.text,
                                transcriptLog = (it.transcriptLog + entry).takeLast(200)
                            )
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        logger.i("📝 OUT: ${event.text}")
                        val entry = TranscriptEntry(direction = TranscriptDirection.OUTPUT, text = event.text)
                        val pairId = currentOpenPairId ?: openNewPair()
                        updatePair(pairId) {
                            val nt = it.translationText + event.text
                            it.copy(
                                translationText = nt,
                                translationLang = pickLangLabel(nt, _state.value.sourceLanguage, _state.value.targetLanguage)
                            )
                        }
                        _state.update {
                            it.copy(
                                lastOutputTranscript = event.text,
                                transcriptLog = (it.transcriptLog + entry).takeLast(200)
                            )
                        }
                        if (hasModelOutputThisTurn.compareAndSet(false, true)) startStuckTurnWatchdog()
                    }

                    is GeminiEvent.Interrupted -> {
                        // С NO_INTERRUPTION + гейтом микрофона ложных Interrupted
                        // от эха не бывает. Если событие пришло — это реальная
                        // граница хода: честно завершаем его.
                        logger.w("⏹ Interrupted — finalizing turn")
                        runCatching { audioEngine.flushPlayback() }
                        stuckTurnWatchdogJob?.cancel()
                        finalizeOpenPair()
                        _state.update { it.copy(isAiSpeaking = false) }
                        hasModelOutputThisTurn.set(false)
                        lastAiAudioChunkAtMs.set(0L)
                        // lastSeenTurnId НЕ трогаем: turnId монотонный, новый ход
                        // всегда проходит фильтр, а stale-чанки старого — нет.
                    }

                    is GeminiEvent.TurnComplete -> {
                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }
                        hasModelOutputThisTurn.set(false)
                        lastAiAudioChunkAtMs.set(0L)
                        stuckTurnWatchdogJob?.cancel()
                        finalizeOpenPair()
                        // Микрофон вернётся сам: гейт в движке открывается, как
                        // только дозвучит аппаратный буфер (+эхо-хвост).
                    }

                    is GeminiEvent.GenerationComplete -> {
                        // Генерация завершена, но звук ещё дозвучивает из буфера —
                        // isAiSpeaking гасим по TurnComplete, не здесь.
                        currentOpenPairId?.let { id -> updatePair(id) { it.copy(translationIsFinal = true) } }
                    }

                    is GeminiEvent.UsageMetadata -> {
                        if (cachedSettings.showUsageMetadata) {
                            _state.update {
                                it.copy(
                                    promptTokens = event.promptTokens,
                                    responseTokens = event.responseTokens,
                                    totalTokens = event.totalTokens
                                )
                            }
                        }
                    }

                    is GeminiEvent.SessionHandleUpdate -> { /* handle хранится в клиенте */ }

                    is GeminiEvent.GoAway -> {
                        logger.w("GoAway received: ${event.timeLeft}. Scheduling reconnect.")
                        scheduleReconnect()
                    }

                    is GeminiEvent.Disconnected -> {
                        micJob?.cancel(); micJob = null
                        audioEngine.stopCapture()

                        val isPermanent = event.code in setOf(1008, 4001, 4003)
                        val isGraceful = event.code == 1000 || event.code == 1001

                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false,
                                isAiSpeaking = false,
                                error = if (isPermanent) "Модель/ключ невалидны (${event.code}): ${event.reason}" else it.error
                            )
                        }

                        if (isPermanent) {
                            logger.e("⛔ Permanent error ${event.code} — reconnect aborted")
                            reconnectAttempt.set(cachedSettings.maxReconnectAttempts.toLong())
                            return@collect
                        }

                        val msg = event.reason
                        val isRate = msg.contains("429") || msg.contains("rate", ignoreCase = true)
                        if (isRate && cachedSettings.autoRotateKeys && cachedSettings.apiKeyBackup.isNotEmpty()) {
                            activeApiKey = if (activeApiKey == cachedSettings.apiKey)
                                cachedSettings.apiKeyBackup else cachedSettings.apiKey
                            logger.d("Rotated to backup key")
                        }

                        if (!isGraceful) scheduleReconnect()
                    }

                    is GeminiEvent.ConnectionError -> {
                        _state.update { it.copy(error = event.message) }
                    }

                    is GeminiEvent.Connected -> { /* статус Ready ставим по SetupComplete */ }

                    else -> {}
                }
            }
        }
    }

    private fun observeNetwork() {
        networkJob?.cancel()
        networkJob = viewModelScope.launch {
            networkMonitor.isConnected.collect { connected ->
                if (!connected) {
                    networkLost.set(true)
                    logger.w("Network lost")
                    return@collect
                }
                if (networkLost.compareAndSet(true, false)) {
                    logger.d("Network restored — fast reconnect")
                    if (_state.value.connectionStatus == ConnectionStatus.Disconnected &&
                        activeApiKey.isNotEmpty()
                    ) {
                        reconnectJob?.cancel()
                        reconnectAttempt.set(0L)
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Reconnecting) }
                        reconnectJob = launch { connectInternal(freshSession = false) }
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  PAIRS / TRANSCRIPT HELPERS
    // ════════════════════════════════════════════════════════════════════

    private fun finalizeOpenPair() {
        val openId = currentOpenPairId ?: return
        _state.update { state ->
            state.copy(pairs = state.pairs.map {
                if (it.id == openId) it.copy(
                    originalIsFinal = true, translationIsFinal = true,
                    originalIsRefined = true, translationIsRefined = true
                ) else it
            })
        }
        currentOpenPairId = null
    }

    private fun scheduleReconnect() {
        val attempts = reconnectAttempt.get()
        val maxAttempts = cachedSettings.maxReconnectAttempts
        if (attempts >= maxAttempts) {
            logger.e("Max reconnect attempts ($maxAttempts) reached")
            reconnectAttempt.set(0L)
            return
        }
        val baseDelay = cachedSettings.reconnectBaseDelayMs
        val maxDelay = cachedSettings.reconnectMaxDelayMs
        val delayMs = (baseDelay * (1L shl minOf(attempts.toInt(), 30))).coerceAtMost(maxDelay)
        reconnectAttempt.incrementAndGet()

        _state.update { it.copy(connectionStatus = ConnectionStatus.Reconnecting) }
        logger.d("Reconnect #${attempts + 1} in ${delayMs}ms")

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if (networkLost.get()) {
                logger.d("Reconnect deferred — no network yet")
                return@launch
            }
            connectInternal(freshSession = false)
        }
    }

    private fun openNewPair(): Long {
        val id = nextPairId++
        currentOpenPairId = id
        _state.update { it.copy(pairs = it.pairs + TranslationPair(id = id)) }
        return id
    }

    private fun updatePair(id: Long, transform: (TranslationPair) -> TranslationPair) {
        _state.update { state ->
            state.copy(pairs = state.pairs.map { if (it.id == id) transform(it) else it })
        }
    }

    fun exportTranscriptLog(): String {
        val entries = _state.value.transcriptLog
        if (entries.isEmpty()) return "Лог пуст"
        return buildString {
            appendLine("═══ TRANSCRIPT LOG (${entries.size} entries) ═══")
            appendLine("Экспорт: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}")
            appendLine()
            entries.forEach { appendLine(it.formatted()) }
        }
    }

    /** К какому из двух выбранных языков ближе фрагмент текста. */
    private fun pickLangLabel(text: String, src: Language, tgt: Language): String {
        if (text.isBlank()) return ""
        val script = dominantScript(text)
        val srcScript = scriptForCode(src.code)
        val tgtScript = scriptForCode(tgt.code)
        val matched = when {
            script == null -> src.code
            srcScript == tgtScript -> src.code   // оба в одном письме — не различаем по символам
            script == srcScript -> src.code
            script == tgtScript -> tgt.code
            else -> src.code
        }
        return matched.uppercase()
    }

    private fun dominantScript(text: String): String? {
        var cyr = 0; var lat = 0; var arab = 0; var han = 0
        var deva = 0; var thai = 0; var hira = 0; var hang = 0
        for (ch in text) {
            when {
                ch in '\u0400'..'\u04FF' -> cyr++
                ch in 'a'..'z' || ch in 'A'..'Z' -> lat++
                ch in '\u0600'..'\u06FF' -> arab++
                ch in '\u4E00'..'\u9FFF' -> han++
                ch in '\u0900'..'\u097F' -> deva++
                ch in '\u0E00'..'\u0E7F' -> thai++
                ch in '\u3040'..'\u30FF' -> hira++
                ch in '\uAC00'..'\uD7AF' -> hang++
            }
        }
        val counts = listOf(
            "cyr" to cyr, "lat" to lat, "arab" to arab, "han" to han,
            "deva" to deva, "thai" to thai, "hira" to hira, "hang" to hang
        )
        val (winner, count) = counts.maxBy { it.second }
        return if (count > 0) winner else null
    }

    private fun scriptForCode(code: String): String = when (code) {
        "ru", "uk", "mn", "kk", "bg", "sr", "be" -> "cyr"
        "ar", "fa", "ur", "ps", "prs", "azb", "ku", "kmr", "skr",
        "arz", "apc", "apd", "arq", "ars", "aec", "acm" -> "arab"
        "zh", "yue", "wuu", "hak", "cjy", "gan", "nan" -> "han"
        "hi", "mr", "bho", "mai", "ne", "sa" -> "deva"
        "th", "tts" -> "thai"
        "ja" -> "hira"
        "ko" -> "hang"
        "bn", "as" -> "beng"
        "ta", "taml" -> "taml"
        "te" -> "telu"
        "kn" -> "knda"
        "ml" -> "mlym"
        "gu" -> "gujr"
        "or" -> "orya"
        "pa" -> "guru"
        "si" -> "sinh"
        "my" -> "mymr"
        "km" -> "khmr"
        "am" -> "ethi"
        "he" -> "hebr"
        "el" -> "grek"
        else -> "lat"
    }

    /**
     * Вотчдог «застрявшего хода»: если модель начала отвечать, но ни аудио,
     * ни turnComplete не приходят > 2 c после последнего чанка — принудительно
     * финализируем ход. Гейт микрофона в движке откроется сам по факту
     * окончания звука, поэтому этот путь НИКОГДА не оставляет приложение глухим.
     */
    private fun startStuckTurnWatchdog() {
        stuckTurnWatchdogJob?.cancel()
        stuckTurnWatchdogJob = viewModelScope.launch {
            delay(5000)
            val now = System.currentTimeMillis()
            val lastT = lastAiAudioChunkAtMs.get()
            val sinceLast = now - lastT
            // Аудио ещё течёт — продлеваем наблюдение.
            if (lastT > 0L && sinceLast < 2_000L) { startStuckTurnWatchdog(); return@launch }
            if (hasModelOutputThisTurn.get()) {
                logger.w("⚠ STUCK_TURN — force finalize")
                runCatching { audioEngine.flushPlayback() }
                runCatching { audioEngine.onTurnComplete() }
                hasModelOutputThisTurn.set(false)
                lastAiAudioChunkAtMs.set(0L)
                _state.update { it.copy(isAiSpeaking = false) }
                finalizeOpenPair()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            runCatching {
                withTimeoutOrNull(1000L) {
                    audioEngine.stopCapture()
                    liveClient.disconnect()
                }
                runCatching { audioEngine.releaseAll() }
            }
            stopForegroundServiceSafe()
        }
    }
}
