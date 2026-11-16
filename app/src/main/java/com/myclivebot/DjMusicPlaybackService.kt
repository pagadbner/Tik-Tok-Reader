package com.myclivebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/**
 * Publishes media controls only while DJ Music has an active track.
 * Background playback opt-in was removed; this service does not keep playback alive.
 */
class DjMusicPlaybackService : Service() {
    companion object {
        const val ACTION_UPDATE = "com.myclivebot.dj.UPDATE"
        const val ACTION_PLAY_PAUSE = "com.myclivebot.dj.PLAY_PAUSE"
        const val ACTION_NEXT = "com.myclivebot.dj.NEXT"
        const val ACTION_CONTROL = "com.myclivebot.dj.CONTROL"
        const val EXTRA_TITLE = "dj_title"
        const val EXTRA_PLAYING = "dj_playing"
        const val EXTRA_CONTROL = "dj_control"
        private const val CHANNEL_ID = "dj_music_playback"
        private const val NOTIFICATION_ID = 4202
        private const val REQUEST_PLAY_PAUSE = 4203
        private const val REQUEST_NEXT = 4204
    }

    private var mediaSession: MediaSession? = null
    private var trackTitle = ""
    private var playing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSession(this, "TikLBot DJ Music").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = dispatchControl("play_pause")
                override fun onPause() = dispatchControl("play_pause")
                override fun onSkipToNext() = dispatchControl("next")
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                trackTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                if (trackTitle.isNotBlank()) {
                    showForegroundNotification()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_PLAY_PAUSE -> dispatchControl("play_pause")
            ACTION_NEXT -> dispatchControl("next")
        }
        return START_NOT_STICKY
    }

    private fun dispatchControl(control: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL, control)
        )
    }

    private fun showForegroundNotification() {
        val session = mediaSession ?: return
        session.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, trackTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "TikLBot · YouTube")
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )

        val openApp = PendingIntent.getActivity(
            this, 4205,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this, REQUEST_PLAY_PAUSE,
            Intent(this, DjMusicPlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, REQUEST_NEXT,
            Intent(this, DjMusicPlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (playing) "Pausar" else "Reproducir"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else Notification.Builder(this)

        val notification = builder
            .setContentTitle(trackTitle)
            .setContentText("TikLBot · YouTube")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(playPauseIcon, playPauseLabel, playPauseIntent).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Siguiente", nextIntent).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DJ Music", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles de reproducción de DJ Music."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
