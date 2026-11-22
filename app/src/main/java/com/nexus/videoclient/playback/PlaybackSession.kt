package com.nexus.videoclient.playback

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nexus.videoclient.data.VideoItem
import com.nexus.videoclient.ui.MainActivity

object PlaybackSession {

    private var appContext: Context? = null
    private var mediaSession: MediaSessionCompat? = null
    private var sessionListener: Player.Listener? = null

    var player: ExoPlayer? = null
        private set

    @Volatile
    var isInPip: Boolean = false

    @Volatile
    var isPlayerForeground: Boolean = false

    var videoId: String = ""
        private set

    var title: String = ""
        private set

    var channel: String = ""
        private set

    var isActive: Boolean = false
        private set

    private val queue = ArrayList<VideoItem>()

    private val previousStack = ArrayList<VideoItem>()

    var currentItem: VideoItem? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureMediaSession()
    }

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val context = appContext ?: return
        val session = MediaSessionCompat(context, "BlackTube")
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setSessionActivity(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                player?.play()
            }

            override fun onPause() {
                player?.pause()
            }

            override fun onStop() {
                player?.pause()
            }

            override fun onSeekTo(pos: Long) {
                player?.seekTo(pos.coerceAtLeast(0L))
            }
        })
        session.isActive = true
        mediaSession = session
        publishState()
    }

    fun mediaSessionToken(): android.support.v4.media.session.MediaSessionCompat.Token? =
        mediaSession?.sessionToken

    fun attach(player: ExoPlayer) {
        this.player = player
        ensureMediaSession()
        sessionListener?.let { runCatching { this.player?.removeListener(it) } }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishState()
                PlaybackService.refreshNotification(appContext)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                publishState()
            }
        }
        sessionListener = listener
        player.addListener(listener)
        publishState()
    }

    private fun publishState() {
        val session = mediaSession ?: return
        val p = player
        val state = when {
            p == null -> PlaybackStateCompat.STATE_NONE
            p.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            p.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            p.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val position = p?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val speed = p?.playbackParameters?.speed ?: 1f
        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, speed)
        runCatching { session.setPlaybackState(builder.build()) }
    }

    private fun publishMetadata() {
        val session = mediaSession ?: return
        val duration = player?.duration?.takeIf { it > 0L } ?: 0L
        val art = if (videoId.isNotBlank()) {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        } else {
            ""
        }
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifBlank { "BlackTube" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, channel.ifBlank { "BlackTube" })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "BlackTube")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        if (art.isNotBlank()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, art)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, art)
        }
        runCatching { session.setMetadata(builder.build()) }
        publishState()
    }

    fun activate(videoId: String, title: String, channel: String = "") {
        if (this.videoId.isNotBlank() && this.videoId != videoId) {
            previousStack.add(
                VideoItem(id = this.videoId, title = this.title, channel = this.channel)
            )
            if (previousStack.size > 30) previousStack.removeAt(0)
        }
        this.videoId = videoId
        this.title = title
        this.channel = channel
        this.currentItem = VideoItem(id = videoId, title = title, channel = channel)
        isActive = true
        ensureMediaSession()
        publishMetadata()
    }

    fun updateMetadata(title: String, channel: String = "") {
        if (title.isNotBlank()) this.title = title
        if (channel.isNotBlank()) this.channel = channel
        if (videoId.isNotBlank()) {
            this.currentItem = VideoItem(id = videoId, title = this.title, channel = this.channel)
        }
        publishMetadata()
    }

    fun addToQueue(item: VideoItem) {
        if (item.id.isBlank() || item.isPlaylist || item.isChannel) return
        queue.removeAll { it.id == item.id }
        queue.add(item)
    }

    fun setQueue(items: List<VideoItem>) {
        queue.clear()
        items.filter { it.id.isNotBlank() && !it.isPlaylist && !it.isChannel }
            .forEach { queue.add(it) }
    }

    fun getQueue(): List<VideoItem> = queue.toList()

    fun popNextInQueue(): VideoItem? =
        if (queue.isNotEmpty()) queue.removeAt(0) else null

    fun hasNext(): Boolean = queue.isNotEmpty()

    fun hasPrevious(): Boolean = previousStack.isNotEmpty()

    fun popPrevious(): VideoItem? =
        if (previousStack.isNotEmpty()) previousStack.removeAt(previousStack.lastIndex) else null

    fun removeFromQueue(videoId: String) {
        queue.removeAll { it.id == videoId }
    }

    fun clearQueue() {
        queue.clear()
    }

    fun stop() {
        isActive = false
        isInPip = false
        isPlayerForeground = false
        sessionListener?.let { runCatching { player?.removeListener(it) } }
        sessionListener = null
        runCatching {
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1f)
                    .build()
            )
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
        player?.let { runCatching { it.release() } }
        player = null
        videoId = ""
        title = ""
        channel = ""
        currentItem = null
        previousStack.clear()
    }
}
