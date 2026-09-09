package com.vellora.cut.autogen.timeline

/**
 * One block on a timeline track (video/image OR audio segment). Used for
 * BOTH [Timeline.clips] (video/image row) and [Timeline.audioClips]
 * (audio row) — they're the same shape, so split/delete/trim logic in
 * MainActivity works identically on either track without duplicating it.
 */
data class TimelineClip(
    val id: String,
    val startMs: Long,
    val durationMs: Long,
    val label: String,
    /** Content URI of the image/video/audio this clip plays. Null = test
     * clip with no real media (Phase 1 placeholder). */
    val sourceUri: String? = null,
    /**
     * Offset into the ORIGINAL source file that this clip's content
     * starts at. Fixed across ripple/trim/split — only [startMs] (the
     * clip's position ON THE TIMELINE) ever moves; this is what lets a
     * split/trimmed audio clip keep playing the correct slice of the
     * original file instead of restarting from 0. Video/image clips
     * don't need this yet (always 0 — each clip is one whole picked
     * image, there's no "source range" within a single image).
     */
    val sourceOffsetMs: Long = 0L,
    /** True if [sourceUri] is a real video file (not a still image or
     * audio track) — tells the preview which loader path to use:
     * [com.vellora.engine.playback.BitmapLoader] decodes a real VIDEO
     * FRAME at the current playhead position (via MediaMetadataRetriever)
     * instead of just loading a static picked image. Always false for
     * audio-track clips. */
    val isVideo: Boolean = false,
    /** Playback volume for this clip's own audio (voice-over clip, OR a
     * video clip's embedded audio) as a multiplier: 1.0 = original
     * (100%), 0.0 = silent, up to 5.0 = 500% boost. Values above 1.0
     * can't be reached with plain player volume (capped at 1.0/100% —
     * that's just "don't attenuate"), so anything above 1.0 is applied
     * via android.media.audiofx.LoudnessEnhancer, which adds real gain
     * rather than scaling an already-maxed-out signal. See
     * AudioClock/VideoAudioPlayer for where this is actually applied. */
    val volumeMultiplier: Float = 1.0f
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * The whole project: TWO independent tracks — [clips] (video/image) and
 * [audioClips] (audio) — each its own list of [TimelineClip]s with its
 * own ids, own ripple-on-delete, own split/trim. They are NOT coupled:
 * deleting a video clip never touches audioClips and vice versa. That
 * independence is deliberate — it's what "audio has its own real track"
 * means, and it's what fixes the earlier bug where deleting an image
 * piece appeared to (or was expected to) also change the audio.
 */
data class Timeline(
    val clips: List<TimelineClip> = emptyList(),
    val audioClips: List<TimelineClip> = emptyList()
) {
    val totalDurationMs: Long get() = maxOf(
        clips.maxOfOrNull { it.endMs } ?: 0L,
        audioClips.maxOfOrNull { it.endMs } ?: 0L
    )
}
