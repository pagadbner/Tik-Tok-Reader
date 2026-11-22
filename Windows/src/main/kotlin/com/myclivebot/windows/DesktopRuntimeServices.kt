package com.myclivebot.windows

import com.myclivebot.core.DesktopLiveChatHistoryStore
import com.myclivebot.core.LiveComment
import com.myclivebot.core.LocalBridgeServer
import org.json.JSONObject

/**
 * Owns desktop-only integrations shared by the UI: durable chat history and
 * the loopback WebSocket bridge. Failures are isolated so optional integrations
 * cannot prevent the LIVE reader from starting.
 */
class DesktopRuntimeServices : AutoCloseable {
    private val history = runCatching { DesktopLiveChatHistoryStore() }.getOrNull()
    private val bridge = LocalBridgeServer { command, value ->
        when (command) {
            "ping" -> bridgeRef?.publish("pong", JSONObject().put("value", value))
            "status" -> bridgeRef?.publish("status", JSONObject().put("value", "running"))
        }
    }

    @Volatile private var closed = false

    init {
        bridgeRef = bridge
        runCatching { bridge.start() }
    }

    fun recordComment(comment: LiveComment): Boolean {
        if (closed) return false
        val stored = history?.addAsync(comment) ?: false
        runCatching {
            bridge.publish(
                "comment",
                JSONObject()
                    .put("id", comment.id)
                    .put("username", comment.displayName)
                    .put("profile_username", comment.profileUsername)
                    .put("text", comment.text)
                    .put("avatar_url", comment.avatarUrl)
                    .put("is_moderator", comment.isModerator)
                    .put("is_follower", comment.isFollower)
                    .put("received_at", comment.receivedAt)
            )
        }
        return stored
    }

    fun publishStatus(status: String) {
        if (closed) return
        runCatching { bridge.publish("status", JSONObject().put("value", status)) }
    }

    fun loadRecentHistory(limit: Int = 500): List<LiveComment> {
        if (closed) return emptyList()
        return runCatching { history?.getAll().orEmpty().takeLast(limit.coerceIn(1, 2_000)) }
            .getOrDefault(emptyList())
    }

    fun clearHistory(): Boolean {
        if (closed) return false
        return runCatching {
            history?.clear()
            true
        }.getOrDefault(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { bridge.stop() }
        runCatching { history?.close() }
        if (bridgeRef === bridge) bridgeRef = null
    }

    companion object {
        @Volatile private var bridgeRef: LocalBridgeServer? = null
    }
}
