package com.translator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GeminiLiveForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "gemini_live_channel"
        private const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.translator.app.ACTION_START_SESSION"
        const val ACTION_STOP = "com.translator.app.ACTION_STOP_SESSION"
        const val EXTRA_FORCE_SPEAKER = "extra_force_speaker"

        fun startIntent(context: Context, forceSpeaker: Boolean = true): Intent =
            Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FORCE_SPEAKER, forceSpeaker)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var audioManager: AudioManager? = null
    private var bluetoothScoActive = false
    private var communicationDeviceSet = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ВСЕГДА startForeground в первую очередь.
        if (!startForegroundSafe()) {
            // Не удалось — нет смысла продолжать (Android 14+ может запретить mic-FGS из фона).
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                requestAudioFocus()
                val forceSpeaker = intent.getBooleanExtra(EXTRA_FORCE_SPEAKER, true)
                routeAudio(forceSpeaker)
            }
            ACTION_STOP -> {
                releaseAudioFocus()
                releaseAudioRouting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Throwable) {
            android.util.Log.e("FGS", "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AUDIO ROUTING
    // ════════════════════════════════════════════════════════════

    private fun routeAudio(forceSpeaker: Boolean) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val devices = am.availableCommunicationDevices
                val target: AudioDeviceInfo? = if (forceSpeaker) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                }
                if (target != null) {
                    communicationDeviceSet = am.setCommunicationDevice(target)
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        am.startBluetoothSco()
                    }
                }
            }
            return
        }
        @Suppress("DEPRECATION")
        run {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        }
    }

    private fun releaseAudioRouting() {
        val am = audioManager ?: return
        if (bluetoothScoActive) {
            am.stopBluetoothSco()
            bluetoothScoActive = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (communicationDeviceSet) {
                runCatching { am.clearCommunicationDevice() }
                communicationDeviceSet = false
            }
            am.mode = AudioManager.MODE_NORMAL
            return
        }
        @Suppress("DEPRECATION")
        run { am.isSpeakerphoneOn = false; am.mode = AudioManager.MODE_NORMAL }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            releaseAudioFocus()
            releaseAudioRouting()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build().also { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, GeminiLiveForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translator активен")
            .setContentText("Голосовой переводчик слушает")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Translator",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Активная голосовая сессия"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        releaseAudioFocus()
        releaseAudioRouting()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAudioRouting()
        releaseAudioFocus()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}