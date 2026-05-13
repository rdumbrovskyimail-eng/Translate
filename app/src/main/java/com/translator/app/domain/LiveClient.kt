// ═══════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/translator/app/domain/LiveClient.kt
//
// Контракт WebSocket-клиента Gemini Live API v1beta (2026).
//
// Жизненный цикл подключения:
//   1. connect() устанавливает WS и отправляет setup
//   2. При onOpen эмитится GeminiEvent.Connected (жёлтый статус UI)
//   3. При получении setupComplete от сервера эмитится
//      GeminiEvent.SetupComplete и isReady=true (зелёный статус UI)
//   4. Если setupComplete не пришёл за setupTimeoutMs —
//      эмитится ConnectionError и WS закрывается
//
// Изменения v2:
//   • sendRealtimeText(text) — текст в ходе уже идущего диалога
//     (через realtimeInput.text). Семантически разделён с sendText().
//   • sendVideoFrame(jpegBytes) — видео-контекст (≤1 FPS).
//   • disconnect() suspend — ждёт onClosed с таймаутом 2с.
// ═══════════════════════════════════════════════════════════
package com.translator.app.domain

import com.translator.app.domain.model.AudioChunk
import com.translator.app.domain.model.ConversationMessage
import com.translator.app.domain.model.GeminiEvent
import com.translator.app.domain.model.SessionConfig
import kotlinx.coroutines.flow.Flow

/**
 * Абстракция WebSocket-клиента Gemini Live API — контракт 2026.
 *
 * Полный набор операций:
 *  1. connect()            — WS + setup с полной конфигурацией
 *  2. sendAudio()          — стрим PCM (realtimeInput.audio)
 *  3. sendText()           — initial user text (clientContent.turns)
 *  4. sendRealtimeText()   — текст в ходе диалога (realtimeInput.text)
 *  5. sendVideoFrame()     — JPEG-кадр (realtimeInput.video, ≤1 FPS)
 *  6. sendAudioStreamEnd() — flush серверного audio кеша при паузе mic
 *  7. sendActivityStart()  — ручной VAD: начало речи (disabled auto VAD)
 *  8. sendActivityEnd()    — ручной VAD: конец речи (disabled auto VAD)
 *  9. sendTurnComplete()   — сигнал окончания хода
 * 10. sendToolResponse()   — ответ на tool call
 * 11. restoreContext()     — seeding истории (только в начале сессии!)
 * 12. disconnect()         — штатное закрытие С ОЖИДАНИЕМ onClosed
 */
interface LiveClient {

    val events: Flow<GeminiEvent>
    val sessionHandle: String?

    /**
     * true только после получения setupComplete от сервера.
     * До этого — false (даже если WS уже open на транспортном уровне).
     */
    val isReady: Boolean

    /**
     * Подключение к Gemini Live API.
     *
     * Последовательность:
     *   1. Открывает WS → эмитит GeminiEvent.Connected
     *   2. Отправляет setup
     *   3. Ждёт setupComplete (с таймаутом config.setupTimeoutMs)
     *   4. При успехе → GeminiEvent.SetupComplete, isReady=true
     *   5. При таймауте/ошибке → GeminiEvent.ConnectionError
     *
     * @param apiKey  API ключ
     * @param config  полная конфигурация сессии
     * @param logRaw  логировать сырые WS-фреймы
     */
    suspend fun connect(apiKey: String, config: SessionConfig, logRaw: Boolean = false)

    fun sendAudio(chunk: AudioChunk)

    /**
     * Отправить initial user message после SetupComplete.
     * Идёт через clientContent.turns — зарезервировано за
     * начальным контекстом сессии (до первого model turn).
     */
    fun sendText(text: String)

    /**
     * Отправить текст в процессе уже идущего диалога (после первого model turn).
     * В Gemini 3.1 Flash Live такой текст шлётся через realtimeInput.text,
     * а НЕ через clientContent (последнее зарезервировано за initial history).
     */
    fun sendRealtimeText(text: String)

    /**
     * Отправить кадр JPEG (для видео-контекста, ≤1 FPS).
     * Заготовка для будущих немецких уроков с карточками.
     */
    fun sendVideoFrame(jpegBytes: ByteArray)

    /**
     * Отправить audioStreamEnd для flush кеша на сервере.
     * Вызывать при паузе/остановке микрофона.
     */
    fun sendAudioStreamEnd()

    /**
     * Отправить activityStart (только в режиме disabled automatic VAD).
     * См. realtimeInputConfig.automaticActivityDetection.disabled = true.
     */
    fun sendActivityStart()

    /**
     * Отправить activityEnd (только в режиме disabled automatic VAD).
     * Заменяет audioStreamEnd в этом режиме.
     */
    fun sendActivityEnd()

    fun sendTurnComplete()
    fun sendToolResponse(responses: List<ToolResponse>)

    /**
     * Seeding начальной истории.
     * ВАЖНО: в Gemini 3.1 разрешено ТОЛЬКО в начале сессии
     * (до первого model turn). После — вызовет 1007.
     */
    fun restoreContext(history: List<ConversationMessage>)

    /**
     * Закрыть WS и ДОЖДАТЬСЯ фактического закрытия
     * (onClosed / onFailure) с таймаутом ~2с.
     */
    suspend fun disconnect()
}

data class ToolResponse(
    val name: String,
    val id: String,
    val result: String
)