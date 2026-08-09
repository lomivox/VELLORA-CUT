package com.vellora.cut.timeline

/**
 * Pure time <-> pixel conversion helpers.
 *
 * The timeline follows the "fixed center playhead, moving strip" model used
 * by mobile editors: the playhead stays visually centered on screen, and the
 * segments scroll underneath it. So converting a segment's time into an X
 * position always needs to account for (a) the current scroll offset in time,
 * and (b) half the viewport width, which keeps the playhead centered.
 */
object TimelineMath {

    /** Convert a millisecond time value to a horizontal pixel offset within the scrollable strip. */
    fun msToPx(ms: Long, scale: Float): Float = (ms / 1000f) * scale

    /** Convert a pixel offset back to milliseconds. */
    fun pxToMs(px: Float, scale: Float): Long = ((px / scale) * 1000f).toLong()

    /**
     * X position (in px, relative to the scrollable content's own coordinate space)
     * where a given timeline time should be drawn.
     */
    fun timeToContentX(ms: Long, scale: Float): Float = msToPx(ms, scale)

    /**
     * Given the current playhead time and viewport width, compute how far the
     * scrollable content should be offset so the playhead stays centered.
     * (This is what gets applied as horizontal scroll/translation.)
     */
    fun scrollOffsetForPlayhead(playheadMs: Long, scale: Float, viewportWidthPx: Float): Float {
        return timeToContentX(playheadMs, scale) - (viewportWidthPx / 2f)
    }

    /**
     * Convert a raw touch X (relative to the viewport, not the content) into a
     * timeline time, given the current scroll offset.
     */
    fun viewportXToTime(viewportX: Float, scrollOffsetPx: Float, scale: Float): Long {
        val contentX = viewportX + scrollOffsetPx
        return pxToMs(contentX, scale).coerceAtLeast(0L)
    }

    /** Zoom limits — keep timeline usable (not too dense, not absurdly stretched). */
    const val MIN_SCALE = 10f   // px per second at max zoom-out
    const val MAX_SCALE = 400f  // px per second at max zoom-in

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)
}
