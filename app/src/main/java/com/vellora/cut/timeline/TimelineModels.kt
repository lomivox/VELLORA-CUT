package com.vellora.cut.timeline

/**
 * Core data model for the video timeline.
 *
 * Design principles (from established, publicly-documented video-editor UX patterns):
 * - Duration (media time) and on-screen pixel geometry are kept separate.
 *   A `scale` value (pixels-per-second) converts between them, so zooming
 *   never touches the underlying time data — only how it's drawn.
 * - Every clip on the timeline is a [ClipSegment]: it has its own
 *   position on the timeline (timelineStartMs) AND its own trimmed range
 *   within the original source file (sourceInMs..sourceOutMs). These are
 *   deliberately different concepts, matching how every professional
 *   editor (desktop or mobile) models a cut.
 */

/** A single clip placed on the video track. */
data class ClipSegment(
    val id: String,
    val sourceUri: String,
    /** Trim in-point within the original source file, in milliseconds. */
    val sourceInMs: Long,
    /** Trim out-point within the original source file, in milliseconds. */
    val sourceOutMs: Long,
    /** Where this segment starts on the overall timeline, in milliseconds. */
    val timelineStartMs: Long
) {
    /** Duration of this segment as it appears on the timeline (out - in). */
    val durationMs: Long get() = sourceOutMs - sourceInMs

    /** Where this segment ends on the overall timeline. */
    val timelineEndMs: Long get() = timelineStartMs + durationMs
}

/** Everything the timeline UI needs to render and react to interaction. */
data class TimelineEditState(
    val segments: List<ClipSegment> = emptyList(),
    /** Pixels per second — the single source of truth for zoom level. */
    val scale: Float = 60f,
    /** Current playhead position, in milliseconds. */
    val playheadMs: Long = 0L,
    /** Currently selected segment, if any (drives which segment Split/Trim act on). */
    val selectedSegmentId: String? = null,
    /** Minimum allowed segment duration after a trim, in milliseconds. Prevents zero/negative-length clips. */
    val minSegmentDurationMs: Long = 200L
) {
    val totalDurationMs: Long get() = segments.maxOfOrNull { it.timelineEndMs } ?: 0L
    val selectedSegment: ClipSegment? get() = segments.find { it.id == selectedSegmentId }
}
