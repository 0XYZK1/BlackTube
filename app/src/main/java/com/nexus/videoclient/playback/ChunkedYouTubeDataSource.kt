package com.nexus.videoclient.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * YouTube throttles long single-range requests after the first ~10 second burst.
 * This DataSource splits the stream into 4 MB range chunks (like NewPipe/Grayjay)
 * so playback stays smooth from start to finish.
 */
class ChunkedYouTubeDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = 4L * 1024L * 1024L
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var currentPos: Long = 0L
    private var totalRemaining: Long = C.LENGTH_UNSET.toLong()
    private var knownTotalSize: Long = C.LENGTH_UNSET.toLong()
    private var chunkRemaining: Long = 0L
    private var upstreamOpened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        currentPos = dataSpec.position
        totalRemaining = dataSpec.length
        knownTotalSize = C.LENGTH_UNSET.toLong()

        val opened = openNextChunk()
        if (knownTotalSize != C.LENGTH_UNSET.toLong() && totalRemaining == C.LENGTH_UNSET.toLong()) {
            totalRemaining = (knownTotalSize - currentPos).coerceAtLeast(0L)
            return totalRemaining
        }
        return if (totalRemaining != C.LENGTH_UNSET.toLong()) totalRemaining else opened
    }

    private fun openNextChunk(): Long {
        closeUpstream()
        val spec = baseSpec ?: return 0L
        if (totalRemaining == 0L) return 0L
        if (knownTotalSize != C.LENGTH_UNSET.toLong() && currentPos >= knownTotalSize) {
            totalRemaining = 0L
            return 0L
        }

        var reqLen = if (totalRemaining != C.LENGTH_UNSET.toLong()) {
            min(chunkSize, totalRemaining)
        } else {
            chunkSize
        }
        if (knownTotalSize != C.LENGTH_UNSET.toLong()) {
            reqLen = min(reqLen, (knownTotalSize - currentPos).coerceAtLeast(0L))
            if (reqLen == 0L) return 0L
        }

        val chunkSpec = spec.buildUpon()
            .setPosition(currentPos)
            .setLength(reqLen)
            .build()

        val openedLen = try {
            upstream.open(chunkSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 416) {
                totalRemaining = 0L
                return 0L
            }
            throw e
        }

        upstreamOpened = true
        parseTotalSizeFromHeaders()
        chunkRemaining = if (openedLen == C.LENGTH_UNSET.toLong()) reqLen else openedLen
        return openedLen
    }

    private fun parseTotalSizeFromHeaders() {
        val range = upstream.responseHeaders["Content-Range"]?.firstOrNull()
            ?: upstream.responseHeaders["content-range"]?.firstOrNull()
            ?: return
        val full = range.substringAfterLast('/').trim().toLongOrNull()
        if (full != null && full > 0L) {
            knownTotalSize = full
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (totalRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (!upstreamOpened || chunkRemaining == 0L) {
            val opened = openNextChunk()
            if (opened == 0L) return C.RESULT_END_OF_INPUT
        }

        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            if (knownTotalSize != C.LENGTH_UNSET.toLong() && currentPos >= knownTotalSize) {
                totalRemaining = 0L
                return C.RESULT_END_OF_INPUT
            }
            if (knownTotalSize == C.LENGTH_UNSET.toLong() && chunkRemaining > 0L) {
                totalRemaining = 0L
                return C.RESULT_END_OF_INPUT
            }
            chunkRemaining = 0L
            val next = openNextChunk()
            return if (next == 0L) C.RESULT_END_OF_INPUT else read(buffer, offset, length)
        }

        currentPos += bytesRead
        if (chunkRemaining > 0L) chunkRemaining = (chunkRemaining - bytesRead).coerceAtLeast(0L)
        if (totalRemaining != C.LENGTH_UNSET.toLong()) {
            totalRemaining = (totalRemaining - bytesRead).coerceAtLeast(0L)
        }
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeUpstream()
        baseSpec = null
    }

    private fun closeUpstream() {
        if (upstreamOpened) {
            upstreamOpened = false
            runCatching { upstream.close() }
        }
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkSize: Long = 4L * 1024L * 1024L
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedYouTubeDataSource(upstreamFactory.createDataSource(), chunkSize)
    }
}
