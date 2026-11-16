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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private val DjPanel = Color(0x663A3944)
private val DjMuted = Color(0xFFB8B5C2)
private val DjCyan = Color(0xFFE4DFFF)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DJMusicScreen(modifier: Modifier = Modifier) {
    val state by DjMusicController.state.collectAsState()
    val context = LocalContext.current
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
        DjMusicController.setMessage("Buscando la primera coincidencia…")
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
        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = DjCyan)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("DJ MUSIC", style = MaterialTheme.typography.titleMedium)
                        Text("Buscador y reproductor de YouTube", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        OutlinedButton(onClick = { accessMenu = true }) {
                            Text(if (state.followersOnly) "Seguidores" else "Todos")
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
                        }
                    }
                }

                Surface(color = Color(0x33FFFFFF), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("COMANDOS DEL LIVE", color = DjCyan, style = MaterialTheme.typography.labelMedium)
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
                    onClick = { DjMusicController.enqueueSearch(searchText, "Tú") },
                    enabled = searchText.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(if (searching) "  BUSCANDO…" else "  BUSCAR Y AGREGAR PRIMER RESULTADO")
                }
                Text(state.message, color = DjMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("RESULTADO SELECCIONADO", color = DjCyan, style = MaterialTheme.typography.labelMedium)
                if (searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Buscando título, carátula y enlace del primer resultado…", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                }
                val selected = result ?: state.currentTrack
                if (selected != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = selected.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${selected.videoId}/hqdefault.jpg" },
                            contentDescription = "Carátula de ${selected.title}",
                            modifier = Modifier.size(104.dp, 72.dp).clip(RoundedCornerShape(12.dp))
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(selected.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text("Primera coincidencia de YouTube", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (!searching) {
                    Text("Busca una canción o solicita !play en el LIVE.", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("EN REPRODUCCIÓN", color = DjCyan, style = MaterialTheme.typography.labelMedium)
                val current = state.currentTrack
                if (current == null) {
                    Text("Todavía no hay una canción en reproducción.", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = current.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${current.videoId}/hqdefault.jpg" },
                            contentDescription = "Carátula de ${current.title}",
                            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(14.dp))
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text("Solicitada por ${current.requestedBy}", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { DjMusicController.skip() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Siguiente canción", tint = DjCyan)
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
                            tint = DjCyan
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
                    Text("${state.volume}%", color = DjMuted, style = MaterialTheme.typography.labelSmall)
                }

                Text("CONTROLES DEL REPRODUCTOR", color = DjMuted, style = MaterialTheme.typography.labelSmall)
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
                Text("REPRODUCTOR OFICIAL DE YOUTUBE", color = DjMuted, style = MaterialTheme.typography.labelSmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
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
                                    "https://www.youtube.com/",
                                    "<html><body style='margin:0;background:#111827;color:#b8bbd0;font-family:sans-serif;display:grid;place-items:center;height:100vh'>El reproductor aparecerá aquí</body></html>",
                                    "text/html", "UTF-8", null
                                )
                            } else {
                                val html = """
                                    <!doctype html>
                                    <html>
                                    <head>
                                      <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
                                      <style>html,body,#player{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}</style>
                                    </head>
                                    <body>
                                      <div id="player"></div>
                                      <script>
                                        var player;
                                        var desiredVolume=${state.volume};
                                        var desiredMuted=${state.muted};
                                        function onYouTubeIframeAPIReady() {
                                          player = new YT.Player('player', {
                                            width:Math.max(document.documentElement.clientWidth, 320),
                                            height:Math.max(document.documentElement.clientHeight, 180),
                                            videoId:'${track.videoId}',
                                            playerVars:{autoplay:1,playsinline:1,controls:1,rel:0,enablejsapi:1,origin:'https://www.youtube-nocookie.com'},
                                            events:{
                                              onReady:function(e){e.target.setVolume(desiredVolume);if(desiredMuted)e.target.mute();else e.target.unMute();TikLBotMusicPlayer.onPlayerReady();e.target.playVideo();},
                                              onStateChange:function(e){if(e.data===0)TikLBotMusicPlayer.onPlayerEnded();if(e.data===1)TikLBotMusicPlayer.onPlayerState(1);if(e.data===2||e.data===0||e.data===-1)TikLBotMusicPlayer.onPlayerState(0);},
                                              onError:function(e){TikLBotMusicPlayer.onPlayerError(e.data);},
                                              onAutoplayBlocked:function(){TikLBotMusicPlayer.onAutoplayBlocked();}
                                            }
                                          });
                                        }
                                        function setVolume(v){desiredVolume=Math.max(0,Math.min(100,v));if(player && player.setVolume)player.setVolume(desiredVolume);}
                                        function setMuted(v){desiredMuted=!!v;if(player){if(desiredMuted)player.mute();else player.unMute();}}
                                        function playPause(){if(!player||!player.getPlayerState)return;if(player.getPlayerState()===1){player.pauseVideo();}else{player.unMute();player.playVideo();}}
                                        function seekBy(seconds){if(player&&player.getCurrentTime&&player.seekTo)player.seekTo(Math.max(0,player.getCurrentTime()+seconds),true);}
                                      </script>
                                      <script src="https://www.youtube.com/iframe_api"></script>
                                    </body>
                                    </html>
                                """.trimIndent()
                                view.loadDataWithBaseURL("https://www.youtube-nocookie.com/", html, "text/html", "UTF-8", null)
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

        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("COLA DE REPRODUCCIÓN", color = DjCyan, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("${state.queue.size} canciones", color = DjMuted, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { DjMusicController.clearQueue() }, enabled = state.queue.isNotEmpty()) { Text("Vaciar") }
                }
                if (state.queue.isEmpty()) {
                    Text("La cola está vacía.", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    state.queue.forEachIndexed { index, track ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = track.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg" },
                                contentDescription = null,
                                modifier = Modifier.size(54.dp, 42.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text("${index + 1}. ${track.title}", maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                Text("Por ${track.requestedBy}", color = DjMuted, style = MaterialTheme.typography.labelSmall)
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
                              function send(items){
                                var x=items&&items[0];
                                if(x&&x.videoId){
                                  var thumbs=x.thumbnails||[];
                                  thumbs.sort(function(a,b){return (b.width||0)-(a.width||0);});
                                  TikLBotMusicSearch.onResults(requestId,JSON.stringify({
                                    id:x.videoId,
                                    title:x.title||'Video de YouTube',
                                    thumbnail:(thumbs[0]&&thumbs[0].url)||''
                                  }));
                                  return true;
                                }
                                return false;
                              }
                              function scan(){
                                if(location.pathname.indexOf('/watch')===0){
                                  var d=window.ytInitialPlayerResponse&&window.ytInitialPlayerResponse.videoDetails;
                                  if(d&&send([{videoId:d.videoId,title:d.title,thumbnails:d.thumbnail&&d.thumbnail.thumbnails}]))return true;
                                }
                                var found=[],data=window.ytInitialData;
                                function walk(o){
                                  if(!o||typeof o!=='object')return;
                                  if(o.videoRenderer&&o.videoRenderer.videoId){
                                    var v=o.videoRenderer;
                                    found.push({videoId:v.videoId,title:(v.title&&((v.title.runs||[]).map(function(r){return r.text}).join('')||v.title.simpleText))||'',thumbnails:(v.thumbnail&&v.thumbnail.thumbnails)||[]});
                                  }
                                  Object.keys(o).forEach(function(k){if(k!=='videoRenderer')walk(o[k]);});
                                }
                                walk(data);
                                if(!found.length){
                                  document.querySelectorAll('a#video-title,a[href*="watch?v="]').forEach(function(a){
                                    var m=(a.href||'').match(/[?&]v=([^&]+)/);if(!m)return;
                                    var p=a.closest('ytd-video-renderer,ytd-rich-item-renderer,ytm-video-with-context-renderer')||a.parentElement;
                                    var im=p&&p.querySelector('img');
                                    found.push({videoId:m[1],title:(a.innerText||a.title||a.getAttribute('aria-label')||'').trim(),thumbnails:im?[{url:im.currentSrc||im.src,width:640}]:[]});
                                  });
                                }
                                return send(found);
                              }
                              function poll(){
                                attempts++;
                                if(scan())return;
                                if(attempts<30)setTimeout(poll,500);
                                else TikLBotMusicSearch.onResults(requestId,JSON.stringify({}));
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
                        val track = runCatching {
                            val item = org.json.JSONObject(json)
                            val id = item.optString("id")
                            if (id.length != 11 || !id.matches(Regex("[A-Za-z0-9_-]{11}"))) null else DjMusicTrack(
                                videoId = id,
                                title = item.optString("title").ifBlank { "Video de YouTube" },
                                thumbnailUrl = item.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
                                requestedBy = searchRequester.get()
                            )
                        }.getOrNull()
                        post {
                            if (requestId != searchRequestId.get()) return@post
                            searching = false
                            DjMusicController.completeSearch(searchQuery.get(), searchRequester.get())
                            if (track != null) {
                                result = track
                                DjMusicController.addTrack(track)
                            } else {
                                DjMusicController.setMessage("No se encontró un resultado legible. Prueba otro título o URL.")
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
