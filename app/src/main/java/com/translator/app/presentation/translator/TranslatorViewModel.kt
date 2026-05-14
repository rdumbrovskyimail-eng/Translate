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
import com.translator.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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
    val pairs: androidx.compose.runtime.snapshots.SnapshotStateList<TranslationPair> = androidx.compose.runtime.mutableStateListOf(),
    val error: String? = null,
    val promptTokens: Int = 0,
    val responseTokens: Int = 0,
    val totalTokens: Int = 0
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

    private val pairMutex = Mutex()

    // Был ли потерян интернет — для немедленного реконнекта при восстановлении.
    private val networkLost = AtomicBoolean(false)

    init {
        viewModelScope.launch { audioEngine.initPlayback() }
        observeGeminiEvents()
        observeNetwork()
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
                it.copy(connectionStatus = ConnectionStatus.Connecting, pairs = emptyList(), error = null)
            }
            currentOpenPairId = null
            reconnectAttempt.set(0L)

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
            connectInternal()
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

    private suspend fun connectInternal() {
        val baseConfig = TranslatorSession.buildConfig(cachedSettings)
        val config = baseConfig.copy(sessionHandle = liveClient.sessionHandle)
        runCatching {
            liveClient.connect(activeApiKey, config, logRaw = cachedSettings.logRawWebSocketFrames)
        }.onFailure { e ->
            logger.e("connect failed: ${e.message}", e)
            _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, error = e.message) }
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            reconnectJob?.cancel(); reconnectJob = null
            reconnectAttempt.set(0L)

            micJob?.cancel(); micJob = null
            audioEngine.stopCapture()
            liveClient.disconnect()
            stopForegroundServiceSafe()

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
                        updatePair(pairId) {
                            val nt = it.originalText + event.text
                            it.copy(originalText = nt, originalLang = detectLang(nt))
                        }
                    }
                    is GeminiEvent.OutputTranscript -> {
                        val pairId = currentOpenPairId ?: openNewPair()
                        updatePair(pairId) {
                            val nt = it.translationText + event.text
                            it.copy(translationText = nt, translationLang = detectLang(nt))
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
                    is GeminiEvent.GoAway -> { logger.w("GoAway: ${event.timeLeft}") }
                    is GeminiEvent.Disconnected -> {
                        // Закрываем mic-петлю чтобы не было fantom-эмиссий.
                        micJob?.cancel(); micJob = null
                        audioEngine.stopCapture()

                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false,
                                isAiSpeaking = false
                            )
                        }
                        if (event.code != 1000 && event.code != 1001) scheduleReconnect()
                    }
                    is GeminiEvent.ConnectionError -> {
                        val msg = event.message
                        val isRate = msg.contains("429") || msg.contains("rate", ignoreCase = true)
                        if (isRate && cachedSettings.autoRotateKeys &&
                            cachedSettings.apiKeyBackup.isNotEmpty()
                        ) {
                            activeApiKey = if (activeApiKey == cachedSettings.apiKey)
                                cachedSettings.apiKeyBackup else cachedSettings.apiKey
                            logger.d("Rotated to backup key")
                        }
                        micJob?.cancel(); micJob = null
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false,
                                isAiSpeaking = false,
                                error = msg
                            )
                        }
                        audioEngine.stopCapture()
                        scheduleReconnect()
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
                        reconnectJob = launch { connectInternal() }
                    }
                }
            }
        }
    }

    private suspend fun finalizeOpenPair() {
        pairMutex.withLock {
            val openId = currentOpenPairId ?: return@withLock
            val idx = _state.value.pairs.indexOfFirst { it.id == openId }
            if (idx >= 0) {
                val p = _state.value.pairs[idx]
                _state.value.pairs[idx] = p.copy(
                    originalIsFinal = true, translationIsFinal = true,
                    originalIsRefined = true, translationIsRefined = true
                )
            }
            currentOpenPairId = null
        }
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
            connectInternal()
        }
    }

    private suspend fun openNewPair(): Long = pairMutex.withLock {
        val id = nextPairId++
        currentOpenPairId = id
        _state.value.pairs.add(TranslationPair(id = id))
        id
    }

    private suspend fun updatePair(id: Long, transform: (TranslationPair) -> TranslationPair) =
        pairMutex.withLock {
            val idx = _state.value.pairs.indexOfFirst { it.id == id }
            if (idx >= 0) {
                _state.value.pairs[idx] = transform(_state.value.pairs[idx])
            }
        }

    private fun detectLang(text: String): String {
        if (text.isBlank()) return ""
        val hasCyr = text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' || it in "іїєґўІЇЄҐЎ" }
        return if (hasCyr) "RU" else "DE"
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