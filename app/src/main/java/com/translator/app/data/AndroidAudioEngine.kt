// ═══════════════════════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/translator/app/data/AndroidAudioEngine.kt
//
// ПОЛНАЯ ЗАМЕНА (v5.0 — полудуплексный гейт микрофона на уровне движка)
//
// ЧТО НОВОГО vs v4.0 (фикс «сказало 2 слова и зависло»):
//
//   1) ПОЛУДУПЛЕКСНЫЙ ГЕЙТ МИКРОФОНА (главный фикс).
//      Пока перевод реально ЗВУЧИТ из динамика, микрофонные чанки не уходят
//      в micOutput. Голос Gemini из динамика больше не влетает в её же
//      микрофонный вход → серверный VAD не видит «речь» → сервер НЕ обрывает
//      генерацию на 2-м слове, и не возникает петля «перевожу собственный
//      перевод».
//
//      Ключевые свойства реализации:
//        • AudioRecord НЕ останавливается — мы продолжаем читать и просто
//          не отправляем. AEC/NS/AGC не теряют сходимость, нет латентности
//          рестарта капчера, и НЕВОЗМОЖЕН дедлок «микрофон забыли включить»
//          (прежний баг longPhraseMode: stopCapture на чанке + startCapture
//          только по TurnComplete → при Interrupted/застрявшем ходе
//          приложение глохло навсегда).
//        • Гейт «слышимости» учитывает аппаратный буфер AudioTrack:
//          isPlaybackAudible = (очередь не пуста) ИЛИ (с последней записи в
//          AudioTrack прошло < PLAYBACK_BUFFER_MS + ECHO_TAIL_MS). То есть
//          гейт держится ровно пока звук физически выходит из динамика
//          плюс короткий хвост комнатного эха, и мгновенно отпускается
//          после flushPlayback().
//        • Гейт проверяется ДО DSP-обработки чанка — ноль лишнего CPU.
//
//   2) Телеметрия гейта: лог при каждом закрытии/открытии (не на чанк).
//
// Всё остальное из v4.0 сохранено: выделенные realtime-потоки capture и
// playback, джиттер-пребуфер, блокирующая запись как backpressure,
// AGC + soft-gate + tanh-клиппинг, пул буферов без аллокаций в hot-loop.
//
// Соответствие официальной документации Gemini Live API:
//   • Вход  — raw 16-bit PCM, 16 kHz, little-endian  (SessionConfig.INPUT_SAMPLE_RATE)
//   • Выход — raw 16-bit PCM, 24 kHz                  (SessionConfig.OUTPUT_SAMPLE_RATE)
// ═══════════════════════════════════════════════════════════════════════════
package com.translator.app.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import com.translator.app.domain.AudioEngine
import com.translator.app.domain.model.AudioBufferPool
import com.translator.app.domain.model.MicAudioChunk
import com.translator.app.domain.model.SessionConfig
import com.translator.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.tanh

