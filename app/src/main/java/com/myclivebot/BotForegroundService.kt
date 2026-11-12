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
import com.myclivebot.core.LocalBridgeServer
import com.myclivebot.core.PiperSpeechEngine
import com.myclivebot.core.PiperVoiceCatalog
import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.SpeechQueueWorker
import com.myclivebot.core.TikTokLiveConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        const val EXTRA_IGNORE_EMOJIS = "ignore_emojis"
        const val EXTRA_IGNORE_SYMBOLS = "ignore_symbols"
        const val EXTRA_ONLY_USERNAME = "only_username"
        const val EXTRA_VOLUME = "speech_volume"
        const val EXTRA_SPEED = "speech_speed"
        const val EXTRA_VOICE_ID = "voice_id"
        private const val CHANNEL_ID = "live_bot_status"
        private const val NOTIFICATION_ID = 4101
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var piper: PiperSpeechEngine? = null
    private var speechQueue: SpeechQueueWorker? = null
    private var connector: TikTokLiveConnector? = null
    private var localBridge: LocalBridgeServer? = null
    private var activeVoiceId: String? = null
    private var activeVolume: Float? = null
    private var activeSpeed: Float? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startLocalBridge()
    }

    private fun startLocalBridge() {
        if (localBridge?.isRunning() == true) return
        val bridge = LocalBridgeServer { command, value ->
            when (command) {
                "ping" -> localBridge?.publish("pong", JSONObject().put("value", value))
                "status" -> localBridge?.publish("status", JSONObject().put("value", "running"))
            }
        }
        localBridge = bridge
        runCatching { bridge.start() }
            .onFailure { updateStatus("Servidor local: \${it.message ?: "no disponible"}") }
    }

    private fun stopLocalBridge() {
        localBridge?.stop()
        localBridge = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                connector?.disconnect()
                connector = null
                speechQueue?.close()
                speechQueue = null
                kotlinx.coroutines.runBlocking(Dispatchers.IO) { runCatching { piper?.stop() } }
                stopLocalBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparando lector LIVE…"))
                startLocalBridge()
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                if (username.isBlank()) {
                    updateStatus("Falta el usuario de TikTok")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val settings = ReaderSettings(
                    readUsername = intent.getBooleanExtra(EXTRA_READ_USERNAME, true),
                    blockedWords = intent.getStringArrayListExtra(EXTRA_BLOCKED_WORDS)?.toSet().orEmpty(),
                    ignoredUsers = intent.getStringArrayListExtra(EXTRA_IGNORED_USERS)?.toSet().orEmpty(),
                    maxQueueSize = intent.getIntExtra(EXTRA_MAX_QUEUE, 30).coerceIn(5, 100),
                    ignoreEmojis = intent.getBooleanExtra(EXTRA_IGNORE_EMOJIS, true),
                    ignoreSymbols = intent.getBooleanExtra(EXTRA_IGNORE_SYMBOLS, false),
                    onlyUsername = intent.getBooleanExtra(EXTRA_ONLY_USERNAME, false),
                    volume = intent.getFloatExtra(EXTRA_VOLUME, 1.0f).coerceIn(0f, 1f),
                    speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.65f, 1.5f),
                    voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID
                )
                if (!PiperSpeechEngine.isVoiceInstalled(this, settings.voiceId)) {
                    updateStatus("Descarga primero la voz seleccionada")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startReader(username, settings)
            }

            ACTION_TEST_VOICE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Prueba de voz"))
                val voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID
                val volume = intent.getFloatExtra(EXTRA_VOLUME, 1.0f).coerceIn(0f, 1f)
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.65f, 1.5f)
                if (!PiperSpeechEngine.isVoiceInstalled(this, voiceId)) {
                    updateStatus("Descarga primero la voz seleccionada")
                } else {
                    ensureSpeechQueue(30, voiceId, volume, speed)
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
        ensureSpeechQueue(settings.maxQueueSize, settings.voiceId, settings.volume, settings.speed)
        val pipeline = CommentPipeline(CommentFilter(), speechQueue!!, settings)
        val liveConnector = TikTokLiveConnector(
            onComment = { comment -> pipeline.onComment(comment) },
            onStatus = ::updateStatus
        )
        connector = liveConnector
        serviceScope.launch(Dispatchers.IO) {
            runCatching { liveConnector.connect(username) }
                .onFailure { updateStatus("No se pudo conectar: \${it.message ?: "error desconocido"}") }
        }
    }

    private fun ensureSpeechQueue(capacity: Int, voiceId: String, volume: Float, speed: Float) {
        val needsRebuild = piper == null || activeVoiceId != voiceId ||
            activeVolume != volume || activeSpeed != speed
        if (needsRebuild) {
            speechQueue?.close()
            speechQueue = null
            piper?.release()
            piper = PiperSpeechEngine(this, voiceId, volume, speed)
            activeVoiceId = voiceId
            activeVolume = volume
            activeSpeed = speed
        }
        if (speechQueue == null) {
            speechQueue = SpeechQueueWorker(piper!!, serviceScope, capacity = capacity)
        }
    }

    private fun updateStatus(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status)
        )
        localBridge?.publish("status", JSONObject().put("value", status))
    }

    override fun onDestroy() {
        connector?.disconnect()
        connector = null
        speechQueue?.close()
        speechQueue = null
        stopLocalBridge()
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
            val channel = NotificationChannel(
                CHANNEL_ID, "Estado del bot", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Estado de la conexión al LIVE y del lector de voz."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else Notification.Builder(this)
        return builder.setContentTitle("TikTok Live Reader")
            .setContentText(status.take(120))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
