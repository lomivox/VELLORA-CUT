package com.vellora.cut.autogen.render

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * YTIQ version (no FFmpeg): pulls the audio track out of a picked video with Android's own
 * MediaExtractor + MediaMuxer and saves it as .m4a. The AAC audio is COPIED, not re-encoded,
 * so it is fast and needs no extra library. Videos whose audio is not AAC (rare for phone
 * videos) return null, same as "no audio found".
 * (VELLORA CUT keeps its own FFmpeg version in src/cut.)
 */
object VideoAudioExtractor {

    suspend fun extract(context: Context, videoUri: String): File? = withContext(Dispatchers.IO) {
        val outputFile = File(File(context.cacheDir, "shorts_audio").apply { mkdirs() }, "extracted_${System.currentTimeMillis()}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val uri = Uri.parse(videoUri)
            if (uri.scheme == null || uri.scheme == "file") {
                extractor.setDataSource(uri.path ?: videoUri)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            val format = extractor.getTrackFormat(trackIndex)
            if (format.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") return@withContext null

            extractor.selectTrack(trackIndex)
            val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outTrack = m.addTrack(format)
            m.start()
            started = true

            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            m.stop()
            started = false
            outputFile.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            runCatching { outputFile.delete() }
            null
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
