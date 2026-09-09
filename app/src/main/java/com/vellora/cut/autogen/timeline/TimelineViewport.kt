package com.vellora.cut.autogen.timeline

/**
 * Converts between timeline time (ms) and screen pixels.
 *
 * [scrollOffsetPx] is now driven automatically by TimelineView every
 * frame (derived from the playhead position, so the playhead stays fixed
 * at screen-center — see TimelineView's class doc) rather than being
 * set directly by touch code. [pixelsPerMs] (zoom) IS still set directly,
 * by the pinch-zoom gesture.
 */
class TimelineViewport(
    /** How many pixels represent one millisecond — this IS the zoom level. */
    var pixelsPerMs: Float = 0.05f, // ~50px per second at default zoom
    /** How far the visible window has scrolled right, in pixels. */
    var scrollOffsetPx: Float = 0f
) {
    fun msToPx(ms: Long): Float = ms * pixelsPerMs - scrollOffsetPx

    fun pxToMs(px: Float): Long = ((px + scrollOffsetPx) / pixelsPerMs).toLong()

    /** Is any part of [startMs]..[endMs] inside the visible width [viewportWidthPx]? */
    fun isVisible(startMs: Long, endMs: Long, viewportWidthPx: Int): Boolean {
        val leftPx = msToPx(startMs)
        val rightPx = msToPx(endMs)
        return rightPx >= 0f && leftPx <= viewportWidthPx
    }
}
