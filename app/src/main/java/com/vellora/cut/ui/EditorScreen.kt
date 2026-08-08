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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Box(modifier = Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AI UHD", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("▾", color = TextSecondary, fontSize = 11.sp)
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenWidth * 0.56f)
                .background(Color.Black)
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

        Column(modifier = Modifier.fillMaxWidth().background(BackgroundDark)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("⛶", color = TextPrimary, fontSize = 18.sp)
                IconButton(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                    Text("▶", color = TextPrimary, fontSize = 18.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⧉", color = TextSecondary, fontSize = 16.sp)
                        Text("ON", color = CyanPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("↩", color = TextSecondary, fontSize = 20.sp)
                    Text("↪", color = TextSecondary, fontSize = 20.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().background(SurfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("00:00 / 00:00", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            Box(modifier = Modifier.fillMaxWidth().height(54.dp).background(SurfaceVariant).horizontalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Text("── Video Clip ──", color = CyanPrimary, fontSize = 12.sp)
            }

            Box(modifier = Modifier.fillMaxWidth().height(42.dp).background(BackgroundDark), contentAlignment = Alignment.CenterStart) {
                Text("  + Add audio", color = TextSecondary, fontSize = 12.sp)
            }

            Box(modifier = Modifier.fillMaxWidth().height(42.dp).background(BackgroundDark), contentAlignment = Alignment.CenterStart) {
                Text("  + Add text", color = TextSecondary, fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            listOf("✂" to "Trim","T" to "Text","♪" to "Audio","🔊" to "Volume","🎙" to "Noise","⟳" to "Speed","✨" to "Filter","↻" to "Rotate","⊞" to "Overlay","⬜" to "Ratio","▦" to "Background").forEach { (icon, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Text(icon, fontSize = 20.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(label, color = TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}
