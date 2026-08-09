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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(videoUri: String, onBack: () -> Unit) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var showAiUhd by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }

    // Timeline state — driven by the actual ExoPlayer, fed into TimelineView
    var videoDurationSec by remember { mutableStateOf(0f) }
    var currentTimeSec by remember { mutableStateOf(0f) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = false
        }
    }

    // Pick up duration once the player is ready, and playing/paused state changes
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.duration > 0) {
                    videoDurationSec = player.duration / 1000f
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
            currentTimeSec = exoPlayer.currentPosition / 1000f
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

            // TIMELINE — now using the real reusable TimelineView composable
            // instead of a duplicated inline implementation.
            TimelineView(
                state = TimelineState(
                    videoDuration = videoDurationSec,
                    currentTime = currentTimeSec
                ),
                onTimeChange = { newTime ->
                    currentTimeSec = newTime
                    exoPlayer.seekTo((newTime * 1000).toLong())
                },
                modifier = Modifier.weight(1f, fill = false)
            )

            // BOTTOM TOOLBAR
            Row(modifier = Modifier.fillMaxWidth().background(SurfaceVariant).horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(vertical = 11.dp)) {
                listOf("✂" to "Trim","T" to "Text","♪" to "Audio","🔊" to "Volume","🎙" to "Noise","⟳" to "Speed","✨" to "Filter","↻" to "Rotate","⊞" to "Overlay","⬜" to "Ratio","▦" to "Background").forEach { (icon, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(icon, fontSize = 20.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.height(3.dp))
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
