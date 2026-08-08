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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vellora.cut.ui.theme.*

@Composable
fun EditorScreen(videoUri: String, onBack: () -> Unit) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BackgroundDark)
    ) {

        // ── TOP BAR ──────────────────────────────────────────
        // background: #161616, padding: 12px 16px
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: X + Search
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = { exoPlayer.release(); onBack() }) {
                    Text("✕", color = TextPrimary, fontSize = 20.sp)
                }
                IconButton(onClick = { }) {
                    Text("🔍", color = TextPrimary, fontSize = 18.sp)
                }
            }
            // Right: 💎 + AI UHD + Export
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("💎", fontSize = 14.sp)
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("AI UHD", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("▾", color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Button(
                    onClick = { },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Export", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── PREVIEW — height: 100vw ───────────────────────────
        // background: #161616, video object-fit: contain
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenWidth)
                .background(Color(0xFF161616))
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = exoPlayer
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── TIMELINE WRAP — height: 275dp ─────────────────────
        // background: #0a0a0a
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(275.dp)
                .background(BackgroundDark)
        ) {
            // Controls row: Fullscreen | Play(center) | Link+Undo+Redo
            // padding: 0 8px
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Left: Fullscreen
                IconButton(
                    onClick = { },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text("⛶", color = TextPrimary, fontSize = 18.sp)
                }

                // Center: Play/Pause — no ripple
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (exoPlayer.isPlaying) { exoPlayer.pause(); isPlaying = false }
                            else { exoPlayer.play(); isPlaying = true }
                        }
                        .padding(8.dp)
                ) {
                    Text(
                        if (isPlaying) "⏸" else "▶",
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                }

                // Right: Link(ON) + Undo + Redo
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⧉", color = TextSecondary, fontSize = 16.sp)
                        Text("ON", color = CyanPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("↩", color = TextSecondary, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    Text("↪", color = TextSecondary, fontSize = 20.sp, modifier = Modifier.padding(end = 4.dp))
                }
            }

            // Ruler row — height: 24dp, background: #1a1a1a
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(SurfaceVariant)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("00:00 / 00:00", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, minLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    listOf("00:01", "00:02", "00:03").forEach {
                        Text(it, color = TextSecondary, fontSize = 9.sp)
                    }
                }
            }

            // Timeline tracks row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // LEFT SIDEBAR — width: 70dp
                Column(
                    modifier = Modifier
                        .width(70.dp)
                        .fillMaxHeight()
                        .background(BackgroundDark),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mute + Cover — height: 54dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .background(BackgroundDark),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("🔇", fontSize = 11.sp)
                            Text("Mute\nclip", color = TextSecondary, fontSize = 6.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("✏️", fontSize = 10.sp)
                                Text("Cover", color = TextSecondary, fontSize = 6.sp)
                            }
                        }
                    }
                    // Audio icon — height: 42dp
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
                            Text("♪", color = CyanPrimary, fontSize = 14.sp)
                        }
                    }
                    // Text icon — height: 42dp
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
                            Text("T", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // RIGHT SCROLLABLE TRACKS
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                ) {
                    // Video clip — height: 54dp, background: #1a1a1a
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(54.dp)
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("── Video Clip ──", color = CyanPrimary, fontSize = 12.sp)
                    }
                    // Audio track — height: 42dp, margin-top: 2dp
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(42.dp)
                            .padding(top = 2.dp)
                            .background(BackgroundDark)
                            .padding(start = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("+ Add audio", color = TextSecondary, fontSize = 12.sp)
                    }
                    // Text track — height: 42dp, margin-top: 2dp
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(42.dp)
                            .padding(top = 2.dp)
                            .background(BackgroundDark)
                            .padding(start = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("+ Add text", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        // ── BOTTOM TOOLBAR — padding: 11dp 20dp ──────────────
        // background: #1a1a1a, tabs scrollable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceVariant)
                .horizontalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(vertical = 11.dp),
        ) {
            listOf(
                "✂" to "Trim",
                "T" to "Text",
                "♪" to "Audio",
                "🔊" to "Volume",
                "🎙" to "Noise",
                "⟳" to "Speed",
                "✨" to "Filter",
                "↻" to "Rotate",
                "⊞" to "Overlay",
                "⬜" to "Ratio",
                "▦" to "Background"
            ).forEach { (icon, label) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Text(icon, fontSize = 20.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(label, color = TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}
