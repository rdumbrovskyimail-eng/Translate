package com.translator.app.util

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor(
    private val buffer: LogBuffer
) {
    companion object {
        private const val TAG = "GeminiLive"
        private const val MAX_DISPLAY_CHARS = 3000
    }

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        Timber.plant(Timber.DebugTree())
        Timber.tag(TAG).d("AppLogger initialized")
    }

    fun d(msg: String) { Timber.tag(TAG).d(msg); buffer.append(LogLevel.D, msg) }
    fun i(msg: String) { Timber.tag(TAG).i(msg); buffer.append(LogLevel.I, msg) }
    fun w(msg: String) { Timber.tag(TAG).w(msg); buffer.append(LogLevel.W, msg) }
    fun e(msg: String, throwable: Throwable? = null) {
        Timber.tag(TAG).e(throwable, msg); buffer.append(LogLevel.E, msg, throwable)
    }

    fun getFullLog(): String = buffer.exportAsText()

    fun getDisplayLog(): String {
        val recent = buffer.entries.value.takeLast(100)
        val text = recent.joinToString("\n") { it.formatted() }
        return if (text.length > MAX_DISPLAY_CHARS) text.takeLast(MAX_DISPLAY_CHARS) else text
    }
}