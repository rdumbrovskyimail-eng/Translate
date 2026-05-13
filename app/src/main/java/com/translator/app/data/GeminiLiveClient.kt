package com.translator.app.data

import android.util.Base64
import com.translator.app.domain.LiveClient
import com.translator.app.domain.ToolResponse
import com.translator.app.domain.model.AudioChunk
import com.translator.app.domain.model.ConversationMessage
import com.translator.app.domain.model.FunctionCall
import com.translator.app.domain.model.GeminiEvent
import com.translator.app.domain.model.ParameterConfig
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

/**
 * Клиент Gemini Live API v1beta с ДИАГНОСТИЧЕСКИМ sendSetup.
 *
 * Каждый блок setup может быть выключен флагом в SessionConfig для
 * поиска источника close code 1007 "Invalid JSON payload".
 *
 * Алгоритм поиска:
 *   1. Запусти с SessionConfig.baselineProfile() — должно работать
 *   2. Если baseline OK — используй withoutXxx-профили по одному
 *   3. Тот профиль, с которым setup проходит — указывает на виновника
 */
class GeminiLiveClient(
    private val logger: AppLogger
) : LiveClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 1.8: ping interval changed from 30 to 60 seconds
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .build()

    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<GeminiEvent> = _events.asSharedFlow()

    @Volatile
    override var sessionHandle: String? = null
        private set

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    private var logRawFrames: Boolean = false

    // 1.9: silent drop counter for diagnostics
    @Volatile private var droppedAudioChunks: Long = 0L

    private var currentConfig: SessionConfig? = null

    @Volatile
    private var closeCompletion: CompletableDeferred<Unit>? = null

    @Volatile
    private var setupWatchdog: Job? = null

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
        if (webSocket != null) disconnect()

        currentConfig = config
        logRawFrames = logRaw
        isReady = false
        droppedAudioChunks = 0L  // 1.9: reset drop counter on new connection
        synchronized(lastSentFrames) { lastSentFrames.clear() }
        closeCompletion = CompletableDeferred()

        val url = "wss://${SessionConfig.WS_HOST}/${SessionConfig.WS_PATH}?key=$apiKey"
        logger.d("Connecting to ${config.model}…")
        logger.d("Diagnostic flags: minimal=${config.diagnosticMinimalSetup} " +
                "thinking=${config.sendThinkingConfig} " +
                "vad=${config.sendVadConfig} " +
                "transcr=${config.sendTranscriptionConfig} " +
                "resumption=${config.sendSessionResumptionConfig} " +
                "compression=${config.sendContextCompressionConfig} " +
                "genParams=${config.sendGenerationParams}")

        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                logger.d("WS opened (${response.code}) — sending setup, waiting for setupComplete…")
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
                try {
                    parseServerMessage(bytes.utf8())
                } catch (e: Exception) {
                    logger.e("Binary frame decode error: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                val desc = describeCloseCode(code)
                logger.d("WS closed: $code $desc reason='$reason'")

                if (code == 1007 || code == 1008) {
                    synchronized(lastSentFrames) {
                        if (lastSentFrames.isNotEmpty()) {
                            logger.e("⚠ LAST SENT FRAMES before close $code:")
                            lastSentFrames.forEachIndexed { i, frame ->
                                logger.e("  [$i] $frame")
                            }
                        } else {
                            logger.e("⚠ No frames tracked (enable logRaw to capture)")
                        }
                    }
                }

                cancelSetupWatchdog()
                isReady = false
                closeCompletion?.complete(Unit)
                if (code != 1000 && code != 1001) {
                    _events.tryEmit(
                        GeminiEvent.ConnectionError(
                            "WS closed $code: $desc ${reason.ifBlank { "" }}"
                        )
                    )
                }
                _events.tryEmit(GeminiEvent.Disconnected(code, reason))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val status = response?.code?.let { " (HTTP $it)" } ?: ""
                logger.e("WS failure$status: ${t.message}")
                cancelSetupWatchdog()
                isReady = false
                closeCompletion?.complete(Unit)
                _events.tryEmit(GeminiEvent.ConnectionError(t.message ?: "Unknown error"))
            }
        })
    }

    private fun startSetupWatchdog(timeoutMs: Long) {
        cancelSetupWatchdog()
        setupWatchdog = internalScope.launch {
            delay(timeoutMs)
            if (!isReady && webSocket != null) {
                logger.e("⚠ SETUP TIMEOUT — no setupComplete in ${timeoutMs}ms")
                _events.tryEmit(
                    GeminiEvent.ConnectionError(
                        "Setup timeout: no setupComplete in ${timeoutMs}ms."
                    )
                )
                runCatching { webSocket?.close(1000, "setup_timeout") }
            }
        }
    }

    private fun cancelSetupWatchdog() {
        setupWatchdog?.cancel()
        setupWatchdog = null
    }

    override suspend fun disconnect() {
        cancelSetupWatchdog()
        val ws = webSocket
        if (ws == null) {
            isReady = false
            return
        }
        val completion = closeCompletion
        runCatching { ws.close(1000, "bye") }
        if (completion != null && !completion.isCompleted) {
            withTimeoutOrNull(2000L) { completion.await() }
        }
        webSocket = null
        isReady = false
        closeCompletion = null
    }

    // ════════════════════════════════════════════════════════════
    //  SETUP — с диагностическими флагами
    // ════════════════════════════════════════════════════════════

    private fun sendSetup(config: SessionConfig) {
        val msg = if (config.diagnosticMinimalSetup) {
            buildMinimalSetup(config)
        } else {
            buildFullSetup(config)
        }

        val raw = msg.toString()
        logger.d("SETUP → ${config.model} (${raw.length} chars)" +
                if (config.diagnosticMinimalSetup) " [MINIMAL PROFILE]" else "")

        if (config.logFullSetupJson || logRawFrames) {
            // Принтим полный JSON построчно чтобы не обрезался в logcat
            logger.d("SETUP_RAW BEGIN ─────────────────")
            raw.chunked(500).forEachIndexed { i, chunk ->
                logger.d("  [$i] $chunk")
            }
            logger.d("SETUP_RAW END ───────────────────")
        }

        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    /**
     * Минимальный setup — только абсолютно необходимые поля.
     * Если с ним 1007 — значит проблема в model, responseModalities,
     * speechConfig или systemInstruction (или в самом transport-е).
     */
    private fun buildMinimalSetup(config: SessionConfig): JsonObject =
        buildJsonObject {
            put("setup", buildJsonObject {
                put("model", config.model)

                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray {
                        add(JsonPrimitive(config.responseModality))
                    })
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", config.voiceId)
                            })
                        })
                    })
                })

                if (config.systemInstruction.isNotBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", config.systemInstruction)
                            })
                        })
                    })
                }
            })
        }

    /**
     * Полный setup с возможностью отключения отдельных блоков через флаги.
     */
    private fun buildFullSetup(config: SessionConfig): JsonObject =
        buildJsonObject {
            put("setup", buildJsonObject {
                put("model", config.model)

                // ─── generationConfig ───
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray {
                        add(JsonPrimitive(config.responseModality))
                    })

                    // 1.1: mediaResolution moved here from root-level setup
                    if (config.mediaResolution.isNotBlank()) {
                        put("mediaResolution", config.mediaResolution)
                    }

                    // 1.2: only temperature, topP, topK, maxOutputTokens — no presencePenalty/frequencyPenalty
                    if (config.sendGenerationParams) {
                        put("temperature", config.temperature)
                        put("topP", config.topP)
                        if (config.topK > 0) put("topK", config.topK)
                        put("maxOutputTokens", config.maxOutputTokens)
                    }

                    if (config.responseModality == "AUDIO") {
                        put("speechConfig", buildJsonObject {
                            put("voiceConfig", buildJsonObject {
                                put("prebuiltVoiceConfig", buildJsonObject {
                                    put("voiceName", config.voiceId)
                                })
                            })
                            if (config.languageCode.isNotBlank()) {
                                put("languageCode", config.languageCode)
                            }
                        })
                    }

                    // Off (thinkingLevel == null) → блок не шлём вообще, модель работает
                    // в максимально быстром режиме без обдумывания.
                    val thinkingLevel = config.latencyProfile.thinkingLevel
                    if (config.sendThinkingConfig && thinkingLevel != null) {
                        put("thinkingConfig", buildJsonObject {
                            put("thinkingLevel", thinkingLevel)
                            if (config.thinkingIncludeThoughts) {
                                put("includeThoughts", true)
                            }
                        })
                    }
                })

                // ─── systemInstruction ───
                if (config.systemInstruction.isNotBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", config.systemInstruction)
                            })
                        })
                    })
                }

                // ─── Tools ───
                val hasTools = config.enableGoogleSearch ||
                        config.functionDeclarations.isNotEmpty()
                if (hasTools) {
                    put("tools", buildJsonArray {
                        if (config.enableGoogleSearch) {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }
                        if (config.functionDeclarations.isNotEmpty()) {
                            add(buildJsonObject {
                                put("functionDeclarations", buildJsonArray {
                                    for (decl in config.functionDeclarations) {
                                        add(buildFunctionDeclaration(decl))
                                    }
                                })
                            })
                        }
                    })
                }

                // ─── realtimeInputConfig ───
                if (config.sendVadConfig) {
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
                }

                // ─── Транскрипция ───
                if (config.sendTranscriptionConfig) {
                    if (config.inputTranscription) {
                        put("inputAudioTranscription", buildJsonObject {
                            // languageCodes ОСТОРОЖНО: Gemini API часто отвергает это поле
                            // с "Cannot find field". Vertex AI принимает. Для безопасности
                            // отправляем только если непусто И только в дополнительной попытке.
                            // По умолчанию НЕ шлём — оставляем пустой объект.
                        })
                    }
                    if (config.outputTranscription) {
                        put("outputAudioTranscription", buildJsonObject {})
                    }
                }

                // 1.3: historyConfig block removed — it only makes sense when seeding
                // clientContent.turns as the first message, which this client doesn't do.
                // Setting it unconditionally can trigger close code 1007 on v1beta.

                // ─── Session Resumption ───
                if (config.sendSessionResumptionConfig && config.enableSessionResumption) {
                    put("sessionResumption", buildJsonObject {
                        config.sessionHandle?.let { put("handle", it) }
                    })
                }

                // ─── Context Window Compression ───
                if (config.sendContextCompressionConfig && config.enableContextCompression) {
                    put("contextWindowCompression", buildJsonObject {
                        if (config.compressionTriggerTokens > 0L) {
                            put("triggerTokens", config.compressionTriggerTokens)
                        }
                        put("slidingWindow", buildJsonObject {
                            if (config.compressionTargetTokens > 0L) {
                                put("targetTokens", config.compressionTargetTokens)
                            }
                        })
                    })
                }
            })
        }

    private fun buildFunctionDeclaration(decl: com.translator.app.domain.model.FunctionDeclarationConfig): JsonObject =
        buildJsonObject {
            put("name", decl.name)
            put("description", decl.description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    for ((pName, pConfig) in decl.parameters) {
                        put(pName, buildParameterSchema(pConfig))
                    }
                })
                if (decl.required.isNotEmpty()) {
                    put("required", buildJsonArray {
                        decl.required.forEach { add(JsonPrimitive(it)) }
                    })
                }
            })
        }

    private fun buildParameterSchema(param: ParameterConfig): JsonObject =
        buildJsonObject {
            val lowerType = param.type.lowercase()
            put("type", lowerType)
            if (param.description.isNotBlank()) {
                put("description", param.description)
            }
            if (param.enumValues.isNotEmpty()) {
                put("enum", buildJsonArray {
                    param.enumValues.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (lowerType == "array" && param.items != null) {
                put("items", buildParameterSchema(param.items))
            }
            if (lowerType == "object" && param.properties.isNotEmpty()) {
                put("properties", buildJsonObject {
                    param.properties.forEach { (k, v) -> put(k, buildParameterSchema(v)) }
                })
                if (param.required.isNotEmpty()) {
                    put("required", buildJsonArray {
                        param.required.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }

    // ════════════════════════════════════════════════════════════
    //  CLIENT → SERVER
    // ════════════════════════════════════════════════════════════

    override fun sendAudio(chunk: AudioChunk) {
        if (!isReady) {
            // 1.9: silent drop counter
            droppedAudioChunks++
            if (droppedAudioChunks % 50L == 0L) {
                logger.w("Dropped $droppedAudioChunks audio chunks (session not ready)")
            }
            chunk.release()
            return
        }
        try {
            val b64 = Base64.encodeToString(chunk.data, 0, chunk.length, Base64.NO_WRAP)
            val raw = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=${SessionConfig.INPUT_SAMPLE_RATE}"}}}"""
            trackSentFrame(raw)
            webSocket?.send(raw)
        } catch (e: Exception) {
            logger.e("Audio send failed: ${e.message}")
        } finally {
            chunk.release()
        }
    }

    // 1.4: sendText now delegates to sendRealtimeText.
    // In Gemini 3.1 Flash Live, text in an active dialogue is sent via
    // realtimeInput.text. clientContent is reserved only for seeding initial
    // history (before the first model turn) — for that, use restoreContext().
    override fun sendText(text: String) {
        sendRealtimeText(text)
    }

    override fun sendRealtimeText(text: String) {
        if (!isReady) return
        val raw = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("text", text)
            })
        }.toString()
        logger.d("REALTIME_TEXT → (${text.length} chars)")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendVideoFrame(jpegBytes: ByteArray) {
        if (!isReady) return
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val raw = """{"realtimeInput":{"video":{"data":"$b64","mimeType":"image/jpeg"}}}"""
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendAudioStreamEnd() {
        if (!isReady) return
        val raw = """{"realtimeInput":{"audioStreamEnd":true}}"""
        logger.d("AUDIO_STREAM_END →")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    // 1.7: manual VAD methods for when autoActivityDetection = false
    override fun sendActivityStart() {
        if (!isReady) return
        val raw = """{"realtimeInput":{"activityStart":{}}}"""
        logger.d("ACTIVITY_START →")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendActivityEnd() {
        if (!isReady) return
        val raw = """{"realtimeInput":{"activityEnd":{}}}"""
        logger.d("ACTIVITY_END →")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendTurnComplete() {
        if (!isReady) return
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turnComplete", true)
            })
        }
        val raw = msg.toString()
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendToolResponse(responses: List<ToolResponse>) {
        val msg = buildJsonObject {
            put("toolResponse", buildJsonObject {
                put("functionResponses", buildJsonArray {
                    for (resp in responses) {
                        add(buildJsonObject {
                            put("name", resp.name)
                            put("id", resp.id)
                            put("response", buildJsonObject {
                                put("result", resp.result)
                            })
                        })
                    }
                })
            })
        }
        val raw = msg.toString()
        logger.d("TOOL_RESPONSE → (${raw.length} chars)")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    // 1.5: added isReady guard; turnComplete set to false — this is history seeding, not an active turn
    override fun restoreContext(history: List<ConversationMessage>) {
        if (history.isEmpty()) return
        if (!isReady) {
            logger.w("restoreContext skipped — session not ready")
            return
        }
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turns", buildJsonArray {
                    for (entry in history) {
                        add(buildJsonObject {
                            put("role", entry.role)
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", entry.text) })
                            })
                        })
                    }
                })
                // НЕ ставим turnComplete — это history seeding, не активный ход
                put("turnComplete", false)
            })
        }
        val raw = msg.toString()
        logger.d("CONTEXT SEED → ${history.size} messages (${raw.length} chars)")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    // ════════════════════════════════════════════════════════════
    //  SERVER → CLIENT (parse)
    // ════════════════════════════════════════════════════════════

    private fun parseServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            if (root.containsKey("setupComplete")) {
                logger.d("✓ SETUP COMPLETE")
                cancelSetupWatchdog()
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
                return
            }

            root["toolCall"]?.jsonObject?.let { toolCall ->
                parseToolCall(toolCall)
                return
            }

            root["toolCallCancellation"]?.jsonObject?.let { cancellation ->
                val ids = cancellation["ids"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                logger.d("TOOL_CALL_CANCELLATION: $ids")
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
                    logger.d("SESSION_RESUMPTION: handle updated (resumable=$resumable)")
                    _events.tryEmit(
                        GeminiEvent.SessionHandleUpdate(
                            handle = newHandle,
                            resumable = resumable,
                            lastConsumedIndex = lastConsumed
                        )
                    )
                }
                return
            }

            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeft = goAway["timeLeft"]?.jsonPrimitive?.content
                logger.d("GO_AWAY — server will close soon (timeLeft=$timeLeft)")
                _events.tryEmit(GeminiEvent.GoAway(timeLeft))
                return
            }

            root["usageMetadata"]?.jsonObject?.let { usage ->
                val prompt = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                // 1.6: responseTokenCount is the correct Live API field name; candidatesTokenCount is generateContent API
                val resp = (usage["responseTokenCount"]
                    ?: usage["candidatesTokenCount"])?.jsonPrimitive?.intOrNull ?: 0
                val total = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                _events.tryEmit(
                    GeminiEvent.UsageMetadata(
                        promptTokens = prompt,
                        responseTokens = resp,
                        totalTokens = total
                    )
                )
            }

            val sc = root["serverContent"]?.jsonObject ?: run {
                if (logRawFrames) {
                    val preview = if (raw.length > 200) raw.take(200) + "…" else raw
                    logger.d("SERVER ← $preview")
                }
                return
            }

            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    logger.d("USER: $text")
                    _events.tryEmit(GeminiEvent.InputTranscript(text))
                }

            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    logger.d("GEMINI: $text")
                    _events.tryEmit(GeminiEvent.OutputTranscript(text))
                }

            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("⚡ INTERRUPTED — barge-in")
                _events.tryEmit(GeminiEvent.Interrupted)
            }

            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("⏹ TURN COMPLETE")
                _events.tryEmit(GeminiEvent.TurnComplete)
            }

            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("✅ GENERATION COMPLETE")
                _events.tryEmit(GeminiEvent.GenerationComplete)
            }

            sc["groundingMetadata"]?.jsonObject?.let { grounding ->
                logger.d("GROUNDING METADATA received")
                _events.tryEmit(GeminiEvent.GroundingMetadata(grounding.toString()))
            }

            val parts = sc["modelTurn"]?.jsonObject?.get("parts") as? JsonArray ?: return

            for (part in parts) {
                val obj = part.jsonObject

                obj["text"]?.jsonPrimitive?.content?.let { text ->
                    logger.d("MODEL_TEXT: $text")
                    _events.tryEmit(GeminiEvent.ModelText(text))
                }

                obj["inlineData"]?.jsonObject?.let { inline ->
                    val mime = inline["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    if (mime.startsWith("audio/pcm")) {
                        inline["data"]?.jsonPrimitive?.content?.let { b64 ->
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            _events.tryEmit(GeminiEvent.AudioChunk(pcm))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("PARSE ERROR: ${e.message}", e)
        }
    }

    private fun parseToolCall(toolCall: JsonObject) {
        val functionCalls = toolCall["functionCalls"]?.jsonArray ?: run {
            logger.w("toolCall without functionCalls")
            return
        }

        val calls = functionCalls.map { fc ->
            val fcObj = fc.jsonObject
            val name = fcObj["name"]?.jsonPrimitive?.content ?: "unknown"
            val id = fcObj["id"]?.jsonPrimitive?.content ?: ""
            val argsObj = fcObj["args"]?.jsonObject
            val args = mutableMapOf<String, String>()
            argsObj?.forEach { (key, value) ->
                args[key] = when (value) {
                    is JsonPrimitive -> value.content
                    is JsonObject    -> value.toString()
                    is JsonArray     -> value.toString()
                    else             -> value.toString()
                }
            }
            logger.d("🔧 TOOL_CALL: $name(id=$id, $args)")
            FunctionCall(name, id, args)
        }

        _events.tryEmit(GeminiEvent.ToolCall(calls))
    }

    private fun describeCloseCode(code: Int): String = when (code) {
        1000 -> "[Normal Closure]"
        1001 -> "[Going Away]"
        1002 -> "[Protocol Error]"
        1003 -> "[Unsupported Data]"
        1006 -> "[Abnormal Closure]"
        1007 -> "[Invalid Frame Payload — невалидный JSON / неизвестное поле в setup / неверный enum]"
        1008 -> "[Policy Violation — модель недоступна ключу / неверный model ID]"
        1011 -> "[Internal Server Error]"
        1013 -> "[Try Again Later]"
        4000 -> "[Gemini: Session expired]"
        4001 -> "[Gemini: Invalid setup]"
        4002 -> "[Gemini: Rate limited (429)]"
        4003 -> "[Gemini: Auth failed — неверный API ключ]"
        else -> "[Code $code]"
    }
}