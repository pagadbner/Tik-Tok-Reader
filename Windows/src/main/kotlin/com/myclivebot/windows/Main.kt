package com.myclivebot.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window as DesktopWindow
import com.myclivebot.core.LiveComment
import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.CommentFilter
import com.myclivebot.core.CommentHandlingResult
import com.myclivebot.core.TikTokLiveConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicReference

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
    var selected by remember { mutableStateOf("Home") }
    var username by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Desconectado") }
    var connected by remember { mutableStateOf(false) }
    val comments = remember { mutableStateListOf<LiveComment>() }
    var readerSettings by remember { mutableStateOf(DesktopSettingsStore.load()) }
    val settingsReference = remember { AtomicReference(readerSettings) }
    val commentFilter = remember { CommentFilter() }
    var filteredComments by remember { mutableStateOf(0) }

    fun saveReaderSettings(next: ReaderSettings) {
        readerSettings = next
        settingsReference.set(next)
        DesktopSettingsStore.save(next)
    }
    var connector by remember { mutableStateOf<TikTokLiveConnector?>(null) }
    var update by remember { mutableStateOf<DesktopUpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("Comprobando actualizaciones…") }
    var updateBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun stopLive() {
        connector?.close()
        connector = null
        connected = false
        status = "Desconectado"
    }

    DisposableEffect(Unit) {
        onDispose { connector?.close() }
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
                                        Button(enabled = !connected && username.isNotBlank(), onClick = {
                                            comments.clear()
                                            status = "Preparando conexión…"
                                            val live = TikTokLiveConnector(
                                                onComment = { comment ->
                                                    val accepted = commentFilter.shouldRead(comment, settingsReference.get())
                                                    SwingUtilities.invokeLater {
                                                        comments.add(0, comment)
                                                        while (comments.size > 300) comments.removeAt(comments.lastIndex)
                                                        if (!accepted) filteredComments++
                                                    }
                                                    if (accepted) CommentHandlingResult.ACCEPTED else CommentHandlingResult.FILTERED
                                                },
                                                onStatus = { value -> SwingUtilities.invokeLater { status = value } }
                                            )
                                            connector = live
                                            connected = true
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
                                StatCard("Estado de conexión", if (connected) "Activo" else "Inactivo", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Chat en vivo", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            CommentList(comments, Modifier.weight(1f))
                        }
                        "Chat en vivo" -> CommentList(comments, Modifier.fillMaxSize())
                        "DJ Music" -> FeatureNotice(
                            "DJ Music",
                            "La interfaz de escritorio y el reproductor de YouTube se están adaptando. El motor Android no se modifica.",
                            "La cola, búsqueda, controles y reproducción se conectarán en la siguiente etapa."
                        )
                        "Voces" -> FeatureNotice(
                            "Motores de voz",
                            "La capa de audio Windows debe sustituir AudioTrack y las rutas de archivos Android.",
                            "La integración de Piper y los proveedores de voz se está preparando para Windows."
                        )
                        "Automatizaciones" -> FeatureNotice(
                            "Automatizaciones del LIVE",
                            "Se conservarán las reglas de bienvenida, regalos, follows, comandos y filtros.",
                            "La conexión base ya reutiliza el transporte JVM de la versión Android."
                        )
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
