package com.myclivebot.core

/**
 * Compatibility bridge for the shared Edge TTS engine's release path.
 * Actual Windows playback is implemented by DesktopAudioPlayer.
 */
internal object ApiAudioPlayer {
    fun stop() = DesktopAudioPlayer.stop()
}
