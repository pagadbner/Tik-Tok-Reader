package com.myclivebot.core

import io.github.jwdeveloper.tiktok.api.live.LiveClient
import io.github.jwdeveloper.tiktok.client.TikTokLive
import java.time.Duration

/**
 * Public TikTok LIVE chat connector using TikTokLiveJava's community Webcast client.
 * This is not an official TikTok API and may need updates if TikTok changes Webcast.
 */
class TikTokLiveConnector(
    private val onComment: (LiveComment) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    private var liveClient: LiveClient? = null

    @Synchronized
    fun connect(rawUsername: String) {
        val username = rawUsername.trim().removePrefix("@").substringBefore("/")
        require(username.matches(Regex("[A-Za-z0-9._]{2,24}"))) {
            "Escribe un usuario de TikTok válido, sin URL ni @."
        }

        disconnect()
        onStatus("Conectando a @$username…")

        try {
            val builder = TikTokLive.newClient(username)
                .configure { settings ->
                    settings.setClientLanguage("es")
                    settings.setPrintToConsole(false)
                    settings.setRetryOnConnectionFailure(true)
                    settings.setRetryConnectionTimeout(Duration.ofSeconds(5))
                }
                .onConnected { _, _ ->
                    onStatus("Conectado al LIVE de @$username")
                }
                .onReconnecting { _, _ ->
                    onStatus("Reconectando a @$username…")
                }
                .onDisconnected { _, _ ->
                    onStatus("Conexión interrumpida; esperando reconexión…")
                }
                .onError { _, event ->
                    onStatus("Error de TikTok: ${event.exception?.message ?: "conexión no disponible"}")
                }
                .onLiveEnded { _, _ ->
                    onStatus("El LIVE de @$username terminó")
                }
                .onComment { _, event ->
                    val user = runCatching { event.user.name }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "Usuario"
                    val message = runCatching { event.text }.getOrNull().orEmpty()
                    if (message.isNotBlank()) {
                        onComment(
                            LiveComment(
                                userId = user,
                                displayName = user,
                                text = message,
                                isModerator = false
                            )
                        )
                    }
                }

            liveClient = builder.buildAndConnect()
        } catch (error: Exception) {
            liveClient = null
            onStatus("No se pudo conectar a @$username: ${error.message ?: "error desconocido"}")
        }
    }

    @Synchronized
    fun disconnect() {
        val current = liveClient
        liveClient = null
        runCatching { current?.disconnect() }
    }
}
