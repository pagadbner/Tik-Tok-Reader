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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

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
                        .putExtra(BotForegroundService.EXTRA_FOLLOWER_ONLY, settings.followerOnlyChat)
                        .putExtra(BotForegroundService.EXTRA_READ_FOLLOWS, settings.readFollows)
                        .putExtra(BotForegroundService.EXTRA_READ_GIFTS, settings.readGifts)
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
                        .putExtra(BotForegroundService.EXTRA_FOLLOWER_ONLY, settings.followerOnlyChat)
                        .putExtra(BotForegroundService.EXTRA_READ_FOLLOWS, settings.readFollows)
                        .putExtra(BotForegroundService.EXTRA_READ_GIFTS, settings.readGifts)
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
                    startService(Intent(this@MainActivity, BotForegroundService::class.java).setAction(BotForegroundService.ACTION_STOP))
                },
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
                onTestVoice = { voiceId, volume, speed ->
                    val intent = Intent(this@MainActivity, BotForegroundService::class.java)
                        .setAction(BotForegroundService.ACTION_TEST_VOICE)
                        .putExtra(BotForegroundService.EXTRA_VOICE_ID, voiceId)
                        .putExtra(BotForegroundService.EXTRA_VOLUME, volume)
                        .putExtra(BotForegroundService.EXTRA_SPEED, speed)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                },
                onDownloadVoice = { voiceId, callback ->
                    lifecycleScope.launch {
                        runCatching { PiperSpeechEngine.downloadVoice(this@MainActivity, voiceId) }
                            .onSuccess { runOnUiThread { callback(null) } }
                            .onFailure { error ->
                                runOnUiThread { callback(error.message ?: "Error descargando la voz") }
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
                                    contentDescription = "TikTok Live Reader"
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
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                val store = LiveChatHistoryStore(this@MainActivity)
                try {
                    store.getAll()
                } finally {
                    store.close()
                }
            }
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
    onSkipComment: (String) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onUpdateSettings: (ReaderSettings) -> Unit,
    onTestVoice: (String, Float, Float) -> Unit,
    onDownloadVoice: (String, (String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tiklbot_preferences", Context.MODE_PRIVATE) }

    var username by remember { mutableStateOf(prefs.getString("last_username", "").orEmpty()) }
    var recentUsernames by remember {
        mutableStateOf(prefs.getStringSet("recent_usernames", mutableSetOf<String>()).orEmpty().toList().take(8))
    }
    var readUsername by remember { mutableStateOf(prefs.getBoolean("read_username", true)) }
    var onlyUsername by remember { mutableStateOf(prefs.getBoolean("only_username", false)) }
    var ignoreEmojis by remember { mutableStateOf(prefs.getBoolean("ignore_emojis", true)) }
    var ignoreSymbols by remember { mutableStateOf(prefs.getBoolean("ignore_symbols", false)) }
    var followerOnlyChat by remember { mutableStateOf(prefs.getBoolean("follower_only_chat", false)) }
    var readFollows by remember { mutableStateOf(prefs.getBoolean("read_follows", true)) }
    var readGifts by remember { mutableStateOf(prefs.getBoolean("read_gifts", true)) }
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
    var status by remember { mutableStateOf("Detenido") }
    var voiceInstalled by remember(voiceId) { mutableStateOf(PiperSpeechEngine.isVoiceInstalled(context, voiceId)) }
    var downloading by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf("") }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var navigationMenuExpanded by remember { mutableStateOf(false) }
    val navigationRequesters = List(6) { remember { BringIntoViewRequester() } }
    val navigationScope = rememberCoroutineScope()

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
        followerOnlyChat = followerOnlyChat,
        readFollows = readFollows,
        readGifts = readGifts,
        randomVoices = randomVoices,
        customCommandsEnabled = customCommandsEnabled,
        customCommands = if (customCommandsEnabled) ChatCommandParser.parseConfig(customCommandsText) else emptyMap(),
        welcomeEnabled = welcomeEnabled,
        welcomeTemplate = welcomeTemplate,
        giftMinimumRepeatCount = giftMinimumRepeat.toIntOrNull()?.coerceIn(1, 999) ?: 1,
        giftAlertTemplate = giftAlertTemplate
    )

    LaunchedEffect(externalStatus) { status = externalStatus }
    LaunchedEffect(username) { prefs.edit().putString("last_username", username).apply() }
    LaunchedEffect(voiceMuted) { prefs.edit().putBoolean("voice_muted", voiceMuted).apply() }
    LaunchedEffect(externalMuted) { voiceMuted = externalMuted }
    LaunchedEffect(readUsername, onlyUsername, ignoreEmojis, ignoreSymbols, followerOnlyChat, readFollows, readGifts, randomVoices, maxQueue, blockedWords, ignoredUsers, volume, speed, voiceId) {
        prefs.edit()
            .putBoolean("follower_only_chat", followerOnlyChat)
            .putBoolean("read_follows", readFollows)
            .putBoolean("read_gifts", readGifts)
            .putBoolean("random_voices", randomVoices)
            .apply()
    }

    LaunchedEffect(readUsername, onlyUsername, ignoreEmojis, ignoreSymbols, maxQueue, blockedWords, ignoredUsers, volume, speed, voiceId) {
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
    val connected = status.contains("Conectado", true) && !status.contains("no se pudo", true)
    LaunchedEffect(
        connected, readUsername, onlyUsername, ignoreEmojis, ignoreSymbols, followerOnlyChat,
        readFollows, readGifts, randomVoices, maxQueue, blockedWords, ignoredUsers,
        speed, voiceId, voiceInstalled, customCommandsEnabled, customCommandsText,
        welcomeEnabled, welcomeTemplate, giftMinimumRepeat, giftAlertTemplate
    ) {
        if (connected && voiceInstalled) {
            delay(180)
            onUpdateSettings(buildReaderSettings())
        }
    }
    // Volume changes are applied immediately to the active AudioTrack; don't debounce slider input.
    LaunchedEffect(volume, connected, voiceInstalled) {
        if (connected && voiceInstalled) onUpdateSettings(buildReaderSettings())
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
                                Text("TIKTOK LIVE READER", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall, letterSpacing = 1.1.sp)
                                Text("PIPER OFFLINE · CONTROL EN VIVO", style = MaterialTheme.typography.labelSmall, color = Cyan, letterSpacing = .8.sp)
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { navigationMenuExpanded = true }) {
                                    Icon(Icons.Default.MenuOpen, contentDescription = "Abrir navegación", tint = Cyan)
                                }
                                DropdownMenu(
                                    expanded = navigationMenuExpanded,
                                    onDismissRequest = { navigationMenuExpanded = false }
                                ) {
                                    val destinations = listOf("Comentarios del directo", "Conexión TikTok LIVE", "Lectura y moderación", "Automatizaciones del chat", "Catálogo de voces", "Controles de voz")
                                    destinations.forEachIndexed { index, destination ->
                                        DropdownMenuItem(
                                            text = { Text(destination) },
                                            leadingIcon = {
                                                Icon(
                                                    when (index) {
                                                        0 -> Icons.Default.Forum
                                                        1 -> Icons.Default.Link
                                                        2 -> Icons.Default.Tune
                                                        3 -> Icons.Default.AutoAwesome
                                                        4 -> Icons.Default.RecordVoiceOver
                                                        else -> Icons.Default.GraphicEq
                                                    }, contentDescription = null, tint = Violet
                                                )
                                            },
                                            onClick = {
                                                navigationMenuExpanded = false
                                                navigationScope.launch { navigationRequesters[index].bringIntoView() }
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0x77111629),
                            titleContentColor = Color(0xFFF4F1FF),
                            actionIconContentColor = Cyan
                        )
                    )
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
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
                                            !voiceInstalled -> status = "Descarga primero la voz seleccionada"
                                            else -> {
                                                recentUsernames = (listOf(clean) + recentUsernames.filterNot { it.equals(clean, true) }).take(8)
                                                prefs.edit().putStringSet("recent_usernames", recentUsernames.toSet()).apply()
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

                    LiveChatPanel(
                        liveComments = liveComments,
                        onSkipComment = onSkipComment,
                        modifier = Modifier.bringIntoViewRequester(navigationRequesters[0])
                    )

                    SectionHeader("01", "Conexión TikTok LIVE", "Introduce el usuario del creador", Modifier.bringIntoViewRequester(navigationRequesters[1]))
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
                            if (recentUsernames.isNotEmpty()) {
                                Text("USUARIOS RECIENTES", color = Cyan, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    recentUsernames.forEach { recent ->
                                        AssistChip(onClick = { username = recent }, label = { Text("@$recent") })
                                    }
                                }
                            }
                            Text("El creador debe tener un LIVE activo. No se solicita contraseña ni cookies.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    SectionHeader("02", "Lectura y moderación", "Personaliza qué contenido se convierte en voz", Modifier.bringIntoViewRequester(navigationRequesters[2]))
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            SettingSwitch("Leer nombre del usuario", readUsername) { readUsername = it }
                            SettingSwitch("Leer solo el nombre (omitir comentarios)", onlyUsername) { onlyUsername = it }
                            SettingSwitch("Ignorar emojis", ignoreEmojis) { ignoreEmojis = it }
                            SettingSwitch("Ignorar símbolos y signos", ignoreSymbols) { ignoreSymbols = it }
                            SettingSwitch("Leer solo comentarios de seguidores", followerOnlyChat) { followerOnlyChat = it }
                            SettingSwitch("Avisar cuando alguien te siga", readFollows) { readFollows = it }
                            SettingSwitch("Avisar cuando recibas regalos", readGifts) { readGifts = it }
                            SettingSwitch("Cambiar de voz aleatoriamente", randomVoices) { randomVoices = it }
                            Text("El modo aleatorio alterna entre las voces descargadas. Si solo tienes una instalada, utilizará esa.", color = Muted, style = MaterialTheme.typography.bodySmall)
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

                    SectionHeader("03", "Automatizaciones del chat", "Comandos locales, bienvenida y alertas avanzadas", Modifier.bringIntoViewRequester(navigationRequesters[3]))
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

                    SectionHeader("04", "Catálogo de voces Piper", "Modelos descargables · síntesis completamente local", Modifier.bringIntoViewRequester(navigationRequesters[4]))
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("SELECCIONA UNA VOZ", color = Cyan, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                            Box {
                                OutlinedButton(onClick = { voiceMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
                                    Icon(Icons.Default.RecordVoiceOver, null, tint = Violet)
                                    Text("  ${selectedVoice.name}", modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ExpandMore, null)
                                }
                                DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                                    PiperVoiceCatalog.voices.forEach { voice ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(voice.name, fontWeight = FontWeight.SemiBold)
                                                    Text(voice.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                                                }
                                            },
                                            onClick = {
                                                voiceId = voice.id
                                                voiceInstalled = PiperSpeechEngine.isVoiceInstalled(context, voice.id)
                                                voiceError = ""
                                                voiceMenuExpanded = false
                                            }
                                        )
                                    }
                                }
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
                                            onDownloadVoice(voiceId) { error ->
                                                downloading = false
                                                if (error == null) {
                                                    voiceInstalled = PiperSpeechEngine.isVoiceInstalled(context, voiceId)
                                                    voiceError = if (voiceInstalled) "Voz instalada y validada." else "No se pudo validar el modelo."
                                                } else voiceError = error
                                            }
                                        },
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        if (downloading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Default.Download, null)
                                        Text(if (downloading) " DESCARGANDO" else " DESCARGAR")
                                    }
                                }
                            }
                            if (voiceError.isNotBlank()) Text(voiceError, color = if (voiceInstalled) Cyan else Color(0xFFFFA6A6), style = MaterialTheme.typography.bodySmall)
                            Text("El catálogo incluye voces oficiales de México, España y Argentina, además de voces comunitarias experimentales.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    SectionHeader("05", "Controles de voz", "Ajusta la reproducción a tu gusto", Modifier.bringIntoViewRequester(navigationRequesters[5]))
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
                                onClick = { onTestVoice(voiceId, volume, speed) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                enabled = voiceInstalled && !downloading,
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Icon(Icons.Default.VolumeUp, null)
                                Text("  PROBAR VOZ")
                            }
                        }
                    }

                    Text(
                        "TIKTOK LIVE READER  •  v0.13.1  •  BY ROXI",
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
}

@Composable
private fun LiveChatPanel(
    liveComments: SnapshotStateList<LiveCommentUi>,
    onSkipComment: (String) -> Unit,
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                            HorizontalDivider(color = Color.White.copy(alpha = .08f))
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