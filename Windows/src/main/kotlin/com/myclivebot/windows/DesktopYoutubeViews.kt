package com.myclivebot.windows

import com.myclivebot.core.DjMusicTrack
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.json.JSONObject
import java.awt.BorderLayout
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Shared Chromium runtime for Windows DJ Music.
 *
 * Android uses Chromium WebView. JavaFX WebView uses a different, older WebKit
 * engine that is not a reliable target for YouTube's current search/player pages.
 * JCEF supplies Chromium while keeping the existing Swing window integration.
 */
object DesktopJcefRuntime {
    private val lock = Any()
    @Volatile private var app: CefApp? = null
    private var initialization: CompletableFuture<CefApp>? = null

    fun initialize(): CompletableFuture<CefApp> = synchronized(lock) {
        app?.let { return@synchronized CompletableFuture.completedFuture(it) }
        initialization?.let { return@synchronized it }

        val future = CompletableFuture.supplyAsync {
            val appData = System.getenv("LOCALAPPDATA")
                ?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home")
            val installDirectory = File(File(appData, "TikTokReader"), "jcef")
            val builder = CefAppBuilder()
            builder.setInstallDir(installDirectory)
            builder.getCefSettings().apply {
                windowless_rendering_enabled = false
                cache_path = File(installDirectory, "profile").absolutePath
                persist_session_cookies = true
            }
            builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})
            builder.build()
        }
        initialization = future
        future.whenComplete { created, error ->
            synchronized(lock) {
                if (error == null) {
                    app = created
                } else if (initialization === future) {
                    initialization = null
                }
            }
        }
        future
    }
}

private fun createDjMusicMessageRouter(
    client: CefClient,
    onMessage: (JSONObject) -> Unit
): CefMessageRouter {
    val router = CefMessageRouter.create()
    router.addHandler(object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback
        ): Boolean {
            runCatching { JSONObject(request) }
                .onSuccess { payload ->
                    callback.success("{}")
                    onMessage(payload)
                }
                .onFailure {
                    callback.failure(-1, "Invalid DJ Music message")
                }
            return true
        }
    }, true)
    client.addMessageRouter(router)
    return router
}

/**
 * Hidden Chromium browser that reads YouTube's own ytInitialData and DOM,
 * following the same extraction strategy as Android's WebView search.
 */
/**
 * YouTube search for Windows.
 * Uses the same ytInitialData/videoRenderer fields as the Android WebView,
 * but fetches the page directly so it is not dependent on hidden-browser
 * loading, JavaScript injection timing, or the CEF message bridge.
 */
