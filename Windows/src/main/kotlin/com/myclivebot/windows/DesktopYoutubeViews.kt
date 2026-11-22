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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
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
            builder.getCefSettings().windowless_rendering_enabled = false
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
class DesktopYoutubeSearchView(
    cefApp: CefApp,
    private val onResults: (Int, String) -> Unit
) : JPanel(BorderLayout()), AutoCloseable {
    private val activeRequest = AtomicInteger(0)
    @Volatile private var loadingRequestId = 0
    @Volatile private var closed = false
    private val client = cefApp.createClient()
    private val router = createDjMusicMessageRouter(client) { payload ->
        if (payload.optString("type") == "searchResults") {
            val requestId = payload.optInt("requestId")
            val json = payload.optString("json", "[]")
            if (!closed && requestId == activeRequest.get()) {
                SwingUtilities.invokeLater {
                    if (!closed && requestId == activeRequest.get()) onResults(requestId, json)
                }
            }
        }
    }
    private val browser = client.createBrowser("about:blank", false, false)

    init {
        add(browser.uiComponent, BorderLayout.CENTER)
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain || closed) return
                val requestId = loadingRequestId
                if (requestId == 0 || requestId != activeRequest.get()) return
                if (!frame.url.contains("youtube.com", ignoreCase = true)) return
                browser.executeJavaScript(searchScript(requestId), frame.url, 0)
            }
        })
    }

    fun search(query: String, requestId: Int) {
        if (closed) return
        activeRequest.set(requestId)
        loadingRequestId = requestId
        val videoId = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")
            .find(query)?.groupValues?.get(1)
        val target = if (videoId != null) {
            "https://www.youtube.com/watch?v=$videoId"
        } else {
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        }
        SwingUtilities.invokeLater {
            if (!closed && requestId == activeRequest.get()) browser.loadURL(target)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        activeRequest.incrementAndGet()
        SwingUtilities.invokeLater {
            runCatching { browser.close(true) }
            runCatching { router.dispose() }
            runCatching { client.dispose() }
            removeAll()
        }
    }

    private fun searchScript(requestId: Int): String = """
        (function(){
          var requestId=$requestId, attempts=0, sent=false;
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
              video.thumbnailOverlays.forEach(function(o){
                var t=o.thumbnailOverlayTimeStatusRenderer&&o.thumbnailOverlayTimeStatusRenderer.text;
                if(!value&&t)value=t.simpleText||(t.runs||[]).map(function(r){return r.text||'';}).join('');
              });
            }
            return cleanTitle(value);
          }
          function emit(items){
            if(sent)return true;
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
                duration:x.duration||'',
                artistName:cleanTitle(x.artistName||''),
                artistUrl:x.artistUrl||'',
                artistThumbnail:x.artistThumbnail||''
              });
            });
            sent=true;
            window.cefQuery({
              request:JSON.stringify({type:'searchResults',requestId:requestId,json:JSON.stringify(list)}),
              persistent:false,onSuccess:function(){},onFailure:function(){}
            });
            return true;
          }
          function scan(){
            if(location.pathname.indexOf('/watch')===0){
              var d=window.ytInitialPlayerResponse&&window.ytInitialPlayerResponse.videoDetails;
              if(d&&emit([{videoId:d.videoId,title:d.title,thumbnails:d.thumbnail&&d.thumbnail.thumbnails,
                duration:d.lengthSeconds||'',artistName:d.author||'',
                artistUrl:d.channelId?('https://www.youtube.com/channel/'+d.channelId):''}]))return true;
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
                found.push({
                  videoId:v.videoId,title:rendererTitle(v),
                  thumbnails:(v.thumbnail&&v.thumbnail.thumbnails)||[],
                  duration:rendererDuration(v),artistName:owner.text||'',
                  artistUrl:channel.canonicalBaseUrl?('https://www.youtube.com'+channel.canonicalBaseUrl):
                    (channel.browseId?('https://www.youtube.com/channel/'+channel.browseId):''),
                  artistThumbnail:channelThumbs[0]&&channelThumbs[0].url||''
                });
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
                found.push({
                  videoId:m[1],title:title,
                  thumbnails:im?[{url:im.currentSrc||im.src,width:640}]:[],
                  duration:durationNode?(durationNode.innerText||'').trim():'',
                  artistName:ownerName,
                  artistUrl:ownerLink?new URL(ownerLink.href,location.origin).href:'',
                  artistThumbnail:''
                });
              });
            }
            return found.length ? emit(found) : false;
          }
          function poll(){
            if(sent)return;
            attempts++;
            if(scan())return;
            if(attempts<30)setTimeout(poll,500);
            else emit([]);
          }
          poll();
        })();
    """.trimIndent()
}

/** Visible Chromium YouTube IFrame player hosted in the existing Swing player window. */
class DesktopYoutubePlayerView(
    cefApp: CefApp,
    private val onEnded: () -> Unit,
    private val onError: (Int) -> Unit,
    private val onReady: () -> Unit,
    private val onState: (Int) -> Unit
) : JPanel(BorderLayout()), AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val origin = "http://127.0.0.1:" + server.localPort
    @Volatile private var html = ""
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
        add(browser.uiComponent, BorderLayout.CENTER)
        Thread(::serve, "TikLBot-YouTube-Local-Origin").apply {
            isDaemon = true
            start()
        }
    }

    fun loadTrack(track: DjMusicTrack, volume: Int, muted: Boolean) {
        require(track.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            "ID de video de YouTube no válido."
        }
        if (closed) return
        html = playerHtml(track.videoId, volume.coerceIn(0, 100), muted)
        val url = origin + "/player?video=" + track.videoId
        SwingUtilities.invokeLater {
            if (!closed) browser.loadURL(url)
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
                runCatching { browser.executeJavaScript(script, browser.url, 0) }
            }
        }
    }

    private fun playerHtml(videoId: String, volume: Int, muted: Boolean): String {
        val safeOrigin = origin
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
 player=new YT.Player('player',{width:'100%',height:'100%',videoId:'$videoId',
  playerVars:{autoplay:1,playsinline:1,controls:1,rel:0,enablejsapi:1,origin:'$safeOrigin'},
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

    private fun serve() {
        while (!closed) {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
                    )
                    val requestLine = reader.readLine().orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val requestPath = requestLine.split(' ').getOrNull(1).orEmpty()
                    // The query string follows /player; match the path, not "/player " literally.
                    val isPlayer = requestPath.substringBefore('?') == "/player"
                    val body = if (isPlayer) html.toByteArray(StandardCharsets.UTF_8) else ByteArray(0)
                    val status = if (isPlayer) "200 OK" else "404 Not Found"
                    val header = "HTTP/1.1 $status\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + body.size + "\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-store\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(header.toByteArray(StandardCharsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            } catch (_: Exception) {
                if (!closed) continue
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        SwingUtilities.invokeLater {
            runCatching { browser.close(true) }
            runCatching { router.dispose() }
            runCatching { client.dispose() }
            removeAll()
        }
    }
}
