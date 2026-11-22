package com.myclivebot.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window as DesktopWindow
import com.myclivebot.core.LiveComment
import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.CommentFilter
import com.myclivebot.core.CommentPipeline
import com.myclivebot.core.SpeechQueueWorker
import com.myclivebot.core.SelectableSpeechEngine
import com.myclivebot.core.EdgeTtsVoiceCatalog
import com.myclivebot.core.CommentHandlingResult
import com.myclivebot.core.TikTokLiveConnector
import com.myclivebot.core.DjMusicController
import com.myclivebot.core.DjMusicState
import com.myclivebot.core.DjMusicTrack
import com.myclivebot.core.TikTokTtsVoiceCatalog
import com.myclivebot.core.TikFinityVoiceCatalog
import com.myclivebot.core.PiperVoiceCatalog
import com.myclivebot.core.PiperSpeechEngine
import com.myclivebot.core.TikTokProfileLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.BorderLayout
import java.awt.Desktop
import java.net.URI
import java.net.URL
import java.awt.Image
import java.awt.image.BufferedImage
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.WindowConstants
import java.util.concurrent.atomic.AtomicInteger
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import javax.swing.SwingUtilities

private val Ink: Color
    @Composable get() = LocalDesktopTheme.current.background
private val Panel: Color
    @Composable get() = LocalDesktopTheme.current.panel
private val PanelRaised: Color
    @Composable get() = LocalDesktopTheme.current.surfaceVariant
private val Violet: Color
    @Composable get() = LocalDesktopTheme.current.primary
private val Cyan: Color
    @Composable get() = LocalDesktopTheme.current.secondary
private val Muted: Color
    @Composable get() = LocalDesktopTheme.current.muted

fun main() {
    applicationWindow()
}

