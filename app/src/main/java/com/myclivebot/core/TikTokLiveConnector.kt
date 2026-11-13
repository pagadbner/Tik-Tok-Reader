package com.myclivebot.core

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Android-native TikTok LIVE public-chat transport.
 * Uses OkHttp and a small protobuf decoder; it does not use java.net.http,
 * a third-party signing service, or logged-in TikTok credentials.
 */
class TikTokLiveConnector(
    private val onComment: (LiveComment) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onEvent: (LiveEvent) -> Unit = {},
    private val cookieFallback: (() -> String)? = null
) : Closeable {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
        private const val HEARTBEAT_SECONDS = 10L
        private val WS_HOSTS = listOf("webcast-ws.tiktok.com", "webcast-ws.us.tiktok.com")
        @Volatile private var cachedTtwid: String? = null
        @Volatile private var cachedTtwidAt: Long = 0L
        private const val TTWID_CACHE_TTL_MS = 4 * 60 * 60 * 1_000L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var currentUsername: String? = null
    @Volatile private var currentRoomId: String? = null
    @Volatile private var currentTtwid: String? = null
    @Volatile private var stopped = true
    private val seenIds = LinkedHashSet<String>()

    @Synchronized
    fun connect(rawUsername: String) {
        val username = rawUsername.trim().removePrefix("@").substringBefore("/").lowercase(Locale.ROOT)
        require(username.matches(Regex("[a-z0-9._]{2,24}"))) {
            "Escribe un usuario público de TikTok válido, sin URL."
        }

        stopConnection()
        stopped = false
        currentUsername = username
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TikLBot-Connection").apply { isDaemon = true }
        }
        onStatus("Buscando el LIVE público de @$username…")

        scheduler?.execute {
            try {
                val roomId = resolveLiveRoom(username)
                if (stopped) return@execute
                currentRoomId = roomId
                onStatus("LIVE encontrado. Preparando conexión segura…")

                val ttwid = try {
                    fetchAnonymousDeviceCookie()
                } catch (httpError: Exception) {
                    // Do not switch transports after an explicit platform rate-limit/deny response.
                    if (httpError.message.orEmpty().contains("limitó")) throw httpError
                    onStatus("La petición HTTP no obtuvo ttwid. Probando recuperación WebView oculta…")
                    val recovered = cookieFallback?.invoke()
                        ?: throw httpError
                    validateTtwid(recovered)
                        ?: throw IOException("WebView terminó la carga, pero TikTok no entregó una cookie ttwid válida.", httpError)
                }
                if (stopped) return@execute
                currentTtwid = ttwid
                openWebSocket(0)
            } catch (error: Exception) {
                if (!stopped) {
                    onStatus("No se pudo preparar la conexión: ${error.message ?: "error de red"}")
                }
            }
        }
    }

    private fun resolveLiveRoom(username: String): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.tiktok.com")
            .addPathSegments("api-live/user/room/")
            .addQueryParameter("aid", "1988")
            .addQueryParameter("app_name", "tiktok_web")
            .addQueryParameter("device_platform", "web_pc")
            .addQueryParameter("uniqueId", username)
            .addQueryParameter("sourceType", "54")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
            .header("Referer", "https://www.tiktok.com/@$username/live")
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("TikTok respondió HTTP ${response.code} al buscar el LIVE.")
            }
            if (body.isBlank()) throw IOException("TikTok devolvió una respuesta vacía.")
            val json = JSONObject(body)
            val statusCode = json.optInt("statusCode", 0)
            if (statusCode != 0) {
                throw IOException(json.optString("message", "TikTok rechazó la consulta."))
            }
            val data = json.optJSONObject("data")
                ?: throw IOException("TikTok no devolvió datos del usuario.")
            val user = data.optJSONObject("user")
                ?: throw IOException("No se encontró ese usuario o no puede transmitir.")
            val liveRoom = data.optJSONObject("liveRoom")
            val roomId = user.optString("roomId")
            if (roomId.isBlank() || roomId == "0" || liveRoom?.optInt("status", 4) != 2) {
                throw IOException("@$username no está transmitiendo en LIVE en este momento.")
            }
            return roomId
        }
    }

    private fun validateTtwid(value: String): String? =
        value.trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,512}")) }

    private fun fetchAnonymousDeviceCookie(): String {
        val now = System.currentTimeMillis()
        cachedTtwid?.takeIf {
            it.isNotBlank() && now - cachedTtwidAt in 0 until TTWID_CACHE_TTL_MS
        }?.let { return it }

        val request = Request.Builder()
            .url("https://www.tiktok.com/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .build()

        var lastHttpCode: Int? = null
        var lastContentType: String? = null
        var lastHost: String? = null
        var lastRedirectCount = 0
        repeat(4) { attempt ->
            http.newCall(request).execute().use { response ->
                lastHttpCode = response.code
                lastContentType = response.header("Content-Type")
                lastHost = response.request.url.host
                var previous: Response? = response
                var redirects = 0
                var cookie: String? = null
                while (previous != null && redirects < 10 && cookie == null) {
                    cookie = previous.headers.values("Set-Cookie")
                        .firstNotNullOfOrNull { header ->
                            header.substringBefore(";").trim()
                                .takeIf { it.startsWith("ttwid=") }
                                ?.substringAfter("ttwid=")
                                ?.takeIf { it.isNotBlank() }
                        }
                    previous = previous.priorResponse
                    if (previous != null) redirects++
                }
                lastRedirectCount = redirects
                if (cookie != null) {
                    cachedTtwid = cookie
                    cachedTtwidAt = System.currentTimeMillis()
                    return cookie
                }
                if (response.code == 403 || response.code == 429) {
                    throw IOException("TikTok limitó la preparación anónima (HTTP ${response.code}). Espera antes de volver a intentarlo; cambiar la IP desde la app no resolvería este límite.")
                }
                if (!response.isSuccessful) {
                    throw IOException("TikTok respondió HTTP ${response.code} al solicitar la cookie anónima.")
                }
            }
            if (attempt < 3) Thread.sleep(1_000L shl attempt)
        }
        val contentType = lastContentType?.substringBefore(";") ?: "desconocido"
        throw IOException(
            "TikTok respondió HTTP ${lastHttpCode ?: "?"} pero no envió ttwid en la respuesta ni en sus redirecciones " +
                "(host: ${lastHost ?: "desconocido"}, tipo: $contentType, redirecciones: $lastRedirectCount). " +
                "Esto no confirma un bloqueo de IP: TikTok pudo omitir la cookie por una respuesta regional, consentimiento o verificación anti-bot. " +
                "Espera unos minutos, cambia de red manualmente si puedes y vuelve a probar."
        )
    }

    @Synchronized
    private fun openWebSocket(hostIndex: Int) {
        if (stopped) return
        val username = currentUsername ?: return
        val roomId = currentRoomId ?: return
        val ttwid = currentTtwid ?: return
        if (hostIndex >= WS_HOSTS.size) {
            onStatus("TikTok no aceptó la conexión WebSocket. Comprueba que el LIVE siga activo e inténtalo de nuevo.")
            return
        }

        val host = WS_HOSTS[hostIndex]
        val request = Request.Builder()
            .url(buildWebSocketUrl(host, roomId))
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ttwid=$ttwid")
            .header("Origin", "https://www.tiktok.com")
            .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
            .build()

        onStatus("Conectando con TikTok LIVE de @$username…")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) {
                    webSocket.close(1000, "TikLBot stopped")
                    return
                }
                socket = webSocket
                if (!webSocket.send(ByteString.of(*encodeEnterRoom(roomId)))) {
                    webSocket.close(1011, "Unable to join room")
                    onStatus("No se pudo entrar a la sala LIVE.")
                    return
                }
                onStatus("Conectado al LIVE de @$username. Esperando comentarios…")
                scheduler?.scheduleAtFixedRate({
                    if (!stopped) runCatching {
                        webSocket.send(ByteString.of(*encodeHeartbeat(roomId)))
                    }
                }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (stopped) return
                runCatching { processWebcastFrame(webSocket, bytes.toByteArray()) }
                    .onFailure { onStatus("Se recibió un paquete LIVE no válido; se omitió sin cerrar la conexión.") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // TikTok chat events arrive as protobuf binary frames.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped && socket === webSocket) {
                    socket = null
                    onStatus("TikTok cerró la conexión (código $code). Pulsa Conectar para volver a intentarlo.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                if (socket === webSocket) socket = null
                if (hostIndex + 1 < WS_HOSTS.size) {
                    onStatus("Probando el servidor alternativo de TikTok…")
                    openWebSocket(hostIndex + 1)
                } else {
                    val detail = response?.code?.let { "HTTP $it" } ?: (t.message ?: "error de red")
                    onStatus("No se pudo conectar con TikTok LIVE: $detail")
                }
            }
        })
    }

    private fun buildWebSocketUrl(host: String, roomId: String): HttpUrl {
        return HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegments("webcast/im/ws_proxy/ws_reuse_supplement/")
            .addQueryParameter("version_code", "180800")
            .addQueryParameter("device_platform", "web")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "es-MX")
            .addQueryParameter("browser_platform", "MacIntel")
            .addQueryParameter("browser_name", "Mozilla")
            .addQueryParameter("browser_version", USER_AGENT.removePrefix("Mozilla/"))
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("tz_name", TimeZone.getDefault().id)
            .addQueryParameter("app_name", "tiktok_web")
            .addQueryParameter("sup_ws_ds_opt", "1")
            .addQueryParameter("update_version_code", "2.0.0")
            .addQueryParameter("compress", "gzip")
            .addQueryParameter("webcast_language", "es")
            .addQueryParameter("ws_direct", "1")
            .addQueryParameter("aid", "1988")
            .addQueryParameter("live_id", "12")
            .addQueryParameter("app_language", "es")
            .addQueryParameter("client_enter", "1")
            .addQueryParameter("room_id", roomId)
            .addQueryParameter("identity", "audience")
            .addQueryParameter("history_comment_count", "6")
            .addQueryParameter("heartbeat_duration", (HEARTBEAT_SECONDS * 1000).toString())
            .addQueryParameter("resp_content_type", "protobuf")
            .addQueryParameter("did_rule", "3")
            .build()
    }

    private fun processWebcastFrame(webSocket: WebSocket, bytes: ByteArray) {
        val frame = ProtoMessage(bytes)
        if (frame.string(7) != "msg" || frame.string(6) != "pb") return
        val payload = frame.bytes(8)
        if (payload.isEmpty()) return

        val decodedPayload = if (
            payload.size >= 3 &&
            payload[0] == 0x1f.toByte() &&
            payload[1] == 0x8b.toByte() &&
            payload[2] == 0x08.toByte()
        ) {
            GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        } else payload

        val result = ProtoMessage(decodedPayload)
        val logId = frame.long(2)
        if (logId != 0L) webSocket.send(ByteString.of(*encodeAck(logId)))

        result.messages(1).forEach { raw ->
            if (raw.bool(6)) return@forEach
            val type = raw.string(1)
            val payloadMessage = ProtoMessage(raw.bytes(2))
            val messageId = raw.long(3).toString()
            if (messageId != "0" && !rememberMessage(messageId)) return@forEach

            when (type) {
                "WebcastChatMessage" -> {
                    val user = payloadMessage.message(2)
                    val identity = payloadMessage.message(18)
                    val comment = payloadMessage.string(3).trim()
                    if (comment.isBlank()) return@forEach
                    onComment(
                        LiveComment(
                            userId = user?.long(1)?.toString().orEmpty(),
                            displayName = displayName(user),
                            text = comment,
                            isModerator = identity?.bool(5) == true,
                            // Current Webcast User exposes is_follower at field 1029 and
                            // follow_status at 1024. Older schemas may nest follow_status
                            // under follow_info (field 22 / subfield 3).
                            isFollower = user?.let { sender ->
                                val explicitFlag = if (sender.has(1029)) sender.bool(1029) else null
                                val status = when {
                                    sender.has(1024) -> sender.long(1024)
                                    sender.message(22)?.has(3) == true -> sender.message(22)?.long(3)
                                    else -> null
                                }
                                FollowerStatusParser.isFollower(explicitFlag, status)
                            }
                        )
                    )
                }
                "WebcastSocialMessage" -> {
                    val user = payloadMessage.message(2)
                    // Current public webcast schema: action=1 is follow; action=3 is share.
                    if (payloadMessage.long(4) == 1L) {
                        onEvent(LiveEvent(LiveEventType.FOLLOW, displayName(user)))
                    }
                }
                "WebcastGiftMessage" -> {
                    val user = payloadMessage.message(7)
                    val repeatCount = payloadMessage.long(5).coerceAtLeast(1L)
                    val detail = if (repeatCount > 1L) "x$repeatCount" else ""
                    onEvent(LiveEvent(LiveEventType.GIFT, displayName(user), detail))
                }
            }
        }
    }

    private fun displayName(user: ProtoMessage?): String =
        user?.string(3)?.takeIf { it.isNotBlank() }
            ?: user?.string(38)?.takeIf { it.isNotBlank() }
            ?: "Usuario"

    @Synchronized
    private fun rememberMessage(id: String): Boolean {
        if (seenIds.contains(id)) return false
        seenIds.add(id)
        while (seenIds.size > 500) {
            val iterator = seenIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else break
        }
        return true
    }

    @Synchronized
    fun disconnect() {
        stopConnection()
        onStatus("Desconectado")
    }

    @Synchronized
    private fun stopConnection() {
        stopped = true
        socket?.close(1000, "TikLBot disconnect")
        socket = null
        scheduler?.shutdownNow()
        scheduler = null
        currentUsername = null
        currentRoomId = null
        currentTtwid = null
        seenIds.clear()
    }

    override fun close() = disconnect()

    private fun encodeEnterRoom(roomId: String): ByteArray {
        val enter = ProtoWriter()
            .varint(1, roomId.toLongOrNull() ?: 0L)
            .varint(4, 12L)
            .string(5, "audience")
            .finish()
        return encodePushFrame(null, "im_enter_room", enter)
    }

    private fun encodeHeartbeat(roomId: String): ByteArray {
        val heartbeat = ProtoWriter()
            .varint(1, roomId.toLongOrNull() ?: 0L)
            .finish()
        return encodePushFrame(null, "hb", heartbeat)
    }

    private fun encodeAck(logId: Long): ByteArray =
        encodePushFrame(logId, "ack", "-".toByteArray(Charsets.UTF_8))

    private fun encodePushFrame(logId: Long?, type: String, payload: ByteArray): ByteArray {
        val writer = ProtoWriter()
        if (logId != null) writer.varint(2, logId)
        writer.string(6, "pb")
        writer.string(7, type)
        writer.bytes(8, payload)
        return writer.finish()
    }

    private class ProtoMessage(bytes: ByteArray) {
        private val fields = LinkedHashMap<Int, MutableList<Any>>()

        init {
            var position = 0
            while (position < bytes.size) {
                val tagRead = readProtoVarint(bytes, position)
                position = tagRead.second
                val tag = tagRead.first.toInt()
                val field = tag ushr 3
                val wire = tag and 7
                if (field == 0) throw IOException("Invalid protobuf field")
                val value: Any
                when (wire) {
                    0 -> {
                        val parsed = readProtoVarint(bytes, position)
                        value = parsed.first
                        position = parsed.second
                    }
                    1 -> {
                        if (position + 8 > bytes.size) throw IOException("Truncated protobuf fixed64")
                        value = bytes.copyOfRange(position, position + 8)
                        position += 8
                    }
                    2 -> {
                        val lengthRead = readProtoVarint(bytes, position)
                        position = lengthRead.second
                        val length = lengthRead.first.toInt()
                        if (length < 0 || position + length > bytes.size) throw IOException("Truncated protobuf bytes")
                        value = bytes.copyOfRange(position, position + length)
                        position += length
                    }
                    5 -> {
                        if (position + 4 > bytes.size) throw IOException("Truncated protobuf fixed32")
                        value = bytes.copyOfRange(position, position + 4)
                        position += 4
                    }
                    else -> throw IOException("Unsupported protobuf wire type $wire")
                }
                fields.getOrPut(field) { ArrayList() }.add(value)
            }
        }

        private fun last(field: Int): Any? = fields[field]?.lastOrNull()
        fun has(field: Int): Boolean = fields[field]?.isNotEmpty() == true
        fun long(field: Int): Long = last(field) as? Long ?: 0L
        fun bool(field: Int): Boolean = long(field) != 0L
        fun string(field: Int): String = (last(field) as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()
        fun bytes(field: Int): ByteArray = (last(field) as? ByteArray) ?: ByteArray(0)
        fun message(field: Int): ProtoMessage? = (last(field) as? ByteArray)?.let(::ProtoMessage)
        fun messages(field: Int): List<ProtoMessage> =
            fields[field].orEmpty().mapNotNull { it as? ByteArray }.map(::ProtoMessage)
    }

    private class ProtoWriter {
        private val output = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): ProtoWriter {
            if (value == 0L) return this
            writeVarint(((field shl 3) or 0).toLong())
            writeVarint(value)
            return this
        }

        fun string(field: Int, value: String): ProtoWriter =
            bytes(field, value.toByteArray(Charsets.UTF_8))

        fun bytes(field: Int, value: ByteArray): ProtoWriter {
            if (value.isEmpty()) return this
            writeVarint(((field shl 3) or 2).toLong())
            writeVarint(value.size.toLong())
            output.write(value)
            return this
        }

        fun finish(): ByteArray = output.toByteArray()

        private fun writeVarint(value: Long) {
            var current = value
            while ((current and 0x7f.inv().toLong()) != 0L) {
                output.write(((current and 0x7f) or 0x80).toInt())
                current = current ushr 7
            }
            output.write(current.toInt())
        }
    }

}

private fun readProtoVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
    var position = start
    var value = 0L
    var shift = 0
    while (position < bytes.size && shift < 70) {
        val byte = bytes[position++].toInt() and 0xff
        value = value or ((byte and 0x7f).toLong() shl shift)
        if ((byte and 0x80) == 0) return value to position
        shift += 7
    }
    throw IOException("Invalid or truncated protobuf varint")
}
