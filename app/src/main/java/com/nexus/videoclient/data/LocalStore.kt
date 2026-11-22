package com.nexus.videoclient.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object LocalStore {

    private const val PREFS = "blacktube_local_store"
    private const val KEY_SEARCH_HISTORY = "search_history"
    private const val KEY_WATCH_LATER = "local_watch_later"
    private const val KEY_FAVORITES = "local_favorites"
    private const val KEY_HISTORY = "local_history"
    private const val KEY_HIDDEN = "hidden_video_ids"
    private const val KEY_WATCH_TIME_SEC = "stats_watch_time_sec"
    private const val KEY_VIDEOS_WATCHED = "stats_videos_watched"
    private const val KEY_LIKED_IDS = "stats_liked_video_ids"
    private const val KEY_DISLIKED_IDS = "stats_disliked_video_ids"
    private const val KEY_CACHED_NOTIFS = "cached_notifications_v1"
    private const val KEY_SYNCED_LIKED_COUNT = "stats_synced_liked_count"
    private const val KEY_SYNCED_WATCH_SEC = "stats_synced_watch_sec"
    private const val KEY_SYNCED_HISTORY_COUNT = "stats_synced_history_count"
    private const val KEY_PLAYBACK_POS = "playback_positions_v1"
    private const val KEY_SPONSORBLOCK = "pref_sponsorblock_enabled"
    private const val KEY_LAST_STATS_SYNC = "stats_last_sync_ms"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun getSearchHistory(): List<String> {
        val raw = prefs.getString(KEY_SEARCH_HISTORY, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    fun addSearchQuery(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val current = getSearchHistory().toMutableList()
        current.removeAll { it.equals(clean, ignoreCase = true) }
        current.add(0, clean)
        saveStringList(KEY_SEARCH_HISTORY, current.take(20))
    }

    fun removeSearchQuery(query: String) {
        val current = getSearchHistory().toMutableList()
        current.removeAll { it.equals(query, ignoreCase = true) }
        saveStringList(KEY_SEARCH_HISTORY, current)
    }

    fun clearSearchHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    fun addWatchLater(item: VideoItem) = upsertVideo(KEY_WATCH_LATER, item)
    fun getWatchLater(): List<VideoItem> = getVideoList(KEY_WATCH_LATER)

    fun toggleFavorite(item: VideoItem): Boolean {
        val list = getVideoList(KEY_FAVORITES).toMutableList()
        val exists = list.any { it.id == item.id }
        if (exists) {
            list.removeAll { it.id == item.id }
        } else {
            list.add(0, item)
        }
        saveVideoList(KEY_FAVORITES, list.take(100))
        return !exists
    }

    fun isFavorite(videoId: String): Boolean =
        getVideoList(KEY_FAVORITES).any { it.id == videoId }

    fun getFavorites(): List<VideoItem> = getVideoList(KEY_FAVORITES)

    fun addHistory(item: VideoItem) = upsertVideo(KEY_HISTORY, item)
    fun getHistory(): List<VideoItem> = getVideoList(KEY_HISTORY).map { item ->
        val savedPct = getPlaybackPercent(item.id)
        if (savedPct > 0) item.copy(percentWatched = savedPct) else item
    }

    fun isSponsorBlockEnabled(): Boolean = prefs.getBoolean(KEY_SPONSORBLOCK, true)

    fun setSponsorBlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPONSORBLOCK, enabled).apply()
    }

    fun getLastStatsSyncMs(): Long = prefs.getLong(KEY_LAST_STATS_SYNC, 0L)

    fun markStatsSyncedNow() {
        prefs.edit().putLong(KEY_LAST_STATS_SYNC, System.currentTimeMillis()).apply()
    }

    fun savePlaybackPosition(videoId: String, positionMs: Long, durationMs: Long) {
        if (videoId.isBlank() || durationMs <= 0L) return
        val map = loadPositionsJson()
        val pct = ((positionMs * 100L) / durationMs).toInt().coerceIn(0, 100)
        if (positionMs < 5_000L || pct >= 95) {
            map.remove(videoId)
        } else {
            map.put(
                videoId,
                JSONObject()
                    .put("pos", positionMs)
                    .put("dur", durationMs)
                    .put("pct", pct)
                    .put("at", System.currentTimeMillis())
            )
            if (map.length() > 250) {
                val keys = map.keys().asSequence().toList()
                val oldest = keys.minByOrNull {
                    map.optJSONObject(it)?.optLong("at", 0L) ?: 0L
                }
                if (oldest != null) map.remove(oldest)
            }
        }
        prefs.edit().putString(KEY_PLAYBACK_POS, map.toString()).apply()
    }

    fun getPlaybackPosition(videoId: String): Long {
        if (videoId.isBlank()) return 0L
        return loadPositionsJson().optJSONObject(videoId)?.optLong("pos", 0L) ?: 0L
    }

    fun getPlaybackPercent(videoId: String): Int {
        if (videoId.isBlank()) return 0
        return loadPositionsJson().optJSONObject(videoId)?.optInt("pct", 0) ?: 0
    }

    fun setVideoLikeState(videoId: String, liked: Boolean, disliked: Boolean) {
        val likes = prefs.getStringSet(KEY_LIKED_IDS, emptySet()).orEmpty().toMutableSet()
        val dislikes = prefs.getStringSet(KEY_DISLIKED_IDS, emptySet()).orEmpty().toMutableSet()
        if (liked) likes.add(videoId) else likes.remove(videoId)
        if (disliked) dislikes.add(videoId) else dislikes.remove(videoId)
        prefs.edit()
            .putStringSet(KEY_LIKED_IDS, likes)
            .putStringSet(KEY_DISLIKED_IDS, dislikes)
            .apply()
    }

    private fun loadPositionsJson(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY_PLAYBACK_POS, "{}").orEmpty())
    }.getOrDefault(JSONObject())

    fun hideVideo(videoId: String) {
        val set = getHiddenIds().toMutableSet()
        set.add(videoId)
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun getHiddenIds(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    fun addWatchSeconds(seconds: Long) {
        if (seconds <= 0L) return
        val current = prefs.getLong(KEY_WATCH_TIME_SEC, 0L)
        prefs.edit().putLong(KEY_WATCH_TIME_SEC, current + seconds).apply()
    }

    fun recordVideoWatched(item: VideoItem) {
        addHistory(item)
        val count = prefs.getInt(KEY_VIDEOS_WATCHED, 0)
        prefs.edit().putInt(KEY_VIDEOS_WATCHED, count + 1).apply()
    }

    fun isLiked(videoId: String): Boolean =
        prefs.getStringSet(KEY_LIKED_IDS, emptySet()).orEmpty().contains(videoId)

    fun isDisliked(videoId: String): Boolean =
        prefs.getStringSet(KEY_DISLIKED_IDS, emptySet()).orEmpty().contains(videoId)

    fun toggleLike(videoId: String): Boolean {
        val likes = prefs.getStringSet(KEY_LIKED_IDS, emptySet()).orEmpty().toMutableSet()
        val dislikes = prefs.getStringSet(KEY_DISLIKED_IDS, emptySet()).orEmpty().toMutableSet()
        val nowLiked = if (likes.contains(videoId)) {
            likes.remove(videoId)
            false
        } else {
            likes.add(videoId)
            dislikes.remove(videoId)
            true
        }
        prefs.edit()
            .putStringSet(KEY_LIKED_IDS, likes)
            .putStringSet(KEY_DISLIKED_IDS, dislikes)
            .apply()
        return nowLiked
    }

    fun toggleDislike(videoId: String): Boolean {
        val likes = prefs.getStringSet(KEY_LIKED_IDS, emptySet()).orEmpty().toMutableSet()
        val dislikes = prefs.getStringSet(KEY_DISLIKED_IDS, emptySet()).orEmpty().toMutableSet()
        val nowDisliked = if (dislikes.contains(videoId)) {
            dislikes.remove(videoId)
            false
        } else {
            dislikes.add(videoId)
            likes.remove(videoId)
            true
        }
        prefs.edit()
            .putStringSet(KEY_LIKED_IDS, likes)
            .putStringSet(KEY_DISLIKED_IDS, dislikes)
            .apply()
        return nowDisliked
    }

    fun getWatchStats(): WatchStats = WatchStats(
        totalWatchSeconds = prefs.getLong(KEY_WATCH_TIME_SEC, 0L),
        videosWatched = prefs.getInt(KEY_VIDEOS_WATCHED, 0),
        likesGiven = prefs.getStringSet(KEY_LIKED_IDS, emptySet()).orEmpty().size
    )

    fun formatWatchTime(totalSec: Long): String {
        if (totalSec < 60) return "${totalSec}s"
        val hours = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    fun saveSyncedAccountStats(stats: AccountSyncStats) {
        prefs.edit()
            .putInt(KEY_SYNCED_LIKED_COUNT, stats.totalLikedCount)
            .putLong(KEY_SYNCED_WATCH_SEC, stats.syncedHistoryWatchSeconds)
            .putInt(KEY_SYNCED_HISTORY_COUNT, stats.syncedHistoryVideoCount)
            .apply()
    }

    fun getSyncedAccountStats(): AccountSyncStats = AccountSyncStats(
        totalLikedCount = prefs.getInt(KEY_SYNCED_LIKED_COUNT, 0),
        syncedHistoryWatchSeconds = prefs.getLong(KEY_SYNCED_WATCH_SEC, 0L),
        syncedHistoryVideoCount = prefs.getInt(KEY_SYNCED_HISTORY_COUNT, 0)
    )

    fun mergeAndGetNotifications(fresh: List<NotificationItem>): List<NotificationItem> {
        val existing = getCachedNotifications()
        val map = LinkedHashMap<String, NotificationItem>()
        fresh.forEach { map[it.id.ifBlank { it.title + it.timestamp }] = it }
        existing.forEach {
            map.putIfAbsent(
                it.id.ifBlank { it.title + it.timestamp },
                it.copy(unread = false)
            )
        }
        val merged = map.values.take(80)
        val arr = JSONArray()
        merged.forEach { n ->
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("timestamp", n.timestamp)
                    .put("avatar", n.avatar)
                    .put("thumbnail", n.thumbnail)
                    .put("videoId", n.videoId)
                    .put("unread", n.unread)
            )
        }
        prefs.edit().putString(KEY_CACHED_NOTIFS, arr.toString()).apply()
        return merged
    }

    fun getCachedNotifications(): List<NotificationItem> {
        val raw = prefs.getString(KEY_CACHED_NOTIFS, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                NotificationItem(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    timestamp = o.optString("timestamp"),
                    avatar = o.optString("avatar"),
                    thumbnail = o.optString("thumbnail"),
                    videoId = o.optString("videoId"),
                    unread = o.optBoolean("unread", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun upsertVideo(key: String, item: VideoItem) {
        if (item.id.isBlank()) return
        val list = getVideoList(key).toMutableList()
        list.removeAll { it.id == item.id }
        list.add(0, item)
        saveVideoList(key, list.take(100))
    }

    private fun getVideoList(key: String): List<VideoItem> {
        val raw = prefs.getString(key, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<VideoItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    VideoItem(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        channel = o.optString("channel"),
                        channelId = o.optString("channelId"),
                        thumbnail = o.optString("thumbnail"),
                        duration = o.optString("duration"),
                        views = o.optString("views"),
                        published = o.optString("published"),
                        isPlaylist = o.optBoolean("isPlaylist", false),
                        isLive = o.optBoolean("isLive", false),
                        isChannel = o.optBoolean("isChannel", false),
                        avatar = o.optString("avatar"),
                        percentWatched = o.optInt("percentWatched", 0)
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveVideoList(key: String, list: List<VideoItem>) {
        val arr = JSONArray()
        list.forEach { v ->
            arr.put(
                JSONObject()
                    .put("id", v.id)
                    .put("title", v.title)
                    .put("channel", v.channel)
                    .put("channelId", v.channelId)
                    .put("thumbnail", v.thumbnail)
                    .put("duration", v.duration)
                    .put("views", v.views)
                    .put("published", v.published)
                    .put("isPlaylist", v.isPlaylist)
                    .put("isLive", v.isLive)
                    .put("isChannel", v.isChannel)
                    .put("avatar", v.avatar)
                    .put("percentWatched", v.percentWatched)
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun saveStringList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