class AndroidAudioEngine(
    private val logger: AppLogger
) : AudioEngine {

    companion object {
        // 40 ms аудио-чанк @ 16 kHz mono = 640 семплов.
        private const val CAPTURE_CHUNK_SAMPLES = 640

        // ─── AGC (программный, добивает поверх системного) ───
        private const val AGC_TARGET_PEAK = 18_000f
        private const val AGC_INITIAL_PEAK = 4_000f
        private const val AGC_ATTACK = 0.4f
        private const val AGC_RELEASE = 0.015f
        private const val AGC_MAX_BOOST = 2.0f
        private const val AGC_MIN_BOOST = 0.8f
        private const val AGC_NOISE_FLOOR = 800f

        // ─── Soft-gate (отсечь дыхание/фон) ───
        private const val GATE_LOW = 600f
        private const val GATE_HIGH = 1200f
        private const val GAIN_CEILING = 2.5f

        // ─── Целевой аппаратный буфер воспроизведения (мс) ───
        private const val PLAYBACK_BUFFER_MS = 200

        // ─── Полудуплексный гейт ───
        // Хвост после последней записи в AudioTrack: буфер ещё дозвучивает
        // (~PLAYBACK_BUFFER_MS) + комнатное эхо/реверберация (~300 мс).
        private const val ECHO_TAIL_MS = 300L
        private const val MIC_GATE_HANGOVER_MS = PLAYBACK_BUFFER_MS + ECHO_TAIL_MS

        // Логировать накопленные дропы/underrun каждые N событий.
        private const val TELEMETRY_EVERY = 50L
    }

    // ════════════════════════════════════════════════════════════════════
    //  CONFIG (volatile — читаются из аудио-потоков)
    // ════════════════════════════════════════════════════════════════════
    @Volatile private var playbackQueueCapacity = 128
    @Volatile private var jitterPreBufferChunks = 2
    @Volatile private var jitterTimeoutMs = 120L

    @Volatile private var playbackGain: Float = 1.0f
    @Volatile private var micGain: Float = 1.0f
    @Volatile private var forceSpeakerOutput: Boolean = true   // фактическая маршрутизация — в FGS
    @Volatile private var useAec: Boolean = true
    @Volatile private var playbackBoost: Float = 1.4f

    // ─── Полудуплекс ───
    @Volatile private var micGateDuringPlayback: Boolean = true
    /** Момент последней реальной записи PCM в AudioTrack (0 = тишина). */
    @Volatile private var lastTrackWriteAtMs: Long = 0L
    /** Только для телеметрии: текущее видимое состояние гейта. */
    @Volatile private var micGateClosedNow: Boolean = false

    // ════════════════════════════════════════════════════════════════════
    //  ВЫДЕЛЕННЫЕ АУДИО-ДИСПЕТЧЕРЫ
    // ════════════════════════════════════════════════════════════════════
    private val captureDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "gem-audio-capture") }
            .asCoroutineDispatcher()

    private val playbackDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "gem-audio-playback") }
            .asCoroutineDispatcher()

    // ════════════════════════════════════════════════════════════════════
    //  ВЫХОДНЫЕ ПОТОКИ
    // ════════════════════════════════════════════════════════════════════

    private val _micChannel = Channel<MicAudioChunk>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { it.release() }
    )
    override val micOutput: Flow<MicAudioChunk> = _micChannel.receiveAsFlow()

    private val _playbackSync = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val playbackSync: Flow<ByteArray> = _playbackSync.asSharedFlow()

    @Volatile override var isCapturing: Boolean = false; private set
    @Volatile override var isPlaying: Boolean = false; private set

    /**
     * Перевод физически звучит из динамика прямо сейчас (или дозвучивает
     * аппаратный буфер + эхо-хвост).
     */
    override val isPlaybackAudible: Boolean
        get() {
            if (!playbackChannel.isEmpty) return true
            val last = lastTrackWriteAtMs
            return last > 0L && System.currentTimeMillis() - last < MIC_GATE_HANGOVER_MS
        }

    // ════════════════════════════════════════════════════════════════════
    //  STATE
    // ════════════════════════════════════════════════════════════════════
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

    // Границы реплики для джиттер-буфера.
    @Volatile private var isFirstBatch = true
    @Volatile private var awaitingDrain = false

    @Volatile private var bufferPool: AudioBufferPool? = null

    // ─── Телеметрия ───
    private val droppedPlaybackChunks = AtomicLong(0L)
    @Volatile private var lastUnderrunCount = 0

    private fun newEngineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + playbackDispatcher)

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

    // micGain ограничен 0.5..1.5 — AGC доберёт остальное (совместно макс ×2.5).
    override fun setMicGain(gain: Float) { micGain = gain.coerceIn(0.5f, 1.5f) }

    override fun setSpeakerRouting(forceSpeaker: Boolean) { forceSpeakerOutput = forceSpeaker }

    override fun setPlaybackBoost(boost: Float) { playbackBoost = boost.coerceIn(1.0f, 1.8f) }

    override fun setUseAec(enabled: Boolean) { useAec = enabled }

    override fun setMicGateDuringPlayback(enabled: Boolean) {
        micGateDuringPlayback = enabled
        logger.d("Mic half-duplex gate: ${if (enabled) "ON" else "OFF"}")
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
        if (isCapturing) { logger.d("startCapture skipped — already capturing"); return }
        if (!engineScope.isActive) engineScope = newEngineScope()

        val sampleRate = SessionConfig.INPUT_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            logger.e("AudioRecord.getMinBufferSize failed: $minBuf"); return
        }

        val (recorder, usedSource) = tryCreateRecorder(sampleRate, minBuf)
        if (recorder == null) { logger.e("AudioRecord: ALL sources failed."); return }

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
        logger.d("Recording started rate=$sampleRate src=$usedSource pool=${poolBufSize}B×32 gate=$micGateDuringPlayback")

        captureJob = engineScope.launch(captureDispatcher) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val buffer = ShortArray(CAPTURE_CHUNK_SAMPLES)
            var rollingPeak = AGC_INITIAL_PEAK

            try {
                while (isActive && isCapturing) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            // ── ПОЛУДУПЛЕКСНЫЙ ГЕЙТ ─────────────────────────
                            // Пока перевод физически звучит из динамика
                            // (+эхо-хвост), микрофонный чанк не отправляем.
                            // Читать из AudioRecord продолжаем — буфер не
                            // переполняется, AEC остаётся сошедшимся, а после
                            // окончания звука первый же чанк уйдёт без
                            // задержки рестарта капчера.
                            if (micGateDuringPlayback && isPlaybackAudible) {
                                if (!micGateClosedNow) {
                                    micGateClosedNow = true
                                    logger.d("🔇 mic gate CLOSED (AI speaking)")
                                }
                                continue
                            }
                            if (micGateClosedNow) {
                                micGateClosedNow = false
                                logger.d("🎙 mic gate OPEN")
                            }

                            // Пик блока.
                            var lp = 0
                            for (i in 0 until read) {
                                val v = abs(buffer[i].toInt())
                                if (v > lp) lp = v
                            }
                            val localPeak = lp.toFloat()

                            rollingPeak = if (localPeak > rollingPeak)
                                rollingPeak + (localPeak - rollingPeak) * AGC_ATTACK
                            else
                                rollingPeak - (rollingPeak - localPeak) * AGC_RELEASE
                            if (rollingPeak < AGC_NOISE_FLOOR) rollingPeak = AGC_NOISE_FLOOR

                            val agcGain = (AGC_TARGET_PEAK / rollingPeak)
                                .coerceIn(AGC_MIN_BOOST, AGC_MAX_BOOST)
                            val finalGain = (agcGain * micGain).coerceAtMost(GAIN_CEILING)

                            val gateFactor = when {
                                localPeak <= GATE_LOW -> 0f
                                localPeak >= GATE_HIGH -> 1f
                                else -> (localPeak - GATE_LOW) / (GATE_HIGH - GATE_LOW)
                            }
                            val totalGain = finalGain * gateFactor

                            // Soft-tanh клиппинг — без хрипа на пиках.
                            for (i in 0 until read) {
                                val norm = (buffer[i].toInt() * totalGain) / 32768f
                                buffer[i] = (tanh(norm * 1.05f) * 32760f).toInt().toShort()
                            }

                            // PCM16 LE → байты из пула (без аллокаций в hot-loop).
                            val outBytes = pool.borrow()
                            var outPos = 0
                            for (i in 0 until read) {
                                val s = buffer[i].toInt()
                                outBytes[outPos] = (s and 0xFF).toByte()
                                outBytes[outPos + 1] = ((s ushr 8) and 0xFF).toByte()
                                outPos += 2
                            }
                            _micChannel.trySend(MicAudioChunk(outBytes, outPos, pool))
                        }
                        read == 0 -> yield()
                        else -> { logger.d("AudioRecord.read=$read — exiting"); break }
                    }
                }
            } catch (e: CancellationException) {
                throw e
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

        withContext(captureDispatcher) {
            runCatching { aec?.enabled = false; aec?.release() }; echoCanceler = null
            runCatching { ns?.enabled = false; ns?.release() }; noiseSuppressor = null
            runCatching { agc?.enabled = false; agc?.release() }; autoGainControl = null
            runCatching { rec?.release() }; audioRecord = null
        }
        micGateClosedNow = false
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

        val sampleRate = SessionConfig.OUTPUT_SAMPLE_RATE   // 24 kHz по докам Live API
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
            logger.e("Device does not support ${sampleRate}Hz!"); return@withLock
        }

        // Буфер на ~PLAYBACK_BUFFER_MS, но не меньше системного минимума.
        val targetBytes = sampleRate * 2 /*PCM16*/ * PLAYBACK_BUFFER_MS / 1000
        val trackBuffer = maxOf(minBuf * 2, targetBytes)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBuffer)
                .build()
        } catch (e: Exception) {
            logger.e("AudioTrack build failed: ${e.message}", e); return@withLock
        }

        audioTrack = track
        runCatching { track.setVolume(playbackGain) }
        isFirstBatch = true
        awaitingDrain = false
        lastTrackWriteAtMs = 0L
        droppedPlaybackChunks.set(0L)
        lastUnderrunCount = 0
        track.play()
        isPlaying = true
        logger.d("Speaker ready (rate=$sampleRate, buf=${trackBuffer}B, boost=${"%.2f".format(playbackBoost)}x)")

        playbackJob = engineScope.launch(playbackDispatcher) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                for (chunk in playbackChannel) {
                    if (!isActive) break

                    if (isFirstBatch) {
                        // Короткий pre-roll: добираем ещё (preBuffer-1) чанков с
                        // таймаутом, чтобы старт реплики был гладким (без подзёрна).
                        var pre = 1
                        if (!writeToTrack(track, chunk)) break
                        while (pre < jitterPreBufferChunks) {
                            val next = withTimeoutOrNull(jitterTimeoutMs) {
                                playbackChannel.receiveCatching().getOrNull()
                            } ?: break
                            if (!writeToTrack(track, next)) { pre = jitterPreBufferChunks; break }
                            pre++
                        }
                        isFirstBatch = false
                    } else {
                        if (!writeToTrack(track, chunk)) break
                    }

                    // Конец реплики (turnComplete пришёл и очередь опустела) →
                    // следующая реплика снова пройдёт через джиттер-пребуфер.
                    if (awaitingDrain && playbackChannel.isEmpty) {
                        awaitingDrain = false
                        isFirstBatch = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("PLAYBACK LOOP ERROR: ${e.message}", e)
            } finally {
                logger.d("Playback loop exited")
            }
        }
    }

    /**
     * Блокирующая запись полного чанка в AudioTrack (на выделенном потоке).
     * Блокировка здесь — это и есть естественный backpressure: когда
     * аппаратный буфер полон, поток ждёт, не теряя данные.
     * @return false при фатальной ошибке записи.
     */
    private fun writeToTrack(track: AudioTrack, pcm: ByteArray): Boolean {
        val boosted = applyPlaybackBoost(pcm)
        _playbackSync.tryEmit(boosted)   // сигнал для UI-визуализации, не блокирует

        var offset = 0
        while (offset < boosted.size) {
            val written = track.write(boosted, offset, boosted.size - offset)
            if (written < 0) { logger.e("AudioTrack.write error: $written"); return false }
            offset += written
        }
        // Метка для полудуплексного гейта: «звук сейчас выходит из динамика».
        lastTrackWriteAtMs = System.currentTimeMillis()
        maybeLogUnderruns(track)
        return true
    }

    private fun maybeLogUnderruns(track: AudioTrack) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val u = runCatching { track.underrunCount }.getOrDefault(lastUnderrunCount)
            if (u > lastUnderrunCount && (u - lastUnderrunCount) % 10 == 0) {
                logger.w("AudioTrack underruns=$u (буфер не успевает наполняться)")
            }
            lastUnderrunCount = u
        }
    }

    // Soft-clip boost (x/(1+|x|)) — поднимает громкость без хрипа на пиках.
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

            val x = (signed / 32768f) * boost
            val soft = x / (1f + abs(x))
            val clipped = (soft * 32760f).toInt()

            out[i] = (clipped and 0xFF).toByte()
            out[i + 1] = ((clipped shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    override suspend fun enqueuePlayback(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        // DROP_OLDEST: при перегрузе теряем САМЫЙ старый чанк (ограничиваем
        // задержку, не даём ей расти бесконечно). Считаем дропы для телеметрии.
        val result = playbackChannel.trySend(pcmData)
        if (!result.isSuccess) {
            val n = droppedPlaybackChunks.incrementAndGet()
            if (n % TELEMETRY_EVERY == 0L) logger.w("Playback queue dropped $n chunks (overrun)")
        }
    }

    override suspend fun flushPlayback() {
        // Barge-in: мгновенно очищаем и очередь, и аппаратный буфер AudioTrack.
        var drained = 0
        while (playbackChannel.tryReceive().isSuccess) drained++
        awaitingDrain = false
        isFirstBatch = true
        audioTrack?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.play() }
        }
        // Гейт микрофона отпускаем сразу — звук физически остановлен.
        lastTrackWriteAtMs = 0L
        if (drained > 0) logger.d("flushPlayback drained=$drained")
    }

    override suspend fun onTurnComplete() {
        // Сигнал «реплика модели закончилась»: как только очередь опустеет,
        // следующая реплика снова пройдёт через джиттер-пребуфер.
        awaitingDrain = true
    }

    override suspend fun releaseAll() = playbackMutex.withLock {
        stopCapture()
        isPlaying = false

        // Строгий порядок закрытия — предотвращает IllegalStateException.
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

        isFirstBatch = true
        awaitingDrain = false
        lastTrackWriteAtMs = 0L
        logger.d("Engine released")
    }
}
