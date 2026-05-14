package com.translator.app.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.translator.app.domain.AudioEngine
import com.translator.app.domain.model.AudioBufferPool
import com.translator.app.domain.model.MicAudioChunk
import com.translator.app.domain.model.SessionConfig
import com.translator.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.math.tanh

class AndroidAudioEngine(
    private val logger: AppLogger
) : AudioEngine {

    // ═══ CONFIG ═══
    @Volatile private var playbackQueueCapacity = 256
    @Volatile private var jitterPreBufferChunks = 3
    @Volatile private var jitterTimeoutMs = 150L

    @Volatile private var playbackGain: Float = 1.0f
    @Volatile private var micGain: Float = 1.0f
    @Volatile private var forceSpeakerOutput: Boolean = true
    @Volatile private var useAec: Boolean = true

    @Volatile private var playbackBoost: Float = 1.4f

    // ═══ FLOWS ═══
    // Принципиально: consumer обязан release. Если эмиссия не прошла — мы тут же release.
    // Но при BufferOverflow.DROP_OLDEST SharedFlow дропает старый элемент без release —
    // поэтому используем onEach + manual release в consumer'е (см. TranslatorViewModel).
    private val _micOutput = MutableSharedFlow<MicAudioChunk>(
        replay = 0, extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    override val micOutput: Flow<MicAudioChunk> = _micOutput.asSharedFlow()

    private val _playbackSync = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    override val playbackSync: Flow<ByteArray> = _playbackSync.asSharedFlow()

    @Volatile override var isCapturing: Boolean = false; private set
    @Volatile override var isPlaying: Boolean = false; private set

    // ═══ STATE ═══
    private var engineScope: CoroutineScope = newEngineScope()
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var autoGainControl: AutomaticGainControl? = null
    @Volatile private var audioTrack: AudioTrack? = null

    private val playbackMutex = Mutex()
    @Volatile private var playbackChannel: Channel<ByteArray> =
        Channel(playbackQueueCapacity, BufferOverflow.DROP_OLDEST)
    @Volatile private var isFirstBatch = true
    @Volatile private var awaitingDrain = false

    @Volatile private var bufferPool: AudioBufferPool? = null

    private fun newEngineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ════════════════════════════════════════════════════════════════════
    //  CONFIG SETTERS
    // ════════════════════════════════════════════════════════════════════

    override fun updateJitterConfig(preBufferChunks: Int, timeoutMs: Long, queueCapacity: Int) {
        jitterPreBufferChunks = preBufferChunks.coerceIn(1, 10)
        jitterTimeoutMs = timeoutMs.coerceIn(50L, 500L)
        playbackQueueCapacity = queueCapacity.coerceIn(64, 512)
        logger.d("Jitter: preBuf=$jitterPreBufferChunks, timeout=${jitterTimeoutMs}ms, q=$playbackQueueCapacity")
    }

    override fun setPlaybackVolume(gain: Float) {
        playbackGain = gain.coerceIn(0f, 1f)
        runCatching { audioTrack?.setVolume(playbackGain) }
    }

    // micGain ограничен 0.5..1.5 — AGC доберёт остальное (макс ×2.5 совместно).
    override fun setMicGain(gain: Float) { micGain = gain.coerceIn(0.5f, 1.5f) }

    override fun setSpeakerRouting(forceSpeaker: Boolean) { forceSpeakerOutput = forceSpeaker }

    override fun setPlaybackBoost(boost: Float) {
        playbackBoost = boost.coerceIn(1.0f, 1.8f)
    }

    override fun setUseAec(enabled: Boolean) { useAec = enabled }

    override fun setNoiseGateThreshold(threshold: Float) {
        // Заглушка, чтобы удовлетворить интерфейс. 
        // В будущем можно привязать к переменной gateLow.
    }

    // ════════════════════════════════════════════════════════════════════
    //  CAPTURE
    // ════════════════════════════════════════════════════════════════════

    @Suppress("MissingPermission")
    private fun tryCreateRecorder(sampleRate: Int, minBuf: Int): Pair<AudioRecord?, Int> {
        val sources = listOf(
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "DEFAULT" to MediaRecorder.AudioSource.DEFAULT
        )
        for ((label, source) in sources) {
            val rec = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()
            } catch (e: SecurityException) {
                logger.e("AudioRecord SECURITY ($label): ${e.message}")
                return null to 0
            } catch (e: Exception) {
                logger.w("AudioRecord ($label): ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                logger.d("AudioRecord OK with source=$label")
                return rec to source
            }
            runCatching { rec?.release() }
        }
        return null to 0
    }

    @Suppress("MissingPermission")
    override suspend fun startCapture() {
        if (isCapturing) {
            logger.d("startCapture skipped — already capturing"); return
        }
        if (!engineScope.isActive) engineScope = newEngineScope()

        val sampleRate = SessionConfig.INPUT_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            logger.e("AudioRecord.getMinBufferSize failed: $minBuf"); return
        }

        val (recorder, usedSource) = tryCreateRecorder(sampleRate, minBuf)
        if (recorder == null) {
            logger.e("AudioRecord: ALL sources failed.")
            return
        }

        if (useAec && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }.onFailure { logger.w("AEC init skipped: ${it.message}") }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }.onFailure { logger.w("NS init skipped: ${it.message}") }
        }
        // Системный AGC если доступен — отключает наш программный, мы и так считаем bonus.
        if (AutomaticGainControl.isAvailable()) {
            runCatching {
                autoGainControl = AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true }
            }.onFailure { logger.w("AGC HW init skipped: ${it.message}") }
        }

        try { recorder.startRecording() } catch (e: Exception) {
            logger.e("startRecording failed: ${e.message}", e)
            runCatching { recorder.release() }; return
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            logger.e("AudioRecord not in RECORDING state")
            runCatching { recorder.stop(); recorder.release() }; return
        }

        val poolBufSize = minBuf * 2
        val pool = bufferPool
            ?: AudioBufferPool(bufferSize = poolBufSize, poolCapacity = 32).also { bufferPool = it }

        audioRecord = recorder
        isCapturing = true
        logger.d("Recording started rate=$sampleRate src=$usedSource pool=${poolBufSize}B×32")

        captureJob = engineScope.launch {
            // Строго 40 мс аудио (16000 Hz * 0.04 sec = 640 сэмплов)
            val chunkSize = 640
            val buffer = ShortArray(chunkSize)

            // Программный мягкий AGC поверх системного.
            var rollingPeak = 4000f
            val targetPeak = 18_000f
            val agcAttack = 0.4f
            val agcRelease = 0.015f
            val agcMaxBoost = 2.0f      // снижено до ×2.0
            val agcMinBoost = 0.8f
            val noiseFloor = 800f

            // Мягкий "gate" через коэффициент: до 600 → 0, до 1200 → линейный fade до 1.
            // НИКАКОГО обнуления буфера — только плавное затухание амплитуды.
            val gateLow = 600f
            val gateHigh = 1200f

            try {
                while (isActive && isCapturing) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            // peak блока
                            var lp = 0
                            for (i in 0 until read) {
                                val v = kotlin.math.abs(buffer[i].toInt())
                                if (v > lp) lp = v
                            }
                            val localPeak = lp.toFloat()

                            rollingPeak = if (localPeak > rollingPeak)
                                rollingPeak + (localPeak - rollingPeak) * agcAttack
                            else
                                rollingPeak - (rollingPeak - localPeak) * agcRelease
                            if (rollingPeak < noiseFloor) rollingPeak = noiseFloor

                            val agcGain = (targetPeak / rollingPeak).coerceIn(agcMinBoost, agcMaxBoost)
                            // Совместный gain не более 2.5×.
                            val finalGain = (agcGain * micGain).coerceAtMost(2.5f)

                            // Плавный fade-out вместо noise gate: коэффициент 0..1 по интерполяции.
                            val gateFactor = when {
                                localPeak <= gateLow -> 0f
                                localPeak >= gateHigh -> 1f
                                else -> (localPeak - gateLow) / (gateHigh - gateLow)
                            }
                            val totalGain = finalGain * gateFactor

                            // Soft-tanh клиппинг — без хрипа на пиках.
                            for (i in 0 until read) {
                                val sample = buffer[i].toInt() * totalGain
                                val norm = sample / 32768f
                                val soft = tanh(norm * 1.05f) * 32760f
                                buffer[i] = soft.toInt().toShort()
                            }

                            val outBytes = pool.borrow()
                            var outPos = 0
                            for (i in 0 until read) {
                                val s = buffer[i].toInt()
                                outBytes[outPos] = (s and 0xFF).toByte()
                                outBytes[outPos + 1] = ((s ushr 8) and 0xFF).toByte()
                                outPos += 2
                            }

                            val chunk = MicAudioChunk(outBytes, outPos, pool)
                            if (!_micOutput.tryEmit(chunk)) {
                                chunk.release()
                            }
                        }
                        read == 0 -> yield()
                        else -> { logger.d("AudioRecord.read=$read — exiting"); break }
                    }
                }
            } catch (e: Exception) {
                logger.e("CAPTURE LOOP ERROR: ${e.message}", e)
            } finally {
                logger.d("Capture loop exited")
            }
        }
    }

    override suspend fun stopCapture() {
        if (!isCapturing && audioRecord == null) return
        isCapturing = false

        runCatching { withTimeoutOrNull(800L) { captureJob?.cancelAndJoin() } }
        captureJob = null

        val rec = audioRecord
        val aec = echoCanceler
        val ns = noiseSuppressor
        val agc = autoGainControl

        runCatching { rec?.stop() }

        withContext(Dispatchers.IO) {
            runCatching { aec?.enabled = false; aec?.release() }; echoCanceler = null
            runCatching { ns?.enabled = false; ns?.release() }; noiseSuppressor = null
            runCatching { agc?.enabled = false; agc?.release() }; autoGainControl = null
            runCatching { rec?.release() }; audioRecord = null
        }
        logger.d("Capture stopped")
    }

    // ════════════════════════════════════════════════════════════════════
    //  PLAYBACK
    // ════════════════════════════════════════════════════════════════════

    override suspend fun initPlayback() = playbackMutex.withLock {
        if (isPlaying) { logger.d("initPlayback skipped — already playing"); return@withLock }
        if (!engineScope.isActive) engineScope = newEngineScope()
        if (playbackChannel.isClosedForSend) {
            playbackChannel = Channel(playbackQueueCapacity, BufferOverflow.DROP_OLDEST)
        }

        val sampleRate = SessionConfig.OUTPUT_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
            logger.e("Device does not support ${sampleRate}Hz!"); return@withLock
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2).build()
        } catch (e: Exception) {
            logger.e("AudioTrack build failed: ${e.message}", e); return@withLock
        }

        audioTrack = track
        runCatching { track.setVolume(playbackGain) }
        track.play()
        isPlaying = true
        logger.d("Speaker ready (rate=$sampleRate, boost=${"%.2f".format(playbackBoost)}x)")

        playbackJob = engineScope.launch {
            try {
                for (chunk in playbackChannel) {
                    if (!isActive) break
                    if (isFirstBatch) {
                        val preBuffer = mutableListOf(chunk)
                        repeat(jitterPreBufferChunks - 1) {
                            try {
                                val next = withTimeoutOrNull(jitterTimeoutMs) {
                                    playbackChannel.receive()
                                }
                                if (next != null) preBuffer.add(next)
                            } catch (_: ClosedReceiveChannelException) { return@repeat }
                            catch (_: Exception) { return@repeat }
                        }
                        for (buf in preBuffer) {
                            val boosted = applyPlaybackBoost(buf)
                            _playbackSync.tryEmit(boosted)
                            val written = track.write(boosted, 0, boosted.size)
                            if (written < 0) {
                                logger.e("AudioTrack write error: $written")
                                break
                            }
                        }
                        isFirstBatch = false
                    } else {
                        val boosted = applyPlaybackBoost(chunk)
                        _playbackSync.tryEmit(boosted)
                        val written = track.write(boosted, 0, boosted.size)
                        if (written < 0) {
                            logger.e("AudioTrack write error: $written")
                            break
                        }
                    }

                    if (awaitingDrain && playbackChannel.isEmpty) {
                        awaitingDrain = false
                        isFirstBatch = true
                    }
                }
            } catch (e: Exception) {
                logger.e("PLAYBACK LOOP ERROR: ${e.message}", e)
            } finally {
                logger.d("Playback loop exited")
            }
        }
    }

    // Soft-tanh boost — без хрипа на пиках.
    private fun applyPlaybackBoost(pcm: ByteArray): ByteArray {
        val boost = playbackBoost
        if (boost <= 1.001f || pcm.size < 2) return pcm

        val out = pcm.copyOf()
        var i = 0
        val end = out.size - 1
        while (i < end) {
            val low = out[i].toInt() and 0xFF
            val high = out[i + 1].toInt()
            val sample = (high shl 8) or low
            val signed = if (sample and 0x8000 != 0) sample or 0xFFFF0000.toInt() else sample
            
            // Идеальный клиппер без искажений
            val x = (signed / 32768f) * boost
            val soft = x / (1f + kotlin.math.abs(x))
            
            val clipped = (soft * 32760f).toInt()
            out[i] = (clipped and 0xFF).toByte()
            out[i + 1] = ((clipped shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    override suspend fun enqueuePlayback(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        val result = playbackChannel.trySend(pcmData)
        if (result.isFailure) {
            playbackChannel.tryReceive()
            playbackChannel.trySend(pcmData)
        }
        awaitingDrain = false
    }

    override suspend fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) { /* drain */ }
        isFirstBatch = true
        awaitingDrain = false
        audioTrack?.apply { runCatching { pause(); flush(); play() } }
    }

    override suspend fun onTurnComplete() {
        awaitingDrain = true
    }

    override suspend fun releaseAll() = playbackMutex.withLock {
        stopCapture()
        isPlaying = false
        // Строгий порядок закрытия для предотвращения IllegalStateException
        runCatching { withTimeoutOrNull(800L) { playbackJob?.cancelAndJoin() } }
        playbackJob = null
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
        runCatching { playbackChannel.close() }
        runCatching { withTimeoutOrNull(800L) { engineScope.coroutineContext[Job]?.cancelAndJoin() } }
        logger.d("Engine released")
    }
}