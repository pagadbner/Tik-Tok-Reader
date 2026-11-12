package com.myclivebot.core

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Loopback-only WebSocket bridge for local integrations and diagnostics.
 * It never binds to Wi-Fi/LAN interfaces and requires no external server.
 */
class LocalBridgeServer(
    private val onCommand: (String, String) -> Unit = { _, _ -> }
) {
    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8765
    }

    private val peers = CopyOnWriteArraySet<WebSocket>()
    @Volatile private var running = false
    @Volatile private var server: WebSocketServer? = null

    @Synchronized
    fun start() {
        if (running) return
        val instance = object : WebSocketServer(
            InetSocketAddress(InetAddress.getByName(HOST), PORT)
        ) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                peers.add(conn)
                conn.send(JSONObject().put("type", "bridge_ready").put("host", HOST).put("port", PORT).toString())
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                peers.remove(conn)
            }

            override fun onMessage(conn: WebSocket, message: String) {
                runCatching {
                    val json = JSONObject(message)
                    val type = json.optString("type")
                    val value = json.optString("value")
                    if (type.isNotBlank()) onCommand(type, value)
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                peers.remove(conn)
            }

            override fun onStart() {
                running = true
                broadcast(JSONObject().put("type", "bridge_status").put("state", "started"))
            }
        }
        server = instance
        instance.isReuseAddr = true
        instance.start()
    }

    fun publish(type: String, payload: JSONObject) {
        val event = JSONObject(payload.toString()).put("type", type).toString()
        peers.forEach { peer -> runCatching { if (peer.isOpen) peer.send(event) } }
    }

    @Synchronized
    fun stop() {
        running = false
        peers.forEach { runCatching { it.close() } }
        peers.clear()
        val current = server
        server = null
        runCatching { current?.stop(1000) }
    }

    fun isRunning(): Boolean = running

    private fun broadcast(json: JSONObject) {
        val value = json.toString()
        peers.forEach { peer -> runCatching { if (peer.isOpen) peer.send(value) } }
    }
}
