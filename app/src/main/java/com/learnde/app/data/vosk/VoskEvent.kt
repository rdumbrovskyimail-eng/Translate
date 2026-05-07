// ═══════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/learnde/app/data/vosk/VoskEvent.kt
//
// События от Vosk-транскрайбера. Источник = микрофон (твой голос)
// или playback (голос ответа Gemini). Лангtag = угаданный язык фразы.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.vosk

enum class VoskSource { MIC, PLAYBACK }

enum class VoskLang { RU, DE, UNKNOWN }

sealed class VoskEvent {
    abstract val source: VoskSource

    /** Текст обновляется по слогам — Vosk печатает в реальном времени. */
    data class Partial(
        override val source: VoskSource,
        val text: String,
        val lang: VoskLang,
    ) : VoskEvent()

    /** Финальная версия фразы. Vosk считает что ты закончил говорить. */
    data class Final(
        override val source: VoskSource,
        val text: String,
        val lang: VoskLang,
    ) : VoskEvent()
}