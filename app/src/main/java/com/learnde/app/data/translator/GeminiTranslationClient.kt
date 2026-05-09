// Путь: app/src/main/java/com/learnde/app/data/translator/GeminiTranslationClient.kt
package com.learnde.app.data.translator

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiTranslationClient @Inject constructor(
    private val logger: AppLogger,
) {
    companion object {
        // Легкая и ультрабыстрая модель
        private const val MODEL = "gemini-3.1-flash-lite-preview"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Жесткие короткие таймауты: модель-лайт отвечает за миллисекунды!
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun reverseTranslate(textFromSocket: String, apiKey: String): ReverseResult = withContext(Dispatchers.IO) {
        if (textFromSocket.isBlank()) return@withContext ReverseResult("", "")

        val reqStart = System.currentTimeMillis()
        val sysPrompt = "Если текст на немецком — выведи русский перевод. Если текст на русском — выведи немецкий перевод. Никаких комментариев, только результат."

        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", sysPrompt) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", textFromSocket) }) }) })
            })
            put("generationConfig", buildJsonObject { put("temperature", 0.0) })
        }

        val request = Request.Builder().url("$ENDPOINT?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()

        try {
            val response = httpClient.newCall(request).execute().body?.string() ?: ""
            
            // Быстро вытаскиваем текст 
            val resultText = json.parseToJsonElement(response)
                .jsonObject["candidates"]?.jsonArray?.get(0)
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")
                ?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim() ?: ""
                
            val time = System.currentTimeMillis() - reqStart
            logger.d("ReverseTr ✓ (${time}ms): [$textFromSocket] -> [$resultText]")
            
            val isCyrl = resultText.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
            ReverseResult(
                reconstructedText = resultText, 
                lang = if (isCyrl) "RU" else "DE"
            )
        } catch (e: Exception) {
            logger.e("ReverseTr ✗ fallback: ${e.message}")
            ReverseResult("", "")
        }
    }
}

data class ReverseResult(
    val reconstructedText: String,
    val lang: String
)