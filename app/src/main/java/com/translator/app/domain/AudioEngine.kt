package com.translator.app.domain

import kotlinx.coroutines.flow.Flow
import com.translator.app.domain.model.AudioChunk

/**
 * Абстракция аудио-подсистемы.
 *
 * Два канала:
 *  CAPTURE  — 16kHz, mono, 16-bit LE (AudioRecord + AEC + AGC)
 *  PLAYBACK — 24kHz, mono, 16-bit LE (AudioTrack + jitter buffer + soft-boost)
 */
interface AudioEngine {

    val micOutput: Flow<AudioChunk>
    val isCapturing: Boolean
    val isPlaying: Boolean

    suspend fun startCapture()
    suspend fun stopCapture()
    suspend fun enqueuePlayback(pcmData: ByteArray)
    suspend fun flushPlayback()
    suspend fun onTurnComplete()
    suspend fun initPlayback()
    suspend fun releaseAll()

    /** Обновить параметры jitter buffer (без перезапуска). */
    fun updateJitterConfig(preBufferChunks: Int, timeoutMs: Long, queueCapacity: Int)

    /** Поток для синхронизации визуализации с реальным воспроизведением. */
    val playbackSync: Flow<ByteArray>

    /** AudioTrack-громкость (0.0 .. 1.0). */
    fun setPlaybackVolume(gain: Float)

    /** Усиление микрофона (0.5 .. 2.0). */
    fun setMicGain(gain: Float)

    /** true — громкий динамик (SPEAKER), false — разрешить earpiece/BT. */
    fun setSpeakerRouting(forceSpeaker: Boolean)

    /**
     * Программное усиление воспроизведения (1.0 .. 2.0) с soft-clip.
     * Применяется к PCM до отправки в AudioTrack.
     * 1.6 (по умолчанию) — заметный buster без искажения речи.
     */
    fun setPlaybackBoost(boost: Float)

    /** Включить / выключить AEC (применяется при следующем startCapture). */
    fun setUseAec(enabled: Boolean)
}