@OptIn(ExperimentalMaterial3Api::class)
private fun applicationWindow() = androidx.compose.ui.window.application {
    // Install the public cookie store before the first JavaFX WebView is created.
    DesktopTtwidCookieProvider.install()
    val scope = rememberCoroutineScope()
    val runtimeServices = remember { DesktopRuntimeServices() }
    var historyComments by remember { mutableStateOf<List<LiveComment>>(emptyList()) }
    var profileBusy by remember { mutableStateOf(false) }
    var profileLookupRequest by remember { mutableIntStateOf(0) }
    var profileLive by remember { mutableStateOf<Boolean?>(null) }
    var profileAvatar by remember { mutableStateOf<ImageIcon?>(null) }
    var profileAvatarUrl by remember { mutableStateOf<String?>(null) }
    var recentProfiles by remember { mutableStateOf(DesktopProfileStore.load()) }
    var selected by remember { mutableStateOf("Home") }
    var windowVisible by remember { mutableStateOf(true) }
    var trayAvailable by remember { mutableStateOf(false) }
    var trayIcon by remember { mutableStateOf<TrayIcon?>(null) }
    var username by remember { mutableStateOf("") }
    val latestProfileUsername = rememberUpdatedState(username)
    var status by remember { mutableStateOf("Desconectado") }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val comments = remember { mutableStateListOf<LiveComment>() }
    val skippedCommentIds = remember { mutableStateListOf<String>() }
    val commentSideEffectLock = remember { Any() }
    val processedCommentSideEffects = remember { LinkedHashSet<String>() }
    val djMusicState by DjMusicController.state.collectAsState()
    var readerSettings by remember { mutableStateOf(DesktopSettingsStore.load()) }
    var filteredComments by remember { mutableStateOf(0) }
    var voiceMuted by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("Motor de voz listo.") }
    val speechEngine = remember {
        SelectableSpeechEngine(readerSettings) { error ->
            SwingUtilities.invokeLater { voiceStatus = error.message ?: "Error del motor de voz." }
        }
    }
    val speechQueue = remember { SpeechQueueWorker(speechEngine, scope, capacity = readerSettings.maxQueueSize) }
    val commentPipeline = remember { CommentPipeline(CommentFilter(), speechQueue, readerSettings) }

    fun saveReaderSettings(next: ReaderSettings) {
        readerSettings = next
        DesktopSettingsStore.save(next)
        commentPipeline.updateSettings(next)
        speechQueue.updateCapacity(next.maxQueueSize)
        speechEngine.update(next)
    }
    var connector by remember { mutableStateOf<TikTokLiveConnector?>(null) }
    var update by remember { mutableStateOf<DesktopUpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("Comprobando actualizaciones…") }
    var updateBusy by remember { mutableStateOf(false) }
    var updatePromptShown by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var desktopTheme by remember { mutableStateOf(DesktopThemeStore.load()) }

    fun saveDesktopTheme(theme: DesktopThemePalette) {
        desktopTheme = theme
        DesktopThemeStore.save(theme.id)
    }

    fun stopLive() {
        connector?.close()
        connector = null
        connected = false
        connecting = false
        synchronized(commentSideEffectLock) { processedCommentSideEffects.clear() }
        status = "Desconectado"
    }

    fun installUpdate(target: DesktopUpdateInfo) {
        scope.launch {
            updateBusy = true
            updateStatus = "Descargando y verificando el instalador…"
            try {
                val installer = withContext(Dispatchers.IO) { DesktopUpdateManager.downloadAndVerify(target) }
                updateStatus = "Instalador verificado. Cerrando la aplicación…"
                DesktopUpdateManager.launchInstaller(installer)
                stopLive()
                exitApplication()
            } catch (error: Exception) {
                updateStatus = "No se pudo instalar: ${error.message ?: "error"}"
                updatePromptShown = false
            } finally {
                updateBusy = false
            }
        }
    }

    fun checkForUpdates() {
        if (updateBusy) return
        scope.launch {
            updateBusy = true
            updateStatus = "Buscando actualizaciones…"
            try {
                val available = withContext(Dispatchers.IO) {
                    DesktopUpdateManager.checkForUpdate(DesktopUpdateManager.CURRENT_VERSION)
                }
                update = available
                updateStatus = if (available == null) {
                    "Tik Tok Reader está actualizado."
                } else {
                    "Disponible la versión ${available.versionName}"
                }
                updatePromptShown = available != null
            } catch (error: Exception) {
                updateStatus = "No se pudo comprobar: ${error.message ?: "error de red"}"
            } finally {
                updateBusy = false
            }
        }
    }

    fun skipQueuedComment(commentId: String) {
        if (speechQueue.skipComment(commentId) && commentId !in skippedCommentIds) {
            skippedCommentIds.add(commentId)
        }
    }

    fun clearLiveChat() {
        comments.clear()
        skippedCommentIds.clear()
        historyComments = emptyList()
        scope.launch(Dispatchers.IO) { runtimeServices.clearHistory() }
    }

    DisposableEffect(Unit) {
        onDispose { connector?.close(); commentPipeline.close(); speechQueue.close(); speechEngine.release(); runtimeServices.close() }
    }

    DisposableEffect(Unit) {
        var installedIcon: TrayIcon? = null
        if (SystemTray.isSupported()) {
            val icon = createTrayIcon(
                onOpen = { windowVisible = true },
                onDisconnect = { stopLive() },
                onExit = { stopLive(); exitApplication() }
            )
            runCatching {
                SystemTray.getSystemTray().add(icon)
                installedIcon = icon
                trayIcon = icon
                trayAvailable = true
            }
        }
        onDispose {
            installedIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
            trayIcon = null
            trayAvailable = false
        }
    }

    LaunchedEffect(connected, connecting, status) {
        trayIcon?.toolTip = when {
            connected -> "Tik Tok Reader — LIVE conectado"
            connecting -> "Tik Tok Reader — conectando al LIVE"
            else -> "Tik Tok Reader — desconectado"
        }
    }

    LaunchedEffect(username, profileLookupRequest) {
        profileBusy = false
        val cleanUsername = username.trim().removePrefix("@").substringBefore("/").lowercase()
        if (!cleanUsername.matches(Regex("[a-z0-9._]{2,24}"))) {
            profileAvatar = null
            profileAvatarUrl = null
            profileLive = null
            return@LaunchedEffect
        }
        profileAvatar = null
        profileAvatarUrl = recentProfiles.firstOrNull { it.username.equals(cleanUsername, true) }?.avatarUrl
        profileLive = null
        delay(550)
        profileBusy = true
        try {
            val avatarUrl = TikTokProfileLookup.fetchAvatarUrl(cleanUsername)
            val avatar = withContext(Dispatchers.IO) {
                avatarUrl?.let { imageUrl ->
                    val connection = URL(imageUrl).openConnection().apply {
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    connection.getInputStream().use { ImageIO.read(it) }
                        ?.getScaledInstance(128, 128, Image.SCALE_SMOOTH)
                        ?.let(::ImageIcon)
                }
            }
            val liveState = TikTokProfileLookup.fetchLiveStatus(cleanUsername)
            val currentUsername = latestProfileUsername.value.trim().removePrefix("@").substringBefore("/").lowercase()
            if (currentUsername == cleanUsername) {
                profileAvatar = avatar
                profileAvatarUrl = avatarUrl
                profileLive = liveState
                recentProfiles = DesktopProfileStore.save(cleanUsername, avatarUrl)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val currentUsername = latestProfileUsername.value.trim().removePrefix("@").substringBefore("/").lowercase()
            if (currentUsername == cleanUsername) {
                profileAvatar = null
                profileLive = null
            }
        } finally {
            val currentUsername = latestProfileUsername.value.trim().removePrefix("@").substringBefore("/").lowercase()
            if (currentUsername == cleanUsername) profileBusy = false
        }
    }

    LaunchedEffect(Unit) {
        launch {
            try {
                withContext(Dispatchers.IO) { speechEngine.initialize() }
                voiceStatus = "Motor de voz preparado."
            } catch (error: Exception) {
                voiceStatus = "No se pudo preparar el motor de voz: ${error.message ?: "revisa el proveedor y la voz seleccionada"}"
            }
        }
        checkForUpdates()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = desktopTheme.primary, secondary = desktopTheme.secondary,
        background = desktopTheme.background, onBackground = Color.White,
        surface = desktopTheme.panel, onSurface = Color.White,
        surfaceVariant = desktopTheme.surfaceVariant, onSurfaceVariant = desktopTheme.muted,
        primaryContainer = desktopTheme.surfaceVariant, onPrimaryContainer = Color.White,
        secondaryContainer = desktopTheme.surfaceVariant, onSecondaryContainer = Color.White,
        onPrimary = Color.White, onSecondary = Color.Black,
        outline = desktopTheme.muted, outlineVariant = desktopTheme.surfaceVariant
    )) {
        CompositionLocalProvider(LocalDesktopTheme provides desktopTheme) {
        DesktopWindow(
            visible = windowVisible,
            onCloseRequest = {
                if (trayAvailable && (connected || connecting)) windowVisible = false
                else { stopLive(); exitApplication() }
            },
            title = "Tik Tok Reader"
        ) {
            if (updatePromptShown && update != null) {
                AlertDialog(
                    onDismissRequest = { if (!updateBusy) updatePromptShown = false },
                    title = { Text("Actualización disponible") },
                    text = { Text("Hay una nueva versión de Tik Tok Reader para Windows: ${update!!.versionName}. El instalador se descargará y verificará mediante SHA-256 antes de ejecutarse.") },
                    confirmButton = {
                        Button(enabled = !updateBusy, onClick = {
                            val target = update
                            if (target != null) {
                                updatePromptShown = false
                                installUpdate(target)
                            }
                        }) { Text(if (updateBusy) "Preparando…" else "Actualizar ahora") }
                    },
                    dismissButton = {
                        TextButton(enabled = !updateBusy, onClick = { updatePromptShown = false }) { Text("Más tarde") }
                    }
                )
            }
            if (showUninstallDialog) {
                AlertDialog(
                    onDismissRequest = { showUninstallDialog = false },
                    title = { Text("Desinstalar Tik Tok Reader") },
                    text = {
                        Text("Se abrirá la configuración de aplicaciones de Windows. Busca Tik Tok Reader y selecciona Desinstalar. Esta acción no elimina manualmente tus datos ni configuraciones personales.")
                    },
                    confirmButton = {
                        Button(onClick = {
                            showUninstallDialog = false
                            runCatching {
                                ProcessBuilder("explorer.exe", "ms-settings:appsfeatures").start()
                            }.onFailure {
                                updateStatus = "No se pudo abrir la configuración de Windows: ${it.message ?: "error"}"
                            }
                        }) { Text("Abrir desinstalación") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUninstallDialog = false }) { Text("Cancelar") }
                    }
                )
            }
            Row(Modifier.fillMaxSize().background(Ink)) {
                Column(
                    Modifier.width(224.dp).fillMaxHeight().background(Panel).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TikTokReaderLogo(42.dp)
                        Column {
                            Text("Tik Tok Reader", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("DESKTOP • ${DesktopUpdateManager.CURRENT_VERSION}", fontSize = 10.sp, color = Muted)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    listOf("Home", "Chat en vivo", "Historial", "DJ Music", "Voces", "Automatizaciones", "Settings").forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item) },
                            selected = selected == item,
                            onClick = { selected = item },
                            icon = {
                                Icon(when(item) {
                                    "Home" -> Icons.Default.Home
                                    "Chat en vivo" -> Icons.Default.Forum
                                    "Historial" -> Icons.Default.History
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
                    Divider(color = PanelRaised)
                    Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Tik Tok Reader", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(updateStatus, color = Muted, fontSize = 10.sp, maxLines = 2)
                            }
                            Button(
                                enabled = !updateBusy,
                                onClick = {
                                    if (update != null) updatePromptShown = true
                                    else checkForUpdates()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (update != null) "Actualizar ahora" else "Buscar actualizaciones", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { showUninstallDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Desinstalar", fontSize = 11.sp)
                            }
                        }
                    }
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
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    when(selected) {
                        "Home" -> {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
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
                                            val cleanUsername = username.trim().removePrefix("@").substringBefore("/").lowercase()
                                            if (!cleanUsername.matches(Regex("[a-z0-9._]{2,24}"))) {
                                                status = "Escribe un @usuario de TikTok válido (2–24 letras, números, puntos o guiones bajos)."
                                                return@Button
                                            }
                                            username = cleanUsername
                                            comments.clear()
                                            skippedCommentIds.clear()
                                            synchronized(commentSideEffectLock) { processedCommentSideEffects.clear() }
                                            connecting = true
                                            connected = false
                                            recentProfiles = DesktopProfileStore.save(cleanUsername, profileAvatarUrl)
                                            status = "Preparando conexión…"
                                            val live = TikTokLiveConnector(
                                                onComment = { comment ->
                                                    val commentKey = comment.id.ifBlank { "${comment.userId}:${comment.displayName}:${comment.text}" }
                                                    val firstDelivery = synchronized(commentSideEffectLock) {
                                                        if (commentKey in processedCommentSideEffects) false
                                                        else {
                                                            processedCommentSideEffects.add(commentKey)
                                                            while (processedCommentSideEffects.size > 10_000) {
                                                                processedCommentSideEffects.remove(processedCommentSideEffects.first())
                                                            }
                                                            true
                                                        }
                                                    }
                                                    if (firstDelivery) {
                                                        // Begin avatar retrieval before the chat row is queued for display.
                                                        scope.launch(Dispatchers.IO) {
                                                            RemoteAvatarCache.preload(comment.avatarUrl, comment.profileUsername)
                                                        }
                                                    }
                                                    if (firstDelivery &&
                                                        (comment.text.trim().startsWith("!play ", ignoreCase = true) ||
                                                            comment.text.trim().equals("!skip", ignoreCase = true))
                                                    ) {
                                                        runCatching { DjMusicController.handleLiveComment(comment) }
                                                    }
                                                    val result = commentPipeline.onComment(comment)
                                                    if (firstDelivery) {
                                                        runtimeServices.recordComment(comment)
                                                        SwingUtilities.invokeLater {
                                                            comments.add(0, comment)
                                                            while (comments.size > 300) comments.removeAt(comments.lastIndex)
                                                            if (result == CommentHandlingResult.FILTERED) filteredComments++
                                                        }
                                                    }
                                                    result
                                                },
                                                onStatus = { value ->
                                                    runtimeServices.publishStatus(value)
                                                    SwingUtilities.invokeLater {
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
                                                onEvent = { event -> commentPipeline.onEvent(event) },
                                                cookieFallback = { DesktopTtwidCookieProvider.acquireTtwid() }
                                            )
                                            connector = live
                                            live.connect(cleanUsername)
                                        }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Conectar") }
                                        OutlinedButton(enabled = connected || connecting, onClick = { stopLive() }) {
                                            Icon(Icons.Default.Stop, null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (connecting && !connected) "Cancelar" else "Desconectar")
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    ProfileAvatar(profileAvatar)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(if (username.isBlank()) "Perfil de TikTok" else "@$username", fontWeight = FontWeight.SemiBold)
                                        Text(when (profileLive) { true -> "Transmitiendo en LIVE"; false -> "No está en LIVE"; null -> "Consulta el avatar y estado público del perfil" }, color = if (profileLive == true) Cyan else Muted, fontSize = 12.sp)
                                    }
                                    OutlinedButton(enabled = username.isNotBlank() && !profileBusy, onClick = { profileLookupRequest++ }) {
                                        Text(if (profileBusy) "Buscando…" else "Actualizar")
                                    }
                                }
                            }
                            if (recentProfiles.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Perfiles recientes", color = Cyan, fontWeight = FontWeight.SemiBold)
                                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(recentProfiles, key = { it.username }) { profile ->
                                                AssistChip(
                                                    onClick = { username = profile.username },
                                                    label = { Text("@${profile.username}") },
                                                    leadingIcon = { RemoteAvatar(profile.avatarUrl, profile.username, 24.dp) }
                                                )
                                            }
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
                            CommentList(
                                comments,
                                Modifier.height(320.dp),
                                onSkipComment = ::skipQueuedComment,
                                onClearChat = ::clearLiveChat,
                                skippedCommentIds = skippedCommentIds.toSet()
                            )
                            }
                        }
                        "Chat en vivo" -> CommentList(
                            comments,
                            Modifier.fillMaxSize(),
                            onSkipComment = ::skipQueuedComment,
                            onClearChat = ::clearLiveChat,
                            skippedCommentIds = skippedCommentIds.toSet()
                        )
                        "Historial" -> {
                            Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Historial guardado localmente", color = Muted)
                                Button(onClick = { scope.launch { historyComments = withContext(Dispatchers.IO) { runtimeServices.loadRecentHistory() } } }) {
                                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Actualizar")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (historyComments.isEmpty()) {
                                Text("Pulsa Actualizar para cargar los últimos comentarios guardados.", color = Muted)
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(historyComments.reversed(), key = { it.id }) { comment ->
                                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(comment.displayName, color = if (comment.isModerator) Cyan else Color.White, fontWeight = FontWeight.SemiBold)
                                                Text(comment.text, color = Color(0xFFE5E6F0))
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }
                        "DJ Music" -> DjMusicPanel(djMusicState)
                        "Voces" -> VoicePanel(
                            readerSettings,
                            voiceMuted,
                            onSettingsChange = ::saveReaderSettings,
                            onMutedChange = { voiceMuted = it; speechEngine.setMuted(it) },
                            onTestVoice = { speechQueue.enqueue("Hola, esta es una prueba de voz de Tik Tok Reader.", priority = true) },
                            connected = connected || connecting,
                            onDeleteVoice = { voiceId, callback ->
                                scope.launch {
                                    try {
                                        speechQueue.clearPendingComments()
                                        speechEngine.stop()
                                        speechEngine.release()
                                        if (readerSettings.speechProvider == "piper" && readerSettings.voiceId == voiceId) {
                                            val fallback = PiperVoiceCatalog.voices.firstOrNull {
                                                it.id != voiceId && PiperSpeechEngine.isVoiceInstalled(it.id)
                                            }?.id
                                            if (fallback != null) {
                                                saveReaderSettings(readerSettings.copy(voiceId = fallback, randomVoices = false))
                                            } else {
                                                saveReaderSettings(readerSettings.copy(speechProvider = "edge", randomVoices = false))
                                            }
                                        }
                                        withContext(Dispatchers.IO) { PiperSpeechEngine.deleteVoice(voiceId) }
                                        withContext(Dispatchers.IO) { speechEngine.initialize() }
                                        callback(null)
                                    } catch (error: Exception) {
                                        runCatching { withContext(Dispatchers.IO) { speechEngine.initialize() } }
                                        callback(error.message ?: "No se pudo eliminar la voz.")
                                    }
                                }
                            },
                            status = voiceStatus
                        )
                        "Automatizaciones" -> AutomationPanel(readerSettings, ::saveReaderSettings)
                        else -> {
                            var themeMenuExpanded by remember { mutableStateOf(false) }
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Apariencia", fontWeight = FontWeight.SemiBold)
                                    Text("Selecciona uno de los cuatro temas de Android. La selección se guarda en este equipo.", color = Muted, fontSize = 12.sp)
                                    Box {
                                        OutlinedButton(onClick = { themeMenuExpanded = true }) {
                                            Text(desktopTheme.name)
                                        }
                                        DropdownMenu(expanded = themeMenuExpanded, onDismissRequest = { themeMenuExpanded = false }) {
                                            DesktopThemePresets.all.forEach { theme ->
                                                DropdownMenuItem(
                                                    text = { Text(theme.name) },
                                                    onClick = {
                                                        saveDesktopTheme(theme)
                                                        themeMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Text(desktopTheme.description, color = Muted, fontSize = 11.sp)
                                }
                            }
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
                                    OutlinedTextField(
                                        value = readerSettings.maxQueueSize.toString(),
                                        onValueChange = { it.toIntOrNull()?.let { value -> saveReaderSettings(readerSettings.copy(maxQueueSize = value.coerceIn(5, 100))) } },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Tamaño máximo de cola de comentarios (5–100)") },
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = readerSettings.maxCommentLength.toString(),
                                        onValueChange = { it.toIntOrNull()?.let { value -> saveReaderSettings(readerSettings.copy(maxCommentLength = value.coerceIn(1, 2000))) } },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Longitud máxima del comentario (1–2000)") },
                                        singleLine = true
                                    )
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
                                        onValueChange = { value -> value.toIntOrNull()?.let { saveReaderSettings(readerSettings.copy(giftMinimumRepeatCount = it.coerceIn(1, 999))) } },
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
                                    CustomCommandEditor(
                                        commands = readerSettings.customCommands,
                                        onCommandsChange = { saveReaderSettings(readerSettings.copy(customCommands = it)) }
                                    )
                                    Text("Configura el motor, la voz, el volumen y la velocidad en la sección Voces.", color = Muted, fontSize = 12.sp)
                                    EditableStringList(
                                        title = "Palabras bloqueadas",
                                        placeholder = "Añadir palabra o frase",
                                        items = readerSettings.blockedWords,
                                        onItemsChange = { saveReaderSettings(readerSettings.copy(blockedWords = it)) }
                                    )
                                    EditableStringList(
                                        title = "Usuarios ignorados",
                                        placeholder = "@usuario",
                                        items = readerSettings.ignoredUsers,
                                        normalize = { it.trim().removePrefix("@") },
                                        onItemsChange = { saveReaderSettings(readerSettings.copy(ignoredUsers = it)) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
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

private fun createTrayIcon(
    onOpen: () -> Unit,
    onDisconnect: () -> Unit,
    onExit: () -> Unit
): TrayIcon {
    val menu = PopupMenu()
    MenuItem("Abrir Tik Tok Reader").apply {
        addActionListener { onOpen() }
        menu.add(this)
    }
    MenuItem("Desconectar LIVE").apply {
        addActionListener { onDisconnect() }
        menu.add(this)
    }
    menu.addSeparator()
    MenuItem("Salir").apply {
        addActionListener { onExit() }
        menu.add(this)
    }

    val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = java.awt.Color(9, 11, 22)
        graphics.fillRoundRect(1, 1, 30, 30, 9, 9)
        graphics.color = java.awt.Color(155, 107, 255)
        graphics.fillOval(5, 5, 22, 22)
        graphics.color = java.awt.Color.WHITE
        graphics.fillRoundRect(10, 11, 12, 3, 2, 2)
        graphics.fillRoundRect(10, 17, 8, 3, 2, 2)
        graphics.color = java.awt.Color(57, 215, 232)
        graphics.fillOval(20, 20, 7, 7)
    } finally {
        graphics.dispose()
    }
    return TrayIcon(image, "Tik Tok Reader", menu).apply {
        isImageAutoSize = true
        addActionListener { onOpen() }
    }
}

@Composable
private fun CustomCommandEditor(
    commands: Map<String, String>,
    onCommandsChange: (Map<String, String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("!") }
    var response by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf("") }
    var editingTrigger by remember { mutableStateOf<String?>(null) }
    var titles by remember { mutableStateOf(DesktopCommandTitleStore.load()) }

    fun clearEditor() {
        title = ""
        trigger = "!"
        response = ""
        validation = ""
        editingTrigger = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("COMANDOS PERSONALIZADOS", color = Cyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(40) },
            label = { Text("Título del comando") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = trigger,
            onValueChange = { trigger = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '!' }.take(25) },
            label = { Text("Activador (ej. !discord)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = response,
            onValueChange = { response = it.take(280) },
            label = { Text("Respuesta que dirá la voz") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = title.isNotBlank() && Regex("^![a-z0-9_]{1,24}$").matches(trigger.trim()) &&
                response.isNotBlank() && (editingTrigger != null || commands.size < 40),
            onClick = {
                val key = trigger.trim()
                val previous = editingTrigger
                val duplicate = commands.keys.any { it == key && it != previous }
                if (duplicate) {
                    validation = "Ya existe un comando con ese activador."
                } else {
                    val next = commands.filterKeys { it != previous && it != key } + (key to response.trim())
                    val nextTitles = titles.filterKeys { it != previous && it != key } + (key to title.trim())
                    onCommandsChange(next)
                    titles = nextTitles
                    DesktopCommandTitleStore.save(nextTitles)
                    clearEditor()
                }
            }
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text(if (editingTrigger == null) "Guardar comando" else "Guardar cambios")
        }
        if (editingTrigger != null) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { clearEditor() }) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Cancelar edición")
            }
        }
        commands.entries.forEach { (key, value) ->
            Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(titles[key] ?: key, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(key, color = Cyan, fontSize = 11.sp)
                        }
                        IconButton(onClick = {
                            title = titles[key] ?: key.removePrefix("!").replaceFirstChar { it.uppercase() }
                            trigger = key
                            response = value
                            validation = ""
                            editingTrigger = key
                        }) { Icon(Icons.Default.Edit, contentDescription = "Editar comando", tint = Cyan) }
                        IconButton(onClick = {
                            onCommandsChange(commands - key)
                            titles = titles - key
                            DesktopCommandTitleStore.save(titles)
                            if (editingTrigger == key) clearEditor()
                        }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar comando", tint = Muted) }
                    }
                    Text(value, color = Muted, fontSize = 12.sp)
                }
            }
        }
        Text("${commands.size}/40 comandos", color = Muted, fontSize = 11.sp)
        Text("Cada activador se reconoce al inicio del comentario. La respuesta se reproduce localmente; no se publica en TikTok.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun EditableStringList(
    title: String,
    placeholder: String,
    items: Set<String>,
    normalize: (String) -> String = { it.trim() },
    onItemsChange: (Set<String>) -> Unit
) {
    var draft by remember(title) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(80) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                enabled = normalize(draft).isNotBlank() && items.none { it.equals(normalize(draft), true) },
                onClick = {
                    val value = normalize(draft)
                    if (value.isNotBlank()) onItemsChange(items + value)
                    draft = ""
                }
            ) { Icon(Icons.Default.Add, contentDescription = "Añadir") }
        }
        if (items.isEmpty()) Text("Todavía no hay elementos.", color = Muted, fontSize = 11.sp)
        items.sorted().forEach { item ->
            Row(
                Modifier.fillMaxWidth().background(PanelRaised, RoundedCornerShape(9.dp)).padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item, modifier = Modifier.weight(1f), color = Color.White, fontSize = 12.sp)
                IconButton(onClick = { onItemsChange(items - item) }) {
                    Icon(Icons.Default.Close, contentDescription = "Eliminar $item", tint = Muted)
                }
            }
        }
    }
}

private object RemoteAvatarCache {
    val images = ConcurrentHashMap<String, ImageBitmap>()
    val userUrls = ConcurrentHashMap<String, String>()
    private val loadLocks = ConcurrentHashMap<String, Mutex>()
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun preload(url: String?, username: String) {
        val cleanUsername = username.trim().removePrefix("@").substringBefore("/")
            .takeIf { it.matches(Regex("[A-Za-z0-9._]{1,24}")) }.orEmpty()
        var resolvedUrl = url?.takeIf { it.startsWith("https://") }
            ?: cleanUsername.takeIf { it.isNotBlank() }?.let { userUrls[it] }
        if (resolvedUrl.isNullOrBlank() && cleanUsername.isNotBlank()) {
            resolvedUrl = TikTokProfileLookup.fetchAvatarUrl(cleanUsername)
        }
        if (!resolvedUrl.isNullOrBlank()) {
            val loaded = images[resolvedUrl] ?: load(resolvedUrl)
            if (loaded != null) {
                if (images.size > 300) images.clear()
                images[resolvedUrl] = loaded
                if (cleanUsername.isNotBlank()) userUrls[cleanUsername] = resolvedUrl
            }
        }
    }

    suspend fun load(url: String): ImageBitmap? {
        images[url]?.let { return it }
        val lock = loadLocks.computeIfAbsent(url) { Mutex() }
        return lock.withLock {
            images[url]?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36")
                        .header("Referer", "https://www.tiktok.com/")
                        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null
                        else {
                            val source = response.body?.byteStream()?.use { ImageIO.read(it) }
                            if (source == null) null
                            else {
                                val target = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
                                val graphics = target.createGraphics()
                                try {
                                    graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                                    graphics.drawImage(source, 0, 0, 128, 128, null)
                                } finally {
                                    graphics.dispose()
                                    source.flush()
                                }
                                target.toComposeImageBitmap()
                            }
                        }
                    }
                }.getOrNull()
            }
            if (loaded != null) {
                if (images.size > 300) images.clear()
                images[url] = loaded
            }
            loaded
        }
    }
}
@Composable
private fun ProfileAvatar(icon: ImageIcon?) {
    val bitmap = remember(icon) {
        val source = icon?.image
        if (source == null || icon.iconWidth <= 0 || icon.iconHeight <= 0) null
        else runCatching {
            BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB).also { target ->
                val graphics = target.createGraphics()
                try { graphics.drawImage(source, 0, 0, icon.iconWidth, icon.iconHeight, null) }
                finally { graphics.dispose() }
            }.toComposeImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.size(64.dp).clip(CircleShape).background(PanelRaised), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = "Avatar del perfil", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, contentDescription = "Avatar del perfil", tint = Muted, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun RemoteAvatar(
    url: String?,
    displayName: String,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    profileUsername: String = ""
) {
    val cleanUsername = profileUsername.trim().removePrefix("@").substringBefore("/")
        .takeIf { it.matches(Regex("[A-Za-z0-9._]{1,24}")) }.orEmpty()
    var bitmap by remember(url, cleanUsername) {
        mutableStateOf(
            url?.takeIf { it.isNotBlank() }?.let { RemoteAvatarCache.images[it] }
                ?: cleanUsername.takeIf { it.isNotBlank() }?.let { RemoteAvatarCache.userUrls[it] }?.let { RemoteAvatarCache.images[it] }
        )
    }
    LaunchedEffect(url, cleanUsername) {
        val requestedUrl = url?.takeIf { it.startsWith("https://") }
            ?: cleanUsername.takeIf { it.isNotBlank() }?.let { RemoteAvatarCache.userUrls[it] }
        var resolvedUrl = requestedUrl
        var loaded = requestedUrl?.let { RemoteAvatarCache.images[it] ?: RemoteAvatarCache.load(it) }
        if (loaded == null && cleanUsername.isNotBlank()) {
            val fallbackUrl = TikTokProfileLookup.fetchAvatarUrl(cleanUsername)
            if (!fallbackUrl.isNullOrBlank()) {
                resolvedUrl = fallbackUrl
                loaded = RemoteAvatarCache.images[fallbackUrl] ?: RemoteAvatarCache.load(fallbackUrl)
            }
        }
        if (loaded != null && !resolvedUrl.isNullOrBlank()) {
            if (RemoteAvatarCache.images.size > 300) RemoteAvatarCache.images.clear()
            RemoteAvatarCache.images[resolvedUrl] = loaded
            if (cleanUsername.isNotBlank()) RemoteAvatarCache.userUrls[cleanUsername] = resolvedUrl
        }
        bitmap = loaded
    }
    Box(Modifier.size(size).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, contentDescription = "Avatar de $displayName", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
private fun CommentList(
    comments: List<LiveComment>,
    modifier: Modifier = Modifier,
    onSkipComment: (String) -> Unit = {},
    onClearChat: () -> Unit = {},
    skippedCommentIds: Set<String> = emptySet()
) {
    val listState = rememberLazyListState()
    LaunchedEffect(comments.firstOrNull()?.id) {
        if (comments.isNotEmpty() && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("CHAT EN VIVO", color = Cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${comments.size} mensajes", color = Muted, fontSize = 11.sp)
                IconButton(onClick = onClearChat, enabled = comments.isNotEmpty(), modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Limpiar chat", tint = if (comments.isNotEmpty()) Muted else Muted.copy(alpha = 0.35f))
                }
            }
            if (comments.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Los comentarios aparecerán aquí cuando se conecte a un LIVE.", color = Muted)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(comments, key = { it.id }) { comment ->
                        Row(
                            Modifier.fillMaxWidth().background(PanelRaised, RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RemoteAvatar(comment.avatarUrl, comment.displayName, profileUsername = comment.profileUsername)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (comment.isModerator || comment.isFollower == true) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (comment.isModerator) {
                                            Surface(color = Violet.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                                                Text("Moderador", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Color(0xFFC6B5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        if (comment.isFollower == true) {
                                            Surface(color = Cyan.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                                                Text("Follower", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text(comment.displayName, color = if (comment.isModerator) Cyan else Color.White, fontWeight = FontWeight.SemiBold)
                                comment.replyToDisplayName?.takeIf { it.isNotBlank() }?.let {
                                    Text("↪ Respondió a @$it", color = Violet, fontSize = 11.sp)
                                }
                                Text(comment.text, color = Color(0xFFE5E6F0))
                            }
                            TextButton(
                                enabled = comment.id !in skippedCommentIds,
                                onClick = { onSkipComment(comment.id) }
                            ) {
                                Text(if (comment.id in skippedCommentIds) "OMITIDO" else "OMITIR", color = if (comment.id in skippedCommentIds) Muted else Cyan, fontSize = 10.sp)
                            }
                        }
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
    connected: Boolean,
    onSettingsChange: (ReaderSettings) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    onDeleteVoice: (String, (String?) -> Unit) -> Unit,
    status: String
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    val downloadScope = rememberCoroutineScope()
    var piperInstalled by remember(settings.voiceId) { mutableStateOf(PiperSpeechEngine.isVoiceInstalled(settings.voiceId)) }
    var piperDownloadRunning by remember(settings.voiceId) { mutableStateOf(false) }
    var piperDeleteRunning by remember(settings.voiceId) { mutableStateOf(false) }
    var showDeleteVoiceDialog by remember(settings.voiceId) { mutableStateOf(false) }
    var piperDownloadProgress by remember(settings.voiceId) { mutableIntStateOf(0) }
    var piperDownloadStatus by remember(settings.voiceId) { mutableStateOf("") }
    val provider = settings.speechProvider.takeIf { it in setOf("edge", "tiktok", "tikfinity", "piper") } ?: "edge"
    val providerName = when (provider) {
        "tiktok" -> "TikTok TTS"
        "tikfinity" -> "TikFinity"
        "piper" -> "Piper local"
        else -> "Microsoft Edge TTS"
    }
    val voiceName = when (provider) {
        "tiktok" -> TikTokTtsVoiceCatalog.find(settings.tikTokVoiceId).name
        "tikfinity" -> TikFinityVoiceCatalog.find(settings.apiVoiceId).name
        "piper" -> PiperVoiceCatalog.find(settings.voiceId).name
        else -> EdgeTtsVoiceCatalog.find(settings.edgeVoiceId).name
    }
    Card(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Voz del lector", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Selecciona el motor de voz sin desconectar el LIVE.", color = Muted, fontSize = 13.sp)
            Text("Motor", color = Muted, fontSize = 12.sp)
            Box {
                OutlinedButton(onClick = { providerMenuExpanded = true }) { Text(providerName) }
                DropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Microsoft Edge TTS") }, onClick = {
                        providerMenuExpanded = false
                        onSettingsChange(settings.copy(speechProvider = "edge"))
                    })
                    DropdownMenuItem(text = { Text("TikTok TTS") }, onClick = {
                        providerMenuExpanded = false
                        onSettingsChange(settings.copy(speechProvider = "tiktok"))
                    })
                    DropdownMenuItem(text = { Text("TikFinity") }, onClick = {
                        providerMenuExpanded = false
                        onSettingsChange(settings.copy(speechProvider = "tikfinity"))
                    })
                    DropdownMenuItem(text = { Text("Piper local") }, onClick = {
                        providerMenuExpanded = false
                        onSettingsChange(settings.copy(speechProvider = "piper"))
                    })
                }
            }
            Text("Voz", color = Muted, fontSize = 12.sp)
            Box {
                OutlinedButton(onClick = { voiceMenuExpanded = true }) { Text(voiceName) }
                DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                    when (provider) {
                        "tiktok" -> TikTokTtsVoiceCatalog.voices.forEach { voice ->
                            DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                                voiceMenuExpanded = false
                                onSettingsChange(settings.copy(tikTokVoiceId = voice.id))
                            })
                        }
                        "tikfinity" -> TikFinityVoiceCatalog.voices.forEach { voice ->
                            DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                                voiceMenuExpanded = false
                                onSettingsChange(settings.copy(apiVoiceId = voice.id))
                            })
                        }
                        "piper" -> PiperVoiceCatalog.voices.forEach { voice ->
                            DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                                voiceMenuExpanded = false
                                onSettingsChange(settings.copy(voiceId = voice.id))
                            })
                        }
                        else -> EdgeTtsVoiceCatalog.voices.forEach { voice ->
                            DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                                voiceMenuExpanded = false
                                onSettingsChange(settings.copy(edgeVoiceId = voice.id))
                            })
                        }
                    }
                }
            }
            if (provider == "piper") {
                val selectedVoice = PiperVoiceCatalog.find(settings.voiceId)
                SettingToggle("Elegir voces aleatorias instaladas", settings.randomVoices) {
                    onSettingsChange(settings.copy(randomVoices = it))
                }
                Text(selectedVoice.description, color = Muted, fontSize = 11.sp)
                Text(selectedVoice.licenseNote, color = Muted, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !piperInstalled && !piperDownloadRunning,
                        onClick = {
                            downloadScope.launch {
                                piperDownloadRunning = true
                                piperDownloadProgress = 0
                                piperDownloadStatus = "Preparando descarga…"
                                try {
                                    PiperSpeechEngine.downloadVoice(settings.voiceId) { percent, message ->
                                        SwingUtilities.invokeLater {
                                            piperDownloadProgress = percent
                                            piperDownloadStatus = message
                                        }
                                    }
                                    piperInstalled = true
                                    piperDownloadStatus = "Voz instalada y validada."
                                } catch (error: Exception) {
                                    piperDownloadStatus = error.message ?: "No se pudo descargar la voz."
                                } finally {
                                    piperDownloadRunning = false
                                }
                            }
                        }
                    ) { Text(if (piperInstalled) "Voz instalada" else if (piperDownloadRunning) "Descargando…" else "Descargar voz") }
                    if (piperDownloadRunning) Text("$piperDownloadProgress%", color = Cyan)
                }
                if (piperDownloadRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (piperInstalled) {
                    OutlinedButton(
                        enabled = !piperDeleteRunning && !piperDownloadRunning && !connected,
                        onClick = { showDeleteVoiceDialog = true }
                    ) { Text(if (piperDeleteRunning) "Eliminando…" else "Eliminar voz") }
                }
                if (connected) Text("Detén el LIVE antes de eliminar modelos de voz.", color = Color(0xFFFFA6A6), fontSize = 11.sp)
                if (showDeleteVoiceDialog) {
                    AlertDialog(
                        onDismissRequest = { if (!piperDeleteRunning) showDeleteVoiceDialog = false },
                        title = { Text("Eliminar voz") },
                        text = { Text("¿Quieres eliminar ${selectedVoice.name} de este equipo? Podrás descargarla nuevamente cuando quieras.") },
                        confirmButton = {
                            TextButton(
                                enabled = !piperDeleteRunning && !connected,
                                onClick = {
                                    showDeleteVoiceDialog = false
                                    piperDeleteRunning = true
                                    onDeleteVoice(settings.voiceId) { error ->
                                        piperDeleteRunning = false
                                        piperInstalled = PiperSpeechEngine.isVoiceInstalled(settings.voiceId)
                                        piperDownloadStatus = error ?: "Voz eliminada del dispositivo."
                                    }
                                }
                            ) { Text("ELIMINAR", color = Color(0xFFFF6B75)) }
                        },
                        dismissButton = {
                            TextButton(enabled = !piperDeleteRunning, onClick = { showDeleteVoiceDialog = false }) { Text("CANCELAR") }
                        }
                    )
                }
                if (piperDownloadStatus.isNotBlank()) Text(piperDownloadStatus, color = Muted, fontSize = 11.sp)
                Text("Los modelos se descargan bajo demanda en la carpeta local de Tik Tok Reader; no se incluyen en el instalador.", color = Muted, fontSize = 10.sp)
            }
            if (provider == "tikfinity") {
                OutlinedTextField(
                    value = settings.tikFinityChannelId,
                    onValueChange = { value -> onSettingsChange(settings.copy(tikFinityChannelId = value.filter { it.isDigit() }.take(30))) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ID numérico de canal TikFinity") },
                    singleLine = true
                )
                SettingToggle("Elegir voz aleatoria", settings.apiRandomVoice) { onSettingsChange(settings.copy(apiRandomVoice = it)) }
            }
            Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Controles de audio", fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = onTestVoice, enabled = provider != "piper" || piperInstalled) {
                            Icon(Icons.Default.VolumeUp, null)
                            Spacer(Modifier.width(5.dp))
                            Text("Probar voz")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Volumen", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${(settings.volume * 100).toInt()}%", color = Cyan, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = settings.volume,
                        onValueChange = { onSettingsChange(settings.copy(volume = it)) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Cyan,
                            activeTrackColor = Violet,
                            inactiveTrackColor = Ink
                        )
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Velocidad", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${settings.speed}x", color = Cyan, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = settings.speed,
                        onValueChange = { onSettingsChange(settings.copy(speed = it)) },
                        valueRange = 0.65f..1.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = Cyan,
                            activeTrackColor = Violet,
                            inactiveTrackColor = Ink
                        )
                    )
                    SettingToggle("Silenciar voz", muted, onMutedChange)
                }
            }
            Text(status, color = if (status.startsWith("Error") || status.startsWith("No se pudo")) Color(0xFFFF8A8A) else Muted, fontSize = 11.sp)
            Text("Piper genera audio localmente por fragmentos; el control de velocidad puede modificar ligeramente el tono.", color = Muted, fontSize = 11.sp)
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
                    Text("Activa los avisos que Tik Tok Reader debe leer durante el LIVE.", color = Muted, fontSize = 13.sp)
                    SettingToggle("Nuevos seguidores", settings.readFollows) { onChange(settings.copy(readFollows = it)) }
                    SettingToggle("Regalos", settings.readGifts) { onChange(settings.copy(readGifts = it)) }
                    SettingToggle("Compartidos", settings.readShares) { onChange(settings.copy(readShares = it)) }
                    SettingToggle("Solo seguidores para compartidos", settings.shareFollowerOnly) { onChange(settings.copy(shareFollowerOnly = it)) }
                    SettingToggle("Entradas al LIVE", settings.readJoins) { onChange(settings.copy(readJoins = it)) }
                    SettingToggle("Solo seguidores para entradas", settings.joinFollowerOnly) { onChange(settings.copy(joinFollowerOnly = it)) }
                    SettingToggle("Likes", settings.readLikes) { onChange(settings.copy(readLikes = it)) }
                    OutlinedTextField(
                        value = settings.giftMinimumRepeatCount.toString(),
                        onValueChange = { it.toIntOrNull()?.let { count -> onChange(settings.copy(giftMinimumRepeatCount = count.coerceIn(1, 999))) } },
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
                    CustomCommandEditor(
                        commands = settings.customCommands,
                        onCommandsChange = { onChange(settings.copy(customCommands = it)) }
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
    var playerWindow by remember { mutableStateOf<JFrame?>(null) }
    var engineRequested by remember { mutableStateOf(false) }
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

    LaunchedEffect(state.pendingSearches, state.activeSearch) {
        if (state.pendingSearches.isNotEmpty() || state.activeSearch != null) {
            engineRequested = true
        }
    }

    // JavaFX is deliberately not initialized just by opening this section.
    // Start the desktop browser only when a search is requested or a player is explicitly opened.
    DisposableEffect(engineRequested) {
        if (!engineRequested) {
            onDispose { }
        } else {
        val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
        val searchRef = java.util.concurrent.atomic.AtomicReference<DesktopYoutubeSearchView?>(null)
        val playerRef = java.util.concurrent.atomic.AtomicReference<DesktopYoutubePlayerView?>(null)
        val searchWindowRef = java.util.concurrent.atomic.AtomicReference<JFrame?>(null)
        val playerWindowRef = java.util.concurrent.atomic.AtomicReference<JFrame?>(null)

        // Start JavaFX off the UI thread before creating JFXPanel instances on Swing's EDT.
        // This prevents the first JavaFX toolkit startup from freezing Compose navigation.
        Thread({
            try {
                val ready = java.util.concurrent.CountDownLatch(1)
                try {
                    javafx.application.Platform.startup {
                        javafx.application.Platform.setImplicitExit(false)
                        ready.countDown()
                    }
                } catch (_: IllegalStateException) {
                    javafx.application.Platform.runLater {
                        javafx.application.Platform.setImplicitExit(false)
                        ready.countDown()
                    }
                }
                if (!ready.await(25, java.util.concurrent.TimeUnit.SECONDS)) {
                    error("JavaFX no terminó de inicializarse.")
                }
                if (!disposed.get()) SwingUtilities.invokeLater {
                    if (!disposed.get()) {
                        try {
                            val search = DesktopYoutubeSearchView { requestId, json ->
                                handleSearchResults.value(requestId, json)
                            }
                            val searchWindow = JFrame("Tik Tok Reader · YouTube Search").apply {
                                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                                type = java.awt.Window.Type.UTILITY
                                isUndecorated = true
                                contentPane.add(search, BorderLayout.CENTER)
                                setSize(640, 480)
                                setLocation(-32000, -32000)
                                isVisible = true
                            }
                            val player = DesktopYoutubePlayerView(
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
                            )
                            val window = JFrame("Tik Tok Reader · DJ Music").apply {
                                defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
                                contentPane.add(player, BorderLayout.CENTER)
                                setSize(820, 480)
                                minimumSize = java.awt.Dimension(640, 360)
                                setLocationRelativeTo(null)
                            }

                            if (disposed.get()) {
                                search.close()
                                player.close()
                                searchWindow.dispose()
                                window.dispose()
                            } else {
                                searchRef.set(search)
                                playerRef.set(player)
                                searchWindowRef.set(searchWindow)
                                playerWindowRef.set(window)
                                searchView = search
                                playerView = player
                                playerWindow = window
                            }
                        } catch (error: Exception) {
                            DjMusicController.setMessage("No se pudo iniciar DJ Music: ${error.message ?: "error de JavaFX"}")
                        }
                    }
                }
            } catch (error: Exception) {
                if (!disposed.get()) {
                    DjMusicController.setMessage("No se pudo preparar DJ Music: ${error.message ?: "error de JavaFX"}")
                }
            }
        }, "TikLBot-JavaFX-Startup").apply { isDaemon = true; start() }

        onDispose {
            disposed.set(true)
            SwingUtilities.invokeLater {
                runCatching { searchRef.getAndSet(null)?.close() }
                runCatching { playerRef.getAndSet(null)?.close() }
                runCatching { searchWindowRef.getAndSet(null)?.dispose() }
                runCatching { playerWindowRef.getAndSet(null)?.dispose() }
            }
            searchView = null
            playerView = null
            playerWindow = null
        }
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
            kotlinx.coroutines.delay(45_000)
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
        if (player != null) {
            playerReady = false
            playerPlaying = false
            if (track != null) {
                runCatching {
                    player.loadTrack(track, state.volume, state.muted)
                    SwingUtilities.invokeLater {
                        playerWindow?.let { window ->
                            window.isVisible = true
                            window.toFront()
                            window.requestFocus()
                        }
                    }
                }.onFailure { DjMusicController.setMessage("No se pudo abrir el reproductor: ${it.message ?: "error"}") }
            } else {
                player.clearTrack()
                SwingUtilities.invokeLater { playerWindow?.isVisible = false }
            }
        }
    }

    LaunchedEffect(state.volume, state.muted, playerView) {
        playerView?.setVolume(state.volume)
        playerView?.setMuted(state.muted)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                        engineRequested = true
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
                Card(
                    Modifier.fillMaxWidth().height(150.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Reproductor independiente", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (current == null) "Selecciona una canción para abrir el reproductor."
                                else "El video se reproduce en una ventana separada para mantener fluida la aplicación.",
                                color = Muted
                            )
                            OutlinedButton(enabled = current != null, onClick = {
                                engineRequested = true
                                SwingUtilities.invokeLater {
                                    playerWindow?.let { it.isVisible = true; it.toFront(); it.requestFocus() }
                                }
                            }) { Text("Mostrar reproductor") }
                        }
                    }
                }
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

        Card(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
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
