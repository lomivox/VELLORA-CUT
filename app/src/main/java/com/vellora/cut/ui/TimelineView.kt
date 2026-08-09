package com.vellora.cut.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.timeline.ClipSegment
import com.vellora.cut.timeline.TimelineEditState
import com.vellora.cut.timeline.TimelineMath
import com.vellora.cut.timeline.TrimController
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.launch

// Layout constants for the timeline strip (kept intentionally matched to
// the reference CapCut Mini prototype's measurements, verified earlier).
const val TIMELINE_HEIGHT = 54  // clip strip height dp
const val HANDLE_WIDTH_DP = 14   // visible handle width
const val HANDLE_HIT_WIDTH_DP = 32 // touch hit-area width (more than 2x visible, per UX research)

@Composable
fun TimelineView(
    editState: TimelineEditState,
    sourceDurationMs: Long,
    onEditStateChange: (TimelineEditState) -> Unit,
    onTimeChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var containerWidthPx by remember { mutableStateOf(0f) }
    val halfWidthPx = containerWidthPx / 2f
    val halfWidthDp = with(density) { halfWidthPx.toDp() }

    val scale = editState.scale
    val totalDurationMs = editState.totalDurationMs

    val contentWidthDp = with(density) {
        (halfWidthPx + TimelineMath.msToPx(totalDurationMs, scale) + halfWidthPx).toDp()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                containerWidthPx = coords.size.width.toFloat()
            }
    ) {
        Column(modifier = Modifier.background(SurfaceDark)) {

            // RULER
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .horizontalScroll(scrollState, enabled = false)
            ) {
                Row(modifier = Modifier.width(contentWidthDp).padding(start = halfWidthDp)) {
                    val secondsTotal = (totalDurationMs / 1000L).toInt() + 1
                    for (s in 0..secondsTotal) {
                        Box(modifier = Modifier.width(with(density) { scale.toDp() })) {
                            Text(
                                text = formatTime(s * 1000L),
                                color = TextSecondary,
                                fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                        }
                    }
                }
            }

            // MAIN CLIP TRACK
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TIMELINE_HEIGHT.dp)
                    .horizontalScroll(scrollState)
                    .pointerInput(totalDurationMs, scale) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            scope.launch { scrollState.scrollBy(-dragAmount) }
                            val newScrollPx = scrollState.value.toFloat()
                            val newTime = TimelineMath.viewportXToTime(halfWidthPx, newScrollPx, scale)
                            onTimeChange(newTime)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .width(contentWidthDp)
                        .fillMaxHeight()
                        .padding(start = halfWidthDp)
                ) {
                    editState.segments.forEach { segment ->
                        ClipSegmentBlock(
                            segment = segment,
                            isSelected = segment.id == editState.selectedSegmentId,
                            scale = scale,
                            sourceDurationMs = sourceDurationMs,
                            minSegmentDurationMs = editState.minSegmentDurationMs,
                            onSelect = {
                                onEditStateChange(editState.copy(selectedSegmentId = segment.id))
                            },
                            onTrim = { edge, deltaMs ->
                                val newState = TrimController.trim(
                                    state = editState,
                                    segmentId = segment.id,
                                    edge = edge,
                                    deltaMs = deltaMs,
                                    sourceDurationMs = sourceDurationMs
                                )
                                onEditStateChange(newState)
                            }
                        )
                    }
                }
            }

            // AUDIO TRACK (placeholder row — kept from previous layout, real audio in a later phase)
            Row(
                modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("+ Add audio", color = TextSecondary, fontSize = 12.sp)
            }

            // TEXT TRACK (placeholder row)
            Row(
                modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("+ Add text", color = TextSecondary, fontSize = 12.sp)
            }
        }

        // FIXED CENTER PLAYHEAD — stays visually centered; the strip scrolls beneath it.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(CyanPrimary)
        )
    }
}

@Composable
private fun ClipSegmentBlock(
    segment: ClipSegment,
    isSelected: Boolean,
    scale: Float,
    sourceDurationMs: Long,
    minSegmentDurationMs: Long,
    onSelect: () -> Unit,
    onTrim: (TrimController.Edge, Long) -> Unit
) {
    val density = LocalDensity.current
    val widthDp = with(density) { TimelineMath.msToPx(segment.durationMs, scale).toDp() }

    Box(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .padding(vertical = 2.dp)
    ) {
        // Clip body — thumbnail placeholder for now (real filmstrip thumbnails: next step)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF3A3A3A), RoundedCornerShape(6.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect() }
        )

        if (isSelected) {
            // Duration badge (top-left, per reference design)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = formatDurationLabel(segment.durationMs),
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // LEFT trim handle — visible width small, hit-area much wider (per UX research: 2x+)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(HANDLE_HIT_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .pointerInput(segment.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaMs = TimelineMath.pxToMs(dragAmount.x, scale)
                            onTrim(TrimController.Edge.LEFT, deltaMs)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(HANDLE_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(Color.White, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                )
            }

            // RIGHT trim handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(HANDLE_HIT_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .pointerInput(segment.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaMs = TimelineMath.pxToMs(dragAmount.x, scale)
                            onTrim(TrimController.Edge.RIGHT, deltaMs)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(HANDLE_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(Color.White, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                )
            }
        }
    }
}

/** Ruler / general time label: mm:ss format. */
private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

/**
 * Selected-clip duration badge: fractional seconds below 60s (e.g. "4.2s"),
 * mm:ss above that — matching documented mobile-editor convention.
 * Seconds are floored, not rounded, so a clip doesn't claim a second it
 * hasn't fully reached yet.
 */
private fun formatDurationLabel(ms: Long): String {
    return if (ms < 60_000) {
        val whole = ms / 1000
        val tenth = (ms % 1000) / 100
        "${whole}.${tenth}s"
    } else {
        formatTime(ms)
    }
}
