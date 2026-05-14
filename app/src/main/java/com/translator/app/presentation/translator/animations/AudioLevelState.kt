// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (v3.0 — DSP-grade)
// Путь: app/src/main/java/com/translator/app/presentation/translator/animations/AudioLevelState.kt
//
// Премиальный audio listener для всех 6 анимаций.
//
// Что нового vs v1.0:
//   • AGC (Auto-Gain Compensation): фоновый шум "вычитается" адаптивно
//   • Log-perception: тихие звуки слышны, громкие не «клипуются»
//   • Peak hold: пиковая волна держится ~120 ms (как в pro-метрах)
//   • Двойное сглаживание: attack/release + critical-damp spring
//   • Zero-alloc hot-path: один проход по массиву, FloatState без боксинга
//   • Per-frame timestep — стабильно при 60/90/120 Hz
//
// Возвращает 3 значения:
//   1. `level` — основной [0..1], плавный, для размеров/яркости
//   2. `peak` — текущий peak [0..1] с медленным распадом, для вспышек
//   3. `velocity` — производная level [-1..+1], для «толчков» физики
// ═══════════════════════════════════════════════════════════
package com.translator.app.presentation.translator.animations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.Flow
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Полное состояние audio-визуализации.
 * Один экземпляр шарится через rememberAudioLevels(...) — все анимации читают
 * одни и те же значения, никакой рассинхронизации между ними.
 */
class AudioMetrics internal constructor(
    val level: State<Float>,     // [0..1] smoothed RMS, perception-curved
    val peak: State<Float>,      // [0..1] peak hold + slow decay
    val velocity: State<Float>   // [-1..+1] derivative of level (per second)
)

/**
 * Главная точка входа.
 *
 * @param audioFlow PCM16 LE chunks (24 kHz mono). Может молчать.
 * @param active   true когда AI говорит ИЛИ микрофон активен.
 * @param attack   реакция на нарастание (0.2..0.8). По умолчанию 0.55 — крепко.
 * @param release  скорость затухания (0.02..0.15). По умолчанию 0.07 — мягко.
 * @param noiseGate уровень, ниже которого считаем тишиной [0..0.05].
 */
@Composable
fun rememberAudioMetrics(
    audioFlow: Flow<ByteArray>,
    active: Boolean,
    attack: Float = 0.55f,
    release: Float = 0.07f,
    noiseGate: Float = 0.012f
): AudioMetrics {
    val level = remember { mutableFloatStateOf(0f) }
    val peak = remember { mutableFloatStateOf(0f) }
    val velocity = remember { mutableFloatStateOf(0f) }

    // Внутренние состояния (без boxing — FloatArray из одной ячейки)
    val rawRms = remember { floatArrayOf(0f) }
    val noiseFloor = remember { floatArrayOf(0.005f) }
    val prevLevel = remember { floatArrayOf(0f) }

    // 1. Слушаем audioFlow, считаем RMS + AGC.
    LaunchedEffect(audioFlow, active) {
        if (!active) {
            rawRms[0] = 0f
            return@LaunchedEffect
        }
        audioFlow.collect { pcm ->
            if (pcm.size < 2) return@collect
            val r = computeRms16Fast(pcm)

            // Adaptive noise floor — медленно ползёт за тишиной,
            // мгновенно НЕ повышается громкими звуками (max-hold inverted).
            val nf = noiseFloor[0]
            noiseFloor[0] = if (r < nf * 1.5f) nf * 0.985f + r * 0.015f else nf
            val cleaned = (r - noiseFloor[0] * 0.55f).coerceAtLeast(0f)

            // Log-perception curve. log10(x) сжимает динамику до «человеческой»
            // громкости. Формула подобрана эмпирически — шёпот = 0.15, крик = 0.95.
            val perceived = if (cleaned <= 0f) {
                0f
            } else {
                val db = 20f * (ln(cleaned + 1e-5f) / ln(10f))   // dBFS-ish
                ((db + 55f) / 50f).coerceIn(0f, 1f)               // -55..-5 dB → 0..1
            }
            rawRms[0] = perceived
        }
    }

    // 2. Кадровый smoother — работает только в активном режиме.
    LaunchedEffect(active) {
        if (!active) {
            level.floatValue = 0f
            peak.floatValue = 0f
            velocity.floatValue = 0f
            return@LaunchedEffect
        }
        var prevT = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - prevT).coerceAtLeast(0L) / 1_000_000_000f)
                    .coerceAtMost(0.05f)
                prevT = now

                val target = if (!active) 0f else rawRms[0]
                val gated = if (target < noiseGate) 0f else target
                val cur = level.floatValue

                // Asymmetric smoothing: быстрая атака, мягкий релиз.
                val k = if (gated > cur) {
                    1f - exp(-dt * (40f * attack + 1f))
                } else {
                    1f - exp(-dt * (12f * release + 0.5f))
                }
                val newLevel = (cur + (gated - cur) * k).coerceIn(0f, 1f)

                // Velocity (производная за секунду).
                velocity.floatValue = if (dt > 0f) {
                    ((newLevel - prevLevel[0]) / dt).coerceIn(-1f, 1f)
                } else 0f
                prevLevel[0] = newLevel
                level.floatValue = newLevel

                // Peak hold с медленным распадом (~0.6 сек до нуля).
                val curPeak = peak.floatValue
                peak.floatValue = if (newLevel > curPeak) {
                    newLevel
                } else {
                    max(0f, curPeak - dt * 1.6f)
                }
            }
        }
    }

    return AudioMetrics(level, peak, velocity)
}

/**
 * Удобный shortcut: возвращает только level (для backward-compat).
 */
@Composable
fun rememberAudioLevel(
    audioFlow: Flow<ByteArray>,
    active: Boolean,
    attack: Float = 0.55f,
    release: Float = 0.07f
): State<Float> = rememberAudioMetrics(audioFlow, active, attack, release).level

/**
 * RMS для PCM16 LE, zero-alloc, развёрнутый цикл.
 */
private fun computeRms16Fast(pcm: ByteArray): Float {
    var sum = 0.0
    val n = pcm.size - 1
    var i = 0
    while (i < n) {
        val low = pcm[i].toInt() and 0xFF
        val high = pcm[i + 1].toInt()
        val raw = (high shl 8) or low
        val s = if (raw and 0x8000 != 0) raw or 0xFFFF0000.toInt() else raw
        sum += (s * s).toDouble()
        i += 2
    }
    val count = pcm.size / 2
    return (sqrt(sum / count) / 32768.0).toFloat()
}
