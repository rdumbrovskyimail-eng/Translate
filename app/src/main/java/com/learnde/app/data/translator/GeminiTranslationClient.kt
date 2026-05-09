package com.learnde.app.data.translator

import android.util.Base64
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiTranslationClient @Inject constructor(
    private val logger: AppLogger,
) {
    companion object {
        // Возвращаем рабочую модель, которая не выдает 404
        private const val MODEL = "gemini-3-flash-preview" 
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:streamGenerateContent"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun translate(
        pcm16kBytes: ByteArray,
        apiKey: String,
        onPartialResult: (TranslationResult) -> Unit
    ): TranslationResult = withContext(Dispatchers.IO) {

        if (pcm16kBytes.isEmpty()) return@withContext TranslationResult("", "")

        val wavBytes = pcmToWav(pcm16kBytes, sampleRate = 16_000)
        val wavBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "audio/wav")
                                put("data", wavBase64)
                            })
                        })
                        add(buildJsonObject {
                            // ПРОСИМ ЧИСТЫЙ ТЕКСТ ДЛЯ МГНОВЕННОГО СТРИМИНГА
                            put("text", "You are a real-time translator. Listen to the audio and output EXACTLY in this format:\nORIGINAL: <transcription>\nTRANSLATION: <translation>\nDo not add any other text.")
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.0)
                put("topP", 0.95)
                put("maxOutputTokens", 256)
                // ВАЖНО: Мы УБРАЛИ responseMimeType = application/json. 
                // Именно он блокировал стриминг и вызывал задержку 8 секунд!
            })
        }

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey&alt=sse")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var fullResponseText = ""
        var finalOriginal = ""
        var finalTranslation = ""
        var firstTokenTime = 0L
        val startedAt = System.currentTimeMillis()

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                
                val source = resp.body?.source() ?: throw IllegalStateException("Empty body")
                
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        if (data == "[DONE]") break
                        
                        val chunkText = extractTextFromChunk(data)
                        if (chunkText.isNotEmpty()) {
                            if (firstTokenTime == 0L) {
                                firstTokenTime = System.currentTimeMillis() - startedAt
                                logger.d("GeminiTranslate: ⚡ FIRST TOKEN in ${firstTokenTime}ms")
                            }
                            
                            fullResponseText += chunkText
                            
                            // Парсим текст регулярками на лету (очень быстро)
                            val origMatch = "ORIGINAL:\\s*(.*?)(?:\\nTRANSLATION:|$)".toRegex(RegexOption.DOT_MATCHES_ALL).find(fullResponseText)
                            val transMatch = "TRANSLATION:\\s*(.*)".toRegex(RegexOption.DOT_MATCHES_ALL).find(fullResponseText)
                            
                            finalOriginal = origMatch?.groupValues?.get(1)?.trim() ?: ""
                            finalTranslation = transMatch?.groupValues?.get(1)?.trim() ?: ""
                            
                            onPartialResult(TranslationResult(finalOriginal, finalTranslation))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("GeminiTranslate ✗ failed: ${e.message}")
        }

        logger.d("GeminiTranslate ← Stream finished in ${System.currentTimeMillis() - startedAt}ms")
        return@withContext TranslationResult(finalOriginal, finalTranslation)
    }

    private fun extractTextFromChunk(chunkJson: String): String {
        return runCatching {
            val root = json.parseToJsonElement(chunkJson).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return@runCatching ""
            if (candidates.isEmpty()) return@runCatching ""
            val parts = candidates[0].jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray ?: return@runCatching ""
            buildString {
                for (part in parts) {
                    val txt = part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    if (!txt.isNullOrEmpty()) append(txt)
                }
            }
        }.getOrDefault("")
    }

    private fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcmBytes.size)
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2
        out.write("RIFF".toByteArray()); out.write(intToLeBytes(totalDataLen)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(intToLeBytes(16)); out.write(shortToLeBytes(1)); out.write(shortToLeBytes(1))
        out.write(intToLeBytes(sampleRate)); out.write(intToLeBytes(byteRate)); out.write(shortToLeBytes(2)); out.write(shortToLeBytes(16))
        out.write("data".toByteArray()); out.write(intToLeBytes(pcmBytes.size)); out.write(pcmBytes)
        return out.toByteArray()
    }
    private fun intToLeBytes(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(), ((v ushr 16) and 0xff).toByte(), ((v ushr 24) and 0xff).toByte())
    private fun shortToLeBytes(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte())
}

data class TranslationResult(
    val original: String,
    val translation: String,
)