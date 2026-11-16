package com.myclivebot

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
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

private val DjPanel = Color(0xCC1B2036)
private val DjMuted = Color(0xFFB8BBD0)
private val DjCyan = Color(0xFF69DED2)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DJMusicScreen(modifier: Modifier = Modifier) {
    val state by DjMusicController.state.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var searchView by remember { mutableStateOf<WebView?>(null) }
    var playerView by remember { mutableStateOf<WebView?>(null) }
    val searchRequestId = remember { AtomicInteger(0) }
    val searchRequester = remember { AtomicReference("Tú") }
    var activeSearchId by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DjMusicTrack?>(null) }
    var accessMenu by remember { mutableStateOf(false) }
    var playerPlaying by remember { mutableStateOf(false) }

    fun startSearch(rawQuery: String, requestedBy: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        val requestId = searchRequestId.incrementAndGet()
        activeSearchId = requestId
        searchRequester.set(requestedBy)
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
                DjMusicController.completeSearch(searchRequester.get())
                DjMusicController.setMessage("YouTube no respondió a tiempo. Comprueba tu conexión o intenta otra búsqueda.")
            }
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp)) {
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

                Surface(color = Color(0x332F9D98), shape = RoundedCornerShape(12.dp)) {
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

        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp)) {
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

        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp)) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { playerView?.evaluateJavascript("playPause()", null) }, enabled = current != null, modifier = Modifier.weight(1f)) {
                        Icon(if (playerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (playerPlaying) "Pausar" else "Reproducir")
                    }
                    OutlinedButton(onClick = { playerView?.evaluateJavascript("seekBy(-10)", null) }, enabled = current != null, modifier = Modifier.weight(1f)) { Text("−10 s") }
                    OutlinedButton(onClick = { playerView?.evaluateJavascript("seekBy(10)", null) }, enabled = current != null, modifier = Modifier.weight(1f)) { Text("+10 s") }
                }
                Text("REPRODUCTOR OFICIAL DE YOUTUBE", color = DjMuted, style = MaterialTheme.typography.labelSmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadsImagesAutomatically = true
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
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
                                        function onYouTubeIframeAPIReady() {
                                          player = new YT.Player('player', {
                                            width:'100%', height:'220',
                                            videoId:'${track.videoId}',
                                            host:'https://www.youtube.com',
                                            playerVars:{autoplay:1,playsinline:1,controls:1,rel:0,enablejsapi:1,origin:'https://com.myclivebot'},
                                            events:{
                                              onReady:function(e){e.target.setVolume(${state.volume});if(${state.muted})e.target.mute();else e.target.unMute();e.target.playVideo();},
                                              onStateChange:function(e){if(e.data===0)TikLBotMusicPlayer.onPlayerEnded();if(e.data===1)TikLBotMusicPlayer.onPlayerState(1);if(e.data===2||e.data===0||e.data===-1)TikLBotMusicPlayer.onPlayerState(0);},
                                              onError:function(e){TikLBotMusicPlayer.onPlayerError(e.data);},
                                              onAutoplayBlocked:function(){TikLBotMusicPlayer.onAutoplayBlocked();}
                                            }
                                          });
                                        }
                                        function setVolume(v){if(player && player.setVolume)player.setVolume(v);}
                                        function setMuted(v){if(player){if(v)player.mute();else player.unMute();}}
                                        function playPause(){if(!player||!player.getPlayerState)return;if(player.getPlayerState()===1){player.pauseVideo();}else{player.unMute();player.playVideo();}}
                                        function seekBy(seconds){if(player&&player.getCurrentTime&&player.seekTo)player.seekTo(Math.max(0,player.getCurrentTime()+seconds),true);}
                                      </script>
                                      <script src="https://www.youtube.com/iframe_api"></script>
                                    </body>
                                    </html>
                                """.trimIndent()
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

        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp)) {
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
                        if (requestId != searchRequestId.get()) return
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
                            DjMusicController.completeSearch(searchRequester.get())
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
