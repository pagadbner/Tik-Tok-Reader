package com.myclivebot.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window as DesktopWindow
import com.myclivebot.core.LiveComment
import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.CommentFilter
import com.myclivebot.core.CommentPipeline
import com.myclivebot.core.SpeechQueueWorker
import com.myclivebot.core.EdgeTtsSpeechEngine
import com.myclivebot.core.EdgeTtsVoiceCatalog
import com.myclivebot.core.CommentHandlingResult
import com.myclivebot.core.TikTokLiveConnector
import com.myclivebot.core.DjMusicController
import com.myclivebot.core.DjMusicState
import com.myclivebot.core.DjMusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import javax.swing.SwingUtilities

private val Ink = Color(0xFF090B16)
private val Panel = Color(0xFF15182A)
private val PanelRaised = Color(0xFF1D2036)
private val Violet = Color(0xFF9B6BFF)
private val Cyan = Color(0xFF39D7E8)
private val Muted = Color(0xFFA7ABC2)

fun main() {
    applicationWindow()
}

@OptIn(ExperimentalMaterial3Api::class)
private fun applicationWindow() = androidx.compose.ui.window.application {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf("Home") }
    var username by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Desconectado") }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val comments = remember { mutableStateListOf<LiveComment>() }
    val djMusicState by DjMusicController.state.collectAsState()
    var readerSettings by remember { mutableStateOf(DesktopSettingsStore.load()) }
    var filteredComments by remember { mutableStateOf(0) }
    var voiceMuted by remember { mutableStateOf(false) }
    val speechEngine = remember { EdgeTtsSpeechEngine(voiceId = readerSettings.edgeVoiceId, volume = readerSettings.volume, speed = readerSettings.speed) }
    val speechQueue = remember { SpeechQueueWorker(speechEngine, scope, capacity = readerSettings.maxQueueSize) }
    val commentPipeline = remember { CommentPipeline(CommentFilter(), speechQueue, readerSettings) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    fun saveReaderSettings(next: ReaderSettings) {
        readerSettings = next
        DesktopSettingsStore.save(next)
        commentPipeline.updateSettings(next)
        speechEngine.update(next.edgeVoiceId, next.volume, next.speed)
    }
    var connector by remember { mutableStateOf<TikTokLiveConnector?>(null) }
    var update by remember { mutableStateOf<DesktopUpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("Comprobando actualizaciones…") }
    var updateBusy by remember { mutableStateOf(false) }

    fun stopLive() {
        connector?.close()
        connector = null
        connected = false
        connecting = false
        status = "Desconectado"
    }

    DisposableEffect(Unit) {
        onDispose { connector?.close(); commentPipeline.close(); speechQueue.close(); speechEngine.release() }
    }

    LaunchedEffect(Unit) {
        updateBusy = true
        try {
            update = withContext(Dispatchers.IO) { DesktopUpdateManager.checkForUpdate("0.13.15") }
            updateStatus = if (update == null) "TikLBot PC está actualizado." else "Disponible TikLBot PC ${update!!.versionName}"
        } catch (error: Exception) {
            updateStatus = "No se pudo comprobar la actualización: ${error.message ?: "error de red"}"
        } finally { updateBusy = false }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Violet, secondary = Cyan, background = Ink, surface = Panel,
        onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
    )) {
        DesktopWindow(onCloseRequest = { stopLive(); exitApplication() }, title = "TikLBot") {
            Row(Modifier.fillMaxSize().background(Ink)) {
                Column(
                    Modifier.width(224.dp).fillMaxHeight().background(Panel).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TikLBotLogo(42.dp)
                        Column {
                            Text("TikLBot", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("DESKTOP • 0.13.15", fontSize = 10.sp, color = Muted)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    listOf("Home", "Chat en vivo", "DJ Music", "Voces", "Automatizaciones", "Settings").forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item) },
                            selected = selected == item,
                            onClick = { selected = item },
                            icon = {
                                Icon(when(item) {
                                    "Home" -> Icons.Default.Home
                                    "Chat en vivo" -> Icons.Default.Forum
                                    "DJ Music" -> Icons.Default.MusicNote
                                    "Voces" -> Icons.Default.RecordVoiceOver
                                    "Automatizaciones" -> Icons.Default.AutoAwesome
                                    else -> Icons.Default.Settings
                                }, null)
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = PanelRaised,
                                selectedTextColor = Color.White,
                                selectedIconColor = Cyan,
                                unselectedTextColor = Muted,
                                unselectedIconColor = Muted
                            ),
                            modifier = Modifier.height(48.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("TikLBot para Windows", color = Muted, fontSize = 11.sp)
                    Text("La conexión LIVE usa el transporte JVM compartido.", color = Muted, fontSize = 10.sp)
                }
                Column(Modifier.fillMaxSize().padding(28.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(selected, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("Herramientas para tus directos de TikTok", color = Muted, fontSize = 13.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).background(if (connected) Cyan else Muted, RoundedCornerShape(50)))
                            Text(status, color = if (connected) Cyan else Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    when(selected) {
                        "Home" -> {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("Conectar con un LIVE público", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                    OutlinedTextField(
                                        value = username, onValueChange = { username = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Usuario de TikTok") },
                                        placeholder = { Text("ejemplo: usuario") },
                                        singleLine = true
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(enabled = !connected && !connecting && username.isNotBlank(), onClick = {
                                            comments.clear()
                                            connecting = true
                                            connected = false
                                            status = "Preparando conexión…"
                                            val live = TikTokLiveConnector(
                                                onComment = { comment ->
                                                    if (comment.text.trim().startsWith("!play ", ignoreCase = true) || comment.text.trim().equals("!skip", ignoreCase = true)) {
                                                        DjMusicController.handleLiveComment(comment)
                                                    }
                                                    val result = commentPipeline.onComment(comment)
                                                    SwingUtilities.invokeLater {
                                                        comments.add(0, comment)
                                                        while (comments.size > 300) comments.removeAt(comments.lastIndex)
                                                        if (result == CommentHandlingResult.FILTERED) filteredComments++
                                                    }
                                                    result
                                                },
                                                onStatus = { value -> SwingUtilities.invokeLater {
                                                    status = value
                                                    connected = value.startsWith("Conectado al LIVE")
                                                    when {
                                                        connected -> connecting = false
                                                        value.startsWith("TikTok no aceptó la conexión") || value.startsWith("No se pudo programar") -> connecting = false
                                                        value.contains("Se reintentará la conexión LIVE") ||
                                                            value.startsWith("Buscando el LIVE") ||
                                                            value.startsWith("LIVE encontrado") ||
                                                            value.startsWith("Preparando conexión") ||
                                                            value.startsWith("Conectando con TikTok") ||
                                                            value.startsWith("Probando el servidor") -> connecting = true
                                                    }
                                                } },
                                                onEvent = { event -> commentPipeline.onEvent(event) }
                                            )
                                            connector = live
                                            live.connect(username)
                                        }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Conectar") }
                                        OutlinedButton(enabled = connected, onClick = { stopLive() }) {
                                            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Desconectar")
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard("Comentarios recibidos", comments.size.toString(), Modifier.weight(1f))
                                StatCard("Filtrados", filteredComments.toString(), Modifier.weight(1f))
                                StatCard("Estado de conexión", when { connected -> "Activo"; connecting -> "Conectando"; else -> "Inactivo" }, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Chat en vivo", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            CommentList(comments, Modifier.weight(1f))
                        }
                        "Chat en vivo" -> CommentList(comments, Modifier.fillMaxSize())
                        "DJ Music" -> DjMusicPanel(djMusicState)
                        "Voces" -> VoicePanel(
                            readerSettings,
                            voiceMuted,
                            onSettingsChange = ::saveReaderSettings,
                            onMutedChange = { voiceMuted = it; speechEngine.setMuted(it) }
                        )
                        "Automatizaciones" -> AutomationPanel(readerSettings, ::saveReaderSettings)
                        else -> {
                            Text("Preferencias del lector", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingToggle("Leer nombre del usuario", readerSettings.readUsername) { saveReaderSettings(readerSettings.copy(readUsername = it)) }
                                    SettingToggle("Solo seguidores", readerSettings.followerOnlyChat) { saveReaderSettings(readerSettings.copy(followerOnlyChat = it)) }
                                    SettingToggle("Ignorar emojis", readerSettings.ignoreEmojis) { saveReaderSettings(readerSettings.copy(ignoreEmojis = it)) }
                                    SettingToggle("Ignorar símbolos", readerSettings.ignoreSymbols) { saveReaderSettings(readerSettings.copy(ignoreSymbols = it)) }
                                    SettingToggle("Leer solamente el nombre", readerSettings.onlyUsername) { saveReaderSettings(readerSettings.copy(onlyUsername = it)) }
                                    SettingToggle("Ignorar moderadores", readerSettings.ignoreModerators) { saveReaderSettings(readerSettings.copy(ignoreModerators = it)) }
                                    SettingToggle("Usar @usuario del perfil", readerSettings.useProfileUsername) { saveReaderSettings(readerSettings.copy(useProfileUsername = it)) }
                                    Divider(color = PanelRaised)
                                    Text("Alertas del LIVE", fontWeight = FontWeight.SemiBold)
                                    SettingToggle("Leer nuevos seguidores", readerSettings.readFollows) { saveReaderSettings(readerSettings.copy(readFollows = it)) }
                                    SettingToggle("Leer regalos", readerSettings.readGifts) { saveReaderSettings(readerSettings.copy(readGifts = it)) }
                                    SettingToggle("Leer compartidos", readerSettings.readShares) { saveReaderSettings(readerSettings.copy(readShares = it)) }
                                    SettingToggle("Compartidos: solo seguidores", readerSettings.shareFollowerOnly) { saveReaderSettings(readerSettings.copy(shareFollowerOnly = it)) }
                                    SettingToggle("Leer cuando alguien entra", readerSettings.readJoins) { saveReaderSettings(readerSettings.copy(readJoins = it)) }
                                    SettingToggle("Entradas: solo seguidores", readerSettings.joinFollowerOnly) { saveReaderSettings(readerSettings.copy(joinFollowerOnly = it)) }
                                    SettingToggle("Leer likes", readerSettings.readLikes) { saveReaderSettings(readerSettings.copy(readLikes = it)) }
                                    OutlinedTextField(
                                        value = readerSettings.giftMinimumRepeatCount.toString(),
                                        onValueChange = { value -> value.toIntOrNull()?.let { saveReaderSettings(readerSettings.copy(giftMinimumRepeatCount = it.coerceIn(1, 100))) } },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Mínimo de repeticiones para anunciar regalos") },
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = readerSettings.giftAlertTemplate,
                                        onValueChange = { saveReaderSettings(readerSettings.copy(giftAlertTemplate = it.take(280))) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Plantilla de regalos ({user}, {count})") },
                                        minLines = 2
                                    )
                                    Divider(color = PanelRaised)
                                    Text("Automatizaciones", fontWeight = FontWeight.SemiBold)
                                    SettingToggle("Dar bienvenida al primer comentario", readerSettings.welcomeEnabled) { saveReaderSettings(readerSettings.copy(welcomeEnabled = it)) }
                                    OutlinedTextField(
                                        value = readerSettings.welcomeTemplate,
                                        onValueChange = { saveReaderSettings(readerSettings.copy(welcomeTemplate = it.take(280))) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Bienvenida ({user})") },
                                        minLines = 2
                                    )
                                    SettingToggle("Activar comandos personalizados (moderadores)", readerSettings.customCommandsEnabled) { saveReaderSettings(readerSettings.copy(customCommandsEnabled = it)) }
                                    OutlinedTextField(
                                        value = readerSettings.customCommands.entries.joinToString("\n") { "${it.key}=${it.value}" },
                                        onValueChange = { value -> saveReaderSettings(readerSettings.copy(customCommands = com.myclivebot.core.ChatCommandParser.parseConfig(value))) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Comandos: !comando=respuesta ({user}, {command})") },
                                        minLines = 3
                                    )
                                    SettingToggle("Silenciar voz", voiceMuted) { voiceMuted = it; speechEngine.setMuted(it) }
                                    Text("Voz Edge TTS", color = Muted, fontSize = 12.sp)
                                    Box {
                                        OutlinedButton(onClick = { voiceMenuExpanded = true }) {
                                            Text(EdgeTtsVoiceCatalog.find(readerSettings.edgeVoiceId).name)
                                        }
                                        DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                                            EdgeTtsVoiceCatalog.voices.forEach { voice ->
                                                DropdownMenuItem(
                                                    text = { Text(voice.name) },
                                                    onClick = {
                                                        voiceMenuExpanded = false
                                                        saveReaderSettings(readerSettings.copy(edgeVoiceId = voice.id))
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Text("Volumen", color = Muted, fontSize = 12.sp)
                                    Slider(value = readerSettings.volume, onValueChange = { saveReaderSettings(readerSettings.copy(volume = it)) }, valueRange = 0f..1f)
                                    Text("Velocidad", color = Muted, fontSize = 12.sp)
                                    Slider(value = readerSettings.speed, onValueChange = { saveReaderSettings(readerSettings.copy(speed = it)) }, valueRange = 0.65f..1.5f)
                                    OutlinedTextField(
                                        value = readerSettings.blockedWords.joinToString("\n"),
                                        onValueChange = { value -> saveReaderSettings(readerSettings.copy(blockedWords = value.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet())) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Palabras bloqueadas (una por línea)") },
                                        minLines = 3
                                    )
                                    OutlinedTextField(
                                        value = readerSettings.ignoredUsers.joinToString("\n"),
                                        onValueChange = { value -> saveReaderSettings(readerSettings.copy(ignoredUsers = value.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet())) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Usuarios ignorados (uno por línea)") },
                                        minLines = 3
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Actualizaciones", fontWeight = FontWeight.SemiBold)
                                    Text(updateStatus, color = Muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(enabled = !updateBusy, onClick = {
                                            scope.launch {
                                                updateBusy = true
                                                try {
                                                    update = withContext(Dispatchers.IO) { DesktopUpdateManager.checkForUpdate("0.13.15") }
                                                    updateStatus = if (update == null) "TikLBot PC está actualizado." else "Disponible TikLBot PC ${update!!.versionName}"
                                                } catch (error: Exception) { updateStatus = error.message ?: "Error de actualización" }
                                                finally { updateBusy = false }
                                            }
                                        }) { Text("Buscar actualizaciones") }
                                        Button(enabled = update != null && !updateBusy, onClick = {
                                            val target = update ?: return@Button
                                            scope.launch {
                                                updateBusy = true
                                                try {
                                                    val installer = withContext(Dispatchers.IO) { DesktopUpdateManager.downloadAndVerify(target) }
                                                    DesktopUpdateManager.launchInstaller(installer)
                                                    exitApplication()
                                                } catch (error: Exception) { updateStatus = "No se pudo instalar: ${error.message ?: "error"}" }
                                                finally { updateBusy = false }
                                            }
                                        }) { Text("Descargar e instalar") }
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
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CommentList(comments: List<LiveComment>, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        if (comments.isEmpty()) Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text("Los comentarios aparecerán aquí cuando se conecte a un LIVE.", color = Muted)
        } else LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(comments) { comment ->
                Row(Modifier.fillMaxWidth().background(PanelRaised, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(comment.displayName, color = if (comment.isModerator) Cyan else Color.White, fontWeight = FontWeight.SemiBold)
                        Text(comment.text, color = Color(0xFFE5E6F0))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePanel(
    settings: ReaderSettings,
    muted: Boolean,
    onSettingsChange: (ReaderSettings) -> Unit,
    onMutedChange: (Boolean) -> Unit
) {
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Voz del lector", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Motor disponible en esta compilación: Microsoft Edge TTS.", color = Muted, fontSize = 13.sp)
            Text("Voz", color = Muted, fontSize = 12.sp)
            Box {
                OutlinedButton(onClick = { voiceMenuExpanded = true }) {
                    Text(EdgeTtsVoiceCatalog.find(settings.edgeVoiceId).name)
                }
                DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                    EdgeTtsVoiceCatalog.voices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.name) },
                            onClick = {
                                voiceMenuExpanded = false
                                onSettingsChange(settings.copy(edgeVoiceId = voice.id))
                            }
                        )
                    }
                }
            }
            SettingToggle("Silenciar voz", muted, onMutedChange)
            Text("Volumen", color = Muted, fontSize = 12.sp)
            Slider(value = settings.volume, onValueChange = { onSettingsChange(settings.copy(volume = it)) }, valueRange = 0f..1f)
            Text("Velocidad", color = Muted, fontSize = 12.sp)
            Slider(value = settings.speed, onValueChange = { onSettingsChange(settings.copy(speed = it)) }, valueRange = 0.65f..1.5f)
            Text("Los motores Piper, TikTok TTS y TikFinity todavía requieren su adaptación y pruebas específicas para Windows.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AutomationPanel(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Alertas automáticas", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Activa los avisos que TikLBot debe leer durante el LIVE.", color = Muted, fontSize = 13.sp)
                    SettingToggle("Nuevos seguidores", settings.readFollows) { onChange(settings.copy(readFollows = it)) }
                    SettingToggle("Regalos", settings.readGifts) { onChange(settings.copy(readGifts = it)) }
                    SettingToggle("Compartidos", settings.readShares) { onChange(settings.copy(readShares = it)) }
                    SettingToggle("Solo seguidores para compartidos", settings.shareFollowerOnly) { onChange(settings.copy(shareFollowerOnly = it)) }
                    SettingToggle("Entradas al LIVE", settings.readJoins) { onChange(settings.copy(readJoins = it)) }
                    SettingToggle("Solo seguidores para entradas", settings.joinFollowerOnly) { onChange(settings.copy(joinFollowerOnly = it)) }
                    SettingToggle("Likes", settings.readLikes) { onChange(settings.copy(readLikes = it)) }
                    OutlinedTextField(
                        value = settings.giftMinimumRepeatCount.toString(),
                        onValueChange = { it.toIntOrNull()?.let { count -> onChange(settings.copy(giftMinimumRepeatCount = count.coerceIn(1, 100))) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Repeticiones mínimas del regalo") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = settings.giftAlertTemplate,
                        onValueChange = { onChange(settings.copy(giftAlertTemplate = it.take(280))) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Plantilla de regalo: {user} y {count}") },
                        minLines = 2
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bienvenidas y comandos", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    SettingToggle("Dar bienvenida en el primer comentario", settings.welcomeEnabled) { onChange(settings.copy(welcomeEnabled = it)) }
                    OutlinedTextField(
                        value = settings.welcomeTemplate,
                        onValueChange = { onChange(settings.copy(welcomeTemplate = it.take(280))) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mensaje de bienvenida ({user})") },
                        minLines = 2
                    )
                    SettingToggle("Comandos personalizados para moderadores", settings.customCommandsEnabled) { onChange(settings.copy(customCommandsEnabled = it)) }
                    OutlinedTextField(
                        value = settings.customCommands.entries.joinToString("\n") { "${it.key}=${it.value}" },
                        onValueChange = { onChange(settings.copy(customCommands = com.myclivebot.core.ChatCommandParser.parseConfig(it))) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Un comando por línea: !hola=Hola {user}") },
                        minLines = 4
                    )
                    Text("Los comandos solo generan respuesta de voz local; no envían mensajes al chat.", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DjMusicPanel(state: DjMusicState) {
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<DjMusicTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var playerReady by remember { mutableStateOf(false) }
    var playerPlaying by remember { mutableStateOf(false) }
    var activeSearch by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activeSearchId by remember { mutableIntStateOf(0) }
    var searchView by remember { mutableStateOf<DesktopYoutubeSearchView?>(null) }
    var playerView by remember { mutableStateOf<DesktopYoutubePlayerView?>(null) }
    var accessMenu by remember { mutableStateOf(false) }
    val searchCounter = remember { AtomicInteger(0) }

    val handleSearchResults = rememberUpdatedState<(Int, String) -> Unit> { requestId, json ->
        if (requestId == searchCounter.get()) {
            val requester = activeSearch
            if (requester != null) {
                val tracks = runCatching {
                    val items = JSONArray(json)
                    (0 until minOf(items.length(), 8)).mapNotNull { index ->
                        val item = items.optJSONObject(index) ?: return@mapNotNull null
                        val id = item.optString("id")
                        if (id.length != 11 || !id.matches(Regex("[A-Za-z0-9_-]{11}"))) return@mapNotNull null
                        DjMusicTrack(
                            videoId = id,
                            title = item.optString("title").ifBlank { "Video de YouTube" },
                            thumbnailUrl = item.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
                            duration = item.optString("duration"),
                            requestedBy = requester.second,
                            artistName = item.optString("artistName"),
                            artistUrl = item.optString("artistUrl"),
                            artistThumbnailUrl = item.optString("artistThumbnail")
                        )
                    }
                }.getOrDefault(emptyList())
                searching = false
                activeSearch = null
                DjMusicController.completeSearch(requester.first, requester.second)
                if (tracks.isNotEmpty()) {
                    if (requester.second == "Tú") {
                        searchResults = tracks
                        DjMusicController.setMessage("Se encontraron ${tracks.size} resultados. Elige una canción.")
                    } else {
                        DjMusicController.addTrack(tracks.first())
                    }
                } else {
                    searchResults = emptyList()
                    DjMusicController.setMessage("No se encontraron resultados legibles. Prueba otro título o URL.")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { searchView?.close() }
            runCatching { playerView?.close() }
        }
    }

    LaunchedEffect(state.pendingSearches, state.activeSearch, searchView, searching) {
        val browser = searchView
        if (browser != null && !searching) {
            val request = state.activeSearch ?: DjMusicController.takeNextSearch()
            if (request != null) {
                activeSearch = request
                val requestId = searchCounter.incrementAndGet()
                activeSearchId = requestId
                searching = true
                searchResults = emptyList()
                DjMusicController.setMessage("Buscando canciones en YouTube…")
                browser.search(request.first, requestId)
            }
        }
    }

    LaunchedEffect(searching, activeSearchId) {
        if (searching) {
            val requestId = activeSearchId
            kotlinx.coroutines.delay(20_000)
            if (searching && activeSearchId == requestId) {
                searching = false
                searchCounter.incrementAndGet()
                activeSearch?.let { DjMusicController.completeSearch(it.first, it.second) }
                activeSearch = null
                DjMusicController.setMessage("YouTube no respondió a tiempo. Comprueba tu conexión e inténtalo otra vez.")
            }
        }
    }

    LaunchedEffect(state.currentTrack?.videoId, playerView) {
        val track = state.currentTrack
        val player = playerView
        if (track != null && player != null) {
            playerReady = false
            playerPlaying = false
            runCatching { player.loadTrack(track, state.volume, state.muted) }
                .onFailure { DjMusicController.setMessage("No se pudo abrir el reproductor: ${it.message ?: "error"}") }
        }
    }

    LaunchedEffect(state.volume, state.muted, playerView) {
        playerView?.setVolume(state.volume)
        playerView?.setMuted(state.muted)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DJ Music · YouTube", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Busca canciones, gestiona solicitudes y controla la reproducción.", color = Muted, fontSize = 12.sp)
                    }
                    Box {
                        OutlinedButton(onClick = { accessMenu = true }) {
                            Text(when { state.moderatorsOnly -> "Moderadores"; state.followersOnly -> "Seguidores"; else -> "Todos" })
                        }
                        DropdownMenu(expanded = accessMenu, onDismissRequest = { accessMenu = false }) {
                            DropdownMenuItem(text = { Text("Todos") }, onClick = {
                                DjMusicController.setFollowersOnly(false)
                                DjMusicController.setModeratorsOnly(false)
                                accessMenu = false
                            })
                            DropdownMenuItem(text = { Text("Solo seguidores") }, onClick = {
                                DjMusicController.setFollowersOnly(true)
                                accessMenu = false
                            })
                            DropdownMenuItem(text = { Text("Solo moderadores") }, onClick = {
                                DjMusicController.setModeratorsOnly(true)
                                accessMenu = false
                            })
                        }
                    }
                }
                Text("Comandos LIVE: !play nombre de canción · !skip (solo moderadores)", color = Cyan, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Canción, artista o URL de YouTube") },
                        placeholder = { Text("José José - Volcán") }
                    )
                    Button(enabled = query.isNotBlank() && !searching, onClick = {
                        DjMusicController.enqueueSearch(query, "Tú")
                        query = ""
                    }) { Text(if (searching) "Buscando…" else "Buscar") }
                }
                Text(state.message, color = Muted, fontSize = 12.sp)
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resultados de búsqueda", color = Cyan, fontWeight = FontWeight.SemiBold)
                if (searchResults.isEmpty()) {
                    Text(if (searching) "Buscando coincidencias en YouTube…" else "Los resultados aparecerán aquí para que elijas qué reproducir.", color = Muted, fontSize = 12.sp)
                } else {
                    searchResults.forEach { track ->
                        Row(Modifier.fillMaxWidth().background(PanelRaised, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(track.title, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2)
                                Text(listOf(track.artistName, track.duration).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.sp)
                                if (track.artistUrl.isNotBlank()) {
                                    TextButton(onClick = { runCatching { Desktop.getDesktop().browse(URI(track.artistUrl)) } }) {
                                        Text("Ver perfil del artista")
                                    }
                                }
                            }
                            OutlinedButton(onClick = {
                                DjMusicController.addTrack(track)
                                searchResults = searchResults.filterNot { it.videoId == track.videoId }
                            }) { Text("Cola") }
                            Button(onClick = {
                                DjMusicController.playTrackNow(track)
                                searchResults = searchResults.filterNot { it.videoId == track.videoId }
                            }) { Text("Reproducir") }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reproduciendo", color = Cyan, fontWeight = FontWeight.SemiBold)
                val current = state.currentTrack
                Text(current?.title ?: "No hay ninguna canción seleccionada.", color = Color.White, fontWeight = FontWeight.Medium)
                if (current != null) {
                    Text("Solicitada por ${current.requestedBy}", color = Muted, fontSize = 11.sp)
                }
                SwingPanel(
                    factory = {
                        DesktopYoutubePlayerView(
                            onEnded = { DjMusicController.skip("La canción terminó.") },
                            onError = { code ->
                                val detail = when (code) {
                                    2 -> "ID o parámetro inválido"
                                    5 -> "falló el reproductor HTML5"
                                    100 -> "el video no existe o es privado"
                                    101, 150 -> "el propietario no permite la reproducción incrustada"
                                    153 -> "YouTube no recibió el HTTP Referer de la aplicación"
                                    else -> "error $code"
                                }
                                DjMusicController.setMessage("YouTube no pudo reproducir el video: $detail. Prueba otra coincidencia.")
                            },
                            onReady = { playerReady = true },
                            onState = { value ->
                                playerPlaying = value == 1
                                if (value == -1) DjMusicController.setMessage("YouTube bloqueó el inicio automático. Pulsa Reproducir.")
                            }
                        ).also { playerView = it }
                    },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    update = { playerView = it as DesktopYoutubePlayerView }
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        DjMusicController.setMuted(!state.muted)
                        playerView?.setMuted(!state.muted)
                    }) { Text(if (state.muted) "Activar sonido" else "Silenciar") }
                    Slider(
                        value = state.volume.toFloat(),
                        onValueChange = {
                            DjMusicController.setVolume(it.toInt())
                            playerView?.setVolume(it.toInt())
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${state.volume}%", color = Muted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = current != null && playerReady, onClick = { playerView?.seekBy(-10) }) { Text("−10 s") }
                    Button(enabled = current != null && playerReady, onClick = { playerView?.playPause() }) {
                        Text(if (playerPlaying) "Pausar" else "Reproducir")
                    }
                    OutlinedButton(enabled = current != null && playerReady, onClick = { playerView?.seekBy(10) }) { Text("+10 s") }
                    OutlinedButton(enabled = current != null, onClick = { DjMusicController.skip() }) { Text("Siguiente") }
                }
            }
        }

        Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cola de reproducción · ${state.queue.size}", color = Cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(enabled = state.queue.isNotEmpty(), onClick = { DjMusicController.clearQueue() }) { Text("Vaciar") }
                }
                if (state.queue.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("La cola está vacía.", color = Muted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(state.queue) { index, track ->
                            Row(Modifier.fillMaxWidth().background(PanelRaised, RoundedCornerShape(9.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${index + 1}. ${track.title}", color = Color.White, maxLines = 1)
                                    Text("Solicitada por ${track.requestedBy}", color = Muted, fontSize = 11.sp)
                                }
                                TextButton(enabled = index > 0, onClick = { DjMusicController.moveQueueTrack(index, index - 1) }) { Text("↑") }
                                TextButton(enabled = index < state.queue.lastIndex, onClick = { DjMusicController.moveQueueTrack(index, index + 1) }) { Text("↓") }
                                TextButton(onClick = { DjMusicController.playQueueTrackNow(index) }) { Text("Ahora") }
                                TextButton(onClick = { DjMusicController.removeQueueTrack(index) }) { Text("Quitar") }
                            }
                        }
                    }
                }
            }
        }
    }

    SwingPanel(
        factory = {
            DesktopYoutubeSearchView { requestId, json -> handleSearchResults.value(requestId, json) }
                .also { searchView = it }
        },
        modifier = Modifier.size(1.dp),
        update = { searchView = it as DesktopYoutubeSearchView }
    )
}

@Composable
private fun FeatureNotice(title: String, description: String, detail: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(description, color = Color.White)
            Text(detail, color = Muted)
            Text("Adaptación en desarrollo — esta sección todavía no se declara funcional.", color = Cyan, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
