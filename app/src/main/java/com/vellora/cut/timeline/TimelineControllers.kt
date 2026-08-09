package com.vellora.cut.timeline

import java.util.UUID

/**
 * Split logic: divides the selected segment into two independent segments
 * at the current playhead time.
 *
 * Rules (matching standard editor behavior):
 * - Split only acts on the currently SELECTED segment — not whatever the
 *   playhead visually overlaps. This avoids ambiguity when multiple tracks exist.
 * - The playhead must fall strictly inside the segment's timeline range
 *   (not exactly on an edge — splitting at an edge would produce a
 *   zero-length piece, which isn't a valid edit).
 * - Segments after the split point (on the same track) are NOT shifted —
 *   splitting only affects the one segment, matching CapCut's documented
 *   behavior ("the clip becomes two separate pieces" in place).
 */
object SplitController {

    sealed class SplitResult {
        data class Success(val newState: TimelineEditState, val newSegmentId: String) : SplitResult()
        data class Failed(val reason: String) : SplitResult()
    }

    fun split(state: TimelineEditState): SplitResult {
        val segment = state.selectedSegment
            ?: return SplitResult.Failed("No segment selected")

        val playhead = state.playheadMs
        if (playhead <= segment.timelineStartMs || playhead >= segment.timelineEndMs) {
            return SplitResult.Failed("Playhead is not inside the selected segment")
        }

        // How far into the segment (in source-time) the cut falls.
        val offsetIntoSegment = playhead - segment.timelineStartMs
        val cutSourceMs = segment.sourceInMs + offsetIntoSegment

        val firstHalf = segment.copy(
            sourceOutMs = cutSourceMs
        )
        val secondHalfId = UUID.randomUUID().toString()
        val secondHalf = segment.copy(
            id = secondHalfId,
            sourceInMs = cutSourceMs,
            timelineStartMs = segment.timelineStartMs + firstHalf.durationMs
        )

        val newSegments = state.segments.map {
            if (it.id == segment.id) firstHalf else it
        } + secondHalf

        val newState = state.copy(
            segments = newSegments.sortedBy { it.timelineStartMs },
            selectedSegmentId = segment.id // keep the first half selected, matching common editor behavior
        )
        return SplitResult.Success(newState, secondHalfId)
    }
}

/**
 * Trim logic: dragging the left or right handle of the selected segment to
 * shorten it from that edge, without creating a new segment.
 *
 * - Trimming the LEFT edge moves sourceInMs forward and shifts timelineStartMs
 *   forward by the same amount (the clip's timeline position advances as its
 *   start is eaten into).
 * - Trimming the RIGHT edge moves sourceOutMs backward; timelineStartMs is untouched.
 * - A segment can never be trimmed shorter than [TimelineEditState.minSegmentDurationMs].
 * - Trimming the left edge can never go before sourceInMs=0 (start of the source file);
 *   trimming the right edge is bounded by the caller-supplied source duration.
 */
object TrimController {

    enum class Edge { LEFT, RIGHT }

    fun trim(
        state: TimelineEditState,
        segmentId: String,
        edge: Edge,
        deltaMs: Long,
        sourceDurationMs: Long
    ): TimelineEditState {
        val segment = state.segments.find { it.id == segmentId } ?: return state
        val minDur = state.minSegmentDurationMs

        val updated = when (edge) {
            Edge.LEFT -> {
                val newSourceIn = (segment.sourceInMs + deltaMs)
                    .coerceIn(0L, segment.sourceOutMs - minDur)
                val actualDelta = newSourceIn - segment.sourceInMs
                segment.copy(
                    sourceInMs = newSourceIn,
                    timelineStartMs = segment.timelineStartMs + actualDelta
                )
            }
            Edge.RIGHT -> {
                val newSourceOut = (segment.sourceOutMs + deltaMs)
                    .coerceIn(segment.sourceInMs + minDur, sourceDurationMs)
                segment.copy(sourceOutMs = newSourceOut)
            }
        }

        return state.copy(
            segments = state.segments.map { if (it.id == segmentId) updated else it }
        )
    }
}

/**
 * Snapping: while dragging (moving or trimming a segment), pulls the dragged
 * time value to nearby "magnetic" points if it's within [thresholdMs].
 *
 * Snap targets, per common editor convention:
 * - Start/end of every OTHER segment currently on the track
 * - The playhead position
 * - Timeline start (0)
 *
 * Only candidate points are considered — the caller is responsible for only
 * passing points that are currently visible in the viewport, per the
 * "don't snap to off-screen things" rule (confusing otherwise).
 */
object SnapController {

    fun snappedTime(
        rawMs: Long,
        candidates: List<Long>,
        thresholdMs: Long
    ): Long {
        val nearest = candidates.minByOrNull { kotlin.math.abs(it - rawMs) }
        return if (nearest != null && kotlin.math.abs(nearest - rawMs) <= thresholdMs) {
            nearest
        } else {
            rawMs
        }
    }

    /** Builds the standard candidate list: other segments' edges + playhead + zero. */
    fun candidatesFor(state: TimelineEditState, excludingSegmentId: String?): List<Long> {
        val edges = state.segments
            .filter { it.id != excludingSegmentId }
            .flatMap { listOf(it.timelineStartMs, it.timelineEndMs) }
        return edges + state.playheadMs + 0L
    }
}
