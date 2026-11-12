package com.myclivebot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.lifecycle.lifecycleScope
import com.myclivebot.core.PiperSpeechEngine
import com.myclivebot.core.PiperVoiceCatalog
import com.myclivebot.core.ReaderSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val Night = Color(0xFF090B18)
private val Panel = Color(0xCC1B2036)
private val Violet = Color(0xFF9B86FF)
private val Cyan = Color(0xFF69DED2)
private val Muted = Color(0xFFB8BBD0)

class MainActivity : ComponentActivity() {
    private val readerStatus = mutableStateOf("Detenido")
    private var statusReceiverRegistered = false
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            readerStatus.value = intent?.getStringExtra(BotForegroundService.EXTRA_STATUS) ?: return
        }
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            BotApp(
                externalStatus = readerStatus.value,
                onStart = { username, settings ->
                    val intent = Intent(this, BotForegroundService::class.java)
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                },
                onUpdateSettings = { settings ->
                    val intent = Intent(this, BotForegroundService::class.java)
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
                    startService(intent)
                },
                onStop = {
                    startService(Intent(this, BotForegroundService::class.java).setAction(BotForegroundService.ACTION_STOP))
                },
                onTestVoice = { voiceId, volume, speed ->
                    val intent = Intent(this, BotForegroundService::class.java)
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
        }
    }

    override fun onStart() {
        super.onStart()
        if (!statusReceiverRegistered) {
            val filter = IntentFilter(BotForegroundService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            statusReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotApp(
    externalStatus: String,
    onStart: (String, ReaderSettings) -> Unit,
    onStop: () -> Unit,
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
    var maxQueue by remember { mutableStateOf(prefs.getInt("max_queue", 30).toString()) }
    var blockedWords by remember { mutableStateOf(prefs.getString("blocked_words", "").orEmpty()) }
    var ignoredUsers by remember { mutableStateOf(prefs.getString("ignored_users", "").orEmpty()) }
    var volume by remember { mutableFloatStateOf(prefs.getFloat("volume", 0.90f).coerceIn(0f, 1f)) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1.0f).coerceIn(0.65f, 1.5f)) }
    var voiceId by remember {
        mutableStateOf(prefs.getString("voice_id", PiperVoiceCatalog.DEFAULT_VOICE_ID) ?: PiperVoiceCatalog.DEFAULT_VOICE_ID)
    }
    var status by remember { mutableStateOf("Detenido") }
    var voiceInstalled by remember(voiceId) { mutableStateOf(PiperSpeechEngine.isVoiceInstalled(context, voiceId)) }
    var downloading by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf("") }
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(externalStatus) { status = externalStatus }
    LaunchedEffect(username) { prefs.edit().putString("last_username", username).apply() }
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

    val selectedVoice = PiperVoiceCatalog.find(voiceId)
    val connected = status.contains("Conectado", true) && !status.contains("no se pudo", true)
    LaunchedEffect(connected, readUsername, onlyUsername, ignoreEmojis, ignoreSymbols, followerOnlyChat, readFollows, readGifts, randomVoices, maxQueue, blockedWords, ignoredUsers, volume, speed, voiceId, voiceInstalled) {
        if (connected && voiceInstalled) {
            delay(350)
            onUpdateSettings(
                ReaderSettings(
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
                    randomVoices = randomVoices
                )
            )
        }
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
                topBar = {
                    TopAppBar(
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("TIKTOK LIVE READER", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, letterSpacing = 1.2.sp)
                                Text("LECTOR INTELIGENTE · PIPER OFFLINE", style = MaterialTheme.typography.labelSmall, color = Cyan, letterSpacing = 1.sp)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0x99111629))
                    )
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
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
                                                val settings = ReaderSettings(
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
                                                    randomVoices = randomVoices
                                                )
                                                onStart(clean, settings)
                                                status = "Servicio iniciado · conectando…"
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
                            SettingSwitch("Cambiar de voz aleatoriamente", randomVoices) { randomVoices = it }
                            HorizontalDivider(color = Color.White.copy(alpha = .09f))
                            OutlinedTextField(value = blockedWords, onValueChange = { blockedWords = it }, label = { Text("Palabras bloqueadas · separadas por coma") }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(16.dp))
                            OutlinedTextField(value = ignoredUsers, onValueChange = { ignoredUsers = it }, label = { Text("Usuarios ignorados · separados por coma") }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(16.dp))
                            OutlinedTextField(value = maxQueue, onValueChange = { maxQueue = it.filter(Char::isDigit).take(3) }, label = { Text("Tamaño de cola (5–100)") }, leadingIcon = { Icon(Icons.Default.Queue, null, tint = Violet) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                        }
                    }

                    SectionHeader("03", "Voces mexicanas Piper", "Modelos descargables · síntesis completamente local")
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
                            Text("Las voces oficiales Piper disponibles para México son Ald (medium) y Claude (high). Las demás son modelos comunitarios experimentales.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    SectionHeader("04", "Controles de voz", "Ajusta la reproducción a tu gusto")
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
                        "TIKTOK LIVE READER  •  v0.7  •  BY ROXI",
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
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(24.dp))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .24f), Violet.copy(alpha = .22f), Color.White.copy(alpha = .04f))), RoundedCornerShape(24.dp))
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
private fun SectionHeader(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(number, color = Violet, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun splitList(value: String): List<String> =
    value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
