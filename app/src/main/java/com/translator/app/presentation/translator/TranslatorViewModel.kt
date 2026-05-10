package com.translator.app.presentation.translator

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translator.app.data.settings.AppSettings
import com.translator.app.domain.AudioEngine
import com.translator.app.domain.LiveClient
import com.translator.app.domain.model.GeminiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

enum class ConnectionStatus { Disconnected, Connecting, Ready, Recording }

data class TranslatorState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val pairs: List<TranslationPair> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class TranslatorViewModel @Inject constructor(
    private val liveClient: LiveClient,
    private val audioEngine: AudioEngine,
    private val settingsStore: DataStore<AppSettings>
) : ViewModel() {

    private val _state = MutableStateFlow(TranslatorState())
    val state = _state.asStateFlow()
    val audioPlaybackFlow = audioEngine.playbackSync

    private var micJob: Job? = null
    private var stuckTurnWatchdogJob: Job? = null

    @Volatile private var nextPairId: Long = 1L
    @Volatile private var currentOpenPairId: Long? = null
    @Volatile private var lastAiAudioChunkAtMs: Long = 0L
    @Volatile private var hasModelOutputThisTurn: Boolean = false

    private val pairMutex = Mutex()

    init {
        viewModelScope.launch { audioEngine.initPlayback() }
        observeGeminiEvents()
    }

    fun toggleMic() {
        if (_state.value.isMicActive) stopMic() else startMic()
    }

    fun startSession() {
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            if (settings.apiKey.isEmpty()) {
                _state.update { it.copy(error = "API ключ не задан") }
                return@launch
            }

            _state.update { it.copy(connectionStatus = ConnectionStatus.Connecting, pairs = emptyList(), error = null) }
            currentOpenPairId = null

            audioEngine.setPlaybackVolume(settings.playbackVolume / 100f)
            audioEngine.setMicGain(settings.micGain / 100f)
            audioEngine.setSpeakerRouting(settings.forceSpeakerOutput)

            val config = TranslatorSession.buildConfig(settings)

            runCatching {
                liveClient.connect(settings.apiKey, config)
            }.onFailure { e ->
                _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, error = e.message) }
            }
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            micJob?.cancel()
            audioEngine.stopCapture()
            liveClient.disconnect()
            _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, isMicActive = false, isAiSpeaking = false) }
        }
    }

    private fun startMic() {
        _state.update { it.copy(isMicActive = true, connectionStatus = ConnectionStatus.Recording) }
        micJob = viewModelScope.launch {
            launch { audioEngine.micOutput.collect { chunk -> liveClient.sendAudio(chunk) } }
            audioEngine.startCapture()
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            liveClient.sendAudioStreamEnd()
            _state.update { it.copy(isMicActive = false, connectionStatus = ConnectionStatus.Ready) }
        }
    }

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.SetupComplete -> {
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Ready) }
                    }
                    is GeminiEvent.AudioChunk -> {
                        lastAiAudioChunkAtMs = System.currentTimeMillis()
                        hasModelOutputThisTurn = true
                        _state.update { it.copy(isAiSpeaking = true) }
                        audioEngine.enqueuePlayback(event.pcmData)
                        startStuckTurnWatchdog()
                    }
                    is GeminiEvent.InputTranscript -> {
                        // Точная логика из LearnCoreViewModel: incremental deltas
                        val pairId = currentOpenPairId ?: openNewPair()
                        updatePair(pairId) { 
                            val newText = it.originalText + event.text
                            it.copy(originalText = newText, originalLang = detectLang(newText)) 
                        }
                    }
                    is GeminiEvent.OutputTranscript -> {
                        // Точная логика из LearnCoreViewModel: incremental deltas
                        val pairId = currentOpenPairId ?: openNewPair()
                        updatePair(pairId) { 
                            val newText = it.translationText + event.text
                            it.copy(translationText = newText, translationLang = detectLang(newText)) 
                        }
                        hasModelOutputThisTurn = true
                        startStuckTurnWatchdog()
                    }
                    is GeminiEvent.TurnComplete, is GeminiEvent.Interrupted -> {
                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }
                        hasModelOutputThisTurn = false
                        stuckTurnWatchdogJob?.cancel()
                        
                        currentOpenPairId?.let { pairId ->
                            updatePair(pairId) { it.copy(originalIsFinal = true, translationIsFinal = true, originalIsRefined = true, translationIsRefined = true) }
                        }
                        currentOpenPairId = null
                    }
                    is GeminiEvent.GenerationComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                        stuckTurnWatchdogJob?.cancel()
                        currentOpenPairId?.let { pairId ->
                            updatePair(pairId) { it.copy(translationIsFinal = true) }
                        }
                    }
                    is GeminiEvent.Disconnected, is GeminiEvent.ConnectionError -> {
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, isMicActive = false, isAiSpeaking = false) }
                        audioEngine.stopCapture()
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun openNewPair(): Long = pairMutex.withLock {
        val id = nextPairId++
        currentOpenPairId = id
        _state.update { it.copy(pairs = it.pairs + TranslationPair(id = id)) }
        id
    }

    private suspend fun updatePair(id: Long, transform: (TranslationPair) -> TranslationPair) = pairMutex.withLock {
        _state.update { s ->
            val idx = s.pairs.indexOfFirst { it.id == id }
            if (idx < 0) return@update s
            val newList = s.pairs.toMutableList().apply { set(idx, transform(s.pairs[idx])) }
            s.copy(pairs = newList)
        }
    }

    private fun detectLang(text: String): String {
        if (text.isBlank()) return ""
        val hasCyrillic = text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        return if (hasCyrillic) "RU" else "DE"
    }

    private fun startStuckTurnWatchdog() {
        stuckTurnWatchdogJob?.cancel()
        stuckTurnWatchdogJob = viewModelScope.launch {
            delay(5000)
            if (hasModelOutputThisTurn && (System.currentTimeMillis() - lastAiAudioChunkAtMs) > 2000) {
                _state.update { it.copy(isAiSpeaking = false) }
                hasModelOutputThisTurn = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}