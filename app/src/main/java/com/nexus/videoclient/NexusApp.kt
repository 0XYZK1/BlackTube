package com.nexus.videoclient

import android.app.Application
import android.webkit.CookieManager
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nexus.videoclient.data.LocalStore
import com.nexus.videoclient.data.Session
import com.nexus.videoclient.data.YtExtractor
import com.nexus.videoclient.playback.PlaybackSession
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        LocalStore.init(this)
        PlaybackSession.init(this)
        YtExtractor.init()

        val imgDispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        }
        val imgClient = OkHttpClient.Builder()
            .dispatcher(imgDispatcher)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient(imgClient)
            .respectCacheHeaders(false) // YouTube avatar URLs are immutable; cache aggressively
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_img_cache"))
                    .maxSizeBytes(120L * 1024L * 1024L)
                    .build()
            }
            .crossfade(120)
            .build()
        Coil.setImageLoader(imageLoader)

        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            if (!Session.isLoggedIn) Session.captureFromWebView()
        }
    }
}
