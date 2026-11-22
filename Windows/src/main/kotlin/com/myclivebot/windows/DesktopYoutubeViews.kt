package com.myclivebot.windows

import com.myclivebot.core.DjMusicTrack
import javafx.application.Platform
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/**
 * Hidden JavaFX browser used only for YouTube's own search page.
 * Search results are read from YouTube's page data; no third-party search API is used.
 */
class DesktopYoutubeSearchView(
    private val onResults: (Int, String) -> Unit
) : JFXPanel(), AutoCloseable {
    private val activeRequest = AtomicInteger(0)
    @Volatile private var engine: WebEngine? = null
    @Volatile private var pendingSearch: Pair<String, Int>? = null
    @Volatile private var loadingRequestId: Int = 0
    @Volatile private var injectedRequestId: Int = 0
    private var injectionTimer: Timeline? = null

    init {
        Platform.setImplicitExit(false)
        Platform.runLater {
            val view = WebView()
            engine = view.engine
            scene = Scene(view)
            view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                val id = loadingRequestId
                if (id == activeRequest.get() &&
                    (state == Worker.State.RUNNING || state == Worker.State.SUCCEEDED)
                ) {
                    tryInjectSearch(view.engine, id)
                }
            }
            pendingSearch?.let { (query, requestId) ->
                pendingSearch = null
                loadSearch(view.engine, query, requestId)
            }
        }
    }

    fun search(query: String, requestId: Int) {
        activeRequest.set(requestId)
        Platform.runLater {
            val current = engine
            if (current == null) pendingSearch = query to requestId
            else loadSearch(current, query, requestId)
        }
    }

    private fun loadSearch(target: WebEngine, query: String, requestId: Int) {
        if (requestId != activeRequest.get()) return
        loadingRequestId = requestId
        injectedRequestId = 0
        injectionTimer?.stop()
        val videoId = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")
            .find(query)?.groupValues?.get(1)
        val url = if (videoId != null) {
            "https://www.youtube.com/watch?v=$videoId&tklbot_request=$requestId"
        } else {
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&tklbot_request=$requestId"
        }
        target.load(url)
        startSearchInjectionPolling(target, requestId)
    }

    inner class SearchBridge {
        fun onResults(requestId: Int, json: String) {
            if (requestId == activeRequest.get()) dispatchResults(requestId, json)
        }
    }

    private fun dispatchResults(requestId: Int, json: String) {
        SwingUtilities.invokeLater {
            if (requestId == activeRequest.get()) onResults(requestId, json)
        }
    }

    override fun close() {
        activeRequest.incrementAndGet()
        pendingSearch = null
        Platform.runLater {
            injectionTimer?.stop()
            injectionTimer = null
            runCatching { engine?.load("about:blank") }
        }
    }

    private fun tryInjectSearch(target: WebEngine, requestId: Int): Boolean {
        if (requestId != activeRequest.get() || requestId != loadingRequestId) return false
        if (injectedRequestId == requestId) return true
        return runCatching {
            val currentUrl = target.location.orEmpty()
            val documentUrl = target.executeScript("document.location.href") as? String ?: return@runCatching false
            if (!currentUrl.contains("tklbot_request=$requestId") ||
                !documentUrl.contains("tklbot_request=$requestId") ||
                !documentUrl.contains("youtube.com")
            ) return@runCatching false
            val window = target.executeScript("window") as JSObject
            window.setMember("TikLBotMusicSearch", SearchBridge())
            target.executeScript(searchScript(requestId))
            injectedRequestId = requestId
            true
        }.getOrDefault(false)
    }

    private fun startSearchInjectionPolling(target: WebEngine, requestId: Int) {
        var attempts = 0
        lateinit var timer: Timeline
        timer = Timeline(KeyFrame(Duration.millis(500.0)) {
            if (requestId != activeRequest.get() || requestId != loadingRequestId) {
                timer.stop()
            } else {
                attempts++
                if (tryInjectSearch(target, requestId)) {
                    timer.stop()
                } else if (attempts >= 50) {
                    timer.stop()
                    dispatchResults(requestId, "[]")
                }
            }
        })
        timer.cycleCount = Timeline.INDEFINITE
        injectionTimer = timer
        timer.play()
    }

    private fun searchScript(requestId: Int): String = """
        (function(){
          var requestId=$requestId, attempts=0;
          function clean(v){return String(v||'').replace(/\s+/g,' ').trim();}
          function duration(v){return /^\d{1,2}:\d{2}(?::\d{2})?$/.test(clean(v));}
          function send(items){
            var seen={},out=[];
            (items||[]).forEach(function(x){
              if(!x||!x.id||!clean(x.title)||duration(x.title)||seen[x.id]||out.length>=8)return;
              seen[x.id]=1;
              var thumbs=x.thumbnails||[];
              thumbs.sort(function(a,b){return (b.width||0)-(a.width||0);});
              out.push({id:x.id,title:clean(x.title),thumbnail:(thumbs[0]&&thumbs[0].url)||'',
                duration:clean(x.duration),artistName:clean(x.artistName),artistUrl:x.artistUrl||'',
                artistThumbnail:x.artistThumbnail||''});
            });
            if(out.length){TikLBotMusicSearch.onResults(requestId,JSON.stringify(out));return true;}
            return false;
          }
          function scan(){
            if(location.pathname.indexOf('/watch')===0){
              var d=window.ytInitialPlayerResponse&&window.ytInitialPlayerResponse.videoDetails;
              if(d&&send([{id:d.videoId,title:d.title,thumbnails:d.thumbnail&&d.thumbnail.thumbnails,
                duration:d.lengthSeconds||'',artistName:d.author||'',
                artistUrl:d.channelId?('https://www.youtube.com/channel/'+d.channelId):''}]))return true;
            }
            var found=[];
            function walk(o){
              if(!o||typeof o!=='object')return;
              var v=o.videoRenderer;
              if(v&&v.videoId){
                var owner=(v.ownerText&&v.ownerText.runs&&v.ownerText.runs[0])||{};
                var endpoint=owner.navigationEndpoint&&owner.navigationEndpoint.browseEndpoint||{};
                var channelThumb=v.channelThumbnailSupportedRenderers&&v.channelThumbnailSupportedRenderers.channelThumbnailWithLinkRenderer;
                var thumbs=channelThumb&&channelThumb.thumbnail&&channelThumb.thumbnail.thumbnails||[];
                var title=v.title&&((v.title.runs||[]).map(function(r){return r.text||'';}).join('')||v.title.simpleText)||'';
                var len=v.lengthText&&(v.lengthText.simpleText||(v.lengthText.runs||[]).map(function(r){return r.text||'';}).join(''))||'';
                found.push({id:v.videoId,title:title,thumbnails:v.thumbnail&&v.thumbnail.thumbnails||[],duration:len,
                  artistName:owner.text||'',artistUrl:endpoint.canonicalBaseUrl?('https://www.youtube.com'+endpoint.canonicalBaseUrl):
                    (endpoint.browseId?('https://www.youtube.com/channel/'+endpoint.browseId):''),
                  artistThumbnail:thumbs.length?thumbs[thumbs.length-1].url:''});
              }
              Object.keys(o).forEach(function(k){if(k!=='videoRenderer')walk(o[k]);});
            }
            walk(window.ytInitialData);
            if(!found.length){
              document.querySelectorAll('a#video-title,a[href*="watch?v="]').forEach(function(a){
                var m=(a.href||'').match(/[?&]v=([A-Za-z0-9_-]{11})/);if(!m)return;
                var card=a.closest('ytd-video-renderer,ytd-rich-item-renderer')||a.parentElement;
                var titleNode=card&&(card.querySelector('a#video-title')||card.querySelector('#video-title')||card.querySelector('h3'));
                var title=clean(titleNode&&(titleNode.getAttribute('title')||titleNode.textContent)||a.getAttribute('title'));
                var img=card&&card.querySelector('img');
                var owner=card&&card.querySelector('ytd-channel-name a,#channel-name a,a[href^="/channel/"],a[href^="/@"]');
                found.push({id:m[1],title:title,thumbnails:img?[{url:img.currentSrc||img.src,width:640}]:[],
                  duration:'',artistName:owner?clean(owner.textContent):'',artistUrl:owner?new URL(owner.href,location.origin).href:''});
              });
            }
            return send(found);
          }
          function poll(){attempts++;if(scan())return;if(attempts<60)setTimeout(poll,500);
            else TikLBotMusicSearch.onResults(requestId,'[]');}
          poll();
        })();
    """.trimIndent()
}

