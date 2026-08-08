package com.vellora.cut.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vellora.cut.ui.theme.*

@Composable
fun EditorScreen(videoUri: String, onBack: () -> Unit) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val context = LocalContext.current

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
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // ── Top Bar ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = {
                exoPlayer.release()
                onBack()
            }) {
                Text("✕", color = TextPrimary, fontSize = 18.sp)
            }
            Text(
                text = "VELLORA CUT",
                color = CyanPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Button(
                onClick = { },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("Export", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Video Preview ─────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenWidth)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = exoPlayer
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Timeline ──────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("00:00", color = TextSecondary, fontSize = 11.sp)
                Text("00:01", color = TextSecondary, fontSize = 11.sp)
                Text("00:02", color = TextSecondary, fontSize = 11.sp)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .horizontalScroll(rememberScrollState())
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("── Video Clip ──", color = CyanPrimary, fontSize = 12.sp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("✂ Split", "🗑 Delete", "⧉ Copy").forEach { btn ->
                    OutlinedButton(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(btn, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── Bottom Toolbar ────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            listOf(
                "✂" to "Trim",
                "T" to "Text",
                "♪" to "Audio",
                "🔊" to "Volume",
                "🎙" to "Noise",
                "⟳" to "Speed",
                "✨" to "Filter",
                "↻" to "Rotate"
            ).forEach { (icon, label) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(icon, fontSize = 20.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(label, color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}
