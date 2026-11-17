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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    @Volatile private var heartbeatTask: ScheduledFuture<*>? = null
    @Volatile private var eventExecutor: BoundedSerialEventExecutor? = null
    @Volatile private var currentUsername: String? = null
    @Volatile private var currentRoomId: String? = null
    @Volatile private var currentTtwid: String? = null
    @Volatile private var stopped = true
    private val seenIds = LinkedHashSet<String>()
    private val sessionGeneration = AtomicLong(0L)
    private val webSocketGeneration = AtomicLong(0L)
    @Volatile private var reconnectAttempt = 0

    @Synchronized
    fun connect(rawUsername: String) {
        val username = rawUsername.trim().removePrefix("@").substringBefore("/").lowercase(Locale.ROOT)
        require(username.matches(Regex("[a-z0-9._]{2,24}"))) {
            "Escribe un usuario público de TikTok válido, sin URL."
        }

        stopConnection()
        val sessionId = sessionGeneration.get()
        stopped = false
        reconnectAttempt = 0
        eventExecutor = BoundedSerialEventExecutor()
        currentUsername = username
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TikLBot-Connection").apply { isDaemon = true }
        }
        onStatus("Buscando el LIVE público de @$username…")

        scheduler?.execute {
            try {
                val roomId = resolveLiveRoom(username)
                if (!isSessionActive(sessionId)) return@execute
                currentRoomId = roomId
                onStatus("LIVE encontrado. Preparando conexión segura…")

                val ttwid = obtainTtwid()
                if (!isSessionActive(sessionId)) return@execute
                currentTtwid = ttwid
                openWebSocket(0, sessionId)
            } catch (error: Exception) {
                if (isSessionActive(sessionId)) {
                    onStatus("No se pudo preparar la conexión: ${error.message ?: "error de red"}")
                    scheduleReconnect(sessionId, error.message ?: "error de preparación")
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
        TtwidCookieParser.validate(value)

    /** Reuses the same validated HTTP/WebView cookie recovery path for initial and retry connections. */
    private fun obtainTtwid(): String {
        return try {
            fetchAnonymousDeviceCookie()
        } catch (httpError: Exception) {
            // Do not switch transports after an explicit platform rate-limit/deny response.
            if (httpError.message.orEmpty().contains("limitó")) throw httpError
            onStatus("La petición HTTP no obtuvo ttwid. Probando recuperación WebView oculta…")
            val recovered = cookieFallback?.invoke() ?: throw httpError
            validateTtwid(recovered)
                ?: throw IOException("WebView terminó la carga, pero TikTok no entregó una cookie ttwid válida.", httpError)
        }
    }

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
                            TtwidCookieParser.fromCookieHeader(header)
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
    private fun openWebSocket(hostIndex: Int, sessionId: Long) {
        if (!isSessionActive(sessionId)) return
        heartbeatTask?.cancel(false)
        heartbeatTask = null
        val attemptId = webSocketGeneration.incrementAndGet()
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
        val overloadWarningActive = AtomicBoolean(false)
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isSessionActive(sessionId) || webSocketGeneration.get() != attemptId) {
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
                scheduler?.schedule({
                    if (isSessionActive(sessionId) && socket === webSocket) reconnectAttempt = 0
                }, 30, TimeUnit.SECONDS)
                heartbeatTask = scheduler?.scheduleAtFixedRate({
                    if (isSessionActive(sessionId) && socket === webSocket) runCatching {
                        webSocket.send(ByteString.of(*encodeHeartbeat(roomId)))
                    }
                }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isSessionActive(sessionId) || webSocketGeneration.get() != attemptId || socket !== webSocket) return
                // Copy and enqueue without waiting: the OkHttp reader must remain free
                // to receive control frames and keep the WebSocket transport responsive.
                val frame = bytes.toByteArray()
                val worker = eventExecutor ?: return
                val logId = runCatching { ProtoMessage(frame).long(2) }.getOrDefault(0L)
                val droppedBefore = worker.droppedTaskCount()
                val accepted = enqueueAndAcknowledge(
                    worker,
                    Runnable {
                        if (isSessionActive(sessionId) && webSocketGeneration.get() == attemptId && socket === webSocket) {
                            runCatching { processWebcastFrame(webSocket, frame, sessionId) }
                                .onFailure {
                                    if (isSessionActive(sessionId)) onStatus("Se recibió un paquete LIVE no válido; se omitió sin cerrar la conexión.")
                                }
                        }
                        // Permit another overload notice after the backlog has recovered.
                        if (worker.queuedCount() <= BoundedSerialEventExecutor.DEFAULT_QUEUE_CAPACITY / 2) {
                            overloadWarningActive.set(false)
                        }
                    }
                ) {
                    if (logId != 0L) {
                        runCatching { webSocket.send(ByteString.of(*encodeAck(logId))) }
                    }
                }
                if (!accepted) {
                    if (isSessionActive(sessionId) && worker.droppedTaskCount() > droppedBefore && overloadWarningActive.compareAndSet(false, true)) {
                        onStatus("Conectado al LIVE, pero la cola de recepción está saturada. Se conservaron los paquetes anteriores y este paquete no fue aceptado; reduciendo carga para proteger los comentarios pendientes.")
                    }
                    return
                }
                if (worker.droppedTaskCount() > droppedBefore &&
                    overloadWarningActive.compareAndSet(false, true)
                ) {
                    onStatus("Conectado al LIVE. La cola de recepción está saturada; se conservan los paquetes ya aceptados para evitar perder comentarios pendientes.")
                    // The worker may have drained the backlog before this callback reports it.
                    if (worker.queuedCount() <= BoundedSerialEventExecutor.DEFAULT_QUEUE_CAPACITY / 2) {
                        overloadWarningActive.set(false)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // TikTok chat events arrive as protobuf binary frames.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isSessionActive(sessionId) && socket === webSocket) {
                    socket = null
                    heartbeatTask?.cancel(false)
                    heartbeatTask = null
                    scheduleReconnect(sessionId, "TikTok cerró la conexión (código $code).")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isSessionActive(sessionId) || webSocketGeneration.get() != attemptId || socket !== webSocket) return
                socket = null
                heartbeatTask?.cancel(false)
                heartbeatTask = null
                if (hostIndex + 1 < WS_HOSTS.size) {
                    onStatus("Probando el servidor alternativo de TikTok…")
                    openWebSocket(hostIndex + 1, sessionId)
                } else {
                    val detail = response?.code?.let { "HTTP $it" } ?: (t.message ?: "error de red")
                    scheduleReconnect(sessionId, "No se pudo conectar con TikTok LIVE: $detail")
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

    private fun processWebcastFrame(webSocket: WebSocket, bytes: ByteArray, sessionId: Long) {
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
        result.messages(1).forEach { raw ->
            if (raw.bool(6)) return@forEach
            val type = raw.string(1)
            val payloadMessage = ProtoMessage(raw.bytes(2))
            val messageId = raw.long(3).toString()
            if (messageId != "0" && !rememberMessage(messageId)) return@forEach

            if (!isSessionActive(sessionId)) return@forEach
            when (type) {
                "WebcastChatMessage" -> {
                    val user = payloadMessage.message(2)
                    val identity = payloadMessage.message(18)
                    val replyToUser = payloadMessage.message(12)
                    val comment = payloadMessage.string(3).trim()
                    if (comment.isBlank()) return@forEach
                    if (isSessionActive(sessionId)) onComment(
                        LiveComment(
                            userId = user?.long(1)?.toString().orEmpty(),
                            displayName = displayName(user),
                            text = comment,
                            profileUsername = user?.string(38)?.trim().orEmpty(),
                            avatarUrl = user?.let { sender ->
                                sender.message(9)?.strings(1)?.firstOrNull()
                                    ?: sender.message(16)?.strings(1)?.firstOrNull()
                                    ?: sender.message(17)?.strings(1)?.firstOrNull()
                            },
                            isModerator = identity?.bool(5) == true,
                            // TikTok exposes the addressed user on the chat payload (atUser).
                            // When present, announce the response target instead of reading
                            // the raw @mention in the message text.
                            replyToDisplayName = replyToUser?.let(::displayName)
                                ?.takeUnless { it == "Usuario" },
                            replyToProfileUsername = replyToUser?.string(38)?.trim()
                                ?.takeIf { it.isNotBlank() },
                            contentLanguage = payloadMessage.string(14).takeIf { it.isNotBlank() },
                            // Current Webcast User exposes is_follower at field 1029 and
                            // follow_status at 1024. Older schemas may nest follow_status
                            // under follow_info (field 22 / subfield 3).
                            isFollower = followerStatus(user)
                        )
                    )
                }
                "WebcastSocialMessage" -> {
                    val user = payloadMessage.message(2)
                    // Current public webcast schema: action=1 is follow; action=3 is share.
                    val userName = displayName(user)
                    val userId = user?.long(1)?.takeIf { it != 0L }?.toString().orEmpty()
                    val follower = followerStatus(user)
                    when (payloadMessage.long(4)) {
                        1L -> if (isSessionActive(sessionId)) onEvent(
                            LiveEvent(LiveEventType.FOLLOW, userName, userId = userId, isFollower = follower)
                        )
                        3L -> if (isSessionActive(sessionId)) onEvent(
                            LiveEvent(LiveEventType.SHARE, userName, userId = userId, isFollower = follower)
                        )
                    }
                }
                "WebcastMemberMessage" -> {
                    val user = payloadMessage.message(2)
                    if (user != null) {
                        if (isSessionActive(sessionId)) onEvent(
                            LiveEvent(
                                LiveEventType.JOIN,
                                displayName(user),
                                userId = user.long(1).takeIf { it != 0L }?.toString().orEmpty(),
                                isFollower = followerStatus(user)
                            )
                        )
                    }
                }
                "WebcastLikeMessage" -> {
                    val user = payloadMessage.message(5)
                    if (user != null) {
                        val likesInEvent = payloadMessage.long(2).coerceAtLeast(1L)
                        if (isSessionActive(sessionId)) onEvent(
                            LiveEvent(
                                LiveEventType.LIKE,
                                displayName(user),
                                detail = likesInEvent.toString(),
                                userId = user.long(1).takeIf { it != 0L }?.toString().orEmpty()
                            )
                        )
                    }
                }
                "WebcastGiftMessage" -> {
                    val user = payloadMessage.message(7)
                    val repeatCount = payloadMessage.long(5).coerceAtLeast(1L)
                    val detail = if (repeatCount > 1L) "x$repeatCount" else ""
                    if (isSessionActive(sessionId)) onEvent(LiveEvent(LiveEventType.GIFT, displayName(user), detail, userId = user?.long(1)?.takeIf { it != 0L }?.toString().orEmpty(), giftGroupId = payloadMessage.long(3).takeIf { it != 0L }?.toString().orEmpty()))
                }
            }
        }
    }

    private fun displayName(user: ProtoMessage?): String =
        user?.string(3)?.takeIf { it.isNotBlank() }
            ?: user?.string(38)?.takeIf { it.isNotBlank() }
            ?: "Usuario"

    private fun followerStatus(user: ProtoMessage?): Boolean? {
        if (user == null) return null
        val explicitFlag = if (user.has(1029)) user.bool(1029) else null
        val status = when {
            user.has(1024) -> user.long(1024)
            user.message(22)?.has(3) == true -> user.message(22)?.long(3)
            else -> null
        }
        return FollowerStatusParser.isFollower(explicitFlag, status)
    }

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

    private fun isSessionActive(sessionId: Long): Boolean =
        !stopped && sessionGeneration.get() == sessionId

    private fun scheduleReconnect(sessionId: Long, reason: String) {
        if (!isSessionActive(sessionId)) return
        val executor = scheduler ?: return
        val attempt = reconnectAttempt.coerceAtMost(4)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        val delaySeconds = (2L shl attempt).coerceAtMost(30L)
        onStatus("$reason Se reintentará la conexión LIVE en $delaySeconds segundos…")
        runCatching {
            executor.schedule({
                if (!isSessionActive(sessionId)) return@schedule
                try {
                    val username = currentUsername ?: return@schedule
                    val roomId = resolveLiveRoom(username)
                    if (!isSessionActive(sessionId)) return@schedule
                    currentRoomId = roomId
                    val refreshedTtwid = obtainTtwid()
                    if (!isSessionActive(sessionId)) return@schedule
                    currentTtwid = refreshedTtwid
                    openWebSocket(0, sessionId)
                } catch (error: Exception) {
                    if (isSessionActive(sessionId)) {
                        scheduleReconnect(sessionId, error.message ?: "No se pudo recuperar la conexión")
                    }
                }
            }, delaySeconds, TimeUnit.SECONDS)
        }.onFailure {
            if (isSessionActive(sessionId)) onStatus("No se pudo programar la reconexión LIVE: ${it.message ?: "error"}")
        }
    }

    @Synchronized
    private fun stopConnection() {
        sessionGeneration.incrementAndGet()
        webSocketGeneration.incrementAndGet()
        stopped = true
        socket?.close(1000, "TikLBot disconnect")
        socket = null
        heartbeatTask?.cancel(false)
        heartbeatTask = null
        scheduler?.shutdownNow()
        scheduler = null
        eventExecutor?.close()
        eventExecutor = null
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
        fun strings(field: Int): List<String> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.toString(Charsets.UTF_8) }
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
