package com.vellora.cut.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vellora.cut.R
import com.vellora.cut.timeline.ClipSegment
import com.vellora.cut.timeline.SplitController
import com.vellora.cut.timeline.TimelineEditState
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Reads a video file's duration off the main thread via MediaMetadataRetriever. */
private suspend fun readVideoDurationMs(context: android.content.Context, uri: Uri): Long =
    withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

/** Given a project-time position, finds which segment it falls in and the seek target within it. */
private fun timelineMsToMediaSeek(segments: List<ClipSegment>, timeMs: Long): Pair<Int, Long> {
    val ordered = segments.sortedBy { it.timelineStartMs }
    val index = ordered.indexOfFirst { timeMs >= it.timelineStartMs && timeMs < it.timelineEndMs }
        .let { if (it == -1) ordered.lastIndex.coerceAtLeast(0) else it }
    val seg = ordered.getOrNull(index) ?: return 0 to 0L
    val withinSourceMs = seg.sourceInMs + (timeMs - seg.timelineStartMs).coerceAtLeast(0L)
    return index to withinSourceMs
}

/** Inverse: current playing media item + position within it -> project-time (timeline) position. */
private fun mediaSeekToTimelineMs(segments: List<ClipSegment>, mediaItemIndex: Int, positionMs: Long): Long {
    val ordered = segments.sortedBy { it.timelineStartMs }
    val seg = ordered.getOrNull(mediaItemIndex) ?: return 0L
    return seg.timelineStartMs + (positionMs - seg.sourceInMs).coerceAtLeast(0L)
}

