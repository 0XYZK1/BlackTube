package com.nexus.videoclient.data

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

data class StreamChoice(
    val url: String,
    val label: String,
    val mime: String,
    val suffix: String,
    val height: Int,
    val bitrate: Int,
    val videoOnly: Boolean,
    val itag: Int = -1,
    val audioTrackId: String = "",
    val audioTrackName: String = "",
    val audioLocale: String = "",
    val isOriginalAudio: Boolean = true,
    val isDubbedAudio: Boolean = false
) {
    val trackKey: String
        get() = when {
            audioTrackId.isNotBlank() -> audioTrackId
            audioTrackName.isNotBlank() -> "$audioTrackName|$isOriginalAudio|$isDubbedAudio"
            audioLocale.isNotBlank() -> "$audioLocale|$isOriginalAudio|$isDubbedAudio"
            else -> "default"
        }

    val displayAudioName: String
        get() {
            val base = audioTrackName.ifBlank { audioLocale.uppercase().ifBlank { "Default" } }
            return when {
                isOriginalAudio && !base.contains("original", true) -> "$base (Original)"
                isDubbedAudio && !base.contains("dub", true) -> "$base (Auto-Dub)"
                else -> base
            }
        }
}

data class Resolved(
    val title: String,
    val uploader: String,
    val uploaderAvatar: String,
    val uploaderUrl: String,
    val durationSec: Long,
    val progressive: List<StreamChoice>,
    val videoOnly: List<StreamChoice>,
    val audio: List<StreamChoice>,
    val hlsUrl: String = ""
) {
    val heights: List<Int>
        get() = (progressive.map { it.height } + videoOnly.map { it.height })
            .filter { it > 0 }
            .distinct()
            .sortedDescending()

    val distinctAudioTracks: List<StreamChoice>
        get() = audio
            .sortedWith(
                compareByDescending<StreamChoice> { it.isOriginalAudio }
                    .thenBy { it.isDubbedAudio }
                    .thenByDescending { it.mime.contains("mp4") }
                    .thenByDescending { it.bitrate }
            )
            .distinctBy { it.trackKey }

    fun bestProgressive(maxHeight: Int): StreamChoice? =
        progressive.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }
            ?: progressive.maxByOrNull { it.height }

    fun bestAdaptive(maxHeight: Int): StreamChoice? =
        videoOnly.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }
            ?: videoOnly.maxByOrNull { it.height }

    fun bestOriginalAudio(preferredTrackKey: String? = null): StreamChoice? {
        if (audio.isEmpty()) return null

        // If the user picked a track manually, always use exactly that track.
        if (!preferredTrackKey.isNullOrBlank()) {
            val match = audio.filter { it.trackKey == preferredTrackKey }
                .sortedWith(
                    compareByDescending<StreamChoice> { it.mime.contains("mp4") }
                        .thenByDescending { it.bitrate }
                )
                .firstOrNull()
            if (match != null) return match
        }

        val nonDubbed = audio.filterNot { it.isDubbedAudio }
        val pool = nonDubbed.ifEmpty { audio }

        return pool.sortedWith(
            compareByDescending<StreamChoice> { it.isOriginalAudio }
                .thenBy { it.isDubbedAudio }
                .thenByDescending { it.mime.contains("mp4") }
                .thenByDescending { it.bitrate }
        ).firstOrNull()
    }
}

object YtExtractor {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    @Volatile
    private var ready = false

    private data class Cached(val value: Resolved, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private const val CACHE_TTL_MS = 18 * 60 * 1000L

    fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(DownloaderImpl(), Localization("en", "US"))
            ready = true
        }
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    fun resolve(videoId: String): Resolved {
        init()
        val hit = cache[videoId]
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) return hit.value

