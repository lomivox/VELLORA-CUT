package com.vellora.cut.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.launch

// Layout constants for the timeline strip
const val PX_PER_SEC = 50f   // 1 second = 50px
const val TIMELINE_HEIGHT = 54  // clip strip height dp

data class TimelineState(
    val videoDuration: Float = 0f,    // seconds
    val currentTime: Float = 0f,       // seconds
    val scrollOffset: Float = 0f       // pixels
)

@Composable
fun TimelineView(
    state: TimelineState,
    onTimeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Screen width ka half — center line position
    var containerWidthPx by remember { mutableStateOf(0f) }
    val halfWidthPx = containerWidthPx / 2f

    // Padding width in dp — half screen width
    val halfWidthDp = with(density) { halfWidthPx.toDp() }

    // Total content width
    val contentWidthDp = with(density) {
        (halfWidthPx + state.videoDuration * PX_PER_SEC + halfWidthPx).toDp()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(275.dp)
            .background(BackgroundDark)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── RULER ROW — height: 24dp ─────────────────
            // background: #1a1a1a
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(SurfaceVariant)
            ) {
                // Time display — left
                Text(
                    text = formatTime(state.currentTime) + " / " + formatTime(state.videoDuration),
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                )

                // Ruler canvas — right side
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 100.dp)
                ) {
                    val startTime = (scrollState.value / PX_PER_SEC) - (halfWidthPx / PX_PER_SEC)
                    val visibleSeconds = size.width / PX_PER_SEC

                    for (i in 0..visibleSeconds.toInt() + 2) {
                        val t = startTime + i
                        if (t < 0) continue
                        val x = t * PX_PER_SEC - scrollState.value + halfWidthPx - 100f

                        // Major tick — every second
                        drawLine(
                            color = Color(0xFF555555),
                            start = Offset(x, size.height - 8f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )

                        // Half second tick
                        val xHalf = x + PX_PER_SEC / 2
                        drawLine(
                            color = Color(0xFF333333),
                            start = Offset(xHalf, size.height - 4f),
                            end = Offset(xHalf, size.height),
                            strokeWidth = 1f
                        )
                    }
                }
            }

            // ── TRACKS AREA ──────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // SCROLLABLE CONTENT
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(scrollState)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                scope.launch {
                                    scrollState.scrollBy(-dragAmount)
                                    // Current time calculate
                                    val newTime = (scrollState.value) / PX_PER_SEC
                                    onTimeChange(newTime.coerceIn(0f, state.videoDuration))
                                }
                            }
                        }
                ) {
                    // Left padding — half screen width
                    Spacer(modifier = Modifier.width(halfWidthDp))

                    // VIDEO CLIP SEGMENT
                    Column {
                        // Main video clip — height: 54dp
                        Box(
                            modifier = Modifier
                                .width(with(density) { (state.videoDuration * PX_PER_SEC).toDp() })
                                .height(TIMELINE_HEIGHT.dp)
                                .background(Color(0xFF1A6B6B), RoundedCornerShape(4.dp))
                        ) {
                            // Thumbnail placeholder
                            Text(
                                text = "▶ Video Clip",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 8.dp)
                            )

                            // Duration badge
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(2.dp)
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = formatTime(state.videoDuration),
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // AUDIO TRACK — height: 42dp, margin-top: 2dp
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(with(density) { (state.videoDuration * PX_PER_SEC).toDp() })
                                .height(42.dp)
                                .background(BackgroundDark)
                                .padding(start = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text("+ Add audio", color = Color(0xFF555555), fontSize = 12.sp)
                        }

                        // TEXT TRACK — height: 42dp, margin-top: 2dp
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(with(density) { (state.videoDuration * PX_PER_SEC).toDp() })
                                .height(42.dp)
                                .background(BackgroundDark)
                                .padding(start = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text("+ Add text", color = Color(0xFF555555), fontSize = 12.sp)
                        }
                    }

                    // Right padding — half screen width
                    Spacer(modifier = Modifier.width(halfWidthDp))
                }

                // LEFT SIDEBAR — position absolute, left:0
                // width: half screen - 1px (center line tak)
                Column(
                    modifier = Modifier
                        .width(70.dp)
                        .fillMaxHeight()
                        .background(BackgroundDark)
                        .align(Alignment.CenterStart)
                ) {
                    // Mute + Cover — height: 54dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("🔇", fontSize = 14.sp)
                            Text(
                                "Mute\nclip",
                                color = Color(0xFFAAAAAA),
                                fontSize = 6.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("✏️", fontSize = 12.sp)
                                Text("Cover", color = Color(0xFFAAAAAA), fontSize = 6.sp)
                            }
                        }
                    }

                    // Audio icon — height: 42dp, margin-top: 2dp
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .background(BackgroundDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("♪", color = CyanPrimary, fontSize = 16.sp)
                        }
                    }

                    // Text icon — height: 42dp, margin-top: 2dp
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .background(BackgroundDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "T",
                                color = Color(0xFFAAAAAA),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // CENTER LINE — position absolute, left:50%
                // width:2px, background:white, height: full
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.White)
                        .align(Alignment.Center)
                )

                // Add clip button — position absolute, right:13px
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .align(Alignment.TopEnd)
                        .offset(x = (-13).dp, y = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Measure container width
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    Modifier.onGloballyPositioned { coords ->
                        containerWidthPx = coords.size.width.toFloat()
                    }
                )
        )
    }
}

// Time formatter: seconds -> "MM:SS"
fun formatTime(seconds: Float): String {
    val s = seconds.toInt()
    val m = s / 60
    val sec = s % 60
    return "%02d:%02d".format(m, sec)
}
