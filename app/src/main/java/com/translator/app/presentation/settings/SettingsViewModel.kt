package com.translator.app.presentation.settings

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translator.app.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject

enum class ModelTestState { IDLE, TESTING, SUCCESS, ERROR }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings = _settings.asStateFlow()

    // Состояния для UI проверки модели
    private val _modelTestState = MutableStateFlow(ModelTestState.IDLE)
    val modelTestState = _modelTestState.asStateFlow()

    private val _modelTestError = MutableStateFlow<String?>(null)
    val modelTestError = _modelTestError.asStateFlow()

    init {
        settingsStore.data
            .distinctUntilChanged()
            .onEach { fromDisk ->
                _settings.update { current ->
                    if (current == fromDisk) current else fromDisk
                }
            }
            .launchIn(viewModelScope)
    }

    fun update(transform: AppSettings.() -> AppSettings) {
        _settings.update(transform)
        viewModelScope.launch {
            settingsStore.updateData { it.transform() }
        }
    }

    fun resetModelTestState() {
        _modelTestState.value = ModelTestState.IDLE
        _modelTestError.value = null
    }

    /**
     * Открывает реальный WebSocket для проверки существования и доступности модели.
     */
    fun testAndApplyModel(modelName: String, onSuccessClearCustom: () -> Unit) {
        val apiKey = settings.value.apiKey
        if (apiKey.isEmpty()) {
            _modelTestState.value = ModelTestState.ERROR
            _modelTestError.value = "Сначала введите API ключ"
            return
        }

        _modelTestState.value = ModelTestState.TESTING
        _modelTestError.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey")
                .build()

            var isResolved = false

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    // Отправляем минимальный setup frame с проверяемой моделью
                    val normalized = if (modelName.startsWith("models/")) modelName else "models/$modelName"
                    val setupJson = """{"setup": {"model": "$normalized"}}"""
                    ws.send(setupJson)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    // Если сервер прислал setupComplete, значит модель существует и поддерживает Live API
                    if (text.contains("setupComplete")) {
                        if (!isResolved) {
                            isResolved = true
                            _modelTestState.value = ModelTestState.SUCCESS
                            update { copy(model = modelName) }
                            
                            // Переключаемся на Main поток для вызова UI-коллбэка
                            launch(Dispatchers.Main) { onSuccessClearCustom() }
                            ws.close(1000, "ok")
                        }
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (!isResolved) {
                        isResolved = true
                        _modelTestState.value = ModelTestState.ERROR
                        _modelTestError.value = "Ошибка $code: $reason"
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (!isResolved) {
                        isResolved = true
                        _modelTestState.value = ModelTestState.ERROR
                        _modelTestError.value = t.message ?: "Ошибка сети"
                    }
                }
            })

            // Таймаут 6 секунд на случай, если сервер завис
            delay(6000)
            if (!isResolved) {
                isResolved = true
                _modelTestState.value = ModelTestState.ERROR
                _modelTestError.value = "Таймаут ответа от сервера"
                ws.cancel()
            }
        }
    }
}