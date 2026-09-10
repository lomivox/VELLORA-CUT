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
    val processedAudioPath: String? = null
)

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

    /** Every option, in the order they should be offered — all 10 map to
     * FFmpeg's built-in `xfade` transition names (see RenderEngine), so
     * every one of these is a REAL transition, not a fake/simulated one. */
    val ALL = listOf(
        CROSSFADE, SLIDE, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN,
        WIPE_LEFT, WIPE_RIGHT, CIRCLE_OPEN, DISSOLVE, PIXELIZE
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

    /** Every option, in the order they should be offered — all 10 are REAL
     * FFmpeg `zoompan` (or plain scale/crop for STATIC) formulas, see
     * RenderEngine, matched live in Preview by applyLiveMotionAndTransition. */
    val ALL = listOf(
        ZOOM_IN, ZOOM_OUT, PAN, PAN_LEFT, PAN_UP, PAN_DOWN,
        ZOOM_IN_PAN_LEFT, ZOOM_IN_PAN_RIGHT, ZOOM_OUT_PAN, STATIC
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