@Composable
fun EditorScreen(videoUri: String, onBack: () -> Unit) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var showAiUhd by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }

    // Timeline edit state — real segment model (Split/Trim/Add-clip act on this).
    // Starts empty; a single segment spanning the whole clip is added once
    // the player reports the real duration (see DisposableEffect below).
    var editState by remember { mutableStateOf(TimelineEditState()) }
    var sourceDurationMs by remember { mutableStateOf(0L) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = false
        }
    }

    // "+" Add Clip — picks another video and appends it as a new segment
    // right after the current last one (matches the reference "Add Clip" button).
    val addClipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val durationMs = readVideoDurationMs(context, uri)
                if (durationMs > 0) {
                    val newSegment = ClipSegment(
                        id = UUID.randomUUID().toString(),
                        sourceUri = uri.toString(),
                        sourceInMs = 0L,
                        sourceOutMs = durationMs,
                        timelineStartMs = editState.totalDurationMs
                    )
                    editState = editState.copy(
                        segments = editState.segments + newSegment,
                        selectedSegmentId = newSegment.id
                    )
                }
            }
        }
    }

    // Pick up duration once the player is ready, and playing/paused state changes.
    // Once we learn the real duration, seed the timeline with a single segment
    // spanning the whole source clip (the starting point before any Split/Trim).
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.duration > 0 && sourceDurationMs == 0L) {
                    sourceDurationMs = player.duration
                    val wholeClip = ClipSegment(
                        id = UUID.randomUUID().toString(),
                        sourceUri = videoUri,
                        sourceInMs = 0L,
                        sourceOutMs = player.duration,
                        timelineStartMs = 0L
                    )
                    editState = editState.copy(
                        segments = listOf(wholeClip),
                        selectedSegmentId = wholeClip.id
                    )
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Rebuild the ExoPlayer playlist whenever the segment list changes (add-clip,
    // split). Each segment becomes one MediaItem, clipped to its own in/out range,
    // so playback genuinely reflects the timeline — not just the visual boxes.
    // NOTE: this also re-fires on every trim-drag pixel (segments change on each
    // callback), which can make trimming feel less smooth during active playback;
    // an optimization (rebuild only on drag-end) can follow later if needed.
    LaunchedEffect(editState.segments.map { "${it.id}:${it.sourceInMs}:${it.sourceOutMs}:${it.timelineStartMs}" }) {
        if (editState.segments.isNotEmpty()) {
            val ordered = editState.segments.sortedBy { it.timelineStartMs }
            val items = ordered.map { seg ->
                MediaItem.Builder()
                    .setUri(seg.sourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(seg.sourceInMs)
                            .setEndPositionMs(seg.sourceOutMs)
                            .build()
                    )
                    .build()
            }
            val wasPlaying = exoPlayer.isPlaying
            exoPlayer.setMediaItems(items)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = wasPlaying
        }
    }

    // While playing, poll current position (converted from media-item-local time
    // back to overall project time) so the timeline scrubber moves with playback.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            editState = editState.copy(
                playheadMs = mediaSeekToTimelineMs(
                    editState.segments,
                    exoPlayer.currentMediaItemIndex,
                    exoPlayer.currentPosition
                )
            )
            delay(200)
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {

            // TOP BAR
            Row(
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(onClick = { exoPlayer.release(); onBack() }) {
                        Text("✕", color = TextPrimary, fontSize = 20.sp)
                    }
                    IconButton(onClick = { }) {
                        Text("🔍", color = TextPrimary, fontSize = 18.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Text("💎", fontSize = 14.sp)
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showAiUhd = !showAiUhd }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("AI UHD", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (showAiUhd) "▴" else "▾", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    Button(
                        onClick = { },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Export", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // PREVIEW
            Box(modifier = Modifier.fillMaxWidth().height(screenWidth - 100.dp).background(Color(0xFF161616))) {
                AndroidView(
                    factory = { PlayerView(it).apply { player = exoPlayer; useController = false } },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // PLAYBACK CONTROL ROW (play/pause + basic transport)
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = { }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Text("⛶", color = TextPrimary, fontSize = 18.sp)
                }
                Box(
                    modifier = Modifier.align(Alignment.Center)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }.padding(8.dp)
                ) {
                    Text(if (isPlaying) "⏸" else "▶", color = TextPrimary, fontSize = 16.sp)
                }
                Row(modifier = Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⧉", color = TextSecondary, fontSize = 16.sp)
                        Text("ON", color = CyanPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("↩", color = TextSecondary, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    Text("↪", color = TextSecondary, fontSize = 20.sp, modifier = Modifier.padding(end = 4.dp))
                }
            }

            // TIMELINE — now driven by the real segment-based edit state
            // (Split/Trim act on this, via SplitController/TrimController),
            // plus the "+" Add Clip button (matches CapCut Mini reference:
            // 28x28 white rounded button, top-right of the timeline area).
            Box(modifier = Modifier.weight(1f, fill = false)) {
                TimelineView(
                    editState = editState,
                    sourceDurationMs = sourceDurationMs,
                    onEditStateChange = { editState = it },
                    onTimeChange = { newTimeMs ->
                        editState = editState.copy(playheadMs = newTimeMs)
                        val (itemIndex, withinSourceMs) = timelineMsToMediaSeek(editState.segments, newTimeMs)
                        exoPlayer.seekTo(itemIndex, withinSourceMs)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 13.dp, top = 6.dp)
                        .size(28.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            addClipLauncher.launch("video/*")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Simple "+" drawn from two bars (no extra icon-library dependency needed)
                    Box(modifier = Modifier.size(width = 14.dp, height = 2.dp).background(Color.Black))
                    Box(modifier = Modifier.size(width = 2.dp, height = 14.dp).background(Color.Black))
                }
            }

            // BOTTOM TOOLBAR — real vector icons (extracted from CapCut Mini reference,
            // converted to Android VectorDrawables), sized to match the 16px reference icons
            // instead of emoji glyphs, so height matches the CapCut Mini ~54-56dp toolbar.
            Row(modifier = Modifier.fillMaxWidth().background(SurfaceVariant).horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(vertical = 11.dp)) {
                listOf(
                    Triple(R.drawable.ic_trim, "Split") {
                        // Scissors icon = Split at playhead (matches CapCut's own convention;
                        // edge trimming happens via the in-timeline drag handles instead).
                        val result = SplitController.split(editState)
                        if (result is SplitController.SplitResult.Success) {
                            editState = result.newState
                        }
                    },
                    Triple(R.drawable.ic_text, "Text") {},
                    Triple(R.drawable.ic_audio, "Audio") {},
                    Triple(R.drawable.ic_volume, "Volume") {},
                    Triple(R.drawable.ic_noise, "Noise") {},
                    Triple(R.drawable.ic_speed, "Speed") {},
                    Triple(R.drawable.ic_filter, "Filter") {},
                    Triple(R.drawable.ic_rotate, "Rotate") {},
                    Triple(R.drawable.ic_overlay, "Overlay") {},
                    Triple(R.drawable.ic_ratio, "Ratio") {},
                    Triple(R.drawable.ic_background, "Background") {}
                ).forEach { (iconRes, label, onClick) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                    ) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = label,
                            tint = TextPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(label, color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }
        }

        // AI UHD Sheet overlay
        AiUhdSheet(
            visible = showAiUhd,
            settings = exportSettings,
            onSettingsChange = { exportSettings = it },
            onClose = { showAiUhd = false }
        )
    }
}
