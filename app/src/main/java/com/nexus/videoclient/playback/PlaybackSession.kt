package com.nexus.videoclient.playback

import androidx.media3.exoplayer.ExoPlayer
import com.nexus.videoclient.data.VideoItem

object PlaybackSession {

    var player: ExoPlayer? = null
        private set

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

    fun attach(player: ExoPlayer) {
        this.player = player
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
        player?.let { runCatching { it.release() } }
        player = null
        videoId = ""
        title = ""
        channel = ""
        currentItem = null
        previousStack.clear()
    }
}