        val resolved = resolveFresh(videoId)
        cache[videoId] = Cached(resolved, System.currentTimeMillis())
        return resolved
    }

    /**
     * Returns the correct user-agent and headers for each googlevideo.com URL.
     * Wrong browser headers (Origin/Referer) on ANDROID_VR URLs cause an immediate HTTP 403.
     */
    fun headersForStreamUrl(url: String): Pair<String, Map<String, String>> {
        val clientParam = runCatching { Uri.parse(url).getQueryParameter("c") }.getOrNull().orEmpty()
        return when {
            clientParam.startsWith("ANDROID_VR", ignoreCase = true) ->
                ANDROID_VR_UA to mapOf("Connection" to "keep-alive")
            clientParam.startsWith("ANDROID", ignoreCase = true) ||
                clientParam.startsWith("IOS", ignoreCase = true) ->
                "Dalvik/2.1.0 (Linux; U; Android 14)" to mapOf("Connection" to "keep-alive")
            else ->
                UA to mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com",
                    "Connection" to "keep-alive"
                )
        }
    }

    private fun resolveFresh(videoId: String): Resolved {
        val info = StreamInfo.getInfo(
            ServiceList.YouTube,
            "https://www.youtube.com/watch?v=$videoId"
        )

        val progressive = info.videoStreams.mapNotNull { stream ->
            val format = stream.format ?: return@mapNotNull null
            StreamChoice(
                url = stream.content,
                label = stream.resolution.ifBlank { "Video" },
                mime = format.mimeType,
                suffix = format.suffix,
                height = heightOf(stream.resolution),
                bitrate = 0,
                videoOnly = false
            )
        }

        val videoOnly = info.videoOnlyStreams.mapNotNull { stream ->
            val format = stream.format ?: return@mapNotNull null
            StreamChoice(
                url = stream.content,
                label = stream.resolution.ifBlank { "Video" },
                mime = format.mimeType,
                suffix = format.suffix,
                height = heightOf(stream.resolution),
                bitrate = stream.bitrate,
                videoOnly = true,
                itag = stream.itagItem?.id ?: -1
            )
        }

        val rawAudio = info.audioStreams
        val hasExplicitOriginal = rawAudio.any { isExplicitOriginalTrack(it) }

        // Keep ALL audio tracks so the user can switch between original and auto-dub at any time.
        val audio = rawAudio.mapNotNull { stream ->
            val format = stream.format ?: return@mapNotNull null
            val trackId = stream.audioTrackId.orEmpty()
            val trackName = stream.audioTrackName.orEmpty()
            val locale = stream.audioLocale?.toLanguageTag().orEmpty()
            val explicitOriginal = isExplicitOriginalTrack(stream)
            val dubbed = !explicitOriginal && isDubbedTrack(stream, hasExplicitOriginal)
            val original = explicitOriginal || (!dubbed && !hasExplicitOriginal)

            val trackBadge = when {
                original && trackName.isNotBlank() -> "$trackName (Original)"
                original -> "Original"
                trackName.isNotBlank() -> trackName
                locale.isNotBlank() -> locale
                else -> "Standard"
            }

            StreamChoice(
                url = stream.content,
                label = "$trackBadge · ${stream.averageBitrate / 1000} kbit/s",
                mime = format.mimeType,
                suffix = format.suffix,
                height = 0,
                bitrate = stream.averageBitrate,
                videoOnly = false,
                itag = stream.itagItem?.id ?: -1,
                audioTrackId = trackId,
                audioTrackName = trackName,
                audioLocale = locale,
                isOriginalAudio = original,
                isDubbedAudio = dubbed
            )
        }

        val uploaderAvatar = info.uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty()

        return Resolved(
            title = info.name.orEmpty(),
            uploader = info.uploaderName.orEmpty(),
            uploaderAvatar = uploaderAvatar,
            uploaderUrl = info.uploaderUrl.orEmpty(),
            durationSec = info.duration,
            progressive = progressive,
            videoOnly = videoOnly,
            audio = audio,
            hlsUrl = info.hlsUrl.orEmpty()
        )
    }

    private fun isExplicitOriginalTrack(stream: AudioStream): Boolean {
        if (stream.audioTrackType == AudioTrackType.ORIGINAL) return true
        val name = stream.audioTrackName.orEmpty().lowercase()
        val id = stream.audioTrackId.orEmpty().lowercase()
        return name.contains("original") ||
            name.contains("원본") ||
            name.contains("オリジナル") ||
            id.endsWith(".4") ||
            id.endsWith(".10")
    }

    private fun isDubbedTrack(stream: AudioStream, videoHasExplicitOriginal: Boolean): Boolean {
        if (stream.audioTrackType == AudioTrackType.DUBBED) return true
        if (stream.audioTrackType == AudioTrackType.DESCRIPTIVE) return true
        val name = stream.audioTrackName.orEmpty().lowercase()
        val id = stream.audioTrackId.orEmpty().lowercase()
        if (id.endsWith(".3") || id.endsWith(".2")) return true
        if (name.contains("dubbed") ||
            name.contains("auto-dubbed") ||
            name.contains("auto dubbed") ||
            name.contains("synchronized") ||
            name.contains("synchronisiert") ||
            name.contains("übersetzt") ||
            name.contains("translated") ||
            name.contains("descriptive")
        ) {
            return true
        }
        return videoHasExplicitOriginal && !isExplicitOriginalTrack(stream)
    }

    val userAgent: String get() = UA

    private fun heightOf(resolution: String): Int =
        resolution.substringBefore('p').toIntOrNull() ?: 0

    private class DownloaderImpl : Downloader() {

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        override fun execute(request: Request): Response {
            val method = request.httpMethod()
            val payload = request.dataToSend()

            val builder = HttpRequest.Builder().url(request.url())
            when {
                payload != null -> builder.method(method, payload.toRequestBody())
                method == "POST" -> builder.method(method, ByteArray(0).toRequestBody(null))
                else -> builder.method(method, null)
            }

            var hasUserAgent = false
            var hasCookie = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                if (name.equals("Cookie", ignoreCase = true)) hasCookie = true
                values.forEach { value -> builder.addHeader(name, value) }
            }
            if (!hasUserAgent) builder.header("User-Agent", UA)
            if (!hasCookie && request.url().contains("youtube.com", ignoreCase = true)) {
                builder.header("Cookie", "SOCS=CAI")
            }

            client.newCall(builder.build()).execute().use { response ->
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string().orEmpty(),
                    response.request.url.toString()
                )
            }
        }
    }
}
