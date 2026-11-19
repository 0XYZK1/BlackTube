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
