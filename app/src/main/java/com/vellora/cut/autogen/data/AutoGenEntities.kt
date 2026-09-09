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
    /** The next image slides in from the right, pushing the current one out. */
    const val SLIDE = "slide"
}

/** Values for [AutoGenProjectEntity.motionEffect] — subtle movement applied to every still image. */
object MotionEffect {
    /** Slow, continuous zoom-in over the image's on-screen duration (classic Ken Burns). */
    const val ZOOM_IN = "zoom_in"
    /** Fixed slight zoom, camera pans left-to-right across the image. */
    const val PAN = "pan"
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
