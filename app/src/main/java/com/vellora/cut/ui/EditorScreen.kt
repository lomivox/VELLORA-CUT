package com.vellora.cut.ui

import android.net.Uri
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
import kotlinx.coroutines.delay
import java.util.UUID

@Composable
fun EditorScreen(videoUri: String, onBack: () -> Unit) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var showAiUhd by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }

    // Timeline edit state — real segment model (Split/Trim act on this).
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

    // While playing, poll current position so the timeline scrubber moves with playback
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            editState = editState.copy(playheadMs = exoPlayer.currentPosition)
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
            Box(modifier = Modifier.fillMaxWidth().height(screenWidth).background(Color(0xFF161616))) {
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
            // (Split/Trim act on this, via SplitController/TrimController).
            TimelineView(
                editState = editState,
                sourceDurationMs = sourceDurationMs,
                onEditStateChange = { editState = it },
                onTimeChange = { newTimeMs ->
                    editState = editState.copy(playheadMs = newTimeMs)
                    exoPlayer.seekTo(newTimeMs)
                },
                modifier = Modifier.weight(1f, fill = false)
            )

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
