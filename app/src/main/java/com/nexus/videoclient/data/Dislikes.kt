package com.nexus.videoclient.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Dislikes {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun count(videoId: String): String? = try {
        val request = Request.Builder()
            .url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                val value = JSONObject(response.body?.string().orEmpty())
                    .optLong("dislikes", -1L)
                if (value < 0) null else formatCount(value)
            }
        }
    } catch (_: Exception) {
        null
    }
}

fun formatCount(value: Long): String {
    if (value < 1000) return value.toString()
    val scaled = if (value < 1_000_000) value / 1000.0 else value / 1_000_000.0
    val unit = if (value < 1_000_000) "K" else "M"
    val text = String.format(java.util.Locale.US, "%.1f", scaled)
    return text.removeSuffix(".0") + unit
}

object SponsorBlock {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun segments(videoId: String): List<SponsorSegment> = try {
        val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId" +
            "&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22%5D"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = org.json.JSONArray(resp.body?.string().orEmpty())
            val out = ArrayList<SponsorSegment>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val seg = o.optJSONArray("segment") ?: continue
                val startMs = (seg.optDouble(0, -1.0) * 1000.0).toLong()
                val endMs = (seg.optDouble(1, -1.0) * 1000.0).toLong()
                val cat = o.optString("category", "sponsor")
                if (startMs >= 0L && endMs > startMs + 500L) {
                    out.add(SponsorSegment(startMs, endMs, cat))
                }
            }
            out.sortedBy { it.startMs }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
