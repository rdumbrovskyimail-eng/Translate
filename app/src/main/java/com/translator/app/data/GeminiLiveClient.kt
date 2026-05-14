package com.translator.app.data

import android.util.Base64
import com.translator.app.domain.LiveClient
import com.translator.app.domain.ToolResponse
import com.translator.app.domain.model.GeminiEvent
import com.translator.app.domain.model.MicAudioChunk
import com.translator.app.domain.model.SessionConfig
import com.translator.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveClient(
    private val logger: AppLogger
) : LiveClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Стабильнее на мобильной сети, чем 60s.
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<GeminiEvent> = _events.asSharedFlow()

    @Volatile override var sessionHandle: String? = null
        private set

    @Volatile override var isReady: Boolean = false
        private set

    // Защита коннекта (ровно с новой строки)
    private val connectionMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile private var logRawFrames: Boolean = false
    @Volatile private var droppedAudioChunks: Long = 0L

    // Turn-ID — каждый InputTranscript/новая речь начинает новый ход.
    // AudioChunk'и из старых ходов после Interrupted просто игнорируются клиентом.
    private val currentTurnId = AtomicLong(0L)
    @Volatile private var activeTurnId: Long = 0L

    private var currentConfig: SessionConfig? = null
    
    @Volatile private var closeCompletion: CompletableDeferred<Unit>? = null
    @Volatile private var setupWatchdog: Job? = null

    private val lastSentFrames = java.util.ArrayDeque<String>(3)

    private fun trackSentFrame(raw: String) {
        if (!logRawFrames) return
        synchronized(lastSentFrames) {
            if (lastSentFrames.size >= 3) lastSentFrames.pollFirst()
            lastSentFrames.offerLast(raw.take(2000))
        }
    }

    // ════════════════════════════════════════════════════════════
    //  CONNECT / DISCONNECT
    // ════════════════════════════════════════════════════════════

    override suspend fun connect(apiKey: String, config: SessionConfig, logRaw: Boolean) {
        connectionMutex.withLock {
            if (webSocket != null) internalDisconnect()

            currentConfig = config
            logRawFrames = logRaw
            isReady = false
            droppedAudioChunks = 0L
            synchronized(lastSentFrames) { lastSentFrames.clear() }
            closeCompletion = CompletableDeferred()

            val url = "wss://${SessionConfig.WS_HOST}/${SessionConfig.WS_PATH}?key=$apiKey"
            logger.d("Connecting to ${config.model}…")

            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                    logger.d("WS opened (${response.code}) — sending setup…")
                    _events.tryEmit(GeminiEvent.Connected)
                    sendSetup(config)
                    startSetupWatchdog(config.setupTimeoutMs)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (logRawFrames) {
                        val preview = if (text.length > 500) text.take(500) + "…" else text
                        logger.d("RAW ← $preview")
                    }
                    parseServerMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    try { parseServerMessage(bytes.utf8()) }
                    catch (e: Exception) { logger.e("Binary frame decode: ${e.message}") }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    val desc = describeCloseCode(code)
                    logger.d("WS closed: $code $desc reason='$reason'")
                    if (code == 1007 || code == 1008) {
                        synchronized(lastSentFrames) {
                            if (lastSentFrames.isNotEmpty()) {
                                logger.e("⚠ LAST SENT FRAMES before $code:")
                                lastSentFrames.forEachIndexed { i, f -> logger.e("  [$i] $f") }
                            }
                        }
                    }
                    cancelSetupWatchdog()
                    isReady = false
                    closeCompletion?.complete(Unit)
                    if (code != 1000 && code != 1001) {
                        _events.tryEmit(GeminiEvent.ConnectionError("WS $code: $desc ${reason}"))
                    }
                    _events.tryEmit(GeminiEvent.Disconnected(code, reason))
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    val status = response?.code?.let { " (HTTP $it)" } ?: ""
                    logger.e("WS failure$status: ${t.message}")
                    cancelSetupWatchdog()
                    isReady = false
                    closeCompletion?.complete(Unit)
                    _events.tryEmit(GeminiEvent.ConnectionError(t.message ?: "Unknown WS error"))
                }
            })
        }
    }

    private fun startSetupWatchdog(timeoutMs: Long) {
        cancelSetupWatchdog()
        setupWatchdog = internalScope.launch {
            delay(timeoutMs)
            if (!isReady && webSocket != null) {
                logger.e("⚠ SETUP TIMEOUT — no setupComplete in ${timeoutMs}ms")
                _events.tryEmit(GeminiEvent.ConnectionError("Setup timeout."))
                runCatching { webSocket?.close(1000, "setup_timeout") }
            }
        }
    }

    private fun cancelSetupWatchdog() { setupWatchdog?.cancel(); setupWatchdog = null }

    private suspend fun internalDisconnect() {
        cancelSetupWatchdog()
        val ws = webSocket
        if (ws == null) { isReady = false; return }
        val completion = closeCompletion
        runCatching { ws.close(1000, "bye") }
        if (completion != null && !completion.isCompleted) {
            withTimeoutOrNull(2000L) { completion.await() }
        }
        webSocket = null
        isReady = false
        closeCompletion = null
        // Любые stale-chunki после disconnect игнорируются.
        activeTurnId = currentTurnId.incrementAndGet()
    }
    
    override suspend fun disconnect() {
        connectionMutex.withLock {
            internalDisconnect()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SETUP
    // ════════════════════════════════════════════════════════════

    private fun sendSetup(config: SessionConfig) {
        val msg = buildFullSetup(config)
        val raw = msg.toString()
        logger.d("SETUP → ${config.model} (${raw.length} chars)")
        if (logRawFrames) raw.chunked(500).forEachIndexed { i, c -> logger.d("[setup $i] $c") }
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    private fun buildFullSetup(config: SessionConfig): JsonObject = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", config.model)

            // ─── generationConfig ───
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive(config.responseModality)) })

                put("temperature", config.temperature)
                put("topP", config.topP)
                put("maxOutputTokens", config.maxOutputTokens)

                if (config.responseModality == "AUDIO") {
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", config.voiceId)
                            })
                        })
                    })
                }

                val thinkingLevel = config.latencyProfile.thinkingLevel
                if (thinkingLevel != null) {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", thinkingLevel)
                        if (config.thinkingIncludeThoughts) put("includeThoughts", true)
                    })
                }
            })

            // ─── systemInstruction ───
            if (config.systemInstruction.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", config.systemInstruction) })
                    })
                })
            }

            // ─── realtimeInputConfig ───
            // activityHandling + turnCoverage шлются ВСЕГДА (они не часть VAD).
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject {
                    put("disabled", !config.autoActivityDetection)
                    if (config.autoActivityDetection) {
                        put("startOfSpeechSensitivity", config.vadStartSensitivity)
                        put("endOfSpeechSensitivity", config.vadEndSensitivity)
                        put("prefixPaddingMs", config.vadPrefixPaddingMs)
                        put("silenceDurationMs", config.vadSilenceDurationMs)
                    }
                })
                put("activityHandling", config.activityHandling)
                put("turnCoverage", config.turnCoverage)
            })

            // ─── Транскрипция + языковая подсказка ASR ───
            if (config.inputTranscription) {
                put("inputAudioTranscription", buildJsonObject {
                    if (config.transcriptionLanguageCodes.isNotEmpty()) {
                        put("languageCodes", buildJsonArray {
                            config.transcriptionLanguageCodes.forEach { add(JsonPrimitive(it)) }
                        })
                    }
                })
            }
            if (config.outputTranscription) {
                put("outputAudioTranscription", buildJsonObject {})
            }

            // ─── Session Resumption ───
            if (config.enableSessionResumption) {
                put("sessionResumption", buildJsonObject {
                    config.sessionHandle?.let { put("handle", it) }
                })
            }

            // ─── Context Compression ───
            if (config.enableContextCompression) {
                put("contextWindowCompression", buildJsonObject {
                    if (config.compressionTriggerTokens > 0L)
                        put("triggerTokens", config.compressionTriggerTokens)
                    put("slidingWindow", buildJsonObject {
                        if (config.compressionTargetTokens > 0L)
                            put("targetTokens", config.compressionTargetTokens)
                    })
                })
            }
        })
    }

    // ════════════════════════════════════════════════════════════
    //  CLIENT → SERVER
    // ════════════════════════════════════════════════════════════

    override fun sendAudio(chunk: MicAudioChunk) {
        val ws = webSocket
        if (!isReady || ws == null) {
            droppedAudioChunks++
            if (droppedAudioChunks % 50L == 0L) {
                logger.w("Dropped $droppedAudioChunks audio chunks (not ready)")
            }
            chunk.release()
            return
        }
        try {
            val b64 = Base64.encodeToString(chunk.data, 0, chunk.length, Base64.NO_WRAP)
            val raw = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=${SessionConfig.INPUT_SAMPLE_RATE}"}}}"""
            val logStub = """{"realtimeInput":{"audio":{"data":"<HIDDEN_BASE64_FOR_MEM_SAFETY>","mimeType":"audio/pcm;rate=${SessionConfig.INPUT_SAMPLE_RATE}"}}}"""
            trackSentFrame(logStub)
            ws.send(raw)
        } catch (e: Exception) {
            logger.e("Audio send failed: ${e.message}")
        } finally {
            chunk.release()
        }
    }

    override fun sendRealtimeText(text: String) {
        val ws = webSocket
        if (!isReady || ws == null) return
        val raw = buildJsonObject {
            put("realtimeInput", buildJsonObject { put("text", text) })
        }.toString()
        trackSentFrame(raw); ws.send(raw)
    }

    override fun sendAudioStreamEnd() {
        val ws = webSocket
        if (!isReady || ws == null) return
        val raw = """{"realtimeInput":{"audioStreamEnd":true}}"""
        trackSentFrame(raw); ws.send(raw)
    }

    override fun sendActivityStart() {
        val ws = webSocket
        if (!isReady || ws == null) return
        ws.send("""{"realtimeInput":{"activityStart":{}}}""")
    }

    override fun sendActivityEnd() {
        val ws = webSocket
        if (!isReady || ws == null) return
        ws.send("""{"realtimeInput":{"activityEnd":{}}}""")
    }

    override fun sendTurnComplete() {
        val ws = webSocket
        if (!isReady || ws == null) return
        val raw = buildJsonObject {
            put("clientContent", buildJsonObject { put("turnComplete", true) })
        }.toString()
        trackSentFrame(raw); ws.send(raw)
    }

    override fun sendToolResponse(responses: List<ToolResponse>) {
        val ws = webSocket
        if (!isReady || ws == null) return
        val raw = buildJsonObject {
            put("toolResponse", buildJsonObject {
                put("functionResponses", buildJsonArray {
                    for (resp in responses) {
                        add(buildJsonObject {
                            put("name", resp.name)
                            put("id", resp.id)
                            put("response", buildJsonObject { put("result", resp.result) })
                        })
                    }
                })
            })
        }.toString()
        trackSentFrame(raw); ws.send(raw)
    }

    // ════════════════════════════════════════════════════════════
    //  SERVER → CLIENT
    // ════════════════════════════════════════════════════════════

    private fun parseServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            if (root.containsKey("setupComplete")) {
                logger.d("✓ SETUP COMPLETE")
                cancelSetupWatchdog()
                isReady = true
                activeTurnId = currentTurnId.incrementAndGet()
                _events.tryEmit(GeminiEvent.SetupComplete)
                return
            }

            root["toolCallCancellation"]?.jsonObject?.let { cancellation ->
                val ids = cancellation["ids"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                _events.tryEmit(GeminiEvent.ToolCallCancellation(ids))
                return
            }

            root["sessionResumptionUpdate"]?.jsonObject?.let { update ->
                val resumable = update["resumable"]?.jsonPrimitive?.booleanOrNull ?: false
                val newHandle = update["newHandle"]?.jsonPrimitive?.content
                val lastConsumed = update["lastConsumedClientMessageIndex"]
                    ?.jsonPrimitive?.longOrNull
                if (newHandle != null && resumable) {
                    sessionHandle = newHandle
                    _events.tryEmit(GeminiEvent.SessionHandleUpdate(newHandle, resumable, lastConsumed))
                }
                return
            }

            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeft = goAway["timeLeft"]?.jsonPrimitive?.content
                _events.tryEmit(GeminiEvent.GoAway(timeLeft))
                return
            }

            root["usageMetadata"]?.jsonObject?.let { usage ->
                val prompt = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                val resp = (usage["responseTokenCount"] ?: usage["candidatesTokenCount"])
                    ?.jsonPrimitive?.intOrNull ?: 0
                val total = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                _events.tryEmit(GeminiEvent.UsageMetadata(prompt, resp, total))
            }

            val sc = root["serverContent"]?.jsonObject ?: return

            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { _events.tryEmit(GeminiEvent.InputTranscript(it)) }

            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { _events.tryEmit(GeminiEvent.OutputTranscript(it)) }

            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                // Новый turn-id — старые AudioChunk-сообщения, ещё летящие по сети, будут отброшены.
                activeTurnId = currentTurnId.incrementAndGet()
                _events.tryEmit(GeminiEvent.Interrupted)
            }

            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                activeTurnId = currentTurnId.incrementAndGet()
                _events.tryEmit(GeminiEvent.TurnComplete)
            }

            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                _events.tryEmit(GeminiEvent.GenerationComplete)
            }

            val parts = sc["modelTurn"]?.jsonObject?.get("parts") as? JsonArray ?: return
            val turnForThisFrame = activeTurnId
            for (part in parts) {
                val obj = part.jsonObject
                obj["text"]?.jsonPrimitive?.content?.let {
                    _events.tryEmit(GeminiEvent.ModelText(it))
                }
                obj["inlineData"]?.jsonObject?.let { inline ->
                    val mime = inline["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    if (mime.startsWith("audio/pcm")) {
                        inline["data"]?.jsonPrimitive?.content?.let { b64 ->
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            _events.tryEmit(GeminiEvent.AudioChunk(pcm, turnForThisFrame))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("PARSE ERROR: ${e.message}", e)
        }
    }

    private fun describeCloseCode(code: Int): String = when (code) {
        1000 -> "[Normal Closure]"
        1001 -> "[Going Away]"
        1002 -> "[Protocol Error]"
        1003 -> "[Unsupported Data]"
        1006 -> "[Abnormal Closure]"
        1007 -> "[Invalid Frame Payload — JSON/enum]"
        1008 -> "[Policy Violation — модель/ключ]"
        1011 -> "[Internal Server Error]"
        1013 -> "[Try Again Later]"
        4000 -> "[Gemini: Session expired]"
        4001 -> "[Gemini: Invalid setup]"
        4002 -> "[Gemini: Rate limited (429)]"
        4003 -> "[Gemini: Auth failed]"
        else -> "[Code $code]"
    }
}