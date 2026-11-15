package com.myclivebot

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.myclivebot.core.DjMusicController
import com.myclivebot.core.DjMusicTrack
import org.json.JSONArray
import java.net.URLEncoder

private val DjPanel = Color(0xCC1B2036)
private val DjMuted = Color(0xFFB8BBD0)
private val DjCyan = Color(0xFF69DED2)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DJMusicScreen(modifier: Modifier = Modifier) {
    val state by DjMusicController.state.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var requester by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var manualSearch by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DjMusicTrack>>(emptyList()) }

    fun search(query: String, who: String?, manual: Boolean) {
        if (query.isBlank()) return
        requester = who
        manualSearch = manual
        searching = true
        results = emptyList()
        DjMusicController.setMessage("Buscando en YouTube: $query")
        webView?.loadUrl("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
    }

    LaunchedEffect(state.pendingSearches.size, webView, searching) {
        if (webView != null && !searching && state.pendingSearches.isNotEmpty()) {
            val request = DjMusicController.takeNextSearch()
            if (request != null) search(request.first, request.second, false)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = DjCyan)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("DJ MUSIC", style = MaterialTheme.typography.titleMedium)
                        Text("Peticiones del chat y reproductor interno", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) { Text(if (state.followersOnly) "Solo seguidores" else "Todos") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("Todos") }, onClick = { DjMusicController.setFollowersOnly(false); expanded = false })
                            DropdownMenuItem(text = { Text("Solo modo seguidores") }, onClick = { DjMusicController.setFollowersOnly(true); expanded = false })
                        }
                    }
                }
                OutlinedTextField(value = searchText, onValueChange = { searchText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar canción o pegar URL de YouTube") }, placeholder = { Text("José José - Volcán") })
                Button(onClick = {
                    val id = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})").find(searchText)?.groupValues?.get(1)
                    if (id != null) DjMusicController.addTrack(DjMusicTrack(id, searchText, "https://i.ytimg.com/vi/$id/hqdefault.jpg"))
                    else search(searchText.trim(), "Tú", true)
                }, enabled = searchText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null); Text("  BUSCAR / AGREGAR")
                }
                Text(state.message, color = DjMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("REPRODUCTOR YOUTUBE", color = DjCyan, style = MaterialTheme.typography.labelSmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = WebChromeClient()
                            addJavascriptInterface(object {
                                @JavascriptInterface fun onResults(json: String) {
                                    val found = runCatching {
                                        val array = JSONArray(json)
                                        (0 until array.length()).mapNotNull { i ->
                                            val item = array.getJSONObject(i)
                                            val id = item.optString("id")
                                            if (id.length != 11) null else DjMusicTrack(id, item.optString("title", "YouTube"), item.optString("thumbnail"), requester ?: "Tú")
                                        }
                                    }.getOrDefault(emptyList())
                                    webView?.post {
                                        results = found
                                        searching = false
                                        if (!manualSearch) {
                                            val first = found.firstOrNull()
                                            if (first != null) DjMusicController.addTrack(first)
                                            else DjMusicController.setMessage("No se pudo identificar el primer resultado. Prueba un título más específico.")
                                            requester = null
                                        }
                                    }
                                }
                                @JavascriptInterface fun onPlayerEnded() { webView?.post { DjMusicController.skip("La canción terminó.") } }
                            }, "TikLBotMusic")
                            webView = this
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url?.contains("youtube.com/results") == true) {
                                        view?.evaluateJavascript("(function(){var a=[...document.querySelectorAll('ytd-video-renderer a#video-title')].slice(0,8);var r=a.map(function(e){var h=e.href||'';var m=h.match(/[?&]v=([^&]+)/);var p=e.closest('ytd-video-renderer');var im=p&&p.querySelector('img');return {id:m?m[1]:'',title:e.innerText||e.title||'',thumbnail:im?im.src:''};}).filter(x=>x.id.length===11);window.TikLBotMusic.onResults(JSON.stringify(r));})();", null)
                                    }
                                }
                            }
                        }
                    },
                    update = { view ->
                        webView = view
                        val track = state.currentTrack
                        if (track != null && view.tag != track.videoId) {
                            view.tag = track.videoId
                            val muteJs = if (state.muted) "e.target.mute();" else "e.target.unMute();"
                            val html = "<!doctype html><html><body style='margin:0;background:#000'><div id='player'></div><script src='https://www.youtube.com/iframe_api'></script><script>var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{width:'100%',height:'220',videoId:'" + track.videoId + "',playerVars:{autoplay:1,playsinline:1,rel:0},events:{onReady:function(e){e.target.setVolume(" + state.volume + ");" + muteJs + "e.target.playVideo();},onStateChange:function(e){if(e.data===0)TikLBotMusic.onPlayerEnded();}}});}function setVolume(v){if(player)player.setVolume(v);}function setMuted(v){if(player){if(v)player.mute();else player.unMute();}}</script></body></html>"
                            view.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
                        }
                    }
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                results.forEach { track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = track.thumbnailUrl, contentDescription = null, modifier = Modifier.size(76.dp, 48.dp).clip(RoundedCornerShape(8.dp)))
                        Text(track.title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { DjMusicController.addTrack(track); results = emptyList() }) { Icon(Icons.Default.PlayArrow, contentDescription = "Agregar") }
                    }
                }
            }
        }
        Surface(color = DjPanel, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("EN REPRODUCCIÓN", color = DjCyan, style = MaterialTheme.typography.labelSmall)
                val current = state.currentTrack
                if (current == null) Text("No hay ninguna canción reproduciéndose.", color = DjMuted, style = MaterialTheme.typography.bodySmall)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = current.thumbnailUrl, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(current.title, maxLines = 2)
                        Text("Solicitada por ${current.requestedBy}", color = DjMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { DjMusicController.skip() }) { Icon(Icons.Default.SkipNext, contentDescription = "Siguiente") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { DjMusicController.setMuted(!state.muted); webView?.evaluateJavascript("setMuted(${!state.muted})", null) }) { Icon(if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, contentDescription = "Silenciar") }
                    Slider(value = state.volume.toFloat(), onValueChange = { DjMusicController.setVolume(it.toInt()); webView?.evaluateJavascript("setVolume(${it.toInt()})", null) }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
                    Text("${state.volume}%", color = DjMuted, style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("COLA (${state.queue.size})", color = DjCyan, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { DjMusicController.clearQueue() }, enabled = state.queue.isNotEmpty()) { Text("Vaciar") }
                }
                state.queue.forEachIndexed { i, track -> Text("${i + 1}. ${track.title}", color = DjMuted, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                if (state.queue.isEmpty()) Text("La cola está vacía.", color = DjMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Comandos LIVE: !play título de la canción · !skip (solo moderadores). La selección automática depende de la estructura visible de YouTube y puede dejar de funcionar si YouTube la modifica.", color = DjMuted, style = MaterialTheme.typography.bodySmall)
    }
}
