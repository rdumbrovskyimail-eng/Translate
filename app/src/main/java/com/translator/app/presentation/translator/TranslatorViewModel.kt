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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    val targetLanguage: Language = Languages.DEFAULT_TARGET
)

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
    private val lastSeenTurnId = AtomicLong(-1L)

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    @Volatile private var activeApiKey: String = ""
    @Volatile private var fgsStarted: Boolean = false

    // Был ли потерян интернет — для немедленного реконнекта при восстановлении.
    private val networkLost = AtomicBoolean(false)

    // Mutex для сериализации операций смены сессии (язык/реконнект),
    // чтобы исключить гонки между переключением языков и автореконнектом.
    private val sessionMutex = Mutex()

    init {
        viewModelScope.launch { audioEngine.initPlayback() }
        observeGeminiEvents()
        observeNetwork()
        observeLanguageSettings()
    }

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

    /**
     * ЖЁСТКИЙ сброс при смене языковой пары.
     *
     * КРИТИЧНО: при смене языков мы НЕ должны переиспользовать sessionHandle,
     * иначе Gemini восстанавливает предыдущий контекст со старым system instruction
     * и историей — модель продолжает переводить на старый язык, "залипает" на
     * прошлой паре, иногда даже мешает оба языка. Поэтому здесь:
     *
     *   1) Отменяем ВСЕ фоновые корутины (mic, reconnect, watchdog, network).
     *   2) Полностью гасим аудио (capture + playback).
     *   3) Дисконнектим клиента.
     *   4) Сбрасываем sessionHandle на стороне клиента (resetSession()).
     *   5) Сбрасываем все per-turn флаги.
     *   6) Чистим UI-state.
     *   7) Подключаемся заново с freshSession = true.
     */
    private suspend fun hardResetForNewLanguagePair(freshSettings: AppSettings) = sessionMutex.withLock {
        val status = _state.value.connectionStatus
        if (status == ConnectionStatus.Disconnected) {
            // Не было активной сессии — просто кэшируем настройки, при следующем startSession()
            // промт уже построится из новых языков.
            cachedSettings = freshSettings
            return@withLock
        }
        logger.d("🔄 Language pair changed → ${freshSettings.sourceLanguageCode}↔${freshSettings.targetLanguageCode}, HARD reset")

        // 1) Гасим все фоновые задачи строго в правильном порядке.
        reconnectJob?.cancelAndJoin(); reconnectJob = null
        reconnectAttempt.set(0L)

        stuckTurnWatchdogJob?.cancelAndJoin(); stuckTurnWatchdogJob = null

        micJob?.cancelAndJoin(); micJob = null

        // 2) Аудио — полная остановка и сброс буферов.
        runCatching { audioEngine.stopCapture() }
        runCatching { audioEngine.flushPlayback() }
        runCatching { audioEngine.onTurnComplete() } // финализирует jitter-буфер

        // 3) Дисконнект клиента.
        runCatching { liveClient.disconnect() }

        // 4) Сбрасываем sessionHandle. Если в LiveClient нет такого метода —
        //    см. примечание ниже, нужно добавить.
        runCatching { liveClient.resetSession() }

        // 5) Сбрасываем per-turn флаги.
        hasModelOutputThisTurn.set(false)
        lastAiAudioChunkAtMs.set(0L)
        lastSeenTurnId.set(-1L)
        currentOpenPairId = null
        nextPairId = 1L

        // 6) UI: чистый старт.
        _state.update {
            it.copy(
                pairs = emptyList(),
                isMicActive = false,
                isAiSpeaking = false,
                connectionStatus = ConnectionStatus.Connecting,
                error = null
            )
        }

        // Маленькая пауза, чтобы старый WebSocket гарантированно закрылся и
        // сервер не путал старую и новую сессии.
        delay(150)

        // 7) Свежая сессия — БЕЗ sessionHandle.
        cachedSettings = freshSettings
        connectInternal(freshSession = true)
    }

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
            // Любой явный старт сессии — это свежий старт.
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
            lastSeenTurnId.set(-1L)
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
            audioEngine.micOutput.collect { chunk ->
                try { liveClient.sendAudio(chunk) }
                finally { chunk.release() }
            }
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
                        // Отсекаем stale-чанки старых ходов.
                        if (event.turnId < lastSeenTurnId.get()) return@collect
                        lastSeenTurnId.set(event.turnId)

                        lastAiAudioChunkAtMs.set(System.currentTimeMillis())
                        hasModelOutputThisTurn.set(true)
                        _state.update { it.copy(isAiSpeaking = true) }
                        audioEngine.enqueuePlayback(event.pcmData)
                        startStuckTurnWatchdog()
                    }
                    is GeminiEvent.InputTranscript -> {
                        val pairId = currentOpenPairId ?: openNewPair()
                        val src = _state.value.sourceLanguage
                        val tgt = _state.value.targetLanguage
                        updatePair(pairId) {
                            val nt = it.originalText + event.text
                            val lang = pickLangLabel(nt, src, tgt)
                            it.copy(originalText = nt, originalLang = lang)
                        }
                    }
                    is GeminiEvent.OutputTranscript -> {
                        val pairId = currentOpenPairId ?: openNewPair()
                        val src = _state.value.sourceLanguage
                        val tgt = _state.value.targetLanguage
                        updatePair(pairId) {
                            val nt = it.translationText + event.text
                            // Translation language = противоположный от оригинала.
                            val originalLangCode = it.originalLang.lowercase()
                            val translationLang = if (originalLangCode == src.code) tgt.code.uppercase()
                                                  else src.code.uppercase()
                            it.copy(translationText = nt, translationLang = translationLang)
                        }
                        hasModelOutputThisTurn.set(true)
                        startStuckTurnWatchdog()
                    }
                    is GeminiEvent.Interrupted -> {
                        runCatching { audioEngine.flushPlayback() }
                        _state.update { it.copy(isAiSpeaking = false) }
                        hasModelOutputThisTurn.set(false)
                        lastSeenTurnId.set(-1L)
                        stuckTurnWatchdogJob?.cancel()
                        finalizeOpenPair()
                    }
                    is GeminiEvent.TurnComplete -> {
                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }
                        hasModelOutputThisTurn.set(false)
                        lastSeenTurnId.set(-1L)
                        stuckTurnWatchdogJob?.cancel()
                        finalizeOpenPair()
                    }
                    is GeminiEvent.GenerationComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                        stuckTurnWatchdogJob?.cancel()
                        currentOpenPairId?.let { id ->
                            updatePair(id) { it.copy(translationIsFinal = true) }
                        }
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
                    is GeminiEvent.SessionHandleUpdate -> { /* handle stored inside client */ }
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
                // Восстановление сети — если мы были в Disconnected, мгновенный реконнект.
                if (networkLost.compareAndSet(true, false)) {
                    logger.d("Network restored — fast reconnect")
                    if (_state.value.connectionStatus == ConnectionStatus.Disconnected &&
                        activeApiKey.isNotEmpty()) {
                        reconnectJob?.cancel()
                        reconnectAttempt.set(0L)
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Reconnecting) }
                        // Реконнект после потери сети — можно с handle (это штатный resume).
                        reconnectJob = launch { connectInternal(freshSession = false) }
                    }
                }
            }
        }
    }

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
            // Не реконнектимся если сеть ещё лежит — networkMonitor сам триггернёт когда восстановится.
            if (networkLost.get()) {
                logger.d("Reconnect deferred — no network yet")
                return@launch
            }
            // Автореконнект — с handle (штатное session resumption).
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

    /**
     * Определяет, к какому из двух выбранных языков ближе фрагмент текста.
     */
    private fun pickLangLabel(text: String, src: Language, tgt: Language): String {
        if (text.isBlank()) return ""
        val script = dominantScript(text)
        val srcScript = scriptForCode(src.code)
        val tgtScript = scriptForCode(tgt.code)
        val matched = when {
            script != null && script == srcScript -> src.code
            script != null && script == tgtScript -> tgt.code
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
        val counts = listOf("cyr" to cyr, "lat" to lat, "arab" to arab, "han" to han,
            "deva" to deva, "thai" to thai, "hira" to hira, "hang" to hang)
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

    private fun startStuckTurnWatchdog() {
        stuckTurnWatchdogJob?.cancel()
        stuckTurnWatchdogJob = viewModelScope.launch {
            delay(5000)
            val now = System.currentTimeMillis()
            val lastT = lastAiAudioChunkAtMs.get()
            val sinceLast = now - lastT
            if (lastT > 0L && sinceLast < 2_000L) {
                startStuckTurnWatchdog(); return@launch
            }
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
            }
            stopForegroundServiceSafe()
        }
    }
}
