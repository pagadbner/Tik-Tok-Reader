package com.myclivebot.core

import io.github.jwdeveloper.tiktok.TikTokLive
import java.time.Duration

/**
 * Public LIVE chat connector. Uses TikTokLiveJava's community Webcast client.
 * This is not an official TikTok API and may need updates if TikTok changes Webcast.
 */
class TikTokLiveConnector(
    private val onComment: (LiveComment) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var liveClient: Any? = null

    fun connect(rawUsername: String) {
        val username = rawUsername.trim().removePrefix("@").substringBefore("/")
        require(username.matches(Regex("[A-Za-z0-9._]{2,24}"))) {
            "Escribe un usuario de TikTok válido, sin URL ni @."
        }
        disconnect()
        onStatus("Conectando a @$username…")

        val builder = TikTokLive.newClient(username)
            .configure { settings ->
                settings.setClientLanguage("es")
                settings.setPrintToConsole(false)
                settings.setRetryOnConnectionFailure(true)
                settings.setRetryConnectionTimeout(Duration.ofSeconds(5))
            }
            .onConnected { client, _ ->
                liveClient = client
                onStatus("Conectado al LIVE de @$username")
            }
            .onReconnecting { client, _ ->
                liveClient = client
                onStatus("Reconectando a @$username…")
            }
            .onDisconnected { client, _ ->
                liveClient = client
                onStatus("Conexión interrumpida; esperando reconexión…")
            }
            .onError { client, event ->
                liveClient = client
                onStatus("Error de TikTok: ${event.exception?.message ?: "conexión no disponible"}")
            }
            .onLiveEnded { client, _ ->
                liveClient = client
                onStatus("El LIVE de @$username terminó")
            }
            .onComment { client, event ->
                liveClient = client
                val user = runCatching { event.user.name }.getOrNull().orEmpty().ifBlank { "Usuario" }
                val text = runCatching { event.text }.getOrNull().orEmpty()
                if (text.isNotBlank()) {
                    onComment(
                        LiveComment(
                            userId = user,
                            displayName = user,
                            text = text,
                            isModerator = false
                        )
                    )
                }
            }

        // buildAndConnect returns the library's LiveClient; keep it without
        // coupling the app to implementation details of its concrete type.
        val builtClient: Any? = builder.buildAndConnect()
        if (builtClient !is Unit) liveClient = builtClient
    }

    fun disconnect() {
        val current = liveClient
        liveClient = null
        if (current != null) {
            runCatching {
                current.javaClass.methods
                    .firstOrNull { it.name == "disconnect" && it.parameterCount == 0 }
                    ?.invoke(current)
            }
        }
    }
}
