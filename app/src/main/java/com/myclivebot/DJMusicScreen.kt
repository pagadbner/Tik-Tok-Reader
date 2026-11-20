package com.myclivebot

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.myclivebot.core.DjMusicController
import com.myclivebot.core.DjMusicTrack
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DJMusicScreen(modifier: Modifier = Modifier) {
    val palette = LocalAppThemeColors.current
    val state by DjMusicController.state.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var playbackServiceActive by remember { mutableStateOf(false) }

    var searchText by remember { mutableStateOf("") }
    var searchView by remember { mutableStateOf<WebView?>(null) }
    var playerView by remember { mutableStateOf<WebView?>(null) }
    val searchRequestId = remember { AtomicInteger(0) }
    val completedSearchId = remember { AtomicInteger(0) }
    val searchRequester = remember { AtomicReference("Tú") }
    val searchQuery = remember { AtomicReference("") }
    var activeSearchId by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DjMusicTrack?>(null) }
    var searchResults by remember { mutableStateOf<List<DjMusicTrack>>(emptyList()) }
    val dragThresholdPx = with(LocalDensity.current) { 68.dp.toPx() }
    var accessMenu by remember { mutableStateOf(false) }
    var playerPlaying by remember { mutableStateOf(false) }
    var playerReady by remember { mutableStateOf(false) }

    fun sendPlaybackServiceAction(action: String, title: String = "", playing: Boolean = false) {
        val intent = Intent(context, DjMusicPlaybackService::class.java)
            .setAction(action)
            .putExtra(DjMusicPlaybackService.EXTRA_TITLE, title)
            .putExtra(DjMusicPlaybackService.EXTRA_PLAYING, playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra(DjMusicPlaybackService.EXTRA_CONTROL)) {
                    "play_pause" -> playerView?.evaluateJavascript("playPause()", null)
                    "next" -> DjMusicController.skip("Siguiente canción desde la notificación.")
                }
            }
        }
        val filter = IntentFilter(DjMusicPlaybackService.ACTION_CONTROL)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Background playback was intentionally removed; pause the official player
                // when the app leaves the foreground.
                playerView?.evaluateJavascript("if(player)player.pauseVideo()", null)
                playerPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.currentTrack?.videoId, playerPlaying) {
        val track = state.currentTrack
        if (track != null || playbackServiceActive) {
            sendPlaybackServiceAction(
                DjMusicPlaybackService.ACTION_UPDATE,
                track?.title.orEmpty(),
                playerPlaying
            )
            playbackServiceActive = track != null
        }
    }


    fun startSearch(rawQuery: String, requestedBy: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        val requestId = searchRequestId.incrementAndGet()
        activeSearchId = requestId
        searchRequester.set(requestedBy)
        searchQuery.set(query)
        searching = true
        result = null
        searchResults = emptyList()
        DjMusicController.setMessage("Buscando canciones…")
        val videoId = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")
            .find(query)?.groupValues?.get(1)
        val target = if (videoId != null) {
            "https://www.youtube.com/watch?v=$videoId"
        } else {
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        }
        searchView?.loadUrl(target)
        if (searchView == null) {
            searching = false
            DjMusicController.setMessage("El buscador todavía se está iniciando. Inténtalo de nuevo.")
        }
    }

    LaunchedEffect(state.pendingSearches.size, state.activeSearch, searchView, searching) {
        if (searchView != null && !searching) {
            val active = state.activeSearch ?: DjMusicController.takeNextSearch()
            if (active != null) startSearch(active.first, active.second)
        }
    }

    LaunchedEffect(searching, activeSearchId) {
        if (searching) {
            val requestId = activeSearchId
            delay(20_000)
            if (searching && activeSearchId == requestId) {
                searching = false
                searchRequestId.incrementAndGet()
                DjMusicController.completeSearch(searchQuery.get(), searchRequester.get())
                DjMusicController.setMessage("YouTube no respondió a tiempo. Comprueba tu conexión o intenta otra búsqueda.")
            }
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(color = palette.panel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, palette.glassBorder)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = palette.secondary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("DJ MUSIC", style = MaterialTheme.typography.titleMedium)
                        Text("Buscador y reproductor de YouTube", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        OutlinedButton(onClick = { accessMenu = true }) {
                            Text(when { state.moderatorsOnly -> "Moderadores"; state.followersOnly -> "Seguidores"; else -> "Todos" })
                        }
                        DropdownMenu(expanded = accessMenu, onDismissRequest = { accessMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Todos") },
                                onClick = { DjMusicController.setFollowersOnly(false); accessMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Solo modo seguidores") },
                                onClick = { DjMusicController.setFollowersOnly(true); accessMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Solo Moderadores") },
                                onClick = { DjMusicController.setModeratorsOnly(true); accessMenu = false }
                            )
                        }
                    }
                }

                Surface(color = palette.primary.copy(alpha = .10f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, palette.glassBorder.copy(alpha = .85f))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("COMANDOS DEL LIVE", color = palette.secondary, style = MaterialTheme.typography.labelMedium)
                        Text("!play nombre de la canción  ·  busca y agrega la primera coincidencia", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Text("!skip  ·  siguiente canción (solo moderadores)", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Título de canción o URL de YouTube") },
                    placeholder = { Text("José José - Volcán") }
                )
                Button(
                    onClick = {
                        val query = searchText.trim()
                        if (query.isNotBlank()) {
                            DjMusicController.enqueueSearch(query, "Tú")
                            searchText = ""
                        }
                    },
                    enabled = searchText.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(if (searching) "  BUSCANDO…" else "  BUSCAR CANCIONES")
                }
                Text(state.message, color = palette.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(color = palette.panel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, palette.glassBorder)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("RESULTADOS DE BÚSQUEDA", color = palette.secondary, style = MaterialTheme.typography.labelMedium)
                if (searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Buscando canciones, miniaturas y perfiles de artistas…", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                }
                if (searchResults.isEmpty() && !searching) {
                    Text("Escribe el nombre de una canción o artista para ver coincidencias y elegir una.", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                }
                searchResults.forEachIndexed { index, track ->
                    Surface(
                        color = palette.panel.copy(alpha = .72f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.glassBorder.copy(alpha = .7f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AsyncImage(
                                    model = track.thumbnailUrl,
                                    contentDescription = "Miniatura de ${track.title}",
                                    modifier = Modifier.size(88.dp, 58.dp).clip(RoundedCornerShape(9.dp))
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    Text(track.duration.ifBlank { "Duración no disponible" }, color = palette.muted, style = MaterialTheme.typography.labelSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        if (track.artistThumbnailUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = track.artistThumbnailUrl,
                                                contentDescription = "Perfil de ${track.artistName}",
                                                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(50.dp))
                                            )
                                        }
                                        Text(
                                            track.artistName.ifBlank { "Perfil del artista no disponible" },
                                            color = palette.secondary,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                        if (track.artistUrl.isNotBlank()) {
                                            TextButton(
                                                onClick = { runCatching { uriHandler.openUri(track.artistUrl) } },
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.height(24.dp)
                                            ) { Text("Ver perfil", style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { DjMusicController.addTrack(track); searchResults = searchResults.filterNot { it.videoId == track.videoId } },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(" Cola", style = MaterialTheme.typography.labelSmall)
                                }
                                Button(
                                    onClick = { DjMusicController.playTrackNow(track); searchResults = searchResults.filterNot { it.videoId == track.videoId } },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(" Reproducir ahora", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(color = palette.panel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, palette.glassBorder)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("EN REPRODUCCIÓN", color = palette.secondary, style = MaterialTheme.typography.labelMedium)
                val current = state.currentTrack
                if (current == null) {
                    Text("Todavía no hay una canción en reproducción.", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = current.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${current.videoId}/hqdefault.jpg" },
                            contentDescription = "Carátula de ${current.title}",
                            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(14.dp))
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text("Solicitada por ${current.requestedBy}", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { DjMusicController.skip() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Siguiente canción", tint = palette.secondary)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val nextMuted = !state.muted
                        DjMusicController.setMuted(nextMuted)
                        playerView?.evaluateJavascript("setMuted($nextMuted)", null)
                    }) {
                        Icon(
                            if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (state.muted) "Activar sonido" else "Silenciar",
                            tint = palette.secondary
                        )
                    }
                    Slider(
                        value = state.volume.toFloat(),
                        onValueChange = {
                            DjMusicController.setVolume(it.toInt())
                            playerView?.evaluateJavascript("setVolume(${it.toInt()})", null)
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${state.volume}%", color = palette.muted, style = MaterialTheme.typography.labelSmall)
                }

                Text("CONTROLES DEL REPRODUCTOR", color = palette.muted, style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { playerView?.evaluateJavascript("seekBy(-10)", null) },
                        enabled = current != null && playerReady,
                        modifier = Modifier.size(width = 72.dp, height = 48.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) { Text("−10 s", style = MaterialTheme.typography.labelLarge) }

                    FilledIconButton(
                        onClick = { playerView?.evaluateJavascript("playPause()", null) },
                        enabled = current != null && playerReady,
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(32.dp)
                    ) {
                        Icon(
                            if (playerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerPlaying) "Pausar" else "Reproducir",
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    FilledTonalButton(
                        onClick = { playerView?.evaluateJavascript("seekBy(10)", null) },
                        enabled = current != null && playerReady,
                        modifier = Modifier.size(width = 72.dp, height = 48.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) { Text("+10 s", style = MaterialTheme.typography.labelLarge) }
                }
                Text("REPRODUCTOR OFICIAL DE YOUTUBE", color = palette.muted, style = MaterialTheme.typography.labelSmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadsImagesAutomatically = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        post {
                                            playerReady = false
                                            playerPlaying = false
                                            DjMusicController.setMessage("No se pudo cargar el reproductor de YouTube. Revisa tu conexión e inténtalo de nuevo.")
                                        }
                                    }
                                }
                            }
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onPlayerEnded() {
                                    post { DjMusicController.skip("La canción terminó.") }
                                }
                                @JavascriptInterface
                                fun onPlayerError(code: Int) {
                                    post {
                                        playerPlaying = false
                                        val detail = when (code) {
                                            2 -> "ID o parámetro inválido"
                                            5 -> "falló el reproductor HTML5"
                                            100 -> "el video no existe o es privado"
                                            101, 150 -> "el propietario no permite la reproducción incrustada"
                                            153 -> "YouTube no recibió el HTTP Referer de identificación de la aplicación"
                                            else -> "error $code"
                                        }
                                        DjMusicController.setMessage("YouTube no pudo reproducir este video: $detail. Prueba otra coincidencia.")
                                    }
                                }
                                @JavascriptInterface
                                fun onAutoplayBlocked() {
                                    post {
                                        playerPlaying = false
                                        DjMusicController.setMessage("YouTube bloqueó el inicio automático. Pulsa Reproducir para comenzar.")
                                    }
                                }
                                @JavascriptInterface
                                fun onPlayerReady() {
                                    post { playerReady = true }
                                }
                                @JavascriptInterface
                                fun onPlayerState(value: Int) {
                                    post { playerPlaying = value == 1 }
                                }
                            }, "TikLBotMusicPlayer")
                            playerView = this
                        }
                    },
                    update = { view ->
                        playerView = view
                        val track = state.currentTrack
                        val tag = track?.videoId ?: "empty-player"
                        if (view.tag != tag) {
                            view.tag = tag
                            playerPlaying = false
                            playerReady = false
                            if (track == null) {
                                view.loadDataWithBaseURL(
                                    "https://com.myclivebot/",
                                    "<html><body style='margin:0;background:#111827;color:#b8bbd0;font-family:sans-serif;display:grid;place-items:center;height:100vh'>El reproductor aparecerá aquí</body></html>",
                                    "text/html", "UTF-8", null
                                )
                            } else {
                                val muteJs = if (state.muted) "e.target.mute();" else "e.target.unMute();" 
                                val html = "<!doctype html><html><head><meta name='referrer' content='strict-origin-when-cross-origin'></head><body style='margin:0;background:#000'><div id='player'></div><script src='https://www.youtube.com/iframe_api'></script><script>var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{width:'100%',height:'220',videoId:'" + track.videoId + "',playerVars:{autoplay:1,playsinline:1,controls:1,rel:0,enablejsapi:1,origin:'https://com.myclivebot'},events:{onReady:function(e){e.target.setVolume(" + state.volume + ");" + muteJs + "TikLBotMusicPlayer.onPlayerReady();e.target.playVideo();},onStateChange:function(e){if(e.data===0)TikLBotMusicPlayer.onPlayerEnded();TikLBotMusicPlayer.onPlayerState(e.data);},onError:function(e){TikLBotMusicPlayer.onPlayerError(e.data);},onAutoplayBlocked:function(){TikLBotMusicPlayer.onAutoplayBlocked();}}});}function setVolume(v){if(player)player.setVolume(v);}function setMuted(v){if(player){if(v)player.mute();else player.unMute();}}function playPause(){if(player){if(player.getPlayerState()===1)player.pauseVideo();else{player.unMute();player.playVideo();}}}function seekBy(s){if(player)player.seekTo(Math.max(0,player.getCurrentTime()+s),true);}</script></body></html>"
                                view.loadDataWithBaseURL("https://com.myclivebot/", html, "text/html", "UTF-8", null)
                            }
                        } else {
                            view.evaluateJavascript(
                                "if(typeof setVolume==='function')setVolume(${state.volume});if(typeof setMuted==='function')setMuted(${state.muted});",
                                null
                            )
                        }
                    }
                )
            }
        }

        Surface(color = palette.panel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, palette.glassBorder)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("COLA DE REPRODUCCIÓN", color = palette.secondary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("${state.queue.size} canciones", color = palette.muted, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { DjMusicController.clearQueue() }, enabled = state.queue.isNotEmpty()) { Text("Vaciar") }
                }
                if (state.queue.isEmpty()) {
                    Text("La cola está vacía.", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    state.queue.forEachIndexed { index, track ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .pointerInput(track.videoId, index, state.queue) {
                                    var accumulated = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragEnd = { accumulated = 0f },
                                        onDragCancel = { accumulated = 0f }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        accumulated += dragAmount.y
                                        if (abs(accumulated) >= dragThresholdPx) {
                                            val target = (index + if (accumulated > 0f) 1 else -1)
                                                .coerceIn(state.queue.indices)
                                            if (target != index) DjMusicController.moveQueueTrack(index, target)
                                            accumulated = 0f
                                        }
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DragHandle, contentDescription = "Mantén pulsado para mover", tint = palette.muted)
                            AsyncImage(
                                model = track.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg" },
                                contentDescription = null,
                                modifier = Modifier.size(48.dp, 38.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text("${index + 1}. ${track.title}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    listOf(track.duration.ifBlank { "Duración desconocida" }, track.requestedBy).joinToString(" · "),
                                    color = palette.muted,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { DjMusicController.playQueueTrackNow(index) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.PlayCircle, contentDescription = "Reproducir ahora: ${track.title}", tint = palette.secondary)
                            }
                            IconButton(onClick = { DjMusicController.removeQueueTrack(index) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar ${track.title} de la cola", tint = palette.muted)
                            }
                        }
                    }
                }
            }
        }


    }

    AndroidView(
        modifier = Modifier.size(2.dp).alpha(0.01f),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (view == null || url == null) return
                        val requestId = searchRequestId.get()
                        val script = """
                            (function(){
                              var requestId=$requestId, attempts=0;
                              function formatSeconds(value){
                                var total=parseInt(value,10);if(!isFinite(total)||total<0)return '';
                                var h=Math.floor(total/3600),m=Math.floor((total%3600)/60),s=total%60;
                                return h? h+':'+String(m).padStart(2,'0')+':'+String(s).padStart(2,'0') : m+':'+String(s).padStart(2,'0');
                              }
                              function cleanTitle(value){return String(value||'').replace(/\s+/g,' ').trim();}
                              function isDuration(value){return /^\d{1,2}:\d{2}(?::\d{2})?$/.test(cleanTitle(value));}
                              function rendererTitle(video){
                                var t=video&&video.title;if(!t)return '';
                                var value=(t.runs||[]).map(function(r){return r.text||'';}).join('');
                                if(!value)value=t.simpleText||'';
                                value=cleanTitle(value);return isDuration(value)?'':value;
                              }
                              function rendererDuration(video){
                                var value=video&&video.lengthText&&(video.lengthText.simpleText||(video.lengthText.runs||[]).map(function(r){return r.text||'';}).join(''));
                                if(!value&&video&&video.thumbnailOverlays){
                                  video.thumbnailOverlays.forEach(function(o){var t=o.thumbnailOverlayTimeStatusRenderer&&o.thumbnailOverlayTimeStatusRenderer.text;if(!value&&t)value=t.simpleText||(t.runs||[]).map(function(r){return r.text||'';}).join('');});
                                }
                                return cleanTitle(value);
                              }
                              function send(items){
                                var valid=(items||[]).filter(function(item){return item&&item.videoId&&cleanTitle(item.title)&&!isDuration(item.title);});
                                var seen={},list=[];
                                valid.forEach(function(x){
                                  if(seen[x.videoId]||list.length>=8)return;
                                  seen[x.videoId]=true;
                                  var thumbs=x.thumbnails||[];
                                  thumbs.sort(function(a,b){return (b.width||0)-(a.width||0);});
                                  list.push({
                                    id:x.videoId,title:cleanTitle(x.title),
                                    thumbnail:(thumbs[0]&&thumbs[0].url)||'',
                                    duration:x.duration||formatSeconds(x.lengthSeconds)||'',
                                    artistName:cleanTitle(x.artistName||''),
                                    artistUrl:x.artistUrl||'',
                                    artistThumbnail:x.artistThumbnail||''
                                  });
                                });
                                if(list.length){
                                  TikLBotMusicSearch.onResults(requestId,JSON.stringify(list));
                                  return true;
                                }
                                return false;
                              }
                              function scan(){
                                if(location.pathname.indexOf('/watch')===0){
                                  var d=window.ytInitialPlayerResponse&&window.ytInitialPlayerResponse.videoDetails;
                                  if(d&&send([{videoId:d.videoId,title:d.title,thumbnails:d.thumbnail&&d.thumbnail.thumbnails,lengthSeconds:d.lengthSeconds,artistName:d.author||'',artistUrl:d.channelId?('https://www.youtube.com/channel/'+d.channelId):''}]))return true;
                                }
                                var found=[],data=window.ytInitialData;
                                function walk(o){
                                  if(!o||typeof o!=='object')return;
                                  if(o.videoRenderer&&o.videoRenderer.videoId){
                                    var v=o.videoRenderer;
                                    var owner=(v.ownerText&&v.ownerText.runs&&v.ownerText.runs[0])||{};
                                    var channel=(owner.navigationEndpoint&&owner.navigationEndpoint.browseEndpoint)||{};
                                    var channelThumb=v.channelThumbnailSupportedRenderers&&v.channelThumbnailSupportedRenderers.channelThumbnailWithLinkRenderer;
                                    var channelThumbs=channelThumb&&channelThumb.thumbnail&&channelThumb.thumbnail.thumbnails||[];
                                    channelThumbs.sort(function(a,b){return (b.width||0)-(a.width||0);});
                                    found.push({videoId:v.videoId,title:rendererTitle(v),thumbnails:(v.thumbnail&&v.thumbnail.thumbnails)||[],duration:rendererDuration(v),artistName:owner.text||'',artistUrl:channel.canonicalBaseUrl?('https://www.youtube.com'+channel.canonicalBaseUrl):(channel.browseId?('https://www.youtube.com/channel/'+channel.browseId):''),artistThumbnail:channelThumbs[0]&&channelThumbs[0].url||''});
                                  }
                                  Object.keys(o).forEach(function(k){if(k!=='videoRenderer')walk(o[k]);});
                                }
                                walk(data);
                                found=found.filter(function(item){return item&&item.title&&!isDuration(item.title);});
                                if(!found.length){
                                  document.querySelectorAll('a#video-title,a[href*="watch?v="]').forEach(function(a){
                                    var m=(a.href||'').match(/[?&]v=([^&]+)/);if(!m)return;
                                    var card=a.closest('ytd-video-renderer,ytd-rich-item-renderer,ytm-video-with-context-renderer')||a.parentElement;
                                    var im=card&&card.querySelector('img');
                                    var titleNode=card&&(card.querySelector('a#video-title')||card.querySelector('#video-title')||card.querySelector('h3'));
                                    var title=cleanTitle(titleNode&&(titleNode.getAttribute('title')||titleNode.getAttribute('aria-label')||titleNode.textContent));
                                    if(!title||isDuration(title))title=cleanTitle(a.getAttribute('title')||a.getAttribute('aria-label'));
                                    if(!title||isDuration(title))return;
                                    var durationNode=card&&card.querySelector('ytd-thumbnail-overlay-time-status-renderer, .ytd-thumbnail-overlay-time-status-renderer');
                                    var ownerLink=card&&card.querySelector('ytd-channel-name a, #channel-name a, a[href^="/channel/"], a[href^="/@"]');
                                    var ownerName=ownerLink?cleanTitle(ownerLink.textContent):'';
                                    found.push({videoId:m[1],title:title,thumbnails:im?[{url:im.currentSrc||im.src,width:640}]:[],duration:durationNode?(durationNode.innerText||'').trim():'',artistName:ownerName,artistUrl:ownerLink?new URL(ownerLink.href,location.origin).href:'',artistThumbnail:''});
                                  });
                                }
                                return send(found);
                              }
                              function poll(){
                                attempts++;
                                if(scan())return;
                                if(attempts<30)setTimeout(poll,500);
                                else TikLBotMusicSearch.onResults(requestId,JSON.stringify([]));
                              }
                              poll();
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(script, null)
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onResults(requestId: Int, json: String) {
                        if (requestId != searchRequestId.get() || completedSearchId.getAndSet(requestId) == requestId) return
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
                                    requestedBy = searchRequester.get(),
                                    artistName = item.optString("artistName"),
                                    artistUrl = item.optString("artistUrl"),
                                    artistThumbnailUrl = item.optString("artistThumbnail")
                                )
                            }
                        }.getOrDefault(emptyList())
                        post {
                            if (requestId != searchRequestId.get()) return@post
                            searching = false
                            DjMusicController.completeSearch(searchQuery.get(), searchRequester.get())
                            if (tracks.isNotEmpty()) {
                                if (searchRequester.get() == "Tú") {
                                    searchResults = tracks
                                    DjMusicController.setMessage("Se encontraron ${tracks.size} resultados. Elige uno.")
                                } else {
                                    DjMusicController.addTrack(tracks.first())
                                }
                            } else {
                                DjMusicController.setMessage("No se encontraron resultados legibles. Prueba otro título o URL.")
                            }
                        }
                    }
                }, "TikLBotMusicSearch")
                searchView = this
            }
        },
        update = { searchView = it }
    )
}
