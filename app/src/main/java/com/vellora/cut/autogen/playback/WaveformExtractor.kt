package com.vellora.cut.autogen.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes an audio file to PCM once (MediaExtractor + MediaCodec — plain
 * Android SDK, no native code) and reduces it to a small array of real
 * peak-amplitude values, one per time-bucket spread evenly across the
 * file's duration. This is actual decoded audio data, not a synthetic
 * placeholder pattern.
 */
class WaveformExtractor(private val context: Context) {

    suspend fun extract(uri: Uri, bucketCount: Int = 400): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex == -1 || format == null) return@withContext FloatArray(0)

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val buckets = FloatArray(bucketCount)
            val bucketDurationUs = if (durationUs > 0) durationUs / bucketCount else 0L

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0 && bucketDurationUs > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        var peak = 0f
                        while (outputBuffer.remaining() >= 2) {
                            val sample = outputBuffer.short.toInt()
                            peak = max(peak, abs(sample) / 32768f)
                        }

                        val bucketIndex = (bufferInfo.presentationTimeUs / bucketDurationUs)
                            .toInt().coerceIn(0, bucketCount - 1)
                        buckets[bucketIndex] = max(buckets[bucketIndex], peak)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }

            codec.stop()
            codec.release()
            buckets
        } catch (e: Exception) {
            FloatArray(0)
        } finally {
            extractor.release()
        }
    }
}
