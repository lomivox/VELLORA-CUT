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
    // Not private: TimelineScreen's live preview uses the exact same value
    // so the on-screen transition timing matches what RenderEngine actually
    // produces.
    const val TRANSITION_DURATION_SEC = 0.7

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

        val voiceOverFile: File?
        try {
            // Prefer the noise-reduced/volume-adjusted copy (real FFmpeg
            // processing, see AudioProcessor) over the raw picked file,
            // when one exists — so the export actually has the effect the
            // person heard in Preview, not the untouched original.
            // Audio is OPTIONAL: a project with no voice-over renders a
            // real (silent) mp4 from the images alone instead of failing.
            val processedPath = project.processedAudioPath
            voiceOverFile = if (processedPath != null && File(processedPath).exists()) {
                File(processedPath)
            } else if (project.voiceOverUri != null) {
                resolveVoiceOverFile(context, project.voiceOverUri, workDir)
            } else {
                null
            }
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
        voiceOverFile: File?,
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
        val voiceOverInputIndex = n
        if (voiceOverFile != null) {
            args += listOf("-i", voiceOverFile.absolutePath)
        }

        val filterComplex = buildFilterComplex(
            n, inputLengths, durationsSec, width, height, transitionType, motionEffect
        )

        args += listOf("-filter_complex", filterComplex.script, "-map", "[${filterComplex.finalVideoLabel}]")
        if (voiceOverFile != null) {
            args += listOf("-map", "$voiceOverInputIndex:a:0", "-c:a", "aac", "-b:a", "192k", "-shortest")
        }
        args += listOf(
            "-r", FPS.toString(),
            "-c:v", "h264_mediacodec",
            "-b:v", "6M",
            "-pix_fmt", "yuv420p",
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
        // first gives zoompan room to move/zoom without visible edges. STATIC skips
        // zoompan entirely (no motion requested — cheaper and avoids any drift).
        for (i in 0 until n) {
            val frames = max(2, (inputLengths[i] * FPS).roundToInt())
            if (motionEffect == MotionEffect.STATIC) {
                parts += "[$i:v]scale=${width}:${height}:force_original_aspect_ratio=increase," +
                    "crop=${width}:${height},setsar=1[seg$i]"
                continue
            }
            val zoompan = zoompanFor(motionEffect, frames, width, height)
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
        val xfadeName = xfadeNameFor(transitionType)
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

    // ---- motion / transition mapping ---------------------------------------------

    /** Builds the `zoompan=...` filter string for one of [MotionEffect]'s 10 values
     * (STATIC is handled separately by the caller, before this is ever called).
     * All are real zoompan z/x/y expressions — nothing here is simulated. */
    private fun zoompanFor(motionEffect: String, frames: Int, width: Int, height: Int): String {
        val centerX = "iw/2-(iw/zoom/2)"
        val centerY = "ih/2-(ih/zoom/2)"
        val panRightX = "(iw-iw/zoom)*on/${frames - 1}"
        val panLeftX = "(iw-iw/zoom)*(1-on/${frames - 1})"
        val panDownY = "(ih-ih/zoom)*on/${frames - 1}"
        val panUpY = "(ih-ih/zoom)*(1-on/${frames - 1})"
        val zoomInZ = "min(zoom+0.0012,1.3)"
        val zoomOutZ = "if(eq(on,0),1.3,max(zoom-0.0012,1.0))"
        val fixedZoomZ = "1.15"

        val (z, x, y) = when (motionEffect) {
            MotionEffect.ZOOM_OUT -> Triple(zoomOutZ, centerX, centerY)
            MotionEffect.PAN -> Triple(fixedZoomZ, panRightX, centerY)
            MotionEffect.PAN_LEFT -> Triple(fixedZoomZ, panLeftX, centerY)
            MotionEffect.PAN_UP -> Triple(fixedZoomZ, centerX, panUpY)
            MotionEffect.PAN_DOWN -> Triple(fixedZoomZ, centerX, panDownY)
            MotionEffect.ZOOM_IN_PAN_LEFT -> Triple(zoomInZ, panLeftX, centerY)
            MotionEffect.ZOOM_IN_PAN_RIGHT -> Triple(zoomInZ, panRightX, centerY)
            MotionEffect.ZOOM_OUT_PAN -> Triple(zoomOutZ, panRightX, centerY)
            // Fast burst to 1.15x in the first ~15% of frames, then holds flat —
            // the "punch-in" look. threshold recomputed each frame from 'on'
            // (current frame index) and the known total 'frames' for this clip.
            MotionEffect.PUNCH_ZOOM -> Triple(
                "if(lte(on,${(frames * 0.15).roundToInt()}),min(zoom+0.02,1.15),1.15)",
                centerX, centerY
            )
            // Small continuous sinusoidal jitter around the centered crop —
            // an authentic handheld-camera feel, not a hard cut.
            MotionEffect.SHAKE -> Triple(
                "1.08",
                "$centerX+3*sin(on/2)",
                "$centerY+3*cos(on/3)"
            )
            MotionEffect.DIAGONAL -> Triple(zoomInZ, panLeftX, panUpY)
            // Same shape as ZOOM_IN but a gentler ceiling (1.15 instead of
            // 1.3) — understated, doesn't scream "zoom effect".
            MotionEffect.CINEMATIC_ZOOM -> Triple("min(zoom+0.0006,1.15)", centerX, centerY)
            // Starts tight (1.5x) and pulls back to 1.0 — a dramatic reveal.
            MotionEffect.DRAMATIC_REVEAL -> Triple(
                "if(eq(on,0),1.5,max(zoom-0.002,1.0))", centerX, centerY
            )
            else -> Triple(zoomInZ, centerX, centerY) // ZOOM_IN (default)
        }
        return "zoompan=z='$z':d=$frames:x='$x':y='$y':s=${width}x${height}:fps=$FPS"
    }

    /** Maps a [TransitionType] value to the exact `xfade` transition name FFmpeg
     * expects. All 10 are from xfade's original built-in transition set (available
     * since the filter was first added), so this works on every FFmpegKit build —
     * unlike a couple of newer xfade transitions (e.g. "zoomin") that only exist on
     * very recent FFmpeg builds and were deliberately left out to avoid that risk. */
    private fun xfadeNameFor(transitionType: String): String = when (transitionType) {
        TransitionType.SLIDE -> "slideleft"
        TransitionType.SLIDE_RIGHT -> "slideright"
        TransitionType.SLIDE_UP -> "slideup"
        TransitionType.SLIDE_DOWN -> "slidedown"
        TransitionType.WIPE_LEFT -> "wipeleft"
        TransitionType.WIPE_RIGHT -> "wiperight"
        TransitionType.CIRCLE_OPEN -> "circleopen"
        TransitionType.DISSOLVE -> "dissolve"
        TransitionType.PIXELIZE -> "pixelize"
        TransitionType.FADE_BLACK -> "fadeblack"
        TransitionType.FADE_WHITE -> "fadewhite"
        TransitionType.RADIAL -> "radial"
        TransitionType.BLUR -> "hblur"
        TransitionType.SMOOTH_LEFT -> "smoothleft"
        TransitionType.SMOOTH_RIGHT -> "smoothright"
        TransitionType.CIRCLE_CLOSE -> "circleclose"
        TransitionType.SQUEEZE -> "squeezeh"
        TransitionType.DIAGONAL -> "diagtl"
        TransitionType.DISTANCE -> "distance"
        else -> "fade" // CROSSFADE (default)
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
