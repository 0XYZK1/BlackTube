package com.nexus.videoclient.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object MediaDownloader {

    private const val CHANNEL_ID = "blacktube_downloads"
    private val nextId = AtomicInteger(8000)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class SavedDownload(val fileName: String, val folderPath: String, val uri: Uri?)

    fun downloadVideoWithNotification(
        context: Context,
        video: StreamChoice?,
        audio: StreamChoice?,
        progressiveFallback: StreamChoice?,
        baseName: String,
        onProgress: (Int) -> Unit
    ): SavedDownload {
        ensureChannel(context)
        val notifId = nextId.incrementAndGet()
        val dir = File(context.cacheDir, "nexus-dl").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val videoFile = File(dir, "v_$stamp.${video?.suffix?.ifBlank { "mp4" } ?: "mp4"}")
        val audioFile = File(dir, "a_$stamp.${audio?.suffix?.ifBlank { "m4a" } ?: "m4a"}")
        val muxedFile = File(dir, "mux_$stamp.tmp")

        fun updateProgress(pct: Int) {
            onProgress(pct)
            showProgressNotification(context, notifId, "$baseName.mp4", pct)
        }

        try {
            updateProgress(0)
            if (video != null && audio != null && video.videoOnly) {
                try {
                    fetch(video.url, videoFile, 0, 68, ::updateProgress)
                    fetch(audio.url, audioFile, 68, 84, ::updateProgress)
                    val ext = mux(videoFile, audioFile, muxedFile)
                    updateProgress(92)
                    val finalName = "$baseName.$ext"
                    val mime = if (ext == "webm") "video/webm" else "video/mp4"
                    val uri = saveToDownloads(context, muxedFile, finalName, mime)
                    updateProgress(100)
                    showCompletedNotification(context, notifId, finalName, uri, mime)
                    return SavedDownload(finalName, "Downloads/BlackTube/$finalName", uri)
                } catch (_: Exception) {
                    if (progressiveFallback == null) throw IllegalStateException("Muxing fehlgeschlagen")
                }
            }

            val direct = progressiveFallback ?: video
                ?: throw IllegalStateException("No video track available")
            val finalName = "$baseName.mp4"
            fetch(direct.url, muxedFile, 0, 95, ::updateProgress)
            val uri = saveToDownloads(context, muxedFile, finalName, "video/mp4")
            updateProgress(100)
            showCompletedNotification(context, notifId, finalName, uri, "video/mp4")
            return SavedDownload(finalName, "Downloads/BlackTube/$finalName", uri)
        } catch (e: Exception) {
            showFailedNotification(context, notifId, baseName, e.message ?: "Fehler")
            throw e
        } finally {
            videoFile.delete()
            audioFile.delete()
            muxedFile.delete()
        }
    }

    fun downloadAudioWithNotification(
        context: Context,
        audio: StreamChoice,
        baseName: String,
        onProgress: (Int) -> Unit
    ): SavedDownload {
        ensureChannel(context)
        val notifId = nextId.incrementAndGet()
        val dir = File(context.cacheDir, "nexus-dl").apply { mkdirs() }
        val ext = if (audio.mime.contains("mp4") || audio.suffix == "m4a") "m4a" else "webm"
        val mime = if (ext == "m4a") "audio/mp4" else "audio/webm"
        val tempFile = File(dir, "aud_${System.currentTimeMillis()}.$ext")
        val finalName = "$baseName.$ext"

        fun updateProgress(pct: Int) {
            onProgress(pct)
            showProgressNotification(context, notifId, finalName, pct)
        }

        try {
            updateProgress(0)
            fetch(audio.url, tempFile, 0, 95, ::updateProgress)
            val uri = saveToDownloads(context, tempFile, finalName, mime)
            updateProgress(100)
            showCompletedNotification(context, notifId, finalName, uri, mime)
            return SavedDownload(finalName, "Downloads/BlackTube/$finalName", uri)
        } catch (e: Exception) {
            showFailedNotification(context, notifId, finalName, e.message ?: "Fehler")
            throw e
        } finally {
            tempFile.delete()
        }
    }

    fun downloadAudioAsMp3WithNotification(
        context: Context,
        audio: StreamChoice,
        bitrateKbps: Int,
        title: String,
        artist: String,
        baseName: String,
        onProgress: (Int) -> Unit
    ): SavedDownload {
        ensureChannel(context)
        val notifId = nextId.incrementAndGet()
        val dir = File(context.cacheDir, "nexus-dl").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val srcExt = if (audio.mime.contains("mp4") || audio.suffix == "m4a") "m4a" else "webm"
        val sourceFile = File(dir, "src_$stamp.$srcExt")
        val mp3File = File(dir, "mp3_$stamp.mp3")
        val finalName = "$baseName.mp3"

        fun updateProgress(pct: Int) {
            onProgress(pct)
            showProgressNotification(context, notifId, finalName, pct)
        }

        try {
            updateProgress(0)
            fetch(audio.url, sourceFile, 0, 55, ::updateProgress)
            updateProgress(56)
            Mp3Transcoder.transcode(
                input = sourceFile,
                output = mp3File,
                bitrateKbps = bitrateKbps,
                title = title,
                artist = artist
            ) { pct ->
                updateProgress(56 + (pct * 40) / 100)
            }
            updateProgress(98)
            val uri = saveToDownloads(context, mp3File, finalName, "audio/mpeg")
            updateProgress(100)
            showCompletedNotification(context, notifId, finalName, uri, "audio/mpeg")
            return SavedDownload(finalName, "Downloads/BlackTube/$finalName", uri)
        } catch (e: Exception) {
            showFailedNotification(context, notifId, finalName, e.message ?: "Fehler")
            throw e
        } finally {
            sourceFile.delete()
            mp3File.delete()
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress and save location in the status bar"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun showProgressNotification(context: Context, id: Int, title: String, pct: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Downloading… $pct%  (Folder: Downloads/BlackTube)")
            .setProgress(100, pct.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(id, notif) }
    }

    private fun showCompletedNotification(
        context: Context,
        id: Int,
        fileName: String,
        uri: Uri?,
        mime: String
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Saved: $fileName")
            .setContentText("Folder: Internal storage → Downloads → BlackTube")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "File: $fileName\nLocation: Internal storage / Downloads / BlackTube"
                )
            )
            .setAutoCancel(true)
            .setOngoing(false)

        if (uri != null) {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                context,
                id,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pi)
            builder.addAction(android.R.drawable.ic_media_play, "Open", pi)
        }
        runCatching { nm.notify(id, builder.build()) }
    }

    private fun showFailedNotification(context: Context, id: Int, title: String, error: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed: $title")
            .setContentText(error)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        runCatching { nm.notify(id, notif) }
    }

    private fun fetch(
        url: String,
        target: File,
        fromPercent: Int,
        toPercent: Int,
        onProgress: (Int) -> Unit
    ) {
        val probeReq = Request.Builder()
            .url(url)
            .header("User-Agent", YtExtractor.userAgent)
            .header("Range", "bytes=0-1")
            .build()

        var totalBytes = -1L
        client.newCall(probeReq).execute().use { resp ->
            val rangeHeader = resp.header("Content-Range").orEmpty() // bytes 0-1/1234567
            val slash = rangeHeader.lastIndexOf('/')
            if (slash > 0) {
                totalBytes = rangeHeader.substring(slash + 1).trim().toLongOrNull() ?: -1L
            }
        }

        target.outputStream().use { output ->
            if (totalBytes > 0) {
                // grosse ranges werden nach ein paar mb gedrosselt, also in haeppchen laden
                val chunkSize = 9L * 1024L * 1024L
                var downloaded = 0L
                var lastPct = -1
                while (downloaded < totalBytes) {
                    val end = minOf(downloaded + chunkSize - 1, totalBytes - 1)
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", YtExtractor.userAgent)
                        .header("Range", "bytes=$downloaded-$end")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("Leere Antwort")
                        body.byteStream().use { input ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                output.write(buf, 0, r)
                                downloaded += r
                                val pct = (fromPercent + (downloaded.toDouble() / totalBytes) * (toPercent - fromPercent)).toInt()
                                    .coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } else {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", YtExtractor.userAgent)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val total = resp.body?.contentLength() ?: -1L
                    var written = 0L
                    var lastPct = -1
                    resp.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val pct = (fromPercent + (written.toDouble() / total) * (toPercent - fromPercent)).toInt()
                                    .coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mux(videoFile: File, audioFile: File, out: File): String {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = firstTrack(videoExtractor, "video/")
                ?: throw IllegalStateException("No video track")
            val audioTrack = firstTrack(audioExtractor, "audio/")
                ?: throw IllegalStateException("No audio track")

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()

            val isMp4 = videoMime.contains("avc") || videoMime.contains("hevc") || videoMime.contains("mp4")
            val extension = if (isMp4) "mp4" else "webm"

            muxer = MediaMuxer(
                out.absolutePath,
                if (isMp4) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            )

            val videoOut = muxer.addTrack(videoFormat)
            val audioOut = muxer.addTrack(audioFormat)
            muxer.start()

            copyTrack(videoExtractor, videoFormat, muxer, videoOut)
            copyTrack(audioExtractor, audioFormat, muxer, audioOut)

            muxer.stop()
            return extension
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun firstTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        muxer: MediaMuxer,
        track: Int
    ) {
        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            1 shl 20
        }
        val buffer = ByteBuffer.allocateDirect(maxInput.coerceAtLeast(1 shl 20))
        val info = MediaCodec.BufferInfo()

        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    private fun saveToDownloads(context: Context, file: File, name: String, mime: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/BlackTube"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore-Eintrag fehlgeschlagen")

            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not write file")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val target = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "BlackTube/$name"
            )
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            Uri.fromFile(target)
        }
    }
}
