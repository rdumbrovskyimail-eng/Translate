package com.translator.app.domain

import kotlinx.coroutines.flow.Flow
import com.translator.app.domain.model.MicAudioChunk

interface AudioEngine {

    val micOutput: Flow<MicAudioChunk>
    val isCapturing: Boolean
    val isPlaying: Boolean

    /**
     * true, пока перевод реально звучит из динамика (очередь не пуста ИЛИ
     * аппаратный буфер AudioTrack ещё не дозвучал + эхо-хвост).
     * Используется полудуплексным гейтом микрофона.
     */
    val isPlaybackAudible: Boolean

    suspend fun startCapture()
    suspend fun stopCapture()
    suspend fun enqueuePlayback(pcmData: ByteArray)
    suspend fun flushPlayback()
    suspend fun onTurnComplete()
    suspend fun initPlayback()
    suspend fun releaseAll()

    fun updateJitterConfig(preBufferChunks: Int, timeoutMs: Long, queueCapacity: Int)
    val playbackSync: Flow<ByteArray>
    fun setPlaybackVolume(gain: Float)
    fun setMicGain(gain: Float)
    fun setSpeakerRouting(forceSpeaker: Boolean)
    fun setPlaybackBoost(boost: Float)
    fun setUseAec(enabled: Boolean)

    /**
     * Полудуплексный гейт: пока isPlaybackAudible == true, микрофонные чанки
     * НЕ отправляются в micOutput (AudioRecord продолжает читать — AEC не
     * теряет сходимость, рестарта капчера нет). Это полностью исключает
     * ложный barge-in от эха собственного динамика — главную причину
     * обрыва перевода на 2-м слове.
     */
    fun setMicGateDuringPlayback(enabled: Boolean)
}
