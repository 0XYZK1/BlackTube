package com.nexus.videoclient.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

object Mp3Transcoder {

    fun transcode(
        input: File,
        output: File,
        bitrateKbps: Int,
        title: String,
        artist: String,
        onProgress: (Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var lame: AndroidLame? = null

        try {
            extractor.setDataSource(input.absolutePath)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                throw IllegalStateException("No audio track in source file")
            }
            extractor.selectTrack(trackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Unknown audio format")
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPct = -1
            val outStream = FileOutputStream(output)

            fun ensureEncoder(): AndroidLame {
                val existing = lame
                if (existing != null) return existing
                val created = LameBuilder()
                    .setInSampleRate(sampleRate)
                    .setOutChannels(if (channels <= 1) 1 else 2)
                    .setOutBitrate(bitrateKbps)
                    .setQuality(2)
                    .setMode(
                        if (channels <= 1) LameBuilder.Mode.MONO
                        else LameBuilder.Mode.JSTEREO
                    )
                    .setVbrMode(LameBuilder.VbrMode.VBR_OFF)
                    .setId3tagTitle(title)
                    .setId3tagArtist(artist)
                    .build()
                lame = created
                return created
            }

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buffer = codec.getInputBuffer(inIdx)
                            val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    size,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        ensureEncoder()
                    } else if (outIdx >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIdx)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val pcmEncoding = if (
                                    codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ) {
                                    codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }

                                val samples: ShortArray = if (
                                    pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
                                ) {
                                    val floats = FloatArray(info.size / 4)
                                    buffer.order(ByteOrder.nativeOrder())
                                        .asFloatBuffer().get(floats)
                                    ShortArray(floats.size) { i ->
                                        (floats[i].coerceIn(-1f, 1f) * 32767f)
                                            .toInt().toShort()
                                    }
                                } else {
                                    val shorts = ShortArray(info.size / 2)
                                    buffer.order(ByteOrder.nativeOrder())
                                        .asShortBuffer().get(shorts)
                                    shorts
                                }

                                val encoder = ensureEncoder()
                                writeSamples(encoder, samples, channels, outStream)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (durationUs > 0) {
                            val pct = ((info.presentationTimeUs * 100L) / durationUs)
                                .coerceIn(0L, 100L).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }

                val encoder = ensureEncoder()
                val flushBuffer = ByteArray(7200)
                val flushed = encoder.flush(flushBuffer)
                if (flushed > 0) outStream.write(flushBuffer, 0, flushed)
                onProgress(100)
            } finally {
                runCatching { outStream.close() }
            }
        } finally {
            runCatching { lame?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeSamples(
        encoder: AndroidLame,
        pcm: ShortArray,
        channels: Int,
        out: FileOutputStream
    ) {
        if (pcm.isEmpty() || channels <= 0) return

        val interleaved: ShortArray
        val frames: Int
        if (channels == 1) {
            interleaved = pcm
            frames = pcm.size
        } else if (channels == 2) {
            interleaved = pcm
            frames = pcm.size / 2
        } else {
            frames = pcm.size / channels
            interleaved = ShortArray(frames * 2)
            for (f in 0 until frames) {
                interleaved[f * 2] = pcm[f * channels]
                interleaved[f * 2 + 1] = pcm[f * channels + 1]
            }
        }
        if (frames <= 0) return

        val mp3Buffer = ByteArray((1.25 * frames + 7200).toInt().coerceAtLeast(7200))
        val written = encoder.encodeBufferInterLeaved(interleaved, frames, mp3Buffer)
        if (written > 0) out.write(mp3Buffer, 0, written)
    }
}
