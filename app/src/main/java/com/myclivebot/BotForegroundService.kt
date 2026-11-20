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
import com.myclivebot.core.CommentHandlingResult
import com.myclivebot.core.ChatCommandParser
import com.myclivebot.core.SelectableSpeechEngine
import com.myclivebot.core.TikFinityVoiceCatalog
import com.myclivebot.core.EdgeTtsVoiceCatalog
import com.myclivebot.core.TikTokTtsVoiceCatalog
import com.myclivebot.core.LocalBridgeServer
import com.myclivebot.core.LiveComment
import com.myclivebot.core.DjMusicController
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong

class BotForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.myclivebot.START"
        const val ACTION_STOP = "com.myclivebot.STOP"
        const val ACTION_TEST_VOICE = "com.myclivebot.TEST_VOICE"
        const val ACTION_UPDATE_SETTINGS = "com.myclivebot.UPDATE_SETTINGS"
        const val ACTION_SKIP_COMMENT = "com.myclivebot.SKIP_COMMENT"
        const val ACTION_COMMENT_SKIPPED = "com.myclivebot.COMMENT_SKIPPED"
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
        const val EXTRA_COMMENT_IS_MODERATOR = "comment_is_moderator"
        const val EXTRA_COMMENT_IS_FOLLOWER = "comment_is_follower"
        const val EXTRA_STATUS = "reader_status"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_READ_USERNAME = "read_username"
        const val EXTRA_USE_PROFILE_USERNAME = "use_profile_username"
        const val EXTRA_BLOCKED_WORDS = "blocked_words"
        const val EXTRA_IGNORED_USERS = "ignored_users"
        const val EXTRA_MAX_QUEUE = "max_queue"
        const val EXTRA_IGNORE_EMOJIS = "ignore_emojis"
        const val EXTRA_IGNORE_SYMBOLS = "ignore_symbols"
        const val EXTRA_ONLY_USERNAME = "only_username"
        const val EXTRA_VOLUME = "speech_volume"
        const val EXTRA_SPEED = "speech_speed"
        const val EXTRA_VOICE_ID = "voice_id"
        const val EXTRA_SPEECH_PROVIDER = "speech_provider"
        const val EXTRA_API_VOICE_ID = "api_voice_id"
        const val EXTRA_API_RANDOM_VOICE = "api_random_voice"
        const val EXTRA_TIKFINITY_CHANNEL_ID = "tikfinity_channel_id"
        const val EXTRA_EDGE_VOICE_ID = "edge_voice_id"
        const val EXTRA_TIKTOK_VOICE_ID = "tiktok_voice_id"
        const val EXTRA_FOLLOWER_ONLY = "follower_only_chat"
        const val EXTRA_READ_FOLLOWS = "read_follows"
        const val EXTRA_READ_GIFTS = "read_gifts"
        const val EXTRA_READ_SHARES = "read_shares"
        const val EXTRA_SHARE_FOLLOWER_ONLY = "share_follower_only"
        const val EXTRA_READ_JOINS = "read_joins"
        const val EXTRA_JOIN_FOLLOWER_ONLY = "join_follower_only"
        const val EXTRA_READ_LIKES = "read_likes"
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
    private var piper: SelectableSpeechEngine? = null
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
    private var isolatedVoiceTests = 0
    private val liveSessionGeneration = AtomicLong(0L)
    private val commentSideEffectLock = Any()
    private val processedCommentSideEffects = LinkedHashSet<String>()

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
                liveSessionGeneration.incrementAndGet()
                connector?.disconnect()
                connector = null
                releaseLiveWakeLock()
                pipeline?.close()
                pipeline = null
                activeSettings = null
                updateStatus("Detenido")
                speechQueue?.close()
                speechQueue = null
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    runCatching { piper?.stop() }
                    runCatching { liveChatHistoryStore.clear() }
                }
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
                if (settings.speechProvider == SelectableSpeechEngine.PROVIDER_PIPER && !PiperSpeechEngine.isVoiceInstalled(this, settings.voiceId)) {
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
                if (activeSettings == null && isolatedVoiceTests == 0) stopSelf(startId)
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
                    if (next.speechProvider == SelectableSpeechEngine.PROVIDER_PIPER && !PiperSpeechEngine.isVoiceInstalled(this, next.voiceId)) {
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
                val provider = intent.getStringExtra(EXTRA_SPEECH_PROVIDER) ?: SelectableSpeechEngine.PROVIDER_PIPER
                val voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID
                val apiVoiceId = intent.getStringExtra(EXTRA_API_VOICE_ID) ?: TikFinityVoiceCatalog.DEFAULT_VOICE_ID
                val apiRandom = intent.getBooleanExtra(EXTRA_API_RANDOM_VOICE, false)
                val channelId = intent.getStringExtra(EXTRA_TIKFINITY_CHANNEL_ID) ?: "0"
                val edgeVoiceId = intent.getStringExtra(EXTRA_EDGE_VOICE_ID) ?: EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID
                val tikTokVoiceId = intent.getStringExtra(EXTRA_TIKTOK_VOICE_ID) ?: TikTokTtsVoiceCatalog.DEFAULT_VOICE_ID
                val volume = intent.getFloatExtra(EXTRA_VOLUME, 1.0f).coerceIn(0f, 1f)
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.65f, 1.5f)
                if (provider != SelectableSpeechEngine.PROVIDER_PIPER) {
                    val testSettings = (activeSettings ?: ReaderSettings()).copy(
                        speechProvider = provider,
                        apiVoiceId = apiVoiceId,
                        apiRandomVoice = apiRandom,
                        tikFinityChannelId = channelId,
                        edgeVoiceId = edgeVoiceId,
                        tikTokVoiceId = tikTokVoiceId,
                        volume = volume,
                        speed = speed
                    )
                    val testText = when (provider) {
                        SelectableSpeechEngine.PROVIDER_EDGE -> "Hola, esta es una prueba de voz de Edge TTS."
                        SelectableSpeechEngine.PROVIDER_TIKTOK -> "Hola, esta es una prueba de voz de TikTok TTS."
                        else -> "Hola, esta es una prueba de voz de TikFinity."
                    }
                    if (activeSettings != null) {
                        // All provider tests are enqueued in the existing LIVE FIFO queue.
                        piper?.update(testSettings)
                        activeSettings = testSettings
                        pipeline?.updateSettings(testSettings)
                        speechQueue?.enqueue(testText, priority = true)
                    } else {
                        launchIsolatedVoiceTest {
                            val testEngine = SelectableSpeechEngine(this@BotForegroundService, testSettings, ::reportSpeechError)
                            try {
                                testEngine.speak(testText)
                            } catch (error: Exception) {
                                updateStatus("Error de voz API: ${error.message ?: "no se pudo generar el audio"}")
                            } finally {
                                testEngine.release()
                            }
                        }
                    }
                } else if (!PiperSpeechEngine.isVoiceInstalled(this, voiceId)) {
                    updateStatus("Descarga primero la voz seleccionada")
                } else {
                    val testText = PiperVoiceCatalog.find(voiceId).testPhrase
                    val currentSettings = activeSettings
                    if (currentSettings != null) {
                        val testSettings = currentSettings.copy(
                            speechProvider = SelectableSpeechEngine.PROVIDER_PIPER,
                            voiceId = voiceId,
                            randomVoices = false,
                            volume = volume,
                            speed = speed
                        )
                        piper?.update(testSettings)
                        activeSettings = testSettings
                        pipeline?.updateSettings(testSettings)
                        speechQueue?.enqueue(testText, priority = true)
                    } else {
                        launchIsolatedVoiceTest {
                            val testEngine = PiperSpeechEngine(this@BotForegroundService, voiceId, volume, speed)
                            try {
                                testEngine.speak(testText)
                            } finally {
                                testEngine.release()
                            }
                        }
                    }
                }
                if (activeSettings == null && isolatedVoiceTests == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }

            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun launchIsolatedVoiceTest(block: suspend () -> Unit) {
        isolatedVoiceTests++
        serviceScope.launch(Dispatchers.IO) {
            try {
                block()
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    isolatedVoiceTests = (isolatedVoiceTests - 1).coerceAtLeast(0)
                    if (activeSettings == null && isolatedVoiceTests == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun startReader(username: String, settings: ReaderSettings) {
        val sessionToken = liveSessionGeneration.incrementAndGet()
        synchronized(commentSideEffectLock) { processedCommentSideEffects.clear() }
        connector?.disconnect()
        connector = null
        releaseLiveWakeLock()
        pipeline?.close()
        pipeline = null
        speechQueue?.close()
        speechQueue = null
        activeSettings = settings
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
                if (liveSessionGeneration.get() != sessionToken) {
                    CommentHandlingResult.RETRY
                } else {
                    // Keep DJ command handling ahead of the speech pipeline as before.
                    // Publish history/UI only after the comment has had first chance to
                    // enter the speech queue, and never repeat either side effect on retry.
                    val firstDelivery = claimCommentSideEffects(comment, sessionToken)
                    if (firstDelivery && liveSessionGeneration.get() == sessionToken) {
                        runCatching { DjMusicController.handleLiveComment(comment) }
                    }
                    val outcome = try {
                        livePipeline.onComment(comment)
                    } finally {
                        if (firstDelivery && liveSessionGeneration.get() == sessionToken) {
                            runCatching { publishLiveComment(comment, sessionToken) }
                        }
                    }
                    if (liveSessionGeneration.get() == sessionToken) {
                        runCatching { publishLiveStatsIfDue() }
                    }
                    outcome
                }
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
            // Drain previous-session history writes and clear the database before
            // allowing the new LIVE session to publish any comments.
            runCatching { liveChatHistoryStore.clear() }
            if (liveSessionGeneration.get() == sessionToken) {
                runCatching { liveConnector.connect(username) }
                    .onFailure { updateStatus("No se pudo conectar: ${it.message ?: "error desconocido"}") }
            }
        }
    }

    private fun ensureSpeechQueue(capacity: Int, settings: ReaderSettings) {
        if (piper == null) {
            piper = SelectableSpeechEngine(this, settings, ::reportSpeechError)
        } else {
            piper?.update(settings)
        }
        activeVoiceId = settings.voiceId
        activeVolume = settings.volume
        piper?.setMuted(voiceMuted)
        activeSpeed = settings.speed
        if (speechQueue == null) {
            speechQueue = SpeechQueueWorker(
                piper!!,
                serviceScope,
                capacity = capacity,
                onCommentQueueFull = { rejectedCount ->
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        updateStatus("La cola de comentarios alcanzó su límite seguro. Se conservaron los mensajes ya encolados; no se aceptaron nuevos comentarios hasta liberar espacio (rechazados: $rejectedCount).")
                    }
                }
            )
        }
    }

    private fun readSettings(intent: Intent) = ReaderSettings(
        readUsername = intent.getBooleanExtra(EXTRA_READ_USERNAME, true),
        useProfileUsername = intent.getBooleanExtra(EXTRA_USE_PROFILE_USERNAME, false),
        blockedWords = intent.getStringArrayListExtra(EXTRA_BLOCKED_WORDS)?.toSet().orEmpty(),
        ignoredUsers = intent.getStringArrayListExtra(EXTRA_IGNORED_USERS)?.toSet().orEmpty(),
        maxQueueSize = intent.getIntExtra(EXTRA_MAX_QUEUE, 30).coerceIn(5, 100),
        ignoreEmojis = intent.getBooleanExtra(EXTRA_IGNORE_EMOJIS, true),
        ignoreSymbols = intent.getBooleanExtra(EXTRA_IGNORE_SYMBOLS, false),
        onlyUsername = intent.getBooleanExtra(EXTRA_ONLY_USERNAME, false),
        volume = intent.getFloatExtra(EXTRA_VOLUME, 1.0f).coerceIn(0f, 1f),
        speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.65f, 1.5f),
        voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID,
        speechProvider = intent.getStringExtra(EXTRA_SPEECH_PROVIDER) ?: SelectableSpeechEngine.PROVIDER_PIPER,
        apiVoiceId = intent.getStringExtra(EXTRA_API_VOICE_ID) ?: TikFinityVoiceCatalog.DEFAULT_VOICE_ID,
        apiRandomVoice = intent.getBooleanExtra(EXTRA_API_RANDOM_VOICE, false),
        tikFinityChannelId = intent.getStringExtra(EXTRA_TIKFINITY_CHANNEL_ID) ?: "0",
        edgeVoiceId = intent.getStringExtra(EXTRA_EDGE_VOICE_ID) ?: EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID,
        tikTokVoiceId = intent.getStringExtra(EXTRA_TIKTOK_VOICE_ID) ?: TikTokTtsVoiceCatalog.DEFAULT_VOICE_ID,
        followerOnlyChat = intent.getBooleanExtra(EXTRA_FOLLOWER_ONLY, false),
        readFollows = intent.getBooleanExtra(EXTRA_READ_FOLLOWS, true),
        readGifts = intent.getBooleanExtra(EXTRA_READ_GIFTS, true),
        readShares = intent.getBooleanExtra(EXTRA_READ_SHARES, true),
        shareFollowerOnly = intent.getBooleanExtra(EXTRA_SHARE_FOLLOWER_ONLY, false),
        readJoins = intent.getBooleanExtra(EXTRA_READ_JOINS, true),
        joinFollowerOnly = intent.getBooleanExtra(EXTRA_JOIN_FOLLOWER_ONLY, false),
        readLikes = intent.getBooleanExtra(EXTRA_READ_LIKES, true),
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

    private fun claimCommentSideEffects(comment: LiveComment, sessionToken: Long): Boolean =
        synchronized(commentSideEffectLock) {
            if (liveSessionGeneration.get() != sessionToken || comment.id.isBlank()) {
                return@synchronized false
            }
            if (!processedCommentSideEffects.add(comment.id)) return@synchronized false
            while (processedCommentSideEffects.size > 10_000) {
                val iterator = processedCommentSideEffects.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                } else break
            }
            true
        }

    private fun publishLiveComment(comment: LiveComment, sessionToken: Long) {
        val timestamp = comment.receivedAt
        if (liveSessionGeneration.get() == sessionToken) {
            runCatching { liveChatHistoryStore.addAsync(comment) }
        }
        sendBroadcast(
            Intent(ACTION_COMMENT)
                .setPackage(packageName)
                .putExtra(EXTRA_COMMENT_ID, comment.id)
                .putExtra(EXTRA_COMMENT_USERNAME, comment.displayName)
                .putExtra(EXTRA_COMMENT_TEXT, comment.text)
                .putExtra(EXTRA_COMMENT_AVATAR, comment.avatarUrl.orEmpty())
                .putExtra(EXTRA_REPLY_TO, comment.replyToDisplayName.orEmpty())
                .putExtra(EXTRA_COMMENT_IS_MODERATOR, comment.isModerator)
                .putExtra(EXTRA_COMMENT_IS_FOLLOWER, comment.isFollower == true)
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

    private fun reportSpeechError(error: Throwable) {
        val message = (error.message ?: "falló la generación del audio")
            .replace("no se pudo", "falló", ignoreCase = true)
        val providerName = when (activeSettings?.speechProvider) {
            SelectableSpeechEngine.PROVIDER_EDGE -> "Edge-TTS"
            SelectableSpeechEngine.PROVIDER_TIKTOK -> "TikTok TTS"
            else -> "TikFinity API"
        }
        val status = if (activeSettings != null && lastKnownStatus.contains("conect", ignoreCase = true)) {
            "Conectado · Error de $providerName: $message"
        } else {
            "Error de $providerName: $message"
        }
        updateStatus(status)
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
                .put("comment_queue_wait_last_ms", stats.commentQueueWaitLastMs)
                .put("comment_queue_wait_max_ms", stats.commentQueueWaitMaxMs)
                .put("comments_started", stats.commentsStarted)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Clear persisted visual history off the main thread without changing
        // the LIVE connector or stopping an active broadcast.
        serviceScope.launch(Dispatchers.IO) {
            runCatching { liveChatHistoryStore.clear() }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        liveSessionGeneration.incrementAndGet()
        connector?.disconnect()
        connector = null
        pipeline?.close()
        pipeline = null
        speechQueue?.close()
        speechQueue = null
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
        return builder.setContentTitle("Tik Tok Reader")
            .setContentText(status.take(120))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
