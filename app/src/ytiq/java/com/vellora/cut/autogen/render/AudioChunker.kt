package com.vellora.cut.autogen.render

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * YTIQ version (no FFmpeg): splits an .m4a (AAC) file into ~60-second pieces with Android's own
 * MediaExtractor + MediaMuxer (the audio is copied, not re-encoded). Whisper needs long audio in
 * chunks. If anything goes wrong the whole file is returned as a single chunk, same as before.
 * (VELLORA CUT keeps its own FFmpeg version in src/cut.)
 */
object AudioChunker {

    /** Exposed so callers (chunk-timestamp offsetting) use the exact same value this split with. */
    const val CHUNK_SECONDS = 60

    suspend fun split(context: Context, audioFile: File): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "whisper_chunks/${System.currentTimeMillis()}").apply { mkdirs() }
        val outputs = mutableListOf<File>()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext listOf(audioFile)
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val chunkUs = CHUNK_SECONDS * 1_000_000L
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var currentIndex = -1
            var outTrack = -1

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                val index = (timeUs / chunkUs).toInt()
                if (muxer == null || index != currentIndex) {
                    if (started) {
                        muxer?.stop()
                        started = false
                    }
                    muxer?.release()
                    val file = File(dir, "chunk_%03d.m4a".format(index))
                    val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = m
                    outTrack = m.addTrack(format)
                    m.start()
                    started = true
                    outputs += file
                    currentIndex = index
                }
                info.set(0, size, timeUs - currentIndex * chunkUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer?.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            if (started) {
                muxer?.stop()
                started = false
            }
            if (outputs.isEmpty()) listOf(audioFile) else outputs.toList()
        } catch (e: Exception) {
            listOf(audioFile) // splitting failed — send the whole file rather than lose the transcript
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
