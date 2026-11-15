package com.myclivebot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.ImageView
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.myclivebot.core.ChatCommandParser
import com.myclivebot.core.PiperSpeechEngine
import com.myclivebot.core.PiperVoiceCatalog
import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.TikFinityVoiceCatalog
import com.myclivebot.core.EdgeTtsVoiceCatalog
import com.myclivebot.core.TikTokTtsVoiceCatalog
import com.myclivebot.core.TikTokProfileLookup
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File

private val Night = Color(0xFF090B18)
private val Panel = Color(0xCC1B2036)
private val Violet = Color(0xFF9B86FF)
private val Cyan = Color(0xFF69DED2)
private val Muted = Color(0xFFB8BBD0)

private data class LiveCommentUi(val id: String, val username: String, val message: String, val avatarUrl: String?, val replyToDisplayName: String?, val receivedAt: Long, val skipped: Boolean = false)
private data class CustomCommandEntry(val title: String, val trigger: String, val response: String)

class MainActivity : ComponentActivity() {
    private val readerStatus = mutableStateOf("Detenido")
    private val liveComments = mutableStateListOf<LiveCommentUi>()
    private val liveCommentIds = HashSet<String>()
    private val voiceMutedFromService = mutableStateOf(false)
    private var statusReceiverRegistered = false
    private var liveChatGeneration = 0L
    private var clearHistoryOnFirstStart = false
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BotForegroundService.ACTION_STATUS -> {
                    readerStatus.value = intent.getStringExtra(BotForegroundService.EXTRA_STATUS) ?: return
                    voiceMutedFromService.value = intent.getBooleanExtra(BotForegroundService.EXTRA_MUTED, voiceMutedFromService.value)
                }
                BotForegroundService.ACTION_COMMENT -> {
                    val message = intent.getStringExtra(BotForegroundService.EXTRA_COMMENT_TEXT).orEmpty()
                    val commentId = intent.getStringExtra(BotForegroundService.EXTRA_COMMENT_ID).orEmpty()
                    if (message.isNotBlank() && commentId.isNotBlank()) {
                        insertLiveComment(
                            LiveCommentUi(
                                id = commentId,
                                username = intent.getStringExtra(BotForegroundService.EXTRA_COMMENT_USERNAME).orEmpty(),
                                message = message,
                                avatarUrl = intent.getStringExtra(BotForegroundService.EXTRA_COMMENT_AVATAR),
                                replyToDisplayName = intent.getStringExtra(BotForegroundService.EXTRA_REPLY_TO)?.takeIf { it.isNotBlank() },
                                receivedAt = intent.getLongExtra(BotForegroundService.EXTRA_COMMENT_TIME, System.currentTimeMillis())
                            )
                        )
                    }
                }
                BotForegroundService.ACTION_COMMENT_SKIPPED -> {
                    val commentId = intent.getStringExtra(BotForegroundService.EXTRA_COMMENT_ID).orEmpty()
                    val index = liveComments.indexOfFirst { it.id == commentId }
                    if (index >= 0) liveComments[index] = liveComments[index].copy(skipped = true)
                }
            }
        }
    }
    private fun clearLiveChat(clearPersistentHistory: Boolean = true) {
        // Invalidate history reads already in flight so they cannot restore
        // old messages after a user-initiated clear.
        liveChatGeneration++
        liveComments.clear()
        liveCommentIds.clear()
        if (clearPersistentHistory) {
            lifecycleScope.launch(Dispatchers.IO) {
                val store = LiveChatHistoryStore(this@MainActivity)
                try {
                    store.clear()
                } finally {
                    store.close()
                }
            }
        }
    }

    private fun insertLiveComment(comment: LiveCommentUi) {
        if (!liveCommentIds.add(comment.id)) return
        var low = 0
        var high = liveComments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (liveComments[middle].receivedAt <= comment.receivedAt) low = middle + 1
            else high = middle
        }
        liveComments.add(low, comment)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // A cold launch clears old session history asynchronously in onStart.
        // The foreground LIVE service itself is never stopped or modified.
        clearHistoryOnFirstStart = savedInstanceState == null
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            var showBrandSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(850)
                showBrandSplash = false
            }
            Box(Modifier.fillMaxSize()) {
            BotApp(
                externalStatus = readerStatus.value,
                liveComments = liveComments,
                externalMuted = voiceMutedFromService.value,
                onStart = { username, settings ->
                    liveComments.clear()
                    liveCommentIds.clear()
                    val intent = Intent(this@MainActivity, BotForegroundService::class.java)
                        .setAction(BotForegroundService.ACTION_START)
                        .putExtra(BotForegroundService.EXTRA_USERNAME, username)
                        .putExtra(BotForegroundService.EXTRA_READ_USERNAME, settings.readUsername)
                        .putStringArrayListExtra(BotForegroundService.EXTRA_BLOCKED_WORDS, ArrayList(settings.blockedWords))
                        .putStringArrayListExtra(BotForegroundService.EXTRA_IGNORED_USERS, ArrayList(settings.ignoredUsers))
                        .putExtra(BotForegroundService.EXTRA_MAX_QUEUE, settings.maxQueueSize)
                        .putExtra(BotForegroundService.EXTRA_IGNORE_EMOJIS, settings.ignoreEmojis)
                        .putExtra(BotForegroundService.EXTRA_IGNORE_SYMBOLS, settings.ignoreSymbols)
                        .putExtra(BotForegroundService.EXTRA_ONLY_USERNAME, settings.onlyUsername)
                        .putExtra(BotForegroundService.EXTRA_VOLUME, settings.volume)
                        .putExtra(BotForegroundService.EXTRA_SPEED, settings.speed)
                        .putExtra(BotForegroundService.EXTRA_VOICE_ID, settings.voiceId)
                        .putExtra(BotForegroundService.EXTRA_SPEECH_PROVIDER, settings.speechProvider)
                        .putExtra(BotForegroundService.EXTRA_API_VOICE_ID, settings.apiVoiceId)
                        .putExtra(BotForegroundService.EXTRA_API_RANDOM_VOICE, settings.apiRandomVoice)
                        .putExtra(BotForegroundService.EXTRA_TIKFINITY_CHANNEL_ID, settings.tikFinityChannelId)
                        .putExtra(BotForegroundService.EXTRA_EDGE_VOICE_ID, settings.edgeVoiceId)
                        .putExtra(BotForegroundService.EXTRA_TIKTOK_VOICE_ID, settings.tikTokVoiceId)
                        .putExtra(BotForegroundService.EXTRA_FOLLOWER_ONLY, settings.followerOnlyChat)
                        .putExtra(BotForegroundService.EXTRA_READ_FOLLOWS, settings.readFollows)
                        .putExtra(BotForegroundService.EXTRA_READ_GIFTS, settings.readGifts)
                        .putExtra(BotForegroundService.EXTRA_READ_SHARES, settings.readShares)
                        .putExtra(BotForegroundService.EXTRA_SHARE_FOLLOWER_ONLY, settings.shareFollowerOnly)
                        .putExtra(BotForegroundService.EXTRA_READ_JOINS, settings.readJoins)
                        .putExtra(BotForegroundService.EXTRA_JOIN_FOLLOWER_ONLY, settings.joinFollowerOnly)
                        .putExtra(BotForegroundService.EXTRA_READ_LIKES, settings.readLikes)
                        .putExtra(BotForegroundService.EXTRA_RANDOM_VOICES, settings.randomVoices)
                        .putExtra(BotForegroundService.EXTRA_COMMANDS_ENABLED, settings.customCommandsEnabled)
                        .putStringArrayListExtra(BotForegroundService.EXTRA_CUSTOM_COMMANDS, ArrayList(settings.customCommands.map { "${it.key}=${it.value}" }))
                        .putExtra(BotForegroundService.EXTRA_WELCOME_ENABLED, settings.welcomeEnabled)
                        .putExtra(BotForegroundService.EXTRA_WELCOME_TEMPLATE, settings.welcomeTemplate)
                        .putExtra(BotForegroundService.EXTRA_GIFT_MINIMUM_REPEAT, settings.giftMinimumRepeatCount)
                        .putExtra(BotForegroundService.EXTRA_GIFT_TEMPLATE, settings.giftAlertTemplate)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                },
                onUpdateSettings = { settings ->
                    val intent = Intent(this@MainActivity, BotForegroundService::class.java)
                        .setAction(BotForegroundService.ACTION_UPDATE_SETTINGS)
                        .putExtra(BotForegroundService.EXTRA_READ_USERNAME, settings.readUsername)
                        .putStringArrayListExtra(BotForegroundService.EXTRA_BLOCKED_WORDS, ArrayList(settings.blockedWords))
                        .putStringArrayListExtra(BotForegroundService.EXTRA_IGNORED_USERS, ArrayList(settings.ignoredUsers))
                        .putExtra(BotForegroundService.EXTRA_MAX_QUEUE, settings.maxQueueSize)
                        .putExtra(BotForegroundService.EXTRA_IGNORE_EMOJIS, settings.ignoreEmojis)
                        .putExtra(BotForegroundService.EXTRA_IGNORE_SYMBOLS, settings.ignoreSymbols)
                        .putExtra(BotForegroundService.EXTRA_ONLY_USERNAME, settings.onlyUsername)
                        .putExtra(BotForegroundService.EXTRA_VOLUME, settings.volume)
                        .putExtra(BotForegroundService.EXTRA_SPEED, settings.speed)
                        .putExtra(BotForegroundService.EXTRA_VOICE_ID, settings.voiceId)
                        .putExtra(BotForegroundService.EXTRA_SPEECH_PROVIDER, settings.speechProvider)
                        .putExtra(BotForegroundService.EXTRA_API_VOICE_ID, settings.apiVoiceId)
                        .putExtra(BotForegroundService.EXTRA_API_RANDOM_VOICE, settings.apiRandomVoice)
                        .putExtra(BotForegroundService.EXTRA_TIKFINITY_CHANNEL_ID, settings.tikFinityChannelId)
                        .putExtra(BotForegroundService.EXTRA_EDGE_VOICE_ID, settings.edgeVoiceId)
                        .putExtra(BotForegroundService.EXTRA_TIKTOK_VOICE_ID, settings.tikTokVoiceId)
                        .putExtra(BotForegroundService.EXTRA_FOLLOWER_ONLY, settings.followerOnlyChat)
                        .putExtra(BotForegroundService.EXTRA_READ_FOLLOWS, settings.readFollows)
                        .putExtra(BotForegroundService.EXTRA_READ_GIFTS, settings.readGifts)
                        .putExtra(BotForegroundService.EXTRA_READ_SHARES, settings.readShares)
                        .putExtra(BotForegroundService.EXTRA_SHARE_FOLLOWER_ONLY, settings.shareFollowerOnly)
                        .putExtra(BotForegroundService.EXTRA_READ_JOINS, settings.readJoins)
                        .putExtra(BotForegroundService.EXTRA_JOIN_FOLLOWER_ONLY, settings.joinFollowerOnly)
                        .putExtra(BotForegroundService.EXTRA_READ_LIKES, settings.readLikes)
                        .putExtra(BotForegroundService.EXTRA_RANDOM_VOICES, settings.randomVoices)
                        .putExtra(BotForegroundService.EXTRA_COMMANDS_ENABLED, settings.customCommandsEnabled)
                        .putStringArrayListExtra(BotForegroundService.EXTRA_CUSTOM_COMMANDS, ArrayList(settings.customCommands.map { "${it.key}=${it.value}" }))
                        .putExtra(BotForegroundService.EXTRA_WELCOME_ENABLED, settings.welcomeEnabled)
                        .putExtra(BotForegroundService.EXTRA_WELCOME_TEMPLATE, settings.welcomeTemplate)
                        .putExtra(BotForegroundService.EXTRA_GIFT_MINIMUM_REPEAT, settings.giftMinimumRepeatCount)
                        .putExtra(BotForegroundService.EXTRA_GIFT_TEMPLATE, settings.giftAlertTemplate)
                    startService(intent)
                },
                onStop = {
                    readerStatus.value = "Detenido"
                    clearLiveChat(clearPersistentHistory = false)
                    startService(Intent(this@MainActivity, BotForegroundService::class.java).setAction(BotForegroundService.ACTION_STOP))
                },
                onClearChat = { clearLiveChat() },
                onSkipComment = { commentId ->
                    startService(
                        Intent(this@MainActivity, BotForegroundService::class.java)
                            .setAction(BotForegroundService.ACTION_SKIP_COMMENT)
                            .putExtra(BotForegroundService.EXTRA_COMMENT_ID, commentId)
                    )
                },
                onSetMuted = { muted ->
                    startService(Intent(this@MainActivity, BotForegroundService::class.java)
                        .setAction(BotForegroundService.ACTION_SET_MUTE)
                        .putExtra(BotForegroundService.EXTRA_MUTED, muted))
                },
                onTestVoice = { settings ->
                    val intent = Intent(this@MainActivity, BotForegroundService::class.java)
                        .setAction(BotForegroundService.ACTION_TEST_VOICE)
                        .putExtra(BotForegroundService.EXTRA_VOICE_ID, settings.voiceId)
                        .putExtra(BotForegroundService.EXTRA_SPEECH_PROVIDER, settings.speechProvider)
                        .putExtra(BotForegroundService.EXTRA_API_VOICE_ID, settings.apiVoiceId)
                        .putExtra(BotForegroundService.EXTRA_API_RANDOM_VOICE, settings.apiRandomVoice)
                        .putExtra(BotForegroundService.EXTRA_TIKFINITY_CHANNEL_ID, settings.tikFinityChannelId)
                        .putExtra(BotForegroundService.EXTRA_EDGE_VOICE_ID, settings.edgeVoiceId)
                        .putExtra(BotForegroundService.EXTRA_TIKTOK_VOICE_ID, settings.tikTokVoiceId)
                        .putExtra(BotForegroundService.EXTRA_VOLUME, settings.volume)
                        .putExtra(BotForegroundService.EXTRA_SPEED, settings.speed)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                },
                onDownloadVoice = { voiceId, onProgress, callback ->
                    lifecycleScope.launch {
                        runCatching {
                            PiperSpeechEngine.downloadVoice(this@MainActivity, voiceId) { percent, stage ->
                                runOnUiThread { onProgress(percent, stage) }
                            }
                        }
                            .onSuccess { runOnUiThread { callback(null) } }
                            .onFailure { error ->
                                runOnUiThread { callback(error.message ?: "Error descargando la voz") }
                            }
                    }
                },
                onDeleteVoice = { voiceId, callback ->
                    lifecycleScope.launch {
                        runCatching { PiperSpeechEngine.deleteVoice(this@MainActivity, voiceId) }
                            .onSuccess { runOnUiThread { callback(null) } }
                            .onFailure { error ->
                                runOnUiThread { callback(error.message ?: "No se pudo eliminar la voz") }
                            }
                    }
                }
            )
            if (showBrandSplash) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Night),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AndroidView(
                            factory = { context ->
                                ImageView(context).apply {
                                    setImageResource(R.mipmap.ic_launcher)
                                    contentDescription = "Tik Tok Reader"
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                }
                            },
                            modifier = Modifier.size(92.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "TikTok Live Reader",
                            color = Color.White,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "LECTOR INTELIGENTE DE DIRECTOS",
                            color = Cyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val preferences = getSharedPreferences("tiklbot_preferences", Context.MODE_PRIVATE)
        readerStatus.value = preferences.getString("reader_status", readerStatus.value).orEmpty()
        voiceMutedFromService.value = preferences.getBoolean("voice_muted", false)
        if (!statusReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BotForegroundService.ACTION_STATUS)
                addAction(BotForegroundService.ACTION_COMMENT)
                addAction(BotForegroundService.ACTION_COMMENT_SKIPPED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            statusReceiverRegistered = true
        }
        val generationAtLoad = liveChatGeneration
        val shouldClearOldHistory = clearHistoryOnFirstStart
        clearHistoryOnFirstStart = false
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                val store = LiveChatHistoryStore(this@MainActivity)
                try {
                    if (shouldClearOldHistory) store.clear()
                    store.getAll()
                } finally {
                    store.close()
                }
            }
            // STOP or manual clear can happen while the database read is active.
            if (generationAtLoad != liveChatGeneration) return@launch
            history.forEach { comment ->
                insertLiveComment(
                    LiveCommentUi(
                        id = comment.id,
                        username = comment.displayName,
                        message = comment.text,
                        avatarUrl = comment.avatarUrl,
                        replyToDisplayName = comment.replyToDisplayName,
                        receivedAt = comment.receivedAt
                    )
                )
            }
        }
        startService(Intent(this, BotForegroundService::class.java).setAction(BotForegroundService.ACTION_REQUEST_STATUS))
    }

    override fun onDestroy() {
        // Avoid synchronous database work during Activity destruction.
        // Task removal and the next cold launch clear the stored session history.
        super.onDestroy()
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BotApp(
    externalStatus: String,
    liveComments: SnapshotStateList<LiveCommentUi>,
    externalMuted: Boolean,
    onStart: (String, ReaderSettings) -> Unit,
    onStop: () -> Unit,
    onClearChat: () -> Unit,
    onSkipComment: (String) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onUpdateSettings: (ReaderSettings) -> Unit,
    onTestVoice: (ReaderSettings) -> Unit,
    onDownloadVoice: (String, (Int, String) -> Unit, (String?) -> Unit) -> Unit,
    onDeleteVoice: (String, (String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tiklbot_preferences", Context.MODE_PRIVATE) }

    var username by remember { mutableStateOf(prefs.getString("last_username", "").orEmpty()) }
    var recentUsernames by remember {
        mutableStateOf(prefs.getStringSet("recent_usernames", mutableSetOf<String>()).orEmpty().toList().take(8))
    }
    var recentAvatarUrls by remember {
        mutableStateOf(
            prefs.getStringSet("recent_user_avatars", emptySet()).orEmpty().mapNotNull { entry ->
                val parts = entry.split("\t", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].startsWith("https://")) parts[0] to parts[1] else null
            }.toMap()
        )
    }
    var profileAvatarUrl by remember { mutableStateOf<String?>(null) }
    var profileLookupBusy by remember { mutableStateOf(false) }
    val normalizedProfileUsername = username.trim().removePrefix("@").substringBefore("/").trim()
    val cachedProfileAvatar = recentAvatarUrls[normalizedProfileUsername]
    LaunchedEffect(normalizedProfileUsername) {
        profileAvatarUrl = cachedProfileAvatar
        if (normalizedProfileUsername.isBlank()) {
            profileLookupBusy = false
        } else {
            profileLookupBusy = true
            delay(450)
            val fetchedAvatar = TikTokProfileLookup.fetchAvatarUrl(normalizedProfileUsername)
            if (fetchedAvatar != null) {
                profileAvatarUrl = fetchedAvatar
                recentAvatarUrls = recentAvatarUrls + (normalizedProfileUsername to fetchedAvatar)
                prefs.edit().putStringSet(
                    "recent_user_avatars",
                    recentAvatarUrls.map { (name, url) -> "$name\t$url" }.toSet()
                ).apply()
            }
            profileLookupBusy = false
        }
    }

    fun saveRecentProfile(cleanUsername: String, avatarUrl: String? = recentAvatarUrls[cleanUsername]) {
        recentUsernames = (listOf(cleanUsername) + recentUsernames.filterNot { it.equals(cleanUsername, true) }).take(8)
        prefs.edit().putStringSet("recent_usernames", recentUsernames.toSet()).apply()
        if (!avatarUrl.isNullOrBlank()) {
            recentAvatarUrls = recentAvatarUrls + (cleanUsername to avatarUrl)
            prefs.edit().putStringSet(
                "recent_user_avatars",
                recentAvatarUrls.map { (name, url) -> "$name\t$url" }.toSet()
            ).apply()
        }
    }
    var readUsername by remember { mutableStateOf(prefs.getBoolean("read_username", true)) }
    var onlyUsername by remember { mutableStateOf(prefs.getBoolean("only_username", false)) }
    var ignoreEmojis by remember { mutableStateOf(prefs.getBoolean("ignore_emojis", true)) }
    var ignoreSymbols by remember { mutableStateOf(prefs.getBoolean("ignore_symbols", false)) }
    var followerOnlyChat by remember { mutableStateOf(prefs.getBoolean("follower_only_chat", false)) }
    var readFollows by remember { mutableStateOf(prefs.getBoolean("read_follows", true)) }
    var readGifts by remember { mutableStateOf(prefs.getBoolean("read_gifts", true)) }
    var readShares by remember { mutableStateOf(prefs.getBoolean("read_shares", true)) }
    var shareFollowerOnly by remember { mutableStateOf(prefs.getBoolean("share_follower_only", false)) }
    var readJoins by remember { mutableStateOf(prefs.getBoolean("read_joins", true)) }
    var joinFollowerOnly by remember { mutableStateOf(prefs.getBoolean("join_follower_only", false)) }
    var readLikes by remember { mutableStateOf(prefs.getBoolean("read_likes", true)) }
    var randomVoices by remember { mutableStateOf(prefs.getBoolean("random_voices", false)) }
    var customCommandsEnabled by remember { mutableStateOf(prefs.getBoolean("custom_commands_enabled", false)) }
    var customCommandsText by remember {
        mutableStateOf(
            prefs.getString(
                "custom_commands",
                "!redes=Mis redes están en mi perfil.\n!discord=Únete a mi comunidad de Discord.\n!youtube=Encuentra mis videos en YouTube.\n!horario=Consulta mi horario en el perfil."
            ).orEmpty()
        )
    }
    var customCommandEntries by remember {
        mutableStateOf(loadCustomCommandEntries(customCommandsText, prefs))
    }
    var welcomeEnabled by remember { mutableStateOf(prefs.getBoolean("welcome_enabled", false)) }
    var welcomeTemplate by remember { mutableStateOf(prefs.getString("welcome_template", "¡Bienvenido al LIVE, {user}!").orEmpty()) }
    var giftMinimumRepeat by remember { mutableStateOf(prefs.getInt("gift_minimum_repeat", 1).toString()) }
    var giftAlertTemplate by remember { mutableStateOf(prefs.getString("gift_alert_template", "").orEmpty()) }
    var maxQueue by remember { mutableStateOf(prefs.getInt("max_queue", 30).toString()) }
    var blockedWords by remember { mutableStateOf(prefs.getString("blocked_words", "").orEmpty()) }
    var ignoredUsers by remember { mutableStateOf(prefs.getString("ignored_users", "").orEmpty()) }
    var volume by remember { mutableFloatStateOf(prefs.getFloat("volume", 0.90f).coerceIn(0f, 1f)) }
    var voiceMuted by remember { mutableStateOf(prefs.getBoolean("voice_muted", false)) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1.0f).coerceIn(0.65f, 1.5f)) }
    var voiceId by remember {
        mutableStateOf(prefs.getString("voice_id", PiperVoiceCatalog.DEFAULT_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID)
    }
    var speechProvider by remember { mutableStateOf(prefs.getString("speech_provider", "piper") ?: "piper") }
    var apiVoiceId by remember { mutableStateOf(prefs.getString("api_voice_id", "es_mx_002") ?: "es_mx_002") }
    var apiRandomVoice by remember { mutableStateOf(prefs.getBoolean("api_random_voice", false)) }
    var tikFinityChannelId by remember { mutableStateOf(prefs.getString("tikfinity_channel_id", "0") ?: "0") }
    var edgeVoiceId by remember { mutableStateOf(prefs.getString("edge_voice_id", EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID) ?: EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID) }
    var tikTokVoiceId by remember { mutableStateOf(prefs.getString("tiktok_voice_id", TikTokTtsVoiceCatalog.DEFAULT_VOICE_ID) ?: TikTokTtsVoiceCatalog.DEFAULT_VOICE_ID) }
    var apiVoiceMenuExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Detenido") }
    var voiceInstalled by remember(voiceId) { mutableStateOf(PiperSpeechEngine.isVoiceInstalled(context, voiceId)) }
    val selectedProviderReady = speechProvider != "piper" || voiceInstalled
    var downloading by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf("") }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadStage by remember { mutableStateOf("") }
    var showDeleteVoiceDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var appUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var appUpdateStatus by remember { mutableStateOf("Buscando actualizaciones oficiales…") }
    var appUpdateBusy by remember { mutableStateOf(false) }
    var showAppUpdateDialog by remember { mutableStateOf(false) }
    var downloadedUpdatePath by remember { mutableStateOf<String?>(null) }
    val updateScope = rememberCoroutineScope()
    val homeScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()

    fun buildReaderSettings(): ReaderSettings = ReaderSettings(
        readUsername = readUsername,
        blockedWords = splitList(blockedWords).toSet(),
        ignoredUsers = splitList(ignoredUsers).toSet(),
        maxQueueSize = maxQueue.toIntOrNull()?.coerceIn(5, 100) ?: 30,
        ignoreEmojis = ignoreEmojis,
        ignoreSymbols = ignoreSymbols,
        onlyUsername = onlyUsername,
        volume = volume,
        speed = speed,
        voiceId = voiceId,
        speechProvider = speechProvider,
        apiVoiceId = apiVoiceId,
        apiRandomVoice = apiRandomVoice,
        tikFinityChannelId = tikFinityChannelId.trim().ifBlank { "0" },
        edgeVoiceId = edgeVoiceId,
        tikTokVoiceId = tikTokVoiceId,
        followerOnlyChat = followerOnlyChat,
        readFollows = readFollows,
        readGifts = readGifts,
        readShares = readShares,
        shareFollowerOnly = shareFollowerOnly,
        readJoins = readJoins,
        joinFollowerOnly = joinFollowerOnly,
        readLikes = readLikes,
        randomVoices = randomVoices,
        customCommandsEnabled = customCommandsEnabled,
        customCommands = if (customCommandsEnabled) ChatCommandParser.parseConfig(customCommandsText) else emptyMap(),
        welcomeEnabled = welcomeEnabled,
        welcomeTemplate = welcomeTemplate,
        giftMinimumRepeatCount = giftMinimumRepeat.toIntOrNull()?.coerceIn(1, 999) ?: 1,
        giftAlertTemplate = giftAlertTemplate
    )

    LaunchedEffect(Unit) {
        try {
            val latest = withContext(Dispatchers.IO) { AppUpdateManager.checkForUpdate(context) }
            appUpdate = latest
            appUpdateStatus = if (latest == null) "Tu aplicación está actualizada." else "Nueva versión ${latest.versionName} disponible."
            if (latest != null) showAppUpdateDialog = true
        } catch (error: Exception) {
            appUpdate = null
            downloadedUpdatePath = null
            showAppUpdateDialog = false
            appUpdateStatus = "No se pudo consultar GitHub. Comprueba tu conexión e inténtalo de nuevo. Detalle: ${error.message ?: "error desconocido"}"
        }
    }

    fun startAppUpdate() {
        val update = appUpdate ?: return
        updateScope.launch {
            appUpdateBusy = true
            appUpdateStatus = "Descargando y verificando la actualización…"
            try {
                val cached = downloadedUpdatePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                val apk = cached ?: withContext(Dispatchers.IO) {
                    AppUpdateManager.downloadAndVerify(context, update)
                }.also { downloadedUpdatePath = it.absolutePath }
                val installerOpened = AppUpdateManager.installOrOpenPermissionSettings(context, apk)
                appUpdateStatus = if (installerOpened) {
                    "Instalador de Android abierto. Confirma la actualización para conservar tus datos."
                } else {
                    "Autoriza a Tik Tok Reader a instalar aplicaciones y vuelve aquí para pulsar INSTALAR ACTUALIZACIÓN."
                }
                if (installerOpened) showAppUpdateDialog = false
            } catch (error: Exception) {
                appUpdateStatus = "No se pudo actualizar: ${error.message ?: "error desconocido"}"
            } finally {
                appUpdateBusy = false
            }
        }
    }

    fun checkAppUpdateManually() {
        updateScope.launch {
            appUpdateBusy = true
            appUpdateStatus = "Comprobando la versión instalada contra GitHub Releases…"
            try {
                val latest = withContext(Dispatchers.IO) { AppUpdateManager.checkForUpdate(context) }
                appUpdate = latest
                downloadedUpdatePath = null
                appUpdateStatus = if (latest == null) {
                    "No hay una versión más reciente. Tu versionCode está actualizado."
                } else {
                    "Nueva versión ${latest.versionName} (código ${latest.versionCode}) disponible."
                }
                showAppUpdateDialog = latest != null
            } catch (error: Exception) {
                appUpdate = null
                downloadedUpdatePath = null
                showAppUpdateDialog = false
                appUpdateStatus = "No se pudo consultar GitHub. Comprueba tu conexión e inténtalo de nuevo. Detalle: ${error.message ?: "error desconocido"}"
            } finally {
                appUpdateBusy = false
            }
        }
    }

    LaunchedEffect(externalStatus) { status = externalStatus }
    LaunchedEffect(username) { prefs.edit().putString("last_username", username).apply() }
    LaunchedEffect(voiceMuted) { prefs.edit().putBoolean("voice_muted", voiceMuted).apply() }
    LaunchedEffect(externalMuted) { voiceMuted = externalMuted }
    LaunchedEffect(readUsername, onlyUsername, ignoreEmojis, ignoreSymbols, followerOnlyChat, readFollows, readGifts, readShares, shareFollowerOnly, readJoins, joinFollowerOnly, readLikes, randomVoices, maxQueue, blockedWords, ignoredUsers, volume, speed, voiceId, speechProvider, apiVoiceId, apiRandomVoice, tikFinityChannelId, edgeVoiceId, tikTokVoiceId) {
        prefs.edit()
            .putBoolean("follower_only_chat", followerOnlyChat)
            .putBoolean("read_follows", readFollows)
            .putBoolean("read_gifts", readGifts)
            .putBoolean("read_shares", readShares)
            .putBoolean("share_follower_only", shareFollowerOnly)
            .putBoolean("read_joins", readJoins)
            .putBoolean("join_follower_only", joinFollowerOnly)
            .putBoolean("read_likes", readLikes)
            .putBoolean("random_voices", randomVoices)
            .putString("speech_provider", speechProvider)
            .putString("api_voice_id", apiVoiceId)
            .putBoolean("api_random_voice", apiRandomVoice)
            .putString("tikfinity_channel_id", tikFinityChannelId)
            .putString("edge_voice_id", edgeVoiceId)
            .putString("tiktok_voice_id", tikTokVoiceId)
            .apply()
    }

    LaunchedEffect(readUsername, onlyUsername, ignoreEmojis, ignoreSymbols, maxQueue, blockedWords, ignoredUsers, volume, speed, voiceId, speechProvider, apiVoiceId, apiRandomVoice, tikFinityChannelId, edgeVoiceId, tikTokVoiceId) {
        prefs.edit()
            .putBoolean("read_username", readUsername)
            .putBoolean("only_username", onlyUsername)
            .putBoolean("ignore_emojis", ignoreEmojis)
            .putBoolean("ignore_symbols", ignoreSymbols)
            .putInt("max_queue", maxQueue.toIntOrNull()?.coerceIn(5, 100) ?: 30)
            .putString("blocked_words", blockedWords)
            .putString("ignored_users", ignoredUsers)
            .putFloat("volume", volume)
            .putFloat("speed", speed)
            .putString("voice_id", voiceId)
            .putString("edge_voice_id", edgeVoiceId)
            .putString("tiktok_voice_id", tikTokVoiceId)
            .apply()
    }

    LaunchedEffect(customCommandsEnabled, customCommandsText, customCommandEntries, welcomeEnabled, welcomeTemplate, giftMinimumRepeat, giftAlertTemplate) {
        customCommandEntries.forEach { entry ->
            prefs.edit().putString("custom_command_title_${entry.trigger}", entry.title).apply()
        }
        prefs.edit()
            .putBoolean("custom_commands_enabled", customCommandsEnabled)
            .putString("custom_commands", customCommandsText)
            .putBoolean("welcome_enabled", welcomeEnabled)
            .putString("welcome_template", welcomeTemplate)
            .putInt("gift_minimum_repeat", giftMinimumRepeat.toIntOrNull()?.coerceIn(1, 999) ?: 1)
            .putString("gift_alert_template", giftAlertTemplate)
            .apply()
    }

    val selectedVoice = PiperVoiceCatalog.find(voiceId)
    val apiVoiceItems = when (speechProvider) {
        "edge" -> EdgeTtsVoiceCatalog.voices.map { Triple(it.id, it.name, it.description) }
        "tiktok" -> TikTokTtsVoiceCatalog.voices.map { Triple(it.id, it.name, it.description) }
        else -> TikFinityVoiceCatalog.voices.map { Triple(it.id, it.name, it.description) }
    }
    val selectedApiVoiceId = when (speechProvider) { "edge" -> edgeVoiceId; "tiktok" -> tikTokVoiceId; else -> apiVoiceId }
    val selectedApiVoiceName = apiVoiceItems.firstOrNull { it.first == selectedApiVoiceId }?.second ?: apiVoiceItems.first().second
    val connected = status.contains("Conectado", true) && !status.contains("no se pudo", true)
    LaunchedEffect(
        connected, readUsername, onlyUsername, ignoreEmojis, ignoreSymbols, followerOnlyChat,
        readFollows, readGifts, readShares, shareFollowerOnly, readJoins, joinFollowerOnly, readLikes,
        randomVoices, maxQueue, blockedWords, ignoredUsers,
        speed, voiceId, voiceInstalled, speechProvider, apiVoiceId, apiRandomVoice, tikFinityChannelId, edgeVoiceId, tikTokVoiceId, customCommandsEnabled, customCommandsText,
        welcomeEnabled, welcomeTemplate, giftMinimumRepeat, giftAlertTemplate
    ) {
        if (connected && selectedProviderReady) {
            delay(180)
            onUpdateSettings(buildReaderSettings())
        }
    }
    // Volume changes are applied immediately to the active AudioTrack; don't debounce slider input.
    LaunchedEffect(volume, connected, selectedProviderReady) {
        if (connected && selectedProviderReady) onUpdateSettings(buildReaderSettings())
    }
    val pulse by animateFloatAsState(if (connected) 1f else 0.72f, label = "statusPulse")

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Violet, secondary = Cyan, background = Night, surface = Panel,
            onBackground = Color(0xFFF4F1FF), onSurface = Color(0xFFF4F1FF),
            surfaceVariant = Color(0xFF292D45), onSurfaceVariant = Muted
        )
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF17152D), Night, Color(0xFF101D2C))))) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 8.dp).size(190.dp).blur(75.dp).background(Violet.copy(alpha = .22f), RoundedCornerShape(100)))
            Box(Modifier.align(Alignment.BottomStart).padding(bottom = 100.dp, start = 0.dp).size(170.dp).blur(75.dp).background(Cyan.copy(alpha = .13f), RoundedCornerShape(100)))
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = Color(0xFFF4F1FF),
                topBar = {
                    TopAppBar(
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("TIK TOK READER", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall, letterSpacing = 1.1.sp)
                                Text(when (speechProvider) { "tikfinity" -> "TIKFINITY API · CONTROL EN VIVO"; "edge" -> "EDGE-TTS · CONTROL EN VIVO"; "tiktok" -> "TIKTOK TTS · CONTROL EN VIVO"; else -> "PIPER OFFLINE · CONTROL EN VIVO" }, style = MaterialTheme.typography.labelSmall, color = Cyan, letterSpacing = .8.sp)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0x77111629),
                            titleContentColor = Color(0xFFF4F1FF),
                            actionIconContentColor = Cyan
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Color(0xCC111629), contentColor = Color(0xFFF4F1FF)) {
                        NavigationBarItem(
                            selected = !showSettings,
                            onClick = { showSettings = false },
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = showSettings,
                            onClick = { showSettings = true },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Settings") }
                        )
                    }
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(if (showSettings) settingsScrollState else homeScrollState).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!showSettings) {
                    GlassCard {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Violet.copy(alpha = .18f)).border(1.dp, Violet.copy(alpha = .5f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.GraphicEq, null, tint = Violet)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("CENTRO DE CONTROL", style = MaterialTheme.typography.labelSmall, color = Cyan, letterSpacing = 1.4.sp)
                                    Text("Estado del lector", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Box(Modifier.size(11.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(RoundedCornerShape(50)).background(if (connected) Cyan else Violet))
                            }
                            Text(status, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text("Lee el chat público del LIVE y convierte los comentarios en voz en tu dispositivo.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        val clean = username.trim().removePrefix("@").substringBefore("/")
                                        when {
                                            clean.isBlank() -> status = "Escribe el usuario de TikTok"
                                            !selectedProviderReady -> status = "Descarga primero la voz seleccionada"
                                            else -> {
                                                saveRecentProfile(clean, profileAvatarUrl)
                                                val settings = buildReaderSettings()
                                                onStart(clean, settings)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    enabled = !downloading,
                                    shape = RoundedCornerShape(17.dp)
                                ) { Icon(Icons.Default.PlayArrow, null); Text(" INICIAR", fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { onStop(); status = "Detenido" }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(17.dp)) {
                                    Icon(Icons.Default.Stop, null); Text(" DETENER", fontWeight = FontWeight.Bold)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = .09f))
                        }
                    }

                    SectionHeader("01", "Conexión TikTok LIVE", "Introduce el usuario del creador")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Usuario de TikTok") },
                                placeholder = { Text("@ejemplo_usuario") },
                                leadingIcon = { Icon(Icons.Default.AlternateEmail, null, tint = Violet) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            )
                            if (normalizedProfileUsername.isNotBlank()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color.White.copy(alpha = .055f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = .22f))
                                ) {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            Modifier.size(58.dp).clip(RoundedCornerShape(50)).background(Panel),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (profileAvatarUrl != null) {
                                                AsyncImage(
                                                    model = profileAvatarUrl,
                                                    contentDescription = "Foto de perfil de @$normalizedProfileUsername",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                            } else {
                                                Icon(Icons.Default.AccountCircle, null, tint = Muted, modifier = Modifier.size(42.dp))
                                            }
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("@$normalizedProfileUsername", fontWeight = FontWeight.Bold, color = Color.White)
                                            Text(
                                                when {
                                                    profileLookupBusy -> "Cargando foto de perfil…"
                                                    profileAvatarUrl != null -> "Perfil de TikTok encontrado"
                                                    else -> "No se pudo cargar la foto; puedes guardar el usuario igualmente"
                                                },
                                                color = Muted,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        FilledTonalIconButton(
                                            onClick = { saveRecentProfile(normalizedProfileUsername, profileAvatarUrl) },
                                            enabled = !recentUsernames.any { it.equals(normalizedProfileUsername, true) }
                                        ) {
                                            Icon(Icons.Default.PersonAdd, contentDescription = "Agregar perfil")
                                        }
                                    }
                                }
                            }
                            if (recentUsernames.isNotEmpty()) {
                                Text("PERFILES GUARDADOS", color = Cyan, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    recentUsernames.forEach { recent ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Color.White.copy(alpha = .055f),
                                            shape = RoundedCornerShape(15.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .07f))
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                AsyncImage(
                                                    model = recentAvatarUrls[recent],
                                                    contentDescription = "Foto de perfil de @$recent",
                                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Panel),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text("@$recent", fontWeight = FontWeight.SemiBold)
                                                    Text("Perfil guardado", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                }
                                                TextButton(onClick = { username = recent }) { Text("USAR") }
                                                IconButton(onClick = {
                                                    recentUsernames = recentUsernames.filterNot { it.equals(recent, true) }
                                                    recentAvatarUrls = recentAvatarUrls.filterKeys { !it.equals(recent, true) }
                                                    prefs.edit()
                                                        .putStringSet("recent_usernames", recentUsernames.toSet())
                                                        .putStringSet("recent_user_avatars", recentAvatarUrls.map { (name, url) -> "$name\t$url" }.toSet())
                                                        .apply()
                                                }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Quitar perfil @$recent", tint = Muted)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Text("El creador debe tener un LIVE activo. No se solicita contraseña ni cookies.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    LiveChatPanel(
                        liveComments = liveComments,
                        onSkipComment = onSkipComment,
                        onClearChat = onClearChat,
                        modifier = Modifier
                    )
                    } else {
                    SectionHeader("06", "Actualizar aplicación", "Descarga la última versión oficial sin salir de TikLBot")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Cyan)
                                Spacer(Modifier.width(9.dp))
                                Text("ACTUALIZACIONES", color = Cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            }
                            Text(appUpdate?.let { "Nueva versión ${it.versionName} (código ${it.versionCode})" } ?: if (appUpdateStatus.startsWith("No se pudo consultar")) "No se pudo comprobar la versión" else "Estado de actualización", fontWeight = FontWeight.SemiBold)
                            Text(appUpdateStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = { checkAppUpdateManually() },
                                enabled = !appUpdateBusy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (appUpdateBusy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, contentDescription = null)
                                Text(if (appUpdateBusy) "  COMPROBANDO…" else "  COMPROBAR ACTUALIZACIONES")
                            }
                            if (appUpdate != null) {
                                Button(
                                    onClick = { startAppUpdate() },
                                    enabled = !appUpdateBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                                    Text(if (downloadedUpdatePath == null) "  ACTUALIZAR DESDE LA APP" else "  INSTALAR ACTUALIZACIÓN")
                                }
                            }
                            Text("La descarga se verifica mediante SHA-256. Android mostrará su confirmación de instalación; los datos de la app se conservan si la firma coincide.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    SectionHeader("02", "Lectura y moderación", "Personaliza qué contenido se convierte en voz")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            SettingSwitch("Leer nombre del usuario", readUsername) { readUsername = it }
                            SettingSwitch("Leer solo el nombre (omitir comentarios)", onlyUsername) { onlyUsername = it }
                            SettingSwitch("Ignorar emojis", ignoreEmojis) { ignoreEmojis = it }
                            SettingSwitch("Ignorar símbolos y signos", ignoreSymbols) { ignoreSymbols = it }
                            SettingSwitch("Leer solo comentarios de seguidores", followerOnlyChat) { followerOnlyChat = it }
                            SettingSwitch("Avisar cuando alguien te siga", readFollows) { readFollows = it }
                            SettingSwitch("Avisar cuando recibas regalos", readGifts) { readGifts = it }
                            HorizontalDivider(color = Color.White.copy(alpha = .09f))
                            Text("ALERTAS DE ACTIVIDAD", color = Cyan, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                            SettingSwitch("Avisar cuando compartan el LIVE (máx. 2 por usuario)", readShares) { readShares = it }
                            if (readShares) SettingSwitch("Compartidos: solo seguidores", shareFollowerOnly) { shareFollowerOnly = it }
                            SettingSwitch("Avisar cuando alguien se una al LIVE", readJoins) { readJoins = it }
                            if (readJoins) SettingSwitch("Usuarios que se unen: solo seguidores", joinFollowerOnly) { joinFollowerOnly = it }
                            SettingSwitch("Agradecer al usuario cada 1,000 me gusta", readLikes) { readLikes = it }
                            Text("Los compartidos se anuncian hasta 2 veces por usuario en cada LIVE. Los me gusta se acumulan por usuario y la alerta puede repetirse después de 10 minutos.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (speechProvider == "piper") {
                                SettingSwitch("Cambiar de voz Piper aleatoriamente", randomVoices) { randomVoices = it }
                                Text("El modo aleatorio alterna entre las voces Piper descargadas. Si solo tienes una instalada, utilizará esa.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("El filtro de seguidores depende del estado que TikTok incluya en cada comentario; si no llega ese dato, el comentario se descarta por seguridad.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(color = Color.White.copy(alpha = .09f))
                            EditableStringList(
                                title = "Palabras bloqueadas",
                                placeholder = "Añadir palabra",
                                items = splitList(blockedWords),
                                onItemsChange = { blockedWords = it.joinToString("\n") }
                            )
                            EditableStringList(
                                title = "Usuarios que el bot no leerá",
                                placeholder = "@usuario",
                                items = splitList(ignoredUsers),
                                onItemsChange = { ignoredUsers = it.joinToString("\n") }
                            )
                            OutlinedTextField(value = maxQueue, onValueChange = { maxQueue = it.filter(Char::isDigit).take(3) }, label = { Text("Tamaño de cola (5–100)") }, leadingIcon = { Icon(Icons.Default.Queue, null, tint = Violet) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                        }
                    }

                    SectionHeader("03", "Automatizaciones del chat", "Comandos locales, bienvenida y alertas avanzadas")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            SettingSwitch("Activar comandos personalizados", customCommandsEnabled) { customCommandsEnabled = it }
                            CustomCommandEditor(
                                commands = customCommandEntries,
                                onCommandsChange = { updated ->
                                    customCommandEntries = updated
                                    customCommandsText = updated.joinToString("\n") { "${it.trigger}=${it.response}" }
                                }
                            )
                            Text("Cada activador se reconoce al inicio del comentario. La respuesta se reproduce solo en tu dispositivo; no se publica en el chat de TikTok.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(color = Color.White.copy(alpha = .09f))
                            SettingSwitch("Dar bienvenida al primer comentario de cada usuario", welcomeEnabled) { welcomeEnabled = it }
                            OutlinedTextField(
                                value = welcomeTemplate,
                                onValueChange = { welcomeTemplate = it.take(280) },
                                label = { Text("Mensaje de bienvenida") },
                                supportingText = { Text("Puedes usar {user}. Se activa una vez por usuario y conexión.") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 2,
                                shape = RoundedCornerShape(16.dp)
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = .09f))
                            OutlinedTextField(
                                value = giftMinimumRepeat,
                                onValueChange = { giftMinimumRepeat = it.filter(Char::isDigit).take(3) },
                                label = { Text("Repeticiones mínimas para alertar (1–999)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp)
                            )
                            OutlinedTextField(
                                value = giftAlertTemplate,
                                onValueChange = { giftAlertTemplate = it.take(280) },
                                label = { Text("Plantilla de alerta de regalo (opcional)") },
                                placeholder = { Text("{user} envió {count} regalos") },
                                supportingText = { Text("Variables disponibles: {user} y {count}. Se usa el contador de repetición recibido de TikTok.") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                shape = RoundedCornerShape(16.dp)
                            )
                            Text("La alerta usa únicamente el contador de repetición disponible en el evento Webcast. No se inventan nombres de regalos ni valores de diamantes.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    SectionHeader("04", "Proveedor de voz", "Elige el motor que leerá los comentarios")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf("piper" to "Piper", "tikfinity" to "TikFinity", "edge" to "Edge", "tiktok" to "TikTok").forEach { (provider, label) ->
                                    Button(
                                        onClick = { speechProvider = provider },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (speechProvider == provider) Violet else Color.White.copy(alpha = .08f), contentColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                                }
                            }
                            Text(
                                when (speechProvider) {
                                    "piper" -> "Piper genera voz en el dispositivo y puede funcionar sin internet."
                                    "tikfinity" -> "TikFinity genera audio MP3 mediante su API. Requiere conexión a internet."
                                    "edge" -> "Edge-TTS utiliza las voces neuronales de Microsoft mediante WebSocket seguro."
                                    else -> "TikTok TTS genera audio MP3 mediante el servicio de voces de TikTok."
                                }, color = Muted, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (speechProvider == "piper") {
                    SectionHeader("05", "Catálogo de voces Piper", "Modelos descargables · síntesis completamente local")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("SELECCIONA UNA VOZ", color = Cyan, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                            OutlinedButton(
                                onClick = { voiceMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Icon(Icons.Default.RecordVoiceOver, null, tint = Violet)
                                Text("  ${selectedVoice.name}", modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ExpandMore, null)
                            }
                            if (voiceMenuExpanded) {
                                AlertDialog(
                                    onDismissRequest = { voiceMenuExpanded = false },
                                    title = { Text("Seleccionar voz") },
                                    text = {
                                        Column(
                                            Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            PiperVoiceCatalog.voices.forEach { voice ->
                                                val installed = PiperSpeechEngine.isVoiceInstalled(context, voice.id)
                                                Surface(
                                                    onClick = {
                                                        voiceId = voice.id
                                                        voiceInstalled = installed
                                                        voiceError = ""
                                                        voiceMenuExpanded = false
                                                    },
                                                    color = if (voice.id == voiceId) Violet.copy(alpha = .20f) else Color.White.copy(alpha = .045f),
                                                    shape = RoundedCornerShape(13.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        Modifier.fillMaxWidth().padding(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                            Text(voice.name, fontWeight = FontWeight.SemiBold)
                                                            Text(voice.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                                                            Text(if (installed) "INSTALADA" else "NO INSTALADA", style = MaterialTheme.typography.labelSmall, color = if (installed) Cyan else Muted)
                                                        }
                                                        if (voice.id == voiceId) Icon(Icons.Default.CheckCircle, contentDescription = "Seleccionada", tint = Cyan)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { voiceMenuExpanded = false }) { Text("CERRAR") }
                                    }
                                )
                            }
                            Text(selectedVoice.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (!selectedVoice.official) {
                                Text("Voz comunitaria experimental. El modelo se descarga gratis desde su repositorio, pero su licencia y calidad pueden variar; no se incluye en la APK.", color = Color(0xFFFFD18A), style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = if (voiceInstalled) Cyan.copy(alpha = .16f) else Violet.copy(alpha = .16f), shape = RoundedCornerShape(50)) {
                                    Text(if (voiceInstalled) "INSTALADA" else "NO INSTALADA", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (voiceInstalled) Cyan else Violet, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.weight(1f))
                                if (!voiceInstalled) {
                                    Button(
                                        enabled = !downloading,
                                        onClick = {
                                            downloading = true
                                            voiceError = ""
                                            downloadProgress = 0
                                            downloadStage = "Preparando descarga"
                                            onDownloadVoice(voiceId, { percent, stage ->
                                                downloadProgress = percent.coerceIn(0, 100)
                                                downloadStage = stage
                                            }) { error ->
                                                downloading = false
                                                if (error == null) {
                                                    downloadProgress = 100
                                                    voiceInstalled = PiperSpeechEngine.isVoiceInstalled(context, voiceId)
                                                    voiceError = if (voiceInstalled) "Voz instalada y validada." else "No se pudo validar el modelo."
                                                } else voiceError = error
                                            }
                                        },
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        if (downloading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Default.Download, null)
                                        Text(if (downloading) " ${downloadProgress}%" else " DESCARGAR")
                                    }
                                }
                            }
                            if (downloading) {
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(downloadStage, color = Muted, style = MaterialTheme.typography.labelSmall)
                                        Text("${downloadProgress}%", color = Cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                    }
                                    LinearProgressIndicator(
                                        progress = downloadProgress / 100f,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Cyan,
                                        trackColor = Color.White.copy(alpha = .10f)
                                    )
                                }
                            }
                            if (voiceInstalled) {
                                Button(
                                    onClick = { showDeleteVoiceDialog = true },
                                    enabled = !downloading && !connected,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3263E), contentColor = Color.White),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.Delete, null)
                                    Text("  ELIMINAR VOZ")
                                }
                                if (connected) Text("Detén el LIVE antes de eliminar modelos de voz.", color = Color(0xFFFFA6A6), style = MaterialTheme.typography.bodySmall)
                            }
                            if (voiceError.isNotBlank()) Text(voiceError, color = if (voiceInstalled) Cyan else Color(0xFFFFA6A6), style = MaterialTheme.typography.bodySmall)
                            Text("El catálogo incluye voces oficiales de México, España y Argentina, además de voces comunitarias experimentales.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (showDeleteVoiceDialog) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteVoiceDialog = false },
                                    title = { Text("Eliminar voz") },
                                    text = { Text("¿Quieres eliminar ${selectedVoice.name} de este dispositivo? Podrás descargarla nuevamente cuando quieras.") },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showDeleteVoiceDialog = false
                                                onDeleteVoice(voiceId) { error ->
                                                    if (error == null) {
                                                        voiceInstalled = false
                                                        voiceError = "Voz eliminada del dispositivo."
                                                    } else voiceError = error
                                                }
                                            }
                                        ) { Text("ELIMINAR", color = Color(0xFFFF6B75)) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteVoiceDialog = false }) { Text("CANCELAR") }
                                    }
                                )
                            }
                        }
                    }

                    } else {
                        SectionHeader("05", "Voces API", when (speechProvider) { "tikfinity" -> "TikFinity · generación de audio por internet"; "edge" -> "Edge-TTS · voces neuronales de Microsoft"; else -> "TikTok TTS · voces de TikTok" })
                        GlassCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("SELECCIONA UNA VOZ API", color = Cyan, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                                OutlinedButton(
                                    onClick = { apiVoiceMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    shape = RoundedCornerShape(15.dp),
                                    enabled = speechProvider != "tikfinity" || !apiRandomVoice
                                ) {
                                    Icon(Icons.Default.RecordVoiceOver, null, tint = Violet)
                                    Text("  $selectedApiVoiceName", modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ExpandMore, null)
                                }
                                if (apiVoiceMenuExpanded) {
                                    AlertDialog(
                                        onDismissRequest = { apiVoiceMenuExpanded = false },
                                        title = { Text("Voces API · ${when (speechProvider) { "tikfinity" -> "TikFinity"; "edge" -> "Edge-TTS"; else -> "TikTok TTS" }}") },
                                        text = {
                                            Column(
                                                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(7.dp)
                                            ) {
                                                apiVoiceItems.forEach { voice ->
                                                    Surface(
                                                        onClick = {
                                                            when (speechProvider) { "edge" -> edgeVoiceId = voice.first; "tiktok" -> tikTokVoiceId = voice.first; else -> apiVoiceId = voice.first }
                                                            apiVoiceMenuExpanded = false
                                                        },
                                                        color = if (voice.first == selectedApiVoiceId) Violet.copy(alpha = .20f) else Color.White.copy(alpha = .045f),
                                                        shape = RoundedCornerShape(13.dp),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            Modifier.fillMaxWidth().padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                                Text(voice.second, fontWeight = FontWeight.SemiBold)
                                                                Text(voice.third, style = MaterialTheme.typography.bodySmall, color = Muted)
                                                                Text(voice.first, style = MaterialTheme.typography.labelSmall, color = Muted)
                                                            }
                                                            if (voice.first == selectedApiVoiceId) Icon(Icons.Default.CheckCircle, contentDescription = "Seleccionada", tint = Cyan)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(onClick = { apiVoiceMenuExpanded = false }) { Text("CERRAR") }
                                        }
                                    )
                                }
                                if (speechProvider == "tikfinity") {
                                    SettingSwitch("Usar voces aleatorias", apiRandomVoice) { apiRandomVoice = it }
                                    Text("Cada comentario utilizará una voz TikFinity aleatoria del catálogo.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(
                                        value = tikFinityChannelId,
                                        onValueChange = { tikFinityChannelId = it.filter(Char::isDigit).take(24) },
                                        label = { Text("Channel ID de TikFinity") },
                                        supportingText = { Text("Valor predeterminado: 0") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(15.dp)
                                    )
                                }
                                Text("Cada proveedor conserva su propia voz seleccionada. Todos utilizan la misma cola FIFO; Piper mantiene sus modelos y ajustes locales independientes.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    SectionHeader("06", "Controles de voz", "Ajusta la reproducción a tu gusto")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Volumen", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    Text("${(volume * 100).toInt()}%", color = Cyan, fontWeight = FontWeight.Bold)
                                }
                                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..1f)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Velocidad de lectura", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    Text(String.format(java.util.Locale.US, "%.2fx", speed), color = Cyan, fontWeight = FontWeight.Bold)
                                }
                                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.65f..1.5f)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Lenta", color = Muted, style = MaterialTheme.typography.labelSmall)
                                    Text("Rápida", color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            OutlinedButton(
                                onClick = { voiceMuted = !voiceMuted; onSetMuted(voiceMuted) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Icon(if (voiceMuted) Icons.Default.VolumeOff else Icons.Default.VolumeMute, null)
                                Text(if (voiceMuted) "  ACTIVAR VOZ" else "  SILENCIAR VOZ")
                            }
                            OutlinedButton(
                                onClick = { onTestVoice(buildReaderSettings()) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                enabled = selectedProviderReady && !downloading,
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Icon(Icons.Default.VolumeUp, null)
                                Text("  PROBAR VOZ")
                            }
                        }
                    }

                    Text(
                        "TIK TOK READER  •  v0.13.6  •  BY ROXI",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = Muted.copy(alpha = .7f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        letterSpacing = 1.sp
                    )
                    }
                }
            }
        }
            if (showAppUpdateDialog && appUpdate != null) {
                AlertDialog(
                    onDismissRequest = { showAppUpdateDialog = false },
                    title = { Text("Nueva actualización disponible") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tik Tok Reader ${appUpdate!!.versionName} ya está publicado en GitHub Releases.")
                            Text("Puedes descargarlo e iniciar la instalación desde aquí, sin abrir el navegador.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (appUpdateStatus.isNotBlank()) Text(appUpdateStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { startAppUpdate() }, enabled = !appUpdateBusy) {
                            Text(if (appUpdateBusy) "PROCESANDO…" else if (downloadedUpdatePath == null) "ACTUALIZAR" else "INSTALAR")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAppUpdateDialog = false }) { Text("MÁS TARDE") }
                    }
                )
            }
    }
}

@Composable
private fun LiveChatPanel(
    liveComments: SnapshotStateList<LiveCommentUi>,
    onSkipComment: (String) -> Unit,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(liveComments.size) {
        // Keep the newest message at the bottom without pulling the reader away
        // when they are manually reviewing older chat messages.
        if (liveComments.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("LIVE", "Comentarios del directo", "Mensajes recibidos en tiempo real")
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CHAT EN VIVO", color = Cyan, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${liveComments.size} mensajes", color = Muted, style = MaterialTheme.typography.labelSmall)
                    IconButton(
                        onClick = onClearChat,
                        enabled = liveComments.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Limpiar chat",
                            tint = if (liveComments.isNotEmpty()) Muted else Muted.copy(alpha = .35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (liveComments.isEmpty()) {
                    Text(
                        "Los comentarios aparecerán aquí cuando el lector se conecte y reciba mensajes.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        state = listState,
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(liveComments.asReversed(), key = { it.id }) { comment ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Panel.copy(alpha = .48f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                AsyncImage(
                                    model = comment.avatarUrl,
                                    contentDescription = "Foto de perfil de ${comment.username}",
                                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(50.dp)).background(Panel),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(comment.username.ifBlank { "Usuario" }, color = Cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                    if (!comment.replyToDisplayName.isNullOrBlank()) {
                                        Text("↪ Respondió a @${comment.replyToDisplayName}", color = Violet, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(comment.message, color = Color(0xFFF4F1FF), style = MaterialTheme.typography.bodyMedium)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(
                                            enabled = !comment.skipped,
                                            onClick = { onSkipComment(comment.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text(if (comment.skipped) "OMITIDO" else "OMITIR COMENTARIO", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(22.dp))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .28f), Violet.copy(alpha = .20f), Cyan.copy(alpha = .08f))), RoundedCornerShape(22.dp))
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xB91B2036),
            contentColor = Color(0xFFF4F1FF)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
private fun SectionHeader(number: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, color = Violet, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun loadCustomCommandEntries(raw: String, prefs: android.content.SharedPreferences): List<CustomCommandEntry> =
    ChatCommandParser.parseConfig(raw).map { (trigger, response) ->
        CustomCommandEntry(
            title = prefs.getString("custom_command_title_$trigger", trigger).orEmpty().ifBlank { trigger },
            trigger = trigger,
            response = response
        )
    }

@Composable
private fun CustomCommandEditor(
    commands: List<CustomCommandEntry>,
    onCommandsChange: (List<CustomCommandEntry>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("!") }
    var response by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("TUS COMANDOS", color = Cyan, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = title, onValueChange = { title = it.take(40) },
            label = { Text("Título del comando") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            value = trigger, onValueChange = { trigger = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '!' }.take(25) },
            label = { Text("Activador (ej. !discord)") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            value = response, onValueChange = { response = it.take(280) },
            label = { Text("Respuesta que dirá la voz") }, modifier = Modifier.fillMaxWidth(),
            minLines = 2, shape = RoundedCornerShape(14.dp)
        )
        if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = title.isNotBlank() && Regex("^![a-z0-9_]{1,24}$").matches(trigger.trim()) && response.isNotBlank() && commands.size < 40,
            onClick = {
                val key = trigger.trim()
                val entry = CustomCommandEntry(title.trim(), key, response.trim())
                onCommandsChange(commands.filterNot { it.trigger == key } + entry)
                title = ""; trigger = "!"; response = ""; validation = ""
            }
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Guardar comando")
        }
        commands.forEach { command ->
            Surface(
                color = Color.White.copy(alpha = .055f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(command.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(command.trigger, color = Cyan, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onCommandsChange(commands.filterNot { it.trigger == command.trigger }) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar comando", tint = Muted)
                        }
                    }
                    Text(command.response, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (commands.isEmpty()) Text("Aún no hay comandos guardados.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("${commands.size}/40 comandos", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun splitList(value: String): List<String> =
    value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }


@Composable
private fun EditableStringList(
    title: String,
    placeholder: String,
    items: List<String>,
    onItemsChange: (List<String>) -> Unit
) {
    var draft by remember(title) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(80) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            FilledTonalIconButton(
                enabled = draft.isNotBlank() && items.none { it.equals(draft.trim(), true) },
                onClick = {
                    val value = draft.trim().removePrefix("@").takeIf { it.isNotBlank() } ?: return@FilledTonalIconButton
                    onItemsChange(items + value)
                    draft = ""
                }
            ) { Icon(Icons.Default.Add, contentDescription = "Añadir") }
        }
        if (items.isEmpty()) {
            Text("Todavía no hay elementos en esta lista.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { item ->
                    Surface(
                        color = Color.White.copy(alpha = .055f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onItemsChange(items.filterNot { it == item }) }) {
                                Icon(Icons.Default.Close, contentDescription = "Eliminar $item", tint = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}