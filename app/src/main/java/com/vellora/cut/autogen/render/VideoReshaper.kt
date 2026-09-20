package com.vellora.cut.autogen.render

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * YouTube decides Short vs Long ENTIRELY from the video's own aspect ratio
 * + duration — there is no API parameter to override it (see
 * ShortsMetadataScreen's predictYouTubeClassification doc comment). So to
 * let a person genuinely choose Short or Long, this actually reshapes the
 * video with real FFmpeg (center-crop to the target aspect ratio, and — for
 * Short — trims to under 3 minutes) rather than just relabeling it.
 */
object VideoReshaper {

    enum class Target { SHORT, LONG }

    /** Returns the original [sourceFile] unchanged if it already matches
     * [target]'s shape/duration requirements — reshaping only runs when
     * actually needed, since re-encoding a video is real, non-trivial work. */
    suspend fun reshapeIfNeeded(context: Context, sourceFile: File, target: Target): File? =
        suspendCancellableCoroutine { cont ->
            val (width, height, durationMs) = probe(sourceFile) ?: run {
                cont.resume(sourceFile) // couldn't read it — upload as-is rather than fail
                return@suspendCancellableCoroutine
            }
            val isVerticalOrSquare = height >= width
            val needsCrop = when (target) {
                Target.SHORT -> !isVerticalOrSquare
                Target.LONG -> isVerticalOrSquare
            }
            val needsTrim = target == Target.SHORT && durationMs > 175_000L

            if (!needsCrop && !needsTrim) {
                cont.resume(sourceFile)
                return@suspendCancellableCoroutine
            }

            val workDir = File(context.cacheDir, "shorts_reshape").apply { mkdirs() }
            val outputFile = File(workDir, "reshaped_${System.currentTimeMillis()}.mp4")

            val cropFilter = when {
                !needsCrop -> null
                target == Target.SHORT -> "crop=ih*9/16:ih" // landscape -> vertical, centered
                else -> "crop=iw:iw*9/16" // vertical/square -> landscape, centered
            }

            val args = mutableListOf("-y", "-i", sourceFile.absolutePath)
            if (cropFilter != null) {
                args += listOf("-vf", cropFilter)
            }
            if (needsTrim) {
                args += listOf("-t", "175")
            }
            args += listOf(
                "-c:v", "h264_mediacodec",
                "-b:v", "6M",
                "-c:a", "copy",
                "-movflags", "+faststart",
                outputFile.absolutePath
            )

            FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { session ->
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
                    cont.resume(outputFile)
                } else {
                    cont.resume(null)
                }
            }
        }

    private data class Probe(val width: Int, val height: Int, val durationMs: Long)
    private operator fun Probe.component1() = width
    private operator fun Probe.component2() = height
    private operator fun Probe.component3() = durationMs

    private fun probe(file: File): Probe? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val widthRaw = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val heightRaw = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val (w, h) = if (rotation == 90 || rotation == 270) heightRaw to widthRaw else widthRaw to heightRaw
            Probe(w, h, durationMs)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
    }
}
