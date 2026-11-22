package com.nexus.videoclient.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.nexus.videoclient.R
import com.nexus.videoclient.ui.MainActivity

class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val p = PlaybackSession.player
                if (p != null) {
                    if (p.isPlaying) p.pause() else p.play()
                }
            }
            ACTION_REWIND -> {
                val p = PlaybackSession.player
                p?.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
            }
            ACTION_FORWARD -> {
                val p = PlaybackSession.player
                if (p != null) {
                    val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    p.seekTo((p.currentPosition + 10_000L).coerceAtMost(dur))
                }
            }
        }
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the currently playing BlackTube video"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "nexus_playback"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_TOGGLE = "com.nexus.blacktube.PLAYBACK_TOGGLE"
        private const val ACTION_REWIND = "com.nexus.blacktube.PLAYBACK_REWIND"
        private const val ACTION_FORWARD = "com.nexus.blacktube.PLAYBACK_FORWARD"

        @Volatile
        private var isRunning = false

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PlaybackService::class.java))
            }
        }

        fun refreshNotification(context: Context?) {
            if (!isRunning || context == null) return
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, buildNotification(context))
            }
        }

        private fun servicePendingIntent(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                context,
                code,
                Intent(context, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun buildNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val playing = PlaybackSession.player?.isPlaying == true
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle(PlaybackSession.title.ifBlank { "BlackTube" })
                .setContentText(PlaybackSession.channel.ifBlank { "Playing in background" })
                .setContentIntent(open)
                .setOngoing(playing)
                .setSilent(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    android.R.drawable.ic_media_previous,
                    "Back 10s",
                    servicePendingIntent(context, ACTION_REWIND, 201)
                )
                .addAction(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                    servicePendingIntent(context, ACTION_TOGGLE, 202)
                )
                .addAction(
                    android.R.drawable.ic_media_next,
                    "Forward 10s",
                    servicePendingIntent(context, ACTION_FORWARD, 203)
                )

            val token = PlaybackSession.mediaSessionToken()
            if (token != null) {
                builder.setStyle(
                    MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
            }
            return builder.build()
        }
    }
}
