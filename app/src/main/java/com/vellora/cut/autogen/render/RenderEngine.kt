package com.vellora.cut.autogen.render

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.data.MotionEffect
import com.vellora.cut.autogen.data.TransitionType
import com.vellora.cut.autogen.timeline.TimelineImage
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Result of a finished (successful or failed) render. */
sealed class RenderResult {
    data class Success(val outputFile: File) : RenderResult()
    data class Failed(val message: String, val ffmpegLog: String) : RenderResult()
}

/**
 * Turns a computed [TimelineImage] sequence + the project's voice-over into a single mp4,
 * with a per-image Ken Burns style motion effect and a crossfade/slide transition between
 * consecutive images (so the output looks like a video, not a static slideshow).
 *
 * Uses FFmpegKit's "min" (LGPL, no GPL libs) package + Android's built-in hardware
 * H.264 encoder (MediaCodec) — no GPL/x264 dependency, safe for Play Store distribution.
 *
 * NOTE: this filter_complex chain (zoompan + xfade) is intricate and has not been
 * verified on-device by the assistant that wrote it. Test on a small (2-3 image)
 * project before running full batches.
 */
object RenderEngine {

    private const val FPS = 30
    private const val TRANSITION_DURATION_SEC = 0.7

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

        if (timeline.isEmpty()) {
            onComplete(RenderResult.Failed("Timeline mein koi image nahi", ""))
            return null
        }

        val (width, height) = resolutionToSize(project.resolution)

        val arguments = try {
            buildArguments(timeline, voiceOverFile, width, height, project.transitionType, project.motionEffect, outputFile)
        } catch (e: Exception) {
            onComplete(RenderResult.Failed("Render command banate hue error: ${e.message}", ""))
            return null
        }

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

    // ---- command building ----------------------------------------------------

    private fun buildArguments(
        timeline: List<TimelineImage>,
        voiceOverFile: File,
        width: Int,
        height: Int,
        transitionType: String,
        motionEffect: String,
        outputFile: File
    ): Array<String> {
        val n = timeline.size
        val durationsSec = timeline.map { it.durationMs / 1000.0 }

        // Each image is fed as its own looped-still input. Every image except the
        // last needs TRANSITION_DURATION_SEC of extra source material so its tail
        // can overlap with the next image during the crossfade/slide — that overlap
        // is what keeps the final output length equal to sum(durationsSec) despite
        // the transitions "eating into" the shown time.
        val inputLengths = durationsSec.mapIndexed { index, d ->
            if (n > 1 && index < n - 1) d + TRANSITION_DURATION_SEC else d
        }

        val args = mutableListOf<String>("-y")
        for (i in 0 until n) {
            val path = requireNotNull(timeline[i].prompt.imagePath) {
                "Image #${i + 1} (${timeline[i].prompt.label}) ki file path missing hai"
            }
            args += listOf("-loop", "1", "-t", "%.3f".format(inputLengths[i]), "-i", path)
        }
        args += listOf("-i", voiceOverFile.absolutePath)
        val voiceOverInputIndex = n

        val filterComplex = buildFilterComplex(
            n, inputLengths, durationsSec, width, height, transitionType, motionEffect
        )

        args += listOf(
            "-filter_complex", filterComplex.script,
            "-map", "[${filterComplex.finalVideoLabel}]",
            "-map", "$voiceOverInputIndex:a:0",
            "-r", FPS.toString(),
            "-c:v", "h264_mediacodec",
            "-b:v", "6M",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            "-shortest",
            "-movflags", "+faststart",
            outputFile.absolutePath
        )
        return args.toTypedArray()
    }

    private data class FilterComplexResult(val script: String, val finalVideoLabel: String)

    private fun buildFilterComplex(
        n: Int,
        inputLengths: List<Double>,
        durationsSec: List<Double>,
        width: Int,
        height: Int,
        transitionType: String,
        motionEffect: String
    ): FilterComplexResult {
        val parts = mutableListOf<String>()

        // Stage 1: per-image upscale-and-cover to a canvas 2x the target size, then
        // zoompan for the motion effect, cropped down to the final WxH. Upscaling
        // first gives zoompan room to move/zoom without visible edges.
        for (i in 0 until n) {
            val frames = max(2, (inputLengths[i] * FPS).roundToInt())
            val zoompan = when (motionEffect) {
                MotionEffect.PAN ->
                    "zoompan=z=1.15:d=$frames:" +
                        "x='(iw-iw/zoom)*on/${frames - 1}':y='ih/2-(ih/zoom/2)':" +
                        "s=${width}x${height}:fps=$FPS"
                else -> // ZOOM_IN (default)
                    "zoompan=z='min(zoom+0.0012,1.3)':d=$frames:" +
                        "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                        "s=${width}x${height}:fps=$FPS"
            }
            parts += "[$i:v]scale=${width * 2}:${height * 2}:force_original_aspect_ratio=increase," +
                "crop=${width * 2}:${height * 2},$zoompan,setsar=1[seg$i]"
        }

        if (n == 1) {
            return FilterComplexResult(parts.joinToString(";"), "seg0")
        }

        // Stage 2: chain xfade transitions. offset for the k-th transition (1-indexed,
        // connecting seg(k-1) and seg(k)) is the cumulative sum of the first k images'
        // *intended* on-screen durations — this is what keeps the final length matching
        // the voice-over regardless of how many transitions are chained.
        val xfadeName = if (transitionType == TransitionType.SLIDE) "slideleft" else "fade"
        var previousLabel = "seg0"
        var cumulative = 0.0
        for (i in 1 until n) {
            cumulative += durationsSec[i - 1]
            val offset = max(0.0, cumulative - TRANSITION_DURATION_SEC)
            val outLabel = if (i == n - 1) "vout" else "x$i"
            parts += "[$previousLabel][seg$i]xfade=transition=$xfadeName:" +
                "duration=%.3f:offset=%.3f".format(TRANSITION_DURATION_SEC, offset) +
                "[$outLabel]"
            previousLabel = outLabel
        }

        return FilterComplexResult(parts.joinToString(";"), previousLabel)
    }

    // ---- helpers ---------------------------------------------------------------

    private fun resolveVoiceOverFile(context: Context, voiceOverUri: String?, workDir: File): File {
        requireNotNull(voiceOverUri) { "Project mein voice-over set nahi hai" }
        val uri = Uri.parse(voiceOverUri)

        if (uri.scheme == null || uri.scheme == "file") {
            return File(uri.path ?: voiceOverUri)
        }

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

    private fun resolutionToSize(resolution: String): Pair<Int, Int> = when (resolution) {
        "tiktok" -> 1080 to 1920 // TikTok / Reels / Shorts — portrait 9:16
        "720p" -> 1280 to 720 // legacy value, kept for old saved projects
        else -> 1920 to 1080 // "youtube" (default) / legacy "1080p" — landscape 16:9
    }
}
