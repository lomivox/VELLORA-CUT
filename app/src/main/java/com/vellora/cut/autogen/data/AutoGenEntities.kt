package com.vellora.cut.autogen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Status values used by [AutoGenProjectEntity.status]. */
object AutoGenProjectStatus {
    const val DRAFT = "draft"
    const val GENERATING = "generating"
    const val READY = "ready"
    const val RENDERED = "rendered"
}

/** Status values used by [PromptEntity.status]. */
object PromptStatus {
    const val PENDING = "pending"
    const val GENERATING = "generating"
    const val DONE = "done"
    const val FAILED = "failed"
}

@Entity(tableName = "autogen_projects")
data class AutoGenProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val voiceOverUri: String?,
    val voiceOverDurationMs: Long,
    val imageDurationSec: Int,
    val resolution: String,
    val status: String,
    val createdAt: Long,
    /** How image durations reconcile against the voice-over length: "scale" or "hold_last". */
    val timelineMode: String = TimelineMode.SCALE,
    /** Local file path of the last successful render (Phase F), null until rendered once. */
    val renderedFilePath: String? = null,
    /** Transition style between consecutive images — see [TransitionType]. */
    val transitionType: String = TransitionType.CROSSFADE,
    /** Per-image motion effect — see [MotionEffect]. */
    val motionEffect: String = MotionEffect.ZOOM_IN,
    /** Real FFmpeg noise-reduction strength on the voice-over, 0-100 (maps
     * to afftdn's nr parameter, 0-97dB). 0 = no noise reduction applied. */
    val noiseReductionPercent: Int = 0,
    /** Real FFmpeg volume gain on the voice-over, 0-500 (100 = original
     * volume, unchanged). Applied via the `volume=` audio filter. */
    val volumePercent: Int = 100,
    /** Local path to the FFmpeg-processed voice-over (noise-reduction +
     * volume already baked in) — regenerated whenever either setting
     * changes. Null until first processed; both the live PreviewPlayer and
     * RenderEngine prefer this over the raw picked file when present. */
    val processedAudioPath: String? = null,
    /** Real Whisper-transcribed captions, stored as a JSON array of
     * {text,startMs,endMs} — see [CaptionSegment]. Null until generated. */
    val captionsJson: String? = null,
    /** Whether captions are burned into the final render / shown live in
     * Preview. Generating captions does NOT turn this on automatically —
     * the person reviews the transcription first, then enables it. */
    val captionsEnabled: Boolean = false
)

