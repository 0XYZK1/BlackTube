package com.nexus.videoclient.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object InnerTube {

    private const val CLIENT_VERSION = "2.20261118.00.00"
    private const val BASE = "https://www.youtube.com/youtubei/v1"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var visitorData: String = ""

    private fun ensureVisitorData(): String {
        if (visitorData.isNotBlank()) return visitorData
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", UA)
                .header("Cookie", "SOCS=CAI")
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val match = Regex("\"(Cg[a-zA-Z0-9_%-]{16,})\"").find(raw)
                val found = match?.groupValues?.getOrNull(1).orEmpty()
                if (found.isNotBlank()) visitorData = found
            }
        }
        return visitorData
    }

    private fun contextObject(): JSONObject {
        val clientObject = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "US")
            .put("originalUrl", "https://www.youtube.com/")
            .put("platform", "DESKTOP")
            .put("userAgent", UA)
        if (visitorData.isNotBlank()) {
            clientObject.put("visitorData", visitorData)
        }
        return JSONObject().put("client", clientObject)
    }

    private data class Cached(val page: Page, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private const val CACHE_TTL_MS = 3 * 60 * 1000L

    fun clearCache() {
        cache.clear()
    }

    fun suggestions(query: String): List<String> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=en&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val list = arr.optJSONArray(1) ?: return emptyList()
                val out = ArrayList<String>(list.length())
                for (i in 0 until list.length()) {
                    val item = list.optString(i)
                    if (item.isNotBlank()) out.add(item)
                }
                out.take(8)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun browse(browseId: String, continuation: String? = null): Page {
        val key = "browse:$browseId:${continuation.orEmpty()}"
        cached(key)?.let { return it }
        val page = parsePage(post("browse", body(browseId = browseId, continuation = continuation)))
        if (continuation == null) cache[key] = Cached(page, System.currentTimeMillis())
        return page
    }

    fun userPlaylists(): List<VideoItem> {
        val seen = LinkedHashMap<String, VideoItem>()
        listOf("FEplaylists", "FElibrary").forEach { id ->
            runCatching { browse(id) }.getOrNull()?.items
                ?.filter { it.isPlaylist }
                ?.forEach { seen.putIfAbsent(it.id, it) }
            if (seen.isNotEmpty()) return seen.values.toList()
        }
        return seen.values.toList()
    }

    fun search(query: String, continuation: String? = null, params: String? = null): Page {
        val key = "search:$query:${params.orEmpty()}:${continuation.orEmpty()}"
        cached(key)?.let { return it }
        val rootObj = JSONObject().put("context", contextObject())
        if (continuation != null) {
            rootObj.put("continuation", continuation)
        } else {
            rootObj.put("query", query)
            if (!params.isNullOrBlank()) rootObj.put("params", params)
        }
        val page = parsePage(post("search", rootObj.toString()))
        if (continuation == null) cache[key] = Cached(page, System.currentTimeMillis())
        return page
    }

    private fun cached(key: String): Page? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.page
    }

    fun watch(videoId: String): WatchPage {
        val root = post("next", body(videoId = videoId))
        var title = ""
        var channel = ""
        var views = ""
        var published = ""
        var description = ""
        var likes = ""
        var commentToken: String? = null
        var commentCount = ""
        var commentParams: String? = null
        var channelId = ""
        var subscribers = ""
        var channelAvatar = ""
        var isSubscribed = false
        var isLiked = false
        var isDisliked = false

        walk(root) { obj ->
            if (commentParams.isNullOrBlank() && obj.has("createCommentParams")) {
                val value = obj.optString("createCommentParams")
                if (value.isNotBlank()) commentParams = value
            }
            obj.optJSONObject("videoPrimaryInfoRenderer")?.let { primary ->
                val t = runs(primary.optJSONObject("title") ?: JSONObject())
                if (t.isNotBlank()) title = t
                val counter = primary.optJSONObject("viewCount")
                    ?.optJSONObject("videoViewCountRenderer")
                if (counter != null) {
                    val value = text(counter.opt("viewCount"))
                    if (value.isNotBlank()) views = value
                }
                val date = text(primary.opt("dateText"))
                if (date.isNotBlank()) published = date
            }

            obj.optJSONObject("videoSecondaryInfoRenderer")?.let { secondary ->
                val modern = secondary.optJSONObject("attributedDescription")
                    ?.optString("content").orEmpty()
                if (modern.isNotBlank()) {
                    description = modern
                } else {
                    val legacy = runs(secondary.optJSONObject("description") ?: JSONObject())
                    if (legacy.isNotBlank()) description = legacy
                }
                val ownerRenderer = secondary.optJSONObject("owner")
                    ?.optJSONObject("videoOwnerRenderer")
                if (ownerRenderer != null) {
                    val ownerName = runs(ownerRenderer.optJSONObject("title") ?: JSONObject())
                    if (ownerName.isNotBlank()) channel = ownerName
                    val subs = text(ownerRenderer.opt("subscriberCountText"))
                    if (subs.isNotBlank()) subscribers = subs
                    val id = ownerRenderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId").orEmpty()
                    if (id.isNotBlank()) channelId = id
                    val avatarUrl = bestThumbArray(
                        ownerRenderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ).orEmpty()
                    if (avatarUrl.isNotBlank()) channelAvatar = fastAvatarUrl(avatarUrl)
                }
            }

            obj.optJSONObject("subscribeButtonRenderer")?.let { sub ->
                if (sub.has("subscribed")) isSubscribed = sub.optBoolean("subscribed", false)
            }

            obj.optJSONObject("likeStatusEntity")?.let { entity ->
                when (entity.optString("likeStatus")) {
                    "LIKE" -> { isLiked = true; isDisliked = false }
                    "DISLIKE" -> { isLiked = false; isDisliked = true }
                    "INDIFFERENT" -> { isLiked = false; isDisliked = false }
                }
            }
            obj.optJSONObject("likeCountEntity")?.let { entity ->
                val value = text(entity.opt("likeCountIfNotLiked"))
                    .ifBlank { text(entity.opt("likeCountIfLiked")) }
                if (value.isNotBlank()) likes = value
            }
            obj.optJSONObject("buttonViewModel")?.let { button ->
                if (likes.isBlank() && button.optString("iconName").equals("LIKE", true)) {
                    val value = text(button.opt("title"))
                    if (value.isNotBlank()) likes = value
                }
            }

            if (obj.optString("sectionIdentifier") == "comment-item-section") {
                if (commentToken.isNullOrBlank()) {
                    walk(obj) { inner ->
                        if (commentToken.isNullOrBlank()) {
                            val token = inner.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token")
                            if (!token.isNullOrBlank()) commentToken = token
                        }
                    }
                }
                val header = text(
                    obj.optJSONObject("header")
                        ?.optJSONObject("commentsHeaderRenderer")
                        ?.opt("countText")
                )
                if (header.isNotBlank()) commentCount = header
            }
        }

        return WatchPage(
            info = WatchInfo(
                title = title,
                channel = channel,
                views = views,
                published = published,
                likes = likes,
                description = description,
                channelId = channelId,
                subscribers = subscribers,
                channelAvatar = channelAvatar,
                isSubscribed = isSubscribed,
                isLiked = isLiked,
                isDisliked = isDisliked
            ),
            commentToken = commentToken,
            commentCount = commentCount,
            createCommentParams = commentParams,
            related = parsePage(root).items
                .filter { it.id != videoId && !it.isPlaylist }
                .take(15)
        )
    }

    fun comments(continuation: String): CommentPage {
        val root = post("next", body(continuation = continuation))
        val items = LinkedHashMap<String, Comment>()
        var next: String? = null
        var createParams: String? = null

        val surfaceByKey = HashMap<String, JSONObject>()
        val stateByKey = HashMap<String, JSONObject>()
        val viewModelByCommentKey = HashMap<String, JSONObject>()

        walk(root) { obj ->
            if (createParams.isNullOrBlank() && obj.has("createCommentParams")) {
                val value = obj.optString("createCommentParams")
                if (value.isNotBlank()) createParams = value
            }
            obj.optJSONObject("engagementToolbarSurfaceEntityPayload")?.let { p ->
                val k = p.optString("key")
                if (k.isNotBlank()) surfaceByKey[k] = p
            }
            obj.optJSONObject("engagementToolbarStateEntityPayload")?.let { p ->
                val k = p.optString("key")
                if (k.isNotBlank()) stateByKey[k] = p
            }
            obj.optJSONObject("commentViewModel")?.let { vm ->
                val ck = vm.optString("commentKey")
                if (ck.isNotBlank()) viewModelByCommentKey[ck] = vm
            }
        }

        fun extractAction(parent: JSONObject?, field: String): String =
            parent?.optJSONObject(field)
                ?.optJSONObject("innertubeCommand")
                ?.optJSONObject("performCommentActionEndpoint")
                ?.optString("action").orEmpty()

        walk(root) { obj ->
            obj.optJSONObject("commentEntityPayload")?.let { payload ->
                val props = payload.optJSONObject("properties") ?: JSONObject()
                val author = payload.optJSONObject("author") ?: JSONObject()
                val toolbar = payload.optJSONObject("toolbar") ?: JSONObject()
                val content = props.optJSONObject("content")?.optString("content").orEmpty()
                if (content.isNotBlank()) {
                    val key = payload.optString("key")
                    val vm = viewModelByCommentKey[key]
                    val surface = surfaceByKey[vm?.optString("toolbarSurfaceKey").orEmpty()]
                        ?: surfaceByKey[props.optString("toolbarSurfaceKey")]
                    val state = stateByKey[vm?.optString("toolbarStateKey").orEmpty()]
                        ?: stateByKey[props.optString("toolbarStateKey")]
                    val likeState = state?.optString("likeState").orEmpty()
                    val cid = props.optString("commentId").ifBlank { key }

                    val comment = Comment(
                        id = cid,
                        author = author.optString("displayName"),
                        avatar = fastAvatarUrl(author.optString("avatarThumbnailUrl")),
                        text = content,
                        published = props.optString("publishedTime"),
                        likes = toolbar.optString("likeCountNotliked"),
                        likesWhenLiked = toolbar.optString("likeCountLiked"),
                        isLiked = likeState == "TOOLBAR_LIKE_STATE_LIKED",
                        isDisliked = likeState == "TOOLBAR_LIKE_STATE_DISLIKED",
                        likeAction = extractAction(surface, "likeCommand"),
                        unlikeAction = extractAction(surface, "unlikeCommand"),
                        dislikeAction = extractAction(surface, "dislikeCommand"),
                        undislikeAction = extractAction(surface, "undislikeCommand")
                    )
                    items.putIfAbsent(
                        cid.ifBlank { comment.author + "|" + comment.text },
                        comment
                    )
                }
            }
            obj.optJSONObject("commentRenderer")?.let { legacy ->
                val content = runs(legacy.optJSONObject("contentText") ?: JSONObject())
                if (content.isNotBlank()) {
                    val buttons = legacy.optJSONObject("actionButtons")
                        ?.optJSONObject("commentActionButtonsRenderer")
                    val likeToggle = buttons?.optJSONObject("likeButton")
                        ?.optJSONObject("toggleButtonRenderer")
                    val dislikeToggle = buttons?.optJSONObject("dislikeButton")
                        ?.optJSONObject("toggleButtonRenderer")

                    val comment = Comment(
                        id = legacy.optString("commentId"),
                        author = runs(legacy.optJSONObject("authorText") ?: JSONObject()),
                        avatar = fastAvatarUrl(
                            bestThumbArray(
                                legacy.optJSONObject("authorThumbnail")?.optJSONArray("thumbnails")
                            ).orEmpty()
                        ),
                        text = content,
                        published = text(legacy.opt("publishedTimeText")),
                        likes = text(legacy.opt("voteCount")),
                        isLiked = likeToggle?.optBoolean("isToggled", false) == true,
                        isDisliked = dislikeToggle?.optBoolean("isToggled", false) == true,
                        likeAction = likeToggle?.optJSONObject("defaultServiceEndpoint")
                            ?.optJSONObject("performCommentActionEndpoint")
                            ?.optString("action").orEmpty(),
                        unlikeAction = likeToggle?.optJSONObject("toggledServiceEndpoint")
                            ?.optJSONObject("performCommentActionEndpoint")
                            ?.optString("action").orEmpty(),
                        dislikeAction = dislikeToggle?.optJSONObject("defaultServiceEndpoint")
                            ?.optJSONObject("performCommentActionEndpoint")
                            ?.optString("action").orEmpty(),
                        undislikeAction = dislikeToggle?.optJSONObject("toggledServiceEndpoint")
                            ?.optJSONObject("performCommentActionEndpoint")
                            ?.optString("action").orEmpty()
                    )
                    items.putIfAbsent(
                        comment.id.ifBlank { comment.author + "|" + comment.text },
                        comment
                    )
                }
            }
            if (next.isNullOrBlank()) {
                val batch = obj.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems") ?: JSONArray()
                for (i in batch.length() - 1 downTo 0) {
                    val token = batch.optJSONObject(i)
                        ?.optJSONObject("continuationItemRenderer")
                        ?.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!token.isNullOrBlank()) {
                        next = token
                        break
                    }
                }
            }
            if (next.isNullOrBlank()) {
                val token = obj.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!token.isNullOrBlank()) next = token
            }
        }

        return CommentPage(items.values.toList(), next, createParams)
    }

    fun accountInfo(): AccountInfo? = try {
        val root = post("account/account_menu", body())
        var name = ""
        var avatar = ""
        var handle = ""
        walk(root) { obj ->
            obj.optJSONObject("activeAccountHeaderRenderer")?.let { header ->
                val n = text(header.opt("accountName"))
                if (n.isNotBlank()) name = n
                val h = text(header.opt("channelHandle"))
                if (h.isNotBlank()) handle = h
                val a = bestThumbArray(
                    header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
                ).orEmpty()
                if (a.isNotBlank()) avatar = fastAvatarUrl(a)
            }
        }
        if (name.isBlank() && avatar.isBlank()) null else AccountInfo(name, avatar, handle)
    } catch (_: Exception) {
        null
    }

    fun addToRemoteWatchLater(videoId: String): Boolean {
        if (!Session.isLoggedIn) return false
        return try {
            val action = JSONObject()
                .put("action", "ACTION_ADD_VIDEO")
                .put("addedVideoId", videoId)
            val payload = JSONObject()
                .put("context", contextObject())
                .put("playlistId", "WL")
                .put("actions", JSONArray().put(action))
            post("browse/edit_playlist", payload.toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fastAvatarUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        return normalized.replace(Regex("=s\\d+[^\"'\\s]*$"), "=s88-c-k-c0x00ffffff-no-rj")
    }

    fun browseChannel(
        channelId: String,
        tab: String = "overview",
        continuation: String? = null
    ): ChannelPage {
        val payload = JSONObject().put("context", contextObject())
        if (continuation != null) {
            payload.put("continuation", continuation)
        } else {
            payload.put("browseId", channelId)
            when (tab) {
                "videos" -> payload.put("params", "EgZ2aWRlb3PyBgQKAjoA")
                "playlists" -> payload.put("params", "EglwbGF5bGlzdHPyBgQKAkIA")
                "live" -> payload.put("params", "EgdzdHJlYW1z8gYECgJ6AA==")
            }
        }
        val root = post("browse", payload.toString())

        var name = ""
        var handle = ""
        var subscribers = ""
        var videoCount = ""
        var description = ""
        var avatar = ""
        var banner = ""
        var subscribed = false

        if (continuation == null) {
            walk(root) { obj ->
                obj.optJSONObject("pageHeaderViewModel")?.let { hdr ->
                    val t = text(
                        hdr.optJSONObject("title")
                            ?.optJSONObject("dynamicTextViewModel")?.opt("text")
                    )
                    if (t.isNotBlank()) name = t
                    val desc = text(
                        hdr.optJSONObject("description")
                            ?.optJSONObject("descriptionPreviewViewModel")?.opt("description")
                    )
                    if (desc.isNotBlank()) description = desc
                    val av = bestThumb(
                        hdr.optJSONObject("image")
                            ?.optJSONObject("decoratedAvatarViewModel")
                            ?.optJSONObject("avatar")
                            ?.optJSONObject("avatarViewModel")
                            ?.optJSONObject("image")
                    ).orEmpty()
                    if (av.isNotBlank()) avatar = fastAvatarUrl(av)
                    val bn = bestThumb(
                        hdr.optJSONObject("banner")
                            ?.optJSONObject("imageBannerViewModel")
                            ?.optJSONObject("image")
                    ).orEmpty()
                    if (bn.isNotBlank()) banner = bn

                    val rows = hdr.optJSONObject("metadata")
                        ?.optJSONObject("contentMetadataViewModel")
                        ?.optJSONArray("metadataRows")
                    if (rows != null) {
                        val parts = ArrayList<String>()
                        for (i in 0 until rows.length()) {
                            val mp = rows.optJSONObject(i)
                                ?.optJSONArray("metadataParts") ?: continue
                            for (j in 0 until mp.length()) {
                                val v = text(mp.optJSONObject(j)?.opt("text"))
                                if (v.isNotBlank()) parts.add(v)
                            }
                        }
                        parts.forEach { p ->
                            when {
                                p.startsWith("@") && handle.isBlank() -> handle = p
                                p.contains("subscriber", true) && subscribers.isBlank() ->
                                    subscribers = p
                                p.contains("video", true) && videoCount.isBlank() ->
                                    videoCount = p
                            }
                        }
                    }
                }

                obj.optJSONObject("channelMetadataRenderer")?.let { meta ->
                    if (name.isBlank()) name = meta.optString("title")
                    if (description.isBlank()) description = meta.optString("description")
                    if (avatar.isBlank()) {
                        avatar = fastAvatarUrl(
                            bestThumbArray(
                                meta.optJSONObject("avatar")?.optJSONArray("thumbnails")
                            ).orEmpty()
                        )
                    }
                }
                obj.optJSONObject("c4TabbedHeaderRenderer")?.let { c4 ->
                    if (name.isBlank()) name = c4.optString("title")
                    if (subscribers.isBlank()) subscribers = text(c4.opt("subscriberCountText"))
                    if (videoCount.isBlank()) videoCount = text(c4.opt("videosCountText"))
                    if (avatar.isBlank()) {
                        avatar = fastAvatarUrl(
                            bestThumbArray(
                                c4.optJSONObject("avatar")?.optJSONArray("thumbnails")
                            ).orEmpty()
                        )
                    }
                    if (banner.isBlank()) {
                        banner = bestThumbArray(
                            c4.optJSONObject("banner")?.optJSONArray("thumbnails")
                        ).orEmpty()
                    }
                }
                obj.optJSONObject("subscribeButtonRenderer")?.let { sub ->
                    if (sub.has("subscribed")) subscribed = sub.optBoolean("subscribed", false)
                }
            }
        }

        val page = parsePage(root)
        val enrichedVideos = page.items.map {
            if (it.avatar.isBlank() && avatar.isNotBlank()) {
                it.copy(
                    avatar = avatar,
                    channel = it.channel.ifBlank { name },
                    channelId = it.channelId.ifBlank { channelId }
                )
            } else {
                it
            }
        }

        return ChannelPage(
            header = ChannelHeader(
                id = channelId,
                name = name,
                handle = handle,
                subscribers = subscribers,
                videoCount = videoCount,
                description = description,
                avatar = avatar,
                banner = banner,
                isSubscribed = subscribed
            ),
            videos = enrichedVideos,
            continuation = page.continuation
        )
    }

    fun channelAbout(channelId: String): ChannelAboutInfo {
        val payload = JSONObject()
            .put("context", contextObject())
            .put("browseId", channelId)
            .put("params", "EgVhYm91dPIGBAoCEgA=")
        val root = post("browse", payload.toString())

        var description = ""
        var joined = ""
        var views = ""
        var subs = ""
        var videos = ""
        var country = ""
        var url = "https://www.youtube.com/channel/$channelId"
        val links = ArrayList<Pair<String, String>>()

        walk(root) { obj ->
            obj.optJSONObject("aboutChannelViewModel")?.let { about ->
                if (description.isBlank()) description = about.optString("description")
                if (joined.isBlank()) joined = text(about.opt("joinedDateText"))
                if (views.isBlank()) views = about.optString("viewCountText")
                if (subs.isBlank()) subs = about.optString("subscriberCountText")
                if (videos.isBlank()) videos = about.optString("videoCountText")
                if (country.isBlank()) country = about.optString("country")
                val canon = about.optString("canonicalChannelUrl")
                if (canon.isNotBlank()) url = canon
                val linkArr = about.optJSONArray("links")
                if (linkArr != null) {
                    for (i in 0 until linkArr.length()) {
                        val lv = linkArr.optJSONObject(i)
                            ?.optJSONObject("channelExternalLinkViewModel") ?: continue
                        val lt = text(lv.opt("title"))
                        val lu = text(lv.opt("link"))
                        if (lu.isNotBlank()) {
                            links.add(
                                (lt.ifBlank { lu }) to
                                    (if (lu.startsWith("http")) lu else "https://$lu")
                            )
                        }
                    }
                }
            }
            obj.optJSONObject("channelAboutFullMetadataRenderer")?.let { legacy ->
                if (description.isBlank()) description = text(legacy.opt("description"))
                if (joined.isBlank()) joined = text(legacy.opt("joinedDateText"))
                if (views.isBlank()) views = text(legacy.opt("viewCountText"))
                if (country.isBlank()) country = text(legacy.opt("country"))
                val canon = legacy.optString("canonicalChannelUrl")
                if (canon.isNotBlank()) url = canon
            }
        }

        return ChannelAboutInfo(
            description = description,
            joinedDate = joined,
            totalViews = views,
            subscriberCount = subs,
            videoCount = videos,
            country = country,
            canonicalUrl = url,
            links = links
        )
    }

    fun setSubscribed(channelId: String, subscribe: Boolean): Boolean {
        if (!Session.isLoggedIn || channelId.isBlank()) return false
        return runCatching {
            val payload = JSONObject()
                .put("context", contextObject())
                .put("channelIds", JSONArray().put(channelId))
            post(
                if (subscribe) "subscription/subscribe" else "subscription/unsubscribe",
                payload.toString()
            )
            true
        }.getOrDefault(false)
    }

    fun notifications(): List<NotificationItem> {
        if (!Session.isLoggedIn) return LocalStore.getCachedNotifications()
        val out = LinkedHashMap<String, NotificationItem>()
        var token: String? = null

        for (pageIdx in 0 until 4) {
            val payload = JSONObject().put("context", contextObject())
            if (token == null) {
                payload.put(
                    "notificationsMenuRequestType",
                    "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX"
                )
            } else {
                payload.put("continuation", token)
            }
            val root = runCatching {
                post("notification/get_notification_menu", payload.toString())
            }.getOrNull() ?: break

            var nextToken: String? = null
            walk(root) { obj ->
                obj.optJSONObject("notificationRenderer")?.let { n ->
                    val id = n.optString("notificationId")
                    val msg = text(n.opt("shortMessage")).ifBlank { text(n.opt("title")) }
                    if (msg.isBlank()) return@let

                    var vid = ""
                    walk(n.opt("navigationEndpoint")) { nav ->
                        if (vid.isBlank()) {
                            val v = nav.optString("videoId")
                            if (v.length == 11) vid = v
                        }
                    }
                    val avatar = fastAvatarUrl(
                        bestThumbArray(
                            n.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ).orEmpty()
                    )
                    val videoThumb = bestThumbArray(
                        n.optJSONObject("videoThumbnail")?.optJSONArray("thumbnails")
                    ).orEmpty()
                    val time = text(n.opt("sentTimeText"))
                    val unread = !n.optBoolean("read", true)
                    val key = id.ifBlank { "$msg|$time" }
                    out.putIfAbsent(
                        key,
                        NotificationItem(id, msg, time, avatar, videoThumb, vid, unread)
                    )
                }
                if (nextToken == null) {
                    val c1 = obj.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                    val c2 = obj.optJSONObject("continuationItemRenderer")
                        ?.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    nextToken = c1?.takeIf { it.isNotBlank() }
                        ?: c2?.takeIf { it.isNotBlank() }
                }
            }
            token = nextToken
            if (token.isNullOrBlank() || out.size >= 60) break
        }
        return LocalStore.mergeAndGetNotifications(out.values.toList())
    }

    fun syncAccountWatchStats(): AccountSyncStats {
        if (!Session.isLoggedIn) return LocalStore.getSyncedAccountStats()
        // watch time gibt's nur ueber die rote leiste im verlauf, alles andere ist raten

        var likedTotal = 0
        runCatching {
            val likedRoot = post("browse", body(browseId = "VLLL"))
            walk(likedRoot) { obj ->
                if (likedTotal == 0) {
                    val txt = text(
                        obj.optJSONObject("playlistHeaderRenderer")?.opt("numVideosText")
                    ).ifBlank {
                        text(
                            obj.optJSONObject("pageHeaderViewModel")?.opt("metadata")
                        )
                    }
                    val digits = Regex("([0-9][0-9.,]*)\\s+video", RegexOption.IGNORE_CASE)
                        .find(txt)?.groupValues?.getOrNull(1)
                        ?.replace(",", "")?.replace(".", "")
                    digits?.toIntOrNull()?.let { likedTotal = it }
                }
            }
            if (likedTotal == 0) {
                likedTotal = parsePage(likedRoot).items.size
            }
        }

        var totalWatchSec = 0L
        var historyCount = 0
        var token: String? = null
        for (p in 0 until 4) {
            val histPage = runCatching { browse("FEhistory", token) }.getOrNull() ?: break
            histPage.items.forEach { item ->
                historyCount++
                val durSec = parseDurationSeconds(item.duration)
                if (durSec > 0) {
                    val pct = if (item.percentWatched > 0) item.percentWatched else 60
                    totalWatchSec += (durSec * pct) / 100L
                }
            }
            token = histPage.continuation
            if (token.isNullOrBlank()) break
        }

        val result = AccountSyncStats(likedTotal, totalWatchSec, historyCount)
        LocalStore.saveSyncedAccountStats(result)
        return result
    }

    private fun parseDurationSeconds(duration: String): Long {
        val parts = duration.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    fun subscribedChannels(): List<SubscribedChannel> {
        if (!Session.isLoggedIn) return emptyList()
        val channels = LinkedHashMap<String, SubscribedChannel>()
        var token: String? = null
        for (p in 0 until 4) {
            val root = runCatching {
                post(
                    "browse",
                    body(
                        browseId = if (token == null) "FEchannels" else null,
                        continuation = token
                    )
                )
            }.getOrNull() ?: break
            var nextToken: String? = null
            walk(root) { obj ->
                val r = obj.optJSONObject("channelRenderer")
                    ?: obj.optJSONObject("gridChannelRenderer")
                    ?: obj.optJSONObject("channelListItemRenderer")
                if (r != null) {
                    val id = r.optString("channelId").ifBlank {
                        r.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId").orEmpty()
                    }
                    val name = text(r.opt("title"))
                    if (id.isNotBlank() && name.isNotBlank()) {
                        val av = fastAvatarUrl(
                            bestThumbArray(
                                r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            ).orEmpty()
                        )
                        val subs = text(r.opt("subscriberCountText"))
                            .ifBlank { text(r.opt("videoCountText")) }
                        channels.putIfAbsent(id, SubscribedChannel(id, name, av, subs))
                    }
                }

                obj.optJSONObject("lockupViewModel")?.let { lk ->
                    val cid = lk.optString("contentId")
                    if (cid.startsWith("UC")) {
                        val meta = lk.optJSONObject("metadata")
                            ?.optJSONObject("lockupMetadataViewModel")
                        val name = text(meta?.opt("title"))
                        if (name.isNotBlank()) {
                            val av = fastAvatarUrl(
                                bestThumb(
                                    meta?.optJSONObject("image")
                                        ?.optJSONObject("decoratedAvatarViewModel")
                                        ?.optJSONObject("avatar")
                                        ?.optJSONObject("avatarViewModel")
                                        ?.optJSONObject("image")
                                ).orEmpty()
                            )
                            val parts = meta?.let { metadataParts(it) } ?: emptyList()
                            val subs = parts.firstOrNull { it.contains("subscriber", true) }
                                ?: parts.firstOrNull().orEmpty()
                            channels.putIfAbsent(
                                cid,
                                SubscribedChannel(cid, name, av, subs)
                            )
                        }
                    }
                }

                if (nextToken == null) {
                    val c = obj.optJSONObject("continuationItemRenderer")
                        ?.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!c.isNullOrBlank()) nextToken = c
                }
            }
            token = nextToken
            if (token.isNullOrBlank()) break
        }
        return channels.values.toList()
    }

    fun removeLike(videoId: String): Boolean {
        if (!Session.isLoggedIn || videoId.isBlank()) return false
        return runCatching {
            val payload = JSONObject()
                .put("context", contextObject())
                .put("target", JSONObject().put("videoId", videoId))
            post("like/removelike", payload.toString(), allowAnonFallback = false)
            true
        }.getOrDefault(false)
    }

    fun likeVideo(videoId: String): Boolean {
        if (!Session.isLoggedIn || videoId.isBlank()) return false
        return runCatching {
            val payload = JSONObject()
                .put("context", contextObject())
                .put("target", JSONObject().put("videoId", videoId))
            post("like/like", payload.toString(), allowAnonFallback = false)
            true
        }.getOrDefault(false)
    }

    fun dislikeVideo(videoId: String): Boolean {
        if (!Session.isLoggedIn || videoId.isBlank()) return false
        return runCatching {
            val payload = JSONObject()
                .put("context", contextObject())
                .put("target", JSONObject().put("videoId", videoId))
            post("like/dislike", payload.toString(), allowAnonFallback = false)
            true
        }.getOrDefault(false)
    }

    fun performCommentAction(actionToken: String): Boolean {
        if (!Session.isLoggedIn || actionToken.isBlank()) return false
        return runCatching {
            val payload = JSONObject()
                .put("context", contextObject())
                .put("actions", JSONArray().put(actionToken))
            post("comment/perform_comment_action", payload.toString(), allowAnonFallback = false)
            true
        }.getOrDefault(false)
    }

    fun addVideoToPlaylist(playlistId: String, videoId: String): Boolean {
        if (!Session.isLoggedIn || playlistId.isBlank() || videoId.isBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        return runCatching {
            val action = JSONObject()
                .put("action", "ACTION_ADD_VIDEO")
                .put("addedVideoId", videoId)
            val payload = JSONObject()
                .put("context", contextObject())
                .put("playlistId", cleanId)
                .put("actions", JSONArray().put(action))
            post("browse/edit_playlist", payload.toString(), allowAnonFallback = false)
            true
        }.getOrDefault(false)
    }

    fun removeVideoFromPlaylist(playlistId: String, videoId: String): Boolean {
        if (!Session.isLoggedIn || playlistId.isBlank() || videoId.isBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        return runCatching {
            val action = JSONObject()
                .put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                .put("removedVideoId", videoId)
            val payload = JSONObject()
                .put("context", contextObject())
                .put("playlistId", cleanId)
                .put("actions", JSONArray().put(action))
            post("browse/edit_playlist", payload.toString(), allowAnonFallback = false)
            true
        }.getOrDefault(false)
    }

    fun createPlaylist(
        title: String,
        privacyStatus: String = "PRIVATE",
        initialVideoId: String? = null
    ): String? {
        if (!Session.isLoggedIn || title.isBlank()) return null
        return runCatching {
            val payload = JSONObject()
                .put("context", contextObject())
                .put("title", title.trim())
                .put("privacyStatus", privacyStatus)
            if (!initialVideoId.isNullOrBlank()) {
                payload.put("videoIds", JSONArray().put(initialVideoId))
            }
            val resp = post("playlist/create", payload.toString(), allowAnonFallback = false)
            clearCache()
            resp.optString("playlistId").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun recentHistory(limit: Int = 15): List<VideoItem> {
        val local = LocalStore.getHistory()
        if (!Session.isLoggedIn) return local.take(limit)
        val remote = runCatching { browse("FEhistory").items }.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, VideoItem>()
        local.forEach { merged[it.id] = it }
        remote.filter { !it.isPlaylist && !it.isChannel }.forEach { item ->
            val savedPct = LocalStore.getPlaybackPercent(item.id)
            val pct = maxOf(savedPct, item.percentWatched)
            merged.putIfAbsent(item.id, item.copy(percentWatched = pct))
        }
        return merged.values.take(limit)
    }

    private fun body(
        browseId: String? = null,
        continuation: String? = null,
        query: String? = null,
        videoId: String? = null
    ): String {
        val root = JSONObject()
        root.put("context", contextObject())
        if (browseId != null) root.put("browseId", browseId)
        if (continuation != null) root.put("continuation", continuation)
        if (query != null) root.put("query", query)
        if (videoId != null) root.put("videoId", videoId)
        return root.toString()
    }

    fun postComment(createCommentParams: String, text: String): Boolean {
        if (!Session.isLoggedIn) throw IllegalStateException("Not signed in")

        val runs = JSONArray().put(JSONObject().put("text", text))
        val payload = JSONObject()
            .put("context", contextObject())
            .put("createCommentParams", createCommentParams)
            .put("richTextMessage", JSONObject().put("runs", runs))

        val response = post("comment/create_comment", payload.toString())
        return response.length() > 0
    }

    fun commentParamsFor(videoId: String): String {
        val id = videoId.toByteArray(Charsets.UTF_8)
        val inner = ByteArray(2 + id.size)
        inner[0] = 0x0A
        inner[1] = id.size.toByte()
        System.arraycopy(id, 0, inner, 2, id.size)

        val outer = ByteArray(2 + inner.size)
        outer[0] = 0x12
        outer[1] = inner.size.toByte()
        System.arraycopy(inner, 0, outer, 2, inner.size)

        return android.util.Base64.encodeToString(
            outer,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING
        )
    }

    private fun post(
        endpoint: String,
        payload: String,
        allowAnonFallback: Boolean = endpoint in setOf("browse", "search", "next")
    ): JSONObject {
        if (visitorData.isBlank() && !Session.isLoggedIn) ensureVisitorData()

        fun execute(useAuth: Boolean): okhttp3.Response {
            val builder = Request.Builder()
                .url("$BASE/$endpoint?prettyPrint=false")
                .post(payload.toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", Session.ORIGIN)
                .header("Referer", "https://www.youtube.com/")
                .header("X-Youtube-Client-Name", "1")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)

            if (visitorData.isNotBlank()) {
                builder.header("X-Goog-Visitor-Id", visitorData)
            }

            if (useAuth && Session.cookieHeader.isNotBlank()) {
                builder.header("Cookie", Session.cookieHeader)
                Session.authorizationHeader()?.let { auth ->
                    builder.header("Authorization", auth)
                    builder.header("X-Origin", Session.ORIGIN)
                    builder.header("X-Goog-AuthUser", "0")
                }
            } else {
                // Prevents the EU consent block in Germany for guests / fallback requests.
                builder.header("Cookie", "SOCS=CAI")
            }
            return client.newCall(builder.build()).execute()
        }

        val tryAuth = Session.isLoggedIn
        execute(tryAuth).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(text)
                val vd = json.optJSONObject("responseContext")?.optString("visitorData").orEmpty()
                if (vd.isNotBlank()) visitorData = vd
                return json
            }

            // On 401/403 refresh cookies from the WebView once and retry.
            if ((response.code == 401 || response.code == 403) && tryAuth) {
                runCatching { Session.captureFromWebView() }
                execute(true).use { retryResp ->
                    val retryText = retryResp.body?.string().orEmpty()
                    if (retryResp.isSuccessful) return JSONObject(retryText)
                }
                // For read-only endpoints fall back to a clean anonymous request.
                if (allowAnonFallback) {
                    visitorData = ""
                    ensureVisitorData()
                    execute(false).use { anonResp ->
                        val anonText = anonResp.body?.string().orEmpty()
                        if (anonResp.isSuccessful) return JSONObject(anonText)
                    }
                }
            }

            when (response.code) {
                401 -> throw IllegalStateException("Not signed in (401)")
                403 -> throw IllegalStateException("YouTube rejected request (403) – try refreshing login")
                else -> throw IllegalStateException("HTTP ${response.code}")
            }
        }
    }

    private fun parsePage(root: JSONObject): Page {
        val items = LinkedHashMap<String, VideoItem>()
        var continuation: String? = null
        val hiddenIds = LocalStore.getHiddenIds()

        walk(root) { obj ->
            if (obj.has("shortsLockupViewModel") || obj.has("reelItemRenderer")) return@walk

            obj.optJSONObject("lockupViewModel")?.let { lockup ->
                parseLockup(lockup)?.let { add(items, it, hiddenIds) }
            }
            obj.optJSONObject("channelRenderer")?.let { ch ->
                val cid = ch.optString("channelId")
                val name = text(ch.opt("title"))
                if (cid.isNotBlank() && name.isNotBlank()) {
                    val av = fastAvatarUrl(
                        bestThumbArray(
                            ch.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ).orEmpty()
                    )
                    add(
                        items,
                        VideoItem(
                            id = cid,
                            title = name,
                            channel = text(ch.opt("subscriberCountText"))
                                .ifBlank { "Channel" },
                            channelId = cid,
                            thumbnail = av,
                            avatar = av,
                            views = text(ch.opt("videoCountText")),
                            isChannel = true
                        ),
                        hiddenIds
                    )
                }
            }
            obj.optJSONObject("videoRenderer")?.let { renderer ->
                parseVideoRenderer(renderer)?.let { add(items, it, hiddenIds) }
            }
            obj.optJSONObject("playlistVideoRenderer")?.let { renderer ->
                parsePlaylistVideo(renderer)?.let { add(items, it, hiddenIds) }
            }
            obj.optJSONObject("gridVideoRenderer")?.let { renderer ->
                parseGridVideo(renderer)?.let { add(items, it, hiddenIds) }
            }
            obj.optJSONObject("playlistRenderer")?.let { renderer ->
                parsePlaylistRenderer(renderer)?.let { add(items, it, hiddenIds) }
            }
            obj.optJSONObject("gridPlaylistRenderer")?.let { renderer ->
                parseGridPlaylist(renderer)?.let { add(items, it, hiddenIds) }
            }
            obj.optJSONObject("compactStationRenderer")?.let { renderer ->
                parseCompactPlaylist(renderer)?.let { add(items, it, hiddenIds) }
            }
            obj.optJSONObject("continuationItemRenderer")?.let { item ->
                val token = item.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!token.isNullOrBlank()) continuation = token
            }
        }

        return Page(items.values.toList(), continuation)
    }

    private fun add(target: MutableMap<String, VideoItem>, item: VideoItem, hidden: Set<String>) {
        if (item.id.isNotBlank() && !hidden.contains(item.id) && !target.containsKey(item.id)) {
            target[item.id] = item
        }
    }

    private fun parseLockup(lockup: JSONObject): VideoItem? {
        val id = lockup.optString("contentId")
        if (id.isBlank() || id.startsWith("RD")) return null
        val contentType = lockup.optString("contentType")
        if (contentType.contains("SHORT", ignoreCase = true)) return null

        val meta = lockup.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = text(meta.opt("title"))
        if (title.isBlank() || title.contains("#shorts", ignoreCase = true)) return null

        val parts = metadataParts(meta)
        val playlist = id.startsWith("PL") || id.startsWith("OL") || id.startsWith("FL") ||
            contentType.contains("PLAYLIST", ignoreCase = true) ||
            parts.firstOrNull()?.equals("Playlist", ignoreCase = true) == true

        val thumb = bestThumb(
            lockup.optJSONObject("contentImage")
                ?.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")
        ) ?: bestThumb(
            lockup.optJSONObject("contentImage")
                ?.optJSONObject("collectionThumbnailViewModel")
                ?.optJSONObject("primaryThumbnail")
                ?.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")
        ) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"

        val avatarDecor = meta.optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
        val avatar = bestThumb(
            avatarDecor?.optJSONObject("avatar")
                ?.optJSONObject("avatarViewModel")
                ?.optJSONObject("image")
        ).orEmpty().let { fastAvatarUrl(it) }

        val channelId = avatarDecor?.optJSONObject("rendererContext")
            ?.optJSONObject("commandContext")
            ?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()

        return VideoItem(
            id = id,
            title = title,
            channel = if (playlist) "Playlist" else parts.getOrNull(0).orEmpty(),
            channelId = channelId,
            thumbnail = thumb,
            duration = lockupDuration(lockup),
            views = if (playlist) parts.joinToString(" · ") else parts.getOrNull(1).orEmpty(),
            published = if (playlist) "" else parts.getOrNull(2).orEmpty(),
            isPlaylist = playlist,
            avatar = avatar,
            percentWatched = if (playlist) 0 else parseResumePercent(lockup)
        )
    }

    private fun parseVideoRenderer(v: JSONObject): VideoItem? {
        val id = v.optString("videoId")
        if (id.isBlank()) return null
        val navUrl = v.optJSONObject("navigationEndpoint")
            ?.optJSONObject("commandMetadata")
            ?.optJSONObject("webCommandMetadata")
            ?.optString("url").orEmpty()
        if (navUrl.contains("/shorts/")) return null

        val title = text(v.opt("title"))
        if (title.isBlank() || title.contains("#shorts", ignoreCase = true)) return null

        val owner = v.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)
            ?: v.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)
        val thumb = bestThumbArray(v.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"

        val avatar = bestThumbArray(
            v.optJSONObject("channelThumbnailSupportedRenderers")
                ?.optJSONObject("channelThumbnailWithLinkRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        ).orEmpty().let { fastAvatarUrl(it) }

        return VideoItem(
            id = id,
            title = title,
            channel = owner?.optString("text").orEmpty(),
            channelId = owner?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty(),
            thumbnail = thumb,
            duration = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty(),
            views = v.optJSONObject("shortViewCountText")?.optString("simpleText").orEmpty(),
            published = v.optJSONObject("publishedTimeText")?.optString("simpleText").orEmpty(),
            isLive = v.optJSONArray("badges")?.toString()
                ?.contains("LIVE", ignoreCase = true) == true,
            avatar = avatar,
            percentWatched = parseResumePercent(v)
        )
    }

    private fun parsePlaylistVideo(v: JSONObject): VideoItem? {
        val id = v.optString("videoId")
        if (id.isBlank()) return null
        val title = text(v.opt("title"))
        if (title.isBlank()) return null
        val owner = v.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)
        return VideoItem(
            id = id,
            title = title,
            channel = owner?.optString("text").orEmpty(),
            channelId = owner?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty(),
            thumbnail = bestThumbArray(v.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            duration = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty(),
            percentWatched = parseResumePercent(v)
        )
    }

    private fun parseGridVideo(v: JSONObject): VideoItem? {
        val id = v.optString("videoId")
        if (id.isBlank()) return null
        val title = text(v.opt("title"))
        if (title.isBlank()) return null
        return VideoItem(
            id = id,
            title = title,
            channel = v.optJSONObject("shortBylineText")?.optJSONArray("runs")
                ?.optJSONObject(0)?.optString("text").orEmpty(),
            thumbnail = bestThumbArray(v.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            duration = v.optJSONArray("thumbnailOverlays")?.optJSONObject(0)
                ?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                ?.let { text(it.opt("text")) }.orEmpty(),
            views = v.optJSONObject("viewCountText")?.optString("simpleText").orEmpty(),
            published = v.optJSONObject("publishedTimeText")?.optString("simpleText").orEmpty(),
            percentWatched = parseResumePercent(v)
        )
    }

    private fun parsePlaylistRenderer(p: JSONObject): VideoItem? {
        val id = p.optString("playlistId")
        if (id.isBlank()) return null
        val title = text(p.opt("title"))
        if (title.isBlank()) return null
        val thumbs = p.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: p.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("playlistVideoThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: p.optJSONArray("thumbnails")?.optJSONObject(0)?.optJSONArray("thumbnails")
        return VideoItem(
            id = id,
            title = title,
            channel = "Playlist",
            thumbnail = bestThumbArray(thumbs).orEmpty(),
            views = text(p.opt("videoCountText")).ifBlank { text(p.opt("videoCountShortText")) },
            isPlaylist = true
        )
    }

    private fun parseGridPlaylist(p: JSONObject): VideoItem? {
        val id = p.optString("playlistId")
        if (id.isBlank()) return null
        val title = text(p.opt("title"))
        if (title.isBlank()) return null
        val thumbs = p.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: p.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("playlistVideoThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        return VideoItem(
            id = id,
            title = title,
            channel = "Playlist",
            thumbnail = bestThumbArray(thumbs).orEmpty(),
            views = text(p.opt("videoCountText")).ifBlank { text(p.opt("videoCountShortText")) },
            isPlaylist = true
        )
    }

    private fun parseCompactPlaylist(p: JSONObject): VideoItem? {
        val id = p.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")?.optString("playlistId").orEmpty()
        if (id.isBlank()) return null
        val title = text(p.opt("title"))
        if (title.isBlank()) return null
        return VideoItem(
            id = id,
            title = title,
            channel = "Playlist",
            thumbnail = bestThumbArray(p.optJSONObject("thumbnail")?.optJSONArray("thumbnails")).orEmpty(),
            views = text(p.opt("videoCountText")),
            isPlaylist = true
        )
    }

    private fun parseResumePercent(v: JSONObject): Int {
        var result = 0
        walk(v) { obj ->
            if (result == 0) {
                val pct = obj.optInt("percentDurationWatched", 0)
                if (pct > 0) result = pct.coerceIn(0, 100)
            }
        }
        return result
    }

    private fun walk(node: Any?, visit: (JSONObject) -> Unit) {        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    private fun text(node: Any?): String = when (node) {
        null -> ""
        is String -> node
        is JSONObject -> when {
            node.has("content") -> node.optString("content")
            node.has("simpleText") -> node.optString("simpleText")
            else -> runs(node)
        }
        else -> ""
    }

    private fun runs(obj: JSONObject): String {
        val array = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            sb.append(array.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    private fun metadataParts(meta: JSONObject): List<String> {
        val rows = meta.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            for (j in 0 until parts.length()) {
                val value = text(parts.optJSONObject(j)?.opt("text"))
                if (value.isNotBlank()) out.add(value)
            }
        }
        return out
    }

    private fun lockupDuration(lockup: JSONObject): String {
        val overlays = lockup.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONArray("overlays") ?: return ""
        for (i in 0 until overlays.length()) {
            val badges = overlays.optJSONObject(i)
                ?.optJSONObject("thumbnailBottomOverlayViewModel")
                ?.optJSONArray("badges") ?: continue
            for (j in 0 until badges.length()) {
                val badge = badges.optJSONObject(j)?.optJSONObject("thumbnailBadgeViewModel")
                val value = badge?.optString("text").orEmpty()
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun bestThumb(image: JSONObject?): String? {
        val sources = image?.optJSONArray("sources") ?: return null
        return bestThumbArray(sources)
    }

    private fun bestThumbArray(array: JSONArray?): String? {
        if (array == null || array.length() == 0) return null
        var url: String? = null
        for (i in 0 until array.length()) {
            val raw = array.optJSONObject(i)?.optString("url").orEmpty()
            if (raw.isNotBlank()) {
                url = if (raw.startsWith("//")) "https:$raw" else raw
            }
        }
        return url
    }
}
