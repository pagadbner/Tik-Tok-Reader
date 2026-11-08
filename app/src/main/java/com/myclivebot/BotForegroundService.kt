package com.myclivebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.myclivebot.core.CommentFilter
import com.myclivebot.core.CommentPipeline
import com.myclivebot.core.PiperSpeechEngine
import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.SpeechQueueWorker
import com.myclivebot.core.TikTokLiveConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BotForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.myclivebot.START"
        const val ACTION_STOP = "com.myclivebot.STOP"
        const val ACTION_TEST_VOICE = "com.myclivebot.TEST_VOICE"
        const val ACTION_STATUS = "com.myclivebot.STATUS"
        const val EXTRA_STATUS = "reader_status"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_READ_USERNAME = "read_username"
        const val EXTRA_BLOCKED_WORDS = "blocked_words"
        const val EXTRA_IGNORED_USERS = "ignored_users"
        const val EXTRA_MAX_QUEUE = "max_queue"
        private const val CHANNEL_ID = "live_bot_status"
        private const val NOTIFICATION_ID = 4101
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var piper: PiperSpeechEngine? = null
    private var speechQueue: SpeechQueueWorker? = null
    private var connector: TikTokLiveConnector? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                connector?.disconnect()
                connector = null
                speechQueue?.close()
                speechQueue = null
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    runCatching { piper?.stop() }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparando lector LIVE…"))
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                if (username.isBlank()) {
                    updateStatus("Falta el usuario de TikTok")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!PiperSpeechEngine.isVoiceInstalled(this)) {
                    updateStatus("Descarga primero la voz Piper español")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val settings = ReaderSettings(
                    readUsername = intent.getBooleanExtra(EXTRA_READ_USERNAME, true),
                    blockedWords = intent.getStringArrayListExtra(EXTRA_BLOCKED_WORDS)?.toSet().orEmpty(),
                    ignoredUsers = intent.getStringArrayListExtra(EXTRA_IGNORED_USERS)?.toSet().orEmpty(),
                    maxQueueSize = intent.getIntExtra(EXTRA_MAX_QUEUE, 30).coerceIn(5, 100)
                )
                startReader(username, settings)
            }
            ACTION_TEST_VOICE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Prueba de voz"))
                if (!PiperSpeechEngine.isVoiceInstalled(this)) {
                    updateStatus("Descarga primero la voz Piper español")
                } else {
                    ensureSpeechQueue(30)
                    speechQueue?.enqueue("Prueba de voz en español de México. El lector está preparado.")
                    updateStatus("Reproduciendo prueba Piper")
                }
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startReader(username: String, settings: ReaderSettings) {
        connector?.disconnect()
        connector = null
        speechQueue?.close()
        speechQueue = null
        ensureSpeechQueue(settings.maxQueueSize)
        val pipeline = CommentPipeline(CommentFilter(), speechQueue!!, settings)
        val liveConnector = TikTokLiveConnector(
            onComment = { comment -> pipeline.onComment(comment) },
            onStatus = ::updateStatus
        )
        connector = liveConnector
        serviceScope.launch(Dispatchers.IO) {
            runCatching { liveConnector.connect(username) }
                .onFailure { updateStatus("No se pudo conectar: ${it.message ?: "error desconocido"}") }
        }
    }

    private fun ensureSpeechQueue(capacity: Int) {
        if (piper == null) piper = PiperSpeechEngine(this)
        if (speechQueue == null) speechQueue = SpeechQueueWorker(piper!!, serviceScope, capacity = capacity)
    }

    private fun updateStatus(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    }

    override fun onDestroy() {
        connector?.disconnect()
        connector = null
        speechQueue?.close()
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            runCatching { piper?.stop() }
            runCatching { piper?.release() }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Estado del bot", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Estado de la conexión al LIVE y del lector de voz."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("TikTok Live Reader")
            .setContentText(status.take(120))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