/** One real Whisper-transcribed line, with its exact spoken timing. */
data class CaptionSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/** JSON (de)serialization for [AutoGenProjectEntity.captionsJson]. */
object CaptionSegments {
    fun toJson(segments: List<CaptionSegment>): String {
        val array = org.json.JSONArray()
        segments.forEach { seg ->
            array.put(
                org.json.JSONObject().apply {
                    put("text", seg.text)
                    put("startMs", seg.startMs)
                    put("endMs", seg.endMs)
                }
            )
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<CaptionSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CaptionSegment(
                    text = obj.optString("text"),
                    startMs = obj.optLong("startMs"),
                    endMs = obj.optLong("endMs")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Values for [AutoGenProjectEntity.timelineMode]. */
object TimelineMode {
    /** Every image's duration is stretched/shrunk equally so total = voice-over length. */
    const val SCALE = "scale"
    /** Every image keeps its set duration; only the last image absorbs the remainder. */
    const val HOLD_LAST = "hold_last"
}

/** Values for [AutoGenProjectEntity.transitionType] — how one image hands off to the next. */
object TransitionType {
    /** One image fades out while the next fades in, overlapping. */
    const val CROSSFADE = "crossfade"
    /** The next image slides in from the right, pushing the current one out (kept under its
     * original name for backward compatibility with projects saved before the other 8 were added). */
    const val SLIDE = "slide"
    const val SLIDE_RIGHT = "slide_right"
    const val SLIDE_UP = "slide_up"
    const val SLIDE_DOWN = "slide_down"
    const val WIPE_LEFT = "wipe_left"
    const val WIPE_RIGHT = "wipe_right"
    const val CIRCLE_OPEN = "circle_open"
    const val DISSOLVE = "dissolve"
    const val PIXELIZE = "pixelize"
    // ---- batch 2: trending/professional (CapCut-style) additions ----
    const val FADE_BLACK = "fade_black"
    const val FADE_WHITE = "fade_white"
    const val RADIAL = "radial"
    const val BLUR = "blur"
    const val SMOOTH_LEFT = "smooth_left"
    const val SMOOTH_RIGHT = "smooth_right"
    const val CIRCLE_CLOSE = "circle_close"
    const val SQUEEZE = "squeeze"
    const val DIAGONAL = "diagonal"
    const val DISTANCE = "distance"

    /** Every option, in the order they should be offered — all 20 map to
     * FFmpeg's built-in `xfade` transition names (see RenderEngine), so
     * every one of these is a REAL transition, not a fake/simulated one. */
    val ALL = listOf(
        CROSSFADE, SLIDE, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN,
        WIPE_LEFT, WIPE_RIGHT, CIRCLE_OPEN, DISSOLVE, PIXELIZE,
        FADE_BLACK, FADE_WHITE, RADIAL, BLUR, SMOOTH_LEFT,
        SMOOTH_RIGHT, CIRCLE_CLOSE, SQUEEZE, DIAGONAL, DISTANCE
    )

    fun label(value: String): String = when (value) {
        CROSSFADE -> "Crossfade"
        SLIDE -> "Slide Left"
        SLIDE_RIGHT -> "Slide Right"
        SLIDE_UP -> "Slide Up"
        SLIDE_DOWN -> "Slide Down"
        WIPE_LEFT -> "Wipe Left"
        WIPE_RIGHT -> "Wipe Right"
        CIRCLE_OPEN -> "Circle Open"
        DISSOLVE -> "Dissolve"
        PIXELIZE -> "Pixelize"
        FADE_BLACK -> "Fade to Black"
        FADE_WHITE -> "Fade to White"
        RADIAL -> "Radial"
        BLUR -> "Blur"
        SMOOTH_LEFT -> "Smooth Left"
        SMOOTH_RIGHT -> "Smooth Right"
        CIRCLE_CLOSE -> "Circle Close"
        SQUEEZE -> "Squeeze"
        DIAGONAL -> "Diagonal"
        DISTANCE -> "Distance"
        else -> value
    }
}

/** Values for [AutoGenProjectEntity.motionEffect] — subtle movement applied to every still image. */
object MotionEffect {
    /** Slow, continuous zoom-in over the image's on-screen duration (classic Ken Burns). */
    const val ZOOM_IN = "zoom_in"
    /** Fixed slight zoom, camera pans left-to-right across the image (kept under its original
     * name for backward compatibility with projects saved before the other 8 were added). */
    const val PAN = "pan"
    const val ZOOM_OUT = "zoom_out"
    const val PAN_LEFT = "pan_left"
    const val PAN_UP = "pan_up"
    const val PAN_DOWN = "pan_down"
    const val ZOOM_IN_PAN_LEFT = "zoom_in_pan_left"
    const val ZOOM_IN_PAN_RIGHT = "zoom_in_pan_right"
    const val ZOOM_OUT_PAN = "zoom_out_pan"
    /** No motion at all — the image just holds still. */
    const val STATIC = "static"
    // ---- batch 2: trending/professional (CapCut-style) additions ----
    /** Fast zoom burst in the first ~15% of the clip, then holds — the
     * "punch-in" look common in fast-cut Reels/TikTok edits. */
    const val PUNCH_ZOOM = "punch_zoom"
    /** Tiny continuous jitter (a few px) — an authentic handheld-camera feel. */
    const val SHAKE = "shake"
    /** Zoom-in while panning both diagonally (up + left) at once. */
    const val DIAGONAL = "diagonal"
    /** A gentler zoom range (1.0→1.15 instead of 1.3) — understated,
     * corporate/cinematic look rather than an obvious "zoom effect". */
    const val CINEMATIC_ZOOM = "cinematic_zoom"
    /** Starts tightly zoomed in (1.5x) and pulls back to reveal the full
     * image — a dramatic "reveal" opener. */
    const val DRAMATIC_REVEAL = "dramatic_reveal"

    /** Every option, in the order they should be offered — all 15 are REAL
     * FFmpeg `zoompan` (or plain scale/crop for STATIC) formulas, see
     * RenderEngine, matched live in Preview by applyLiveMotionAndTransition. */
    val ALL = listOf(
        ZOOM_IN, ZOOM_OUT, PAN, PAN_LEFT, PAN_UP, PAN_DOWN,
        ZOOM_IN_PAN_LEFT, ZOOM_IN_PAN_RIGHT, ZOOM_OUT_PAN, STATIC,
        PUNCH_ZOOM, SHAKE, DIAGONAL, CINEMATIC_ZOOM, DRAMATIC_REVEAL
    )

    fun label(value: String): String = when (value) {
        ZOOM_IN -> "Zoom In"
        ZOOM_OUT -> "Zoom Out"
        PAN -> "Pan Right"
        PAN_LEFT -> "Pan Left"
        PAN_UP -> "Pan Up"
        PAN_DOWN -> "Pan Down"
        ZOOM_IN_PAN_LEFT -> "Zoom+Pan Left"
        ZOOM_IN_PAN_RIGHT -> "Zoom+Pan Right"
        ZOOM_OUT_PAN -> "Zoom Out+Pan"
        STATIC -> "Static"
        PUNCH_ZOOM -> "Punch Zoom"
        SHAKE -> "Handheld Shake"
        DIAGONAL -> "Ken Burns Diagonal"
        CINEMATIC_ZOOM -> "Cinematic Zoom"
        DRAMATIC_REVEAL -> "Dramatic Reveal"
        else -> value
    }
}

@Entity(tableName = "autogen_prompts")
data class PromptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val orderIndex: Int,
    val label: String,
    val promptText: String,
    val status: String,
    val imagePath: String? = null,
    val errorMessage: String? = null,
    /** User-set duration override for this image, in ms. Null = automatic
     * (Scale/Hold-Last sync mode decides it, as before). */
    val manualDurationMs: Long? = null
)