class DesktopYoutubeSearchView(
    private val onResults: (Int, String) -> Unit,
    private val onError: (Int, String) -> Unit
) : AutoCloseable {
    private val activeRequest = AtomicInteger(0)
    @Volatile private var closed = false
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "TikLBot-YouTube-Search").apply { isDaemon = true }
    }
    private val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(query: String, requestId: Int) {
        if (closed) return
        activeRequest.set(requestId)
        activeCall.getAndSet(null)?.cancel()
        executor.execute {
            if (closed || requestId != activeRequest.get()) return@execute
            try {
                val videoId = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")
                    .find(query)?.groupValues?.get(1)
                val target = if (videoId != null) {
                    "https://www.youtube.com/watch?v=$videoId"
                } else {
                    "https://www.youtube.com/results?search_query=" +
                        URLEncoder.encode(query, "UTF-8") + "&hl=es&gl=MX"
                }
                val request = okhttp3.Request.Builder()
                    .url(target)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
                    )
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "es-MX,es;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Referer", "https://www.youtube.com/")
                    .build()
                val call = http.newCall(request)
                activeCall.set(call)
                val html = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw java.io.IOException("YouTube respondió HTTP " + response.code)
                    }
                    response.body?.string()
                        ?: throw java.io.IOException("YouTube devolvió una página vacía")
                }
                if (closed || requestId != activeRequest.get()) return@execute
                val tracks = if (videoId != null) parseWatchPage(html) else parseSearchPage(html)
                if (tracks.isEmpty()) {
                    throw java.io.IOException(
                        "YouTube no entregó resultados legibles. Puede haber solicitado verificación o bloqueado la consulta."
                    )
                }
                val json = org.json.JSONArray()
                tracks.take(8).forEach { json.put(it) }
                dispatchResults(requestId, json.toString())
            } catch (error: Exception) {
                if (closed || requestId != activeRequest.get() || error is java.io.InterruptedIOException) {
                    return@execute
                }
                val message = error.message?.takeIf { it.isNotBlank() } ?: "No se pudo consultar YouTube."
                javax.swing.SwingUtilities.invokeLater {
                    if (!closed && requestId == activeRequest.get()) onError(requestId, message)
                }
            } finally {
                activeCall.set(null)
            }
        }
    }

    private fun dispatchResults(requestId: Int, json: String) {
        javax.swing.SwingUtilities.invokeLater {
            if (!closed && requestId == activeRequest.get()) onResults(requestId, json)
        }
    }

    private fun parseSearchPage(html: String): List<org.json.JSONObject> {
        val initialData = extractEmbeddedObject(html, "ytInitialData")
            ?: throw java.io.IOException("No se encontró ytInitialData en la respuesta de YouTube.")
        val renderers = mutableListOf<org.json.JSONObject>()
        collectVideoRenderers(initialData, renderers, 0)
        val seen = HashSet<String>()
        return renderers.mapNotNull { renderer ->
            val track = trackFromRenderer(renderer) ?: return@mapNotNull null
            if (!seen.add(track.optString("id"))) null else track
        }.take(8)
    }

    private fun parseWatchPage(html: String): List<org.json.JSONObject> {
        val playerResponse = extractEmbeddedObject(html, "ytInitialPlayerResponse")
            ?: throw java.io.IOException("YouTube no entregó los datos del video solicitado.")
        val details = playerResponse.optJSONObject("videoDetails")
            ?: throw java.io.IOException("No se pudieron leer los datos del video.")
        val id = details.optString("videoId")
        val title = details.optString("title").trim()
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) || title.isBlank()) {
            throw java.io.IOException("El enlace no contiene un video público válido.")
        }
        val thumbnail = bestThumbnail(details.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
        val channelId = details.optString("channelId")
        return listOf(
            org.json.JSONObject()
                .put("id", id)
                .put("title", title)
                .put("thumbnail", thumbnail.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" })
                .put("duration", formatDuration(details.optString("lengthSeconds")))
                .put("artistName", details.optString("author"))
                .put("artistUrl", if (channelId.isNotBlank()) "https://www.youtube.com/channel/$channelId" else "")
                .put("artistThumbnail", "")
        )
    }

    private fun trackFromRenderer(video: org.json.JSONObject): org.json.JSONObject? {
        val id = video.optString("videoId")
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val titleObject = video.optJSONObject("title")
        val title = titleObject?.optJSONArray("runs")?.let { runs ->
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }?.ifBlank { titleObject?.optString("simpleText").orEmpty() }
            ?: titleObject?.optString("simpleText").orEmpty()
        val cleanTitle = title.replace(Regex("\\s+"), " ").trim()
        if (cleanTitle.isBlank() || cleanTitle.matches(Regex("""\d{1,2}:\d{2}(?::\d{2})?"""))) return null

        val durationObject = video.optJSONObject("lengthText")
        val duration = durationObject?.optString("simpleText").orEmpty().ifBlank {
            durationObject?.optJSONArray("runs")?.let { runs ->
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }.orEmpty()
        }
        val owner = video.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)
        val artistName = owner?.optString("text").orEmpty()
        val browse = owner?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        val canonical = browse?.optString("canonicalBaseUrl").orEmpty()
        val browseId = browse?.optString("browseId").orEmpty()
        val artistUrl = when {
            canonical.isNotBlank() -> "https://www.youtube.com$canonical"
            browseId.isNotBlank() -> "https://www.youtube.com/channel/$browseId"
            else -> ""
        }
        val channelThumbnail = video.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        return org.json.JSONObject()
            .put("id", id)
            .put("title", cleanTitle)
            .put(
                "thumbnail",
                bestThumbnail(video.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                    .ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
            )
            .put("duration", duration)
            .put("artistName", artistName)
            .put("artistUrl", artistUrl)
            .put("artistThumbnail", bestThumbnail(channelThumbnail))
    }

    private fun collectVideoRenderers(node: Any?, output: MutableList<org.json.JSONObject>, depth: Int) {
        if (node == null || depth > 90 || output.size >= 40) return
        when (node) {
            is org.json.JSONObject -> {
                val renderer = node.optJSONObject("videoRenderer")
                if (renderer != null && renderer.optString("videoId").isNotBlank()) output.add(renderer)
                val keys = node.keys()
                while (keys.hasNext() && output.size < 40) {
                    val key = keys.next()
                    if (key != "videoRenderer") collectVideoRenderers(node.opt(key), output, depth + 1)
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until minOf(node.length(), 2000)) {
                    if (output.size >= 40) break
                    collectVideoRenderers(node.opt(index), output, depth + 1)
                }
            }
        }
    }

    private fun extractEmbeddedObject(html: String, key: String): org.json.JSONObject? {
        var searchFrom = 0
        while (searchFrom < html.length) {
            val keyIndex = html.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            val separator = html.indexOfAny(charArrayOf('=', ':'), keyIndex + key.length)
            if (separator < 0 || separator - keyIndex > 120) {
                searchFrom = keyIndex + key.length
                continue
            }
            val objectStart = html.indexOf('{', separator + 1)
            if (objectStart < 0 || objectStart - separator > 20) {
                searchFrom = keyIndex + key.length
                continue
            }
            val objectEnd = findJsonObjectEnd(html, objectStart)
            if (objectEnd > objectStart) {
                val parsed = runCatching {
                    org.json.JSONObject(html.substring(objectStart, objectEnd + 1))
                }.getOrNull()
                if (parsed != null) return parsed
            }
            searchFrom = keyIndex + key.length
        }
        return null
    }

    private fun findJsonObjectEnd(source: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val char = source[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return -1
    }

    private fun bestThumbnail(thumbnails: org.json.JSONArray?): String {
        if (thumbnails == null) return ""
        var bestUrl = ""
        var bestWidth = -1
        for (index in 0 until thumbnails.length()) {
            val item = thumbnails.optJSONObject(index) ?: continue
            val width = item.optInt("width")
            val url = item.optString("url")
            if (url.isNotBlank() && width >= bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun formatDuration(rawSeconds: String): String {
        val seconds = rawSeconds.toLongOrNull() ?: return ""
        if (seconds < 0) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return if (hours > 0) {
            hours.toString() + ":" + minutes.toString().padStart(2, '0') + ":" +
                remainder.toString().padStart(2, '0')
        } else {
            minutes.toString() + ":" + remainder.toString().padStart(2, '0')
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        activeRequest.incrementAndGet()
        activeCall.getAndSet(null)?.cancel()
        executor.shutdownNow()
    }
}

/** Visible Chromium YouTube IFrame player hosted in the existing Swing player window. */
class DesktopYoutubePlayerView(
    cefApp: CefApp,
    private val onEnded: () -> Unit,
    private val onError: (Int) -> Unit,
    private val onReady: () -> Unit,
    private val onState: (Int) -> Unit
) : JPanel(BorderLayout()), AutoCloseable {
    // CefBrowser.loadString(html, baseUrl) is the JCEF equivalent of
    // Android WebView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null).
    private val appBaseUrl = "https://com.myclivebot/"
    @Volatile private var closed = false
    private val client = cefApp.createClient()
    private val router = createDjMusicMessageRouter(client) { payload ->
        if (payload.optString("type") == "player") {
            val event = payload.optString("event")
            val args = payload.optJSONArray("args")
            SwingUtilities.invokeLater {
                if (closed) return@invokeLater
                when (event) {
                    "onPlayerEnded" -> onEnded()
                    "onPlayerError" -> onError(args?.optInt(0) ?: 0)
                    "onPlayerReady" -> onReady()
                    "onPlayerState" -> onState(args?.optInt(0) ?: -1)
                    "onAutoplayBlocked" -> onState(-1)
                }
            }
        }
    }
    private val browser = client.createBrowser("about:blank", false, false)

    init {
        browser.uiComponent.isFocusable = false
        add(browser.uiComponent, BorderLayout.CENTER)
    }

    fun loadTrack(track: DjMusicTrack, volume: Int, muted: Boolean) {
        require(track.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            "ID de video de YouTube no válido."
        }
        if (closed) return
        val html = playerHtml(track.videoId, volume.coerceIn(0, 100), muted)
        SwingUtilities.invokeLater {
            if (!closed) browser.loadString(html, appBaseUrl)
        }
    }

    fun releaseKeyboardFocus() {
        SwingUtilities.invokeLater {
            if (!closed) runCatching { browser.setFocus(false) }
        }
    }

    fun clearTrack() = evaluate("clearPlayer()")
    fun setVolume(value: Int) = evaluate("setVolume(" + value.coerceIn(0, 100) + ")")
    fun setMuted(value: Boolean) = evaluate("setMuted($value)")
    fun playPause() = evaluate("playPause()")
    fun seekBy(seconds: Int) = evaluate("seekBy(" + seconds.coerceIn(-60, 60) + ")")

    private fun evaluate(script: String) {
        SwingUtilities.invokeLater {
            if (!closed && browser.hasDocument()) {
                runCatching { browser.executeJavaScript(script, appBaseUrl, 0) }
            }
        }
    }

    private fun playerHtml(videoId: String, volume: Int, muted: Boolean): String {
        val muteScript = if (muted) "e.target.mute();" else "e.target.unMute();"
        return """<!doctype html>
<html><head><meta name="referrer" content="strict-origin-when-cross-origin">
<meta charset="utf-8"></head>
<body style="margin:0;background:#000;overflow:hidden">
<div id="player"></div>
<script>
function notifyPlayer(event,args){
  if(typeof window.cefQuery==='function'){
    window.cefQuery({
      request:JSON.stringify({type:'player',event:event,args:args||[]}),
      persistent:false,onSuccess:function(){},onFailure:function(){}
    });
  }
}
</script>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){
 player=new YT.Player('player',{width:'100%',height:'220',videoId:'$videoId',
  playerVars:{autoplay:1,playsinline:1,controls:1,rel:0,enablejsapi:1,origin:'https://com.myclivebot'},
  events:{
   onReady:function(e){e.target.setVolume($volume);$muteScript;notifyPlayer('onPlayerReady');e.target.playVideo();},
   onStateChange:function(e){if(e.data===0)notifyPlayer('onPlayerEnded');notifyPlayer('onPlayerState',[e.data]);},
   onError:function(e){notifyPlayer('onPlayerError',[e.data]);},
   onAutoplayBlocked:function(){notifyPlayer('onAutoplayBlocked');}
  }});
}
function clearPlayer(){if(player){player.stopVideo();player.destroy();player=null;}}
function setVolume(v){if(player)player.setVolume(v);}
function setMuted(v){if(player){if(v)player.mute();else player.unMute();}}
function playPause(){if(player){if(player.getPlayerState()===1)player.pauseVideo();else{player.unMute();player.playVideo();}}}
function seekBy(s){if(player)player.seekTo(Math.max(0,player.getCurrentTime()+s),true);}
</script></body></html>"""
    }

    override fun close() {
        if (closed) return
        closed = true
        SwingUtilities.invokeLater {
            runCatching { browser.close(true) }
            runCatching { router.dispose() }
            runCatching { client.dispose() }
            removeAll()
        }
    }
}
