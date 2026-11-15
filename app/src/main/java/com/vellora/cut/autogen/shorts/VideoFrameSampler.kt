package com.vellora.cut.autogen.shorts

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Grabs a few evenly spaced frames from a video as small JPEGs, so an AI can SEE a video that has no speech. */
object VideoFrameSampler {

    suspend fun sample(context: Context, videoUri: String, count: Int = 6, maxSide: Int = 640): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val out = ArrayList<ByteArray>()
            try {
                val uri = Uri.parse(videoUri)
                if (uri.scheme == null || uri.scheme == "file") {
                    retriever.setDataSource(uri.path ?: videoUri)
                } else {
                    retriever.setDataSource(context, uri)
                }
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durationMs <= 0L) return@withContext out
                for (i in 0 until count) {
                    // Spread across the video, avoiding the very first/last instant (often black).
                    val timeUs = durationMs * 1000L * (2 * i + 1) / (2 * count)
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val scale = maxSide.toFloat() / maxOf(frame.width, frame.height)
                    val bmp = if (scale < 1f) {
                        Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
                    } else frame
                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    out.add(stream.toByteArray())
                    if (bmp !== frame) frame.recycle()
                    bmp.recycle()
                }
            } catch (e: Exception) {
                // Best effort — fewer frames (or none) is fine.
            } finally {
                runCatching { retriever.release() }
            }
            out
        }
}
