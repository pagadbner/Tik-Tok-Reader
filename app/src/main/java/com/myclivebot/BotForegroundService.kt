package com.myclivebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import com.myclivebot.core.CommentFilter
import com.myclivebot.core.CommentPipeline
import com.myclivebot.core.ChatCommandParser
import com.myclivebot.core.DynamicPiperSpeechEngine
import com.myclivebot.core.LocalBridgeServer
import com.myclivebot.core.LiveComment
import com.myclivebot.core.PiperSpeechEngine
import com.myclivebot.core.PiperVoiceCatalog
import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.SpeechQueueWorker
import com.myclivebot.core.TikTokLiveConnector
import com.myclivebot.core.TikTokWebViewCookieProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class BotForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.myclivebot.START"
        const val ACTION_STOP = "com.myclivebot.STOP"
        const val ACTION_TEST_VOICE = "com.myclivebot.TEST_VOICE"
        const val ACTION_UPDATE_SETTINGS = "com.myclivebot.UPDATE_SETTINGS"
        const val ACTION_SKIP_COMMENT = "com.myclivebot.SKIP_COMMENT"
        const val ACTION_COMMENT_SKIPPED = "com.myclivebot.COMMENT_SKIPPED"
        const val ACTION_CHAT_HISTORY = "com.myclivebot.CHAT_HISTORY"
        const val ACTION_SET_MUTE = "com.myclivebot.SET_MUTE"
        const val EXTRA_MUTED = "voice_muted"
        const val ACTION_STATUS = "com.myclivebot.STATUS"
        const val ACTION_REQUEST_STATUS = "com.myclivebot.REQUEST_STATUS"
        const val ACTION_COMMENT = "com.myclivebot.COMMENT"
        const val EXTRA_COMMENT_USERNAME = "comment_username"
        const val EXTRA_COMMENT_TEXT = "comment_text"
        const val EXTRA_COMMENT_TIME = "comment_time"
        const val EXTRA_COMMENT_AVATAR = "comment_avatar"
        const val EXTRA_COMMENT_ID = "comment_id"
        const val EXTRA_REPLY_TO = "reply_to_username"
        const val EXTRA_CHAT_HISTORY = "chat_history"
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
        const val EXTRA_FOLLOWER_ONLY = "follower_only_chat"
        const val EXTRA_READ_FOLLOWS = "read_follows"
        const val EXTRA_READ_GIFTS = "read_gifts"
        const val EXTRA_RANDOM_VOICES = "random_voices"
        const val EXTRA_COMMANDS_ENABLED = "custom_commands_enabled"
        const val EXTRA_CUSTOM_COMMANDS = "custom_commands"
        const val EXTRA_WELCOME_ENABLED = "welcome_enabled"
        const val EXTRA_WELCOME_TEMPLATE = "welcome_template"
        const val EXTRA_GIFT_MINIMUM_REPEAT = "gift_minimum_repeat"
        const val EXTRA_GIFT_TEMPLATE = "gift_alert_template"
        private const val CHANNEL_ID = "live_bot_status"
        private const val NOTIFICATION_ID = 4101
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var piper: DynamicPiperSpeechEngine? = null
    private var speechQueue: SpeechQueueWorker? = null
    private var connector: TikTokLiveConnector? = null
    private var localBridge: LocalBridgeServer? = null
    private lateinit var liveChatHistoryStore: LiveChatHistoryStore
    private var liveWakeLock: PowerManager.WakeLock? = null
    private var activeVoiceId: String? = null
    private var activeVolume: Float? = null
    private var activeSpeed: Float? = null
    private var activeSettings: ReaderSettings? = null
    @Volatile private var voiceMuted: Boolean = false
    private var pipeline: CommentPipeline? = null
    @Volatile private var lastKnownStatus: String = "Detenido"
    @Volatile private var lastStatsPublishAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        liveChatHistoryStore = LiveChatHistoryStore(this)
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
            .onFailure { updateStatus("Servidor local: ${it.message ?: "no disponible"}") }
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
                releaseLiveWakeLock()
                pipeline = null
                activeSettings = null
                updateStatus("Detenido")
                speechQueue?.close()
                speechQueue = null
                kotlinx.coroutines.runBlocking(Dispatchers.IO) { runCatching { piper?.stop() } }
                stopLocalBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                voiceMuted = getSharedPreferences("tiklbot_preferences", MODE_PRIVATE)
                    .getBoolean("voice_muted", false)
                startForeground(NOTIFICATION_ID, buildNotification("Preparando lector LIVE…"))
                startLocalBridge()
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                if (username.isBlank()) {
                    updateStatus("Falta el usuario de TikTok")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val settings = readSettings(intent)
                if (!PiperSpeechEngine.isVoiceInstalled(this, settings.voiceId)) {
                    updateStatus("Descarga primero la voz seleccionada")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startReader(username, settings)
            }


            ACTION_REQUEST_STATUS -> {
                val status = if (activeSettings != null) lastKnownStatus else "Detenido"
                sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
                    .putExtra(EXTRA_STATUS, status)
                    .putExtra(EXTRA_MUTED, voiceMuted))
                sendChatHistory()
                if (activeSettings == null) stopSelf(startId)
            }

            ACTION_SKIP_COMMENT -> {
                val commentId = intent.getStringExtra(EXTRA_COMMENT_ID).orEmpty()
                if (commentId.isNotBlank() && speechQueue?.skipComment(commentId) == true) {
                    sendBroadcast(
                        Intent(ACTION_COMMENT_SKIPPED).setPackage(packageName)
                            .putExtra(EXTRA_COMMENT_ID, commentId)
                    )
                }
            }

            ACTION_SET_MUTE -> {
                voiceMuted = intent.getBooleanExtra(EXTRA_MUTED, false)
                getSharedPreferences("tiklbot_preferences", MODE_PRIVATE).edit()
                    .putBoolean("voice_muted", voiceMuted).apply()
                piper?.setMuted(voiceMuted)
                updateStatus(if (voiceMuted) "Voz silenciada" else "Voz activada")
            }

            ACTION_UPDATE_SETTINGS -> {
                val previous = activeSettings
                if (previous != null) {
                    var next = readSettings(intent)
                    if (!PiperSpeechEngine.isVoiceInstalled(this, next.voiceId)) {
                        next = next.copy(voiceId = previous.voiceId)
                    }
                    activeSettings = next
                    pipeline?.updateSettings(next)
                    piper?.update(next)
                    activeVolume = next.volume
                    activeSpeed = next.speed
                    activeVoiceId = next.voiceId
                    localBridge?.publish("settings", JSONObject().put("value", "updated"))
                }
            }

            ACTION_TEST_VOICE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Prueba de voz"))
                val voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID
                val volume = intent.getFloatExtra(EXTRA_VOLUME, 1.0f).coerceIn(0f, 1f)
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.65f, 1.5f)
                if (!PiperSpeechEngine.isVoiceInstalled(this, voiceId)) {
                    updateStatus("Descarga primero la voz seleccionada")
                } else {
                    val testText = PiperVoiceCatalog.find(voiceId).testPhrase
                    val testSettings = activeSettings
                    if (testSettings != null && testSettings.voiceId == voiceId) {
                        // Reuse the already initialized speech engine and serialized queue.
                        speechQueue?.enqueue(testText, priority = true)
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val testEngine = PiperSpeechEngine(this@BotForegroundService, voiceId, volume, speed)
                            try {
                                testEngine.speak(testText)
                            } finally {
                                testEngine.release()
                            }
                        }
                    }
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
        releaseLiveWakeLock()
        speechQueue?.close()
        speechQueue = null
        activeSettings = settings
        liveChatHistoryStore.clear()
        acquireLiveWakeLock()
        ensureSpeechQueue(settings.maxQueueSize, settings)
        // Warm the selected native voice before the first LIVE comment arrives.
        // This moves model initialization and asset preparation off the first utterance.
        serviceScope.launch(Dispatchers.IO) {
            runCatching { piper?.initialize() }
        }
        val livePipeline = CommentPipeline(CommentFilter(), speechQueue!!, settings)
        pipeline = livePipeline
        val liveConnector = TikTokLiveConnector(
            onComment = { comment ->
                publishLiveComment(comment)
                livePipeline.onComment(comment)
                publishLiveStatsIfDue()
            },
            onStatus = ::updateStatus,
            onEvent = { event ->
                livePipeline.onEvent(event)
                publishLiveStatsIfDue()
            },
            cookieFallback = { TikTokWebViewCookieProvider(this).acquireTtwid() }
        )
        connector = liveConnector
        serviceScope.launch(Dispatchers.IO) {
            runCatching { liveConnector.connect(username) }
                .onFailure { updateStatus("No se pudo conectar: ${it.message ?: "error desconocido"}") }
        }
    }

    private fun ensureSpeechQueue(capacity: Int, settings: ReaderSettings) {
        if (piper == null) {
            piper = DynamicPiperSpeechEngine(this, settings)
        } else {
            piper?.update(settings)
        }
        activeVoiceId = settings.voiceId
        activeVolume = settings.volume
        piper?.setMuted(voiceMuted)
        activeSpeed = settings.speed
        if (speechQueue == null) {
            speechQueue = SpeechQueueWorker(piper!!, serviceScope, capacity = capacity)
        }
    }

    private fun readSettings(intent: Intent) = ReaderSettings(
        readUsername = intent.getBooleanExtra(EXTRA_READ_USERNAME, true),
        blockedWords = intent.getStringArrayListExtra(EXTRA_BLOCKED_WORDS)?.toSet().orEmpty(),
        ignoredUsers = intent.getStringArrayListExtra(EXTRA_IGNORED_USERS)?.toSet().orEmpty(),
        maxQueueSize = intent.getIntExtra(EXTRA_MAX_QUEUE, 30).coerceIn(5, 100),
        ignoreEmojis = intent.getBooleanExtra(EXTRA_IGNORE_EMOJIS, true),
        ignoreSymbols = intent.getBooleanExtra(EXTRA_IGNORE_SYMBOLS, false),
        onlyUsername = intent.getBooleanExtra(EXTRA_ONLY_USERNAME, false),
        volume = intent.getFloatExtra(EXTRA_VOLUME, 1.0f).coerceIn(0f, 1f),
        speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.65f, 1.5f),
        voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID,
        followerOnlyChat = intent.getBooleanExtra(EXTRA_FOLLOWER_ONLY, false),
        readFollows = intent.getBooleanExtra(EXTRA_READ_FOLLOWS, true),
        readGifts = intent.getBooleanExtra(EXTRA_READ_GIFTS, true),
        randomVoices = intent.getBooleanExtra(EXTRA_RANDOM_VOICES, false),
        customCommandsEnabled = intent.getBooleanExtra(EXTRA_COMMANDS_ENABLED, false),
        customCommands = ChatCommandParser.parseConfig(
            intent.getStringArrayListExtra(EXTRA_CUSTOM_COMMANDS).orEmpty().joinToString("\n")
        ),
        welcomeEnabled = intent.getBooleanExtra(EXTRA_WELCOME_ENABLED, false),
        welcomeTemplate = intent.getStringExtra(EXTRA_WELCOME_TEMPLATE) ?: "¡Bienvenido al LIVE, {user}!",
        giftMinimumRepeatCount = intent.getIntExtra(EXTRA_GIFT_MINIMUM_REPEAT, 1).coerceIn(1, 999),
        giftAlertTemplate = intent.getStringExtra(EXTRA_GIFT_TEMPLATE).orEmpty()
    )

    private fun publishLiveComment(comment: LiveComment) {
        val timestamp = comment.receivedAt
        runCatching { liveChatHistoryStore.add(comment) }
        sendBroadcast(
            Intent(ACTION_COMMENT)
                .setPackage(packageName)
                .putExtra(EXTRA_COMMENT_ID, comment.id)
                .putExtra(EXTRA_COMMENT_USERNAME, comment.displayName)
                .putExtra(EXTRA_COMMENT_TEXT, comment.text)
                .putExtra(EXTRA_COMMENT_AVATAR, comment.avatarUrl.orEmpty())
                .putExtra(EXTRA_REPLY_TO, comment.replyToDisplayName.orEmpty())
                .putExtra(EXTRA_COMMENT_TIME, timestamp)
        )
        localBridge?.publish(
            "live_comment",
            JSONObject()
                .put("id", comment.id)
                .put("username", comment.displayName)
                .put("comment", comment.text)
                .put("reply_to", comment.replyToDisplayName.orEmpty())
                .put("received_at", timestamp)
        )
    }

    private fun sendChatHistory() {
        val history = JSONArray()
        runCatching { liveChatHistoryStore.getAll() }.getOrDefault(emptyList()).forEach { comment ->
            history.put(
                JSONObject()
                    .put("id", comment.id)
                    .put("username", comment.displayName)
                    .put("message", comment.text)
                    .put("avatar", comment.avatarUrl.orEmpty())
                    .put("reply_to", comment.replyToDisplayName.orEmpty())
                    .put("received_at", comment.receivedAt)
            )
        }
        sendBroadcast(
            Intent(ACTION_CHAT_HISTORY).setPackage(packageName)
                .putExtra(EXTRA_CHAT_HISTORY, history.toString())
        )
    }

    private fun acquireLiveWakeLock() {
        if (liveWakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        liveWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TikLBot:LiveConnection"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLiveWakeLock() {
        liveWakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        liveWakeLock = null
    }

    private fun publishLiveStatsIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastStatsPublishAt < 500L) return
        lastStatsPublishAt = now
        val stats = pipeline?.stats() ?: return
        localBridge?.publish(
            "live_stats",
            JSONObject()
                .put("comments_received", stats.commentsReceived)
                .put("comments_filtered", stats.commentsFiltered)
                .put("comments_queued", stats.commentsQueued)
                .put("events_received", stats.eventsReceived)
                .put("events_queued", stats.eventsQueued)
                .put("speech_queue_size", stats.speechQueueSize)
        )
    }

    private fun updateStatus(status: String) {
        val isConnectionState = status.contains("conect", ignoreCase = true) ||
            status.contains("LIVE encontrado", ignoreCase = true) ||
            status.contains("LIVE público", ignoreCase = true) ||
            status.contains("TikTok cerró", ignoreCase = true) ||
            status.contains("desconect", ignoreCase = true) ||
            status.contains("no se pudo", ignoreCase = true) ||
            status.contains("error", ignoreCase = true) ||
            status == "Detenido"
        if (activeSettings != null && !isConnectionState) return
        lastKnownStatus = status
        getSharedPreferences("tiklbot_preferences", MODE_PRIVATE).edit()
            .putString("reader_status", status).apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_MUTED, voiceMuted)
        )
        localBridge?.publish("status", JSONObject().put("value", status))
    }

    override fun onDestroy() {
        connector?.disconnect()
        connector = null
        speechQueue?.close()
        speechQueue = null
        pipeline = null
        activeSettings = null
        stopLocalBridge()
        releaseLiveWakeLock()
        liveChatHistoryStore.close()
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
