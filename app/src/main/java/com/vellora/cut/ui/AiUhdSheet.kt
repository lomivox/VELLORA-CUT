package com.vellora.cut.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.ui.theme.*

data class ExportSettings(
    val tab: String = "video",        // "video" or "gif"
    val resolution: Int = 1,          // 0=480p, 1=720p, 2=1080p, 3=2K/4K
    val fps: Int = 2,                 // 0=24, 1=25, 2=30, 3=50, 4=60
    val bitrate: Int = 1,             // 0=5, 1=10, 2=20, 3=50, 4=100
    val quality: String = "720p",     // "480p", "720p", "1080p"
    val gifQuality: Int = 0           // 0=240P, 1=320P, 2=640P
)

@Composable
fun AiUhdSheet(
    visible: Boolean,
    settings: ExportSettings,
    onSettingsChange: (ExportSettings) -> Unit,
    onClose: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundDark)
        ) {
            // ── TAB ROW: Video | GIF ─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 16.dp)
            ) {
                listOf("video", "gif").forEach { tab ->
                    val active = settings.tab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSettingsChange(settings.copy(tab = tab)) }
                            .border(
                                width = 2.dp,
                                color = if (active) CyanPrimary else Color.Transparent,
                                shape = RoundedCornerShape(0.dp)
                            )
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (tab == "video") "Video" else "GIF",
                            color = if (active) Color.White else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // ── CONTENT ──────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (settings.tab == "video") {

                    // ── RESOLUTION ───────────────────────
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Resolution", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = listOf(
                                "Low definition - smaller file size",
                                "Standard definition - TikTok recommended",
                                "High definition - sharp quality 💎",
                                "Ultra high definition - best quality 💎"
                            )[settings.resolution],
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(0.55f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Slider(
                        value = settings.resolution.toFloat(),
                        onValueChange = { onSettingsChange(settings.copy(resolution = it.toInt())) },
                        valueRange = 0f..3f,
                        steps = 2,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = CyanPrimary,
                            inactiveTrackColor = Color(0xFF333333)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("480p", color = TextSecondary, fontSize = 12.sp)
                        Text("720p", color = TextSecondary, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("1080p", color = TextSecondary, fontSize = 12.sp)
                            Text(" 💎", fontSize = 10.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("2K/4K", color = TextSecondary, fontSize = 12.sp)
                            Text(" 💎", fontSize = 10.sp)
                        }
                    }

                    Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 16.dp))

                    // ── FRAME RATE ───────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Frame rate", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = listOf("Cinematic feel", "Standard", "Smoother playback", "Very smooth", "Ultra smooth")[settings.fps],
                            color = TextSecondary, fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Slider(
                        value = settings.fps.toFloat(),
                        onValueChange = { onSettingsChange(settings.copy(fps = it.toInt())) },
                        valueRange = 0f..4f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = CyanPrimary,
                            inactiveTrackColor = Color(0xFF333333)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("24", "25", "30", "50", "60").forEach {
                            Text(it, color = TextSecondary, fontSize = 12.sp)
                        }
                    }

                    Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 16.dp))

                    // ── BITRATE ──────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bitrate (Mbps)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Recommended(${listOf("5","10","20","50","100")[settings.bitrate]})",
                            color = TextSecondary, fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Slider(
                        value = settings.bitrate.toFloat(),
                        onValueChange = { onSettingsChange(settings.copy(bitrate = it.toInt())) },
                        valueRange = 0f..4f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = CyanPrimary,
                            inactiveTrackColor = Color(0xFF333333)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("5", "10", "20", "50", "100").forEach {
                            Text(it, color = TextSecondary, fontSize = 12.sp)
                        }
                    }

                    Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 16.dp))

                    // ── QUALITY BOXES ────────────────────
                    Text("Quality", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("480p" to "Small", "720p" to "Recommended", "1080p" to "High 💎").forEach { (res, desc) ->
                            val selected = settings.quality == res
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color(0xFF1A1A1A), RoundedCornerShape(14.dp))
                                    .border(
                                        width = 2.dp,
                                        color = if (selected) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onSettingsChange(settings.copy(quality = res)) }
                                    .padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(res, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Divider(color = Color(0xFF444444), modifier = Modifier.fillMaxWidth(0.6f))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(desc, color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 16.dp))

                    // ── WATERMARK ────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Watermark", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("None", color = TextSecondary, fontSize = 13.sp)
                            Text("›", color = TextSecondary, fontSize = 16.sp)
                        }
                    }

                    // ── FILE SIZE ────────────────────────
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Estimated file size: 6 MB", color = TextSecondary, fontSize = 13.sp)
                    }

                } else {
                    // ── GIF TAB ──────────────────────────
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("240P" to "Standard", "320P" to "High 💎", "640P" to "Ultra 💎").forEachIndexed { idx, (res, desc) ->
                            val selected = settings.gifQuality == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color(0xFF1A1A1A), RoundedCornerShape(14.dp))
                                    .border(
                                        width = 2.dp,
                                        color = if (selected) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onSettingsChange(settings.copy(gifQuality = idx)) }
                                    .padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(res, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Divider(color = Color(0xFF444444), modifier = Modifier.fillMaxWidth(0.6f))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(desc, color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Watermark", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("None", color = TextSecondary, fontSize = 13.sp)
                            Text("›", color = TextSecondary, fontSize = 16.sp)
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Size of exported GIF is about 3MB", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