/**
 * Visible YouTube IFrame player. A loopback-only HTTP origin supplies a real Referer
 * to YouTube, matching the Android WebView's base-URL behavior.
 */
class DesktopYoutubePlayerView(
    private val onEnded: () -> Unit,
    private val onError: (Int) -> Unit,
    private val onReady: () -> Unit,
    private val onState: (Int) -> Unit
) : JFXPanel(), AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val origin = "http://127.0.0.1:${server.localPort}"
    @Volatile private var engine: WebEngine? = null
    @Volatile private var html = ""
    @Volatile private var pendingPlayerUrl: String? = null
    @Volatile private var closed = false
    private var bridgeTimer: Timeline? = null

    init {
        Platform.setImplicitExit(false)
        Thread(::serve, "TikLBot-YouTube-Local-Origin").apply { isDaemon = true }.start()
        Platform.runLater {
            val view = WebView()
            engine = view.engine
            scene = Scene(view)
            view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.FAILED && !closed) {
                    SwingUtilities.invokeLater { onError(5) }
                }
            }
            pendingPlayerUrl?.let { url ->
                pendingPlayerUrl = null
                view.engine.load(url)
                startPlayerBridgePolling(view.engine)
            }
        }
    }

    fun loadTrack(track: DjMusicTrack, volume: Int, muted: Boolean) {
        require(track.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "ID de video de YouTube no válido." }
        html = playerHtml(track.videoId, volume.coerceIn(0, 100), muted)
        val url = "$origin/player?video=${track.videoId}"
        Platform.runLater {
            val current = engine
            if (current == null) pendingPlayerUrl = url
            else {
                current.load(url)
                startPlayerBridgePolling(current)
            }
        }
    }

    private fun startPlayerBridgePolling(target: WebEngine) {
        bridgeTimer?.stop()
        var attempts = 0
        lateinit var timer: Timeline
        timer = Timeline(KeyFrame(Duration.millis(150.0)) {
            if (closed) {
                timer.stop()
            } else {
                attempts++
                val installed = runCatching {
                    val ready = target.executeScript(
                        "typeof window.notifyPlayer === 'function' && typeof window.__tiklbotPending !== 'undefined' && typeof window.TikLBotMusicPlayer !== 'undefined'"
                    ) as? Boolean ?: false
                    if (!ready) false
                    else {
                        val window = target.executeScript("window") as JSObject
                        window.setMember("TikLBotMusicPlayer", PlayerBridge())
                        target.executeScript("""
                            window.__tiklbotBridgeReady=true;
                            (window.__tiklbotPending||[]).forEach(function(event){
                              var callback=window.TikLBotMusicPlayer[event[0]];
                              if(callback)callback.apply(window.TikLBotMusicPlayer,event[1]||[]);
                            });
                            window.__tiklbotPending=[];
                        """.trimIndent())
                        true
                    }
                }.getOrDefault(false)
                if (installed) {
                    timer.stop()
                } else if (attempts >= 200) {
                    timer.stop()
                    SwingUtilities.invokeLater { onError(5) }
                }
            }
        })
        timer.cycleCount = Timeline.INDEFINITE
        bridgeTimer = timer
        timer.play()
    }

    fun clearTrack() = evaluate("clearPlayer()")
    fun setVolume(value: Int) = evaluate("setVolume(${value.coerceIn(0, 100)})")
    fun setMuted(value: Boolean) = evaluate("setMuted($value)")
    fun playPause() = evaluate("playPause()")
    fun seekBy(seconds: Int) = evaluate("seekBy(${seconds.coerceIn(-60, 60)})")

    private fun evaluate(script: String) {
        Platform.runLater { runCatching { engine?.executeScript(script) } }
    }

    inner class PlayerBridge {
        fun onPlayerEnded() = SwingUtilities.invokeLater(onEnded)
        fun onPlayerError(code: Int) = SwingUtilities.invokeLater { onError(code) }
        fun onPlayerReady() = SwingUtilities.invokeLater(onReady)
        fun onPlayerState(value: Int) = SwingUtilities.invokeLater { onState(value) }
        fun onAutoplayBlocked() = SwingUtilities.invokeLater { onState(-1) }
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
window.__tiklbotBridgeReady=false;
window.__tiklbotPending=[];
function notifyPlayer(method,args){
 if(window.__tiklbotBridgeReady){var fn=window.TikLBotMusicPlayer[method];if(fn)fn.apply(window.TikLBotMusicPlayer,args||[]);}
 else window.__tiklbotPending.push([method,args||[]]);
}
window.TikLBotMusicPlayer={
 onPlayerEnded:function(){notifyPlayer('onPlayerEnded');},
 onPlayerError:function(code){notifyPlayer('onPlayerError',[code]);},
 onPlayerReady:function(){notifyPlayer('onPlayerReady');},
 onPlayerState:function(value){notifyPlayer('onPlayerState',[value]);},
 onAutoplayBlocked:function(){notifyPlayer('onAutoplayBlocked');}
};
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
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    val requestLine = reader.readLine().orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val isPlayer = requestLine.startsWith("GET /player ")
                    val body = if (isPlayer) html.toByteArray(StandardCharsets.UTF_8) else ByteArray(0)
                    val status = if (isPlayer) "200 OK" else "404 Not Found"
                    val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
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
        closed = true
        pendingPlayerUrl = null
        runCatching { server.close() }
        Platform.runLater {
            bridgeTimer?.stop()
            bridgeTimer = null
            runCatching { engine?.load("about:blank") }
        }
    }
}
