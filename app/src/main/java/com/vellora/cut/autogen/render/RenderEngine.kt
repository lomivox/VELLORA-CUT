package com.vellora.cut.autogen.render

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.timeline.TimelineImage
import java.io.File
import java.io.FileOutputStream

/** Result of a finished (successful or failed) render. */
sealed class RenderResult {
    data class Success(val outputFile: File) : RenderResult()
    data class Failed(val message: String, val ffmpegLog: String) : RenderResult()
}

/**
 * Turns a computed [TimelineImage] sequence + the project's voice-over into a single mp4.
 *
 * Uses FFmpegKit's "min" (LGPL, no GPL libs) package + Android's built-in hardware
 * H.264 encoder (MediaCodec) — no GPL/x264 dependency, safe for Play Store distribution.
 */
object RenderEngine {

    /**
     * Starts an async render. [onProgress] is called repeatedly with 0f..1f.
     * [onComplete] is called exactly once, on the main thread, when finished.
     * Returns the FFmpegSession so the caller can cancel it if needed.
     */
    fun render(
        context: Context,
        project: AutoGenProjectEntity,
        timeline: List<TimelineImage>,
        totalDurationMs: Long,
        onProgress: (Float) -> Unit,
        onComplete: (RenderResult) -> Unit
    ): FFmpegSession? {
        val outputDir = File(context.getExternalFilesDir(null), "renders").apply { mkdirs() }
        val workDir = File(context.cacheDir, "render_work_${project.id}").apply {
            deleteRecursively()
            mkdirs()
        }

        val outputFile = File(outputDir, "episode_${project.id}_${System.currentTimeMillis()}.mp4")

        val voiceOverFile: File
        try {
            voiceOverFile = resolveVoiceOverFile(context, project.voiceOverUri, workDir)
        } catch (e: Exception) {
            onComplete(RenderResult.Failed("Voice-over file open nahi ho saka: ${e.message}", ""))
            return null
        }

        val concatFile = try {
            writeConcatFile(timeline, workDir)
        } catch (e: Exception) {
            onComplete(RenderResult.Failed("Timeline images file nahi mili: ${e.message}", ""))
            return null
        }

        val (width, height) = resolutionToSize(project.resolution)

        // scale to fit + pad to exact target size so every image (any aspect ratio)
        // fills the frame without distortion; h264_mediacodec = Android hardware encoder.
        val vf = "scale=$width:$height:force_original_aspect_ratio=decrease," +
            "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,format=yuv420p"

        // Passed as an argument array (not a single command string) so file paths
        // never need manual shell-quoting/escaping.
        val arguments = arrayOf(
            "-y",
            "-f", "concat", "-safe", "0", "-i", concatFile.absolutePath,
            "-i", voiceOverFile.absolutePath,
            "-vf", vf,
            "-r", "30",
            "-c:v", "h264_mediacodec",
            "-b:v", "6M",
            "-c:a", "aac", "-b:a", "192k",
            "-shortest",
            "-movflags", "+faststart",
            outputFile.absolutePath
        )

        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { completedSession ->
                if (ReturnCode.isSuccess(completedSession.returnCode)) {
                    onComplete(RenderResult.Success(outputFile))
                } else {
                    val log = completedSession.allLogsAsString ?: ""
                    onComplete(
                        RenderResult.Failed(
                            "FFmpeg render fail hua (code ${completedSession.returnCode})",
                            log.takeLast(4000)
                        )
                    )
                }
            },
            { /* per-line log callback — intentionally unused, allLogsAsString covers failures */ },
            { stats: Statistics ->
                if (totalDurationMs > 0) {
                    val fraction = (stats.time.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                    onProgress(fraction)
                }
            }
        )
        return session
    }

    /** Cancels an in-progress render session. */
    fun cancel(session: FFmpegSession) {
        FFmpegKit.cancel(session.sessionId)
    }

    private fun resolveVoiceOverFile(context: Context, voiceOverUri: String?, workDir: File): File {
        requireNotNull(voiceOverUri) { "Project mein voice-over set nahi hai" }
        val uri = Uri.parse(voiceOverUri)

        // file:// or a plain path — already usable directly.
        if (uri.scheme == null || uri.scheme == "file") {
            return File(uri.path ?: voiceOverUri)
        }

        // content:// (SAF picker result) — copy to a real file first, ffmpeg
        // native code can't read content:// URIs directly.
        val extension = guessAudioExtension(context, uri)
        val dest = File(workDir, "voiceover.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Voice-over URI khol nahi saka" }
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun guessAudioExtension(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: return "m4a"
        return when {
            type.contains("mpeg") -> "mp3"
            type.contains("wav") -> "wav"
            type.contains("ogg") -> "ogg"
            else -> "m4a"
        }
    }

    /** FFmpeg concat-demuxer file: `file '<path>'` + `duration <sec>` per image. */
    private fun writeConcatFile(timeline: List<TimelineImage>, workDir: File): File {
        require(timeline.isNotEmpty()) { "Timeline mein koi image nahi" }
        val file = File(workDir, "concat.txt")
        file.bufferedWriter().use { writer ->
            for (item in timeline) {
                val path = requireNotNull(item.prompt.imagePath) {
                    "Prompt '${item.prompt.label}' ki image path missing hai"
                }
                val escaped = path.replace("'", "'\\''")
                val durationSec = item.durationMs / 1000.0
                writer.write("file '$escaped'\n")
                writer.write("duration ${"%.3f".format(durationSec)}\n")
            }
            // concat demuxer quirk: the last "duration" is only honored if the
            // final file is repeated once more without a duration line.
            val lastPath = requireNotNull(timeline.last().prompt.imagePath).replace("'", "'\\''")
            writer.write("file '$lastPath'\n")
        }
        return file
    }

    private fun resolutionToSize(resolution: String): Pair<Int, Int> = when (resolution) {
        "720p" -> 1280 to 720
        else -> 1920 to 1080 // default / "1080p"
    }
}
