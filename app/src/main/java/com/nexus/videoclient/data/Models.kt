package com.nexus.videoclient.data

data class VideoItem(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelId: String = "",
    val thumbnail: String = "",
    val duration: String = "",
    val views: String = "",
    val published: String = "",
    val isPlaylist: Boolean = false,
    val isLive: Boolean = false,
    val isChannel: Boolean = false,
    val avatar: String = "",
    val percentWatched: Int = 0
) {
    val watchUrl: String
        get() = when {
            isChannel -> "https://www.youtube.com/channel/$id"
            isPlaylist -> "https://www.youtube.com/playlist?list=$id"
            else -> "https://www.youtube.com/watch?v=$id"
        }
}

data class SubscribedChannel(
    val id: String,
    val name: String,
    val avatar: String,
    val subscribers: String
)

data class AccountSyncStats(
    val totalLikedCount: Int,
    val syncedHistoryWatchSeconds: Long,
    val syncedHistoryVideoCount: Int
)

data class ChannelAboutInfo(
    val description: String = "",
    val joinedDate: String = "",
    val totalViews: String = "",
    val subscriberCount: String = "",
    val videoCount: String = "",
    val country: String = "",
    val canonicalUrl: String = "",
    val links: List<Pair<String, String>> = emptyList()
)

data class Page(val items: List<VideoItem>, val continuation: String?)

data class AccountInfo(val name: String, val avatar: String, val handle: String = "")

data class WatchInfo(
    val title: String = "",
    val channel: String = "",
    val views: String = "",
    val published: String = "",
    val likes: String = "",
    val description: String = "",
    val channelId: String = "",
    val subscribers: String = "",
    val channelAvatar: String = "",
    val isSubscribed: Boolean = false
)

data class WatchPage(
    val info: WatchInfo,
    val commentToken: String?,
    val commentCount: String = "",
    val createCommentParams: String? = null,
    val related: List<VideoItem> = emptyList()
)

data class Comment(
    val author: String,
    val avatar: String,
    val text: String,
    val published: String,
    val likes: String
)

data class CommentPage(
    val items: List<Comment>,
    val continuation: String?,
    val createCommentParams: String? = null
)

data class ChannelHeader(
    val id: String = "",
    val name: String = "",
    val handle: String = "",
    val subscribers: String = "",
    val videoCount: String = "",
    val description: String = "",
    val avatar: String = "",
    val banner: String = "",
    val isSubscribed: Boolean = false
)

data class ChannelPage(
    val header: ChannelHeader,
    val videos: List<VideoItem>,
    val continuation: String?
)

data class NotificationItem(
    val id: String,
    val title: String,
    val timestamp: String,
    val avatar: String,
    val thumbnail: String,
    val videoId: String,
    val unread: Boolean = false
)

data class WatchStats(
    val totalWatchSeconds: Long,
    val videosWatched: Int,
    val likesGiven: Int
)
