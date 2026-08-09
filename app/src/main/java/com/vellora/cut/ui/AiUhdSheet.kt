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
    val tab: String = "video",
    val resolution: Int = 1,
    val fps: Int = 2,
    val bitrate: Int = 1,
    val quality: String = "720p",
    val gifQuality: Int = 0
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
        // Overlay background
        Box(modifier = Modifier.fillMaxSize()) {
            // Dimmed background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onClose() }
            )

            // Sheet — background:#111, border-radius:20px 20px 0 0, padding:16px
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.BottomCenter)
                    .background(Color(0xFF111111), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                // TAB ROW: Video | GIF
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111111))
                ) {
                    listOf("video" to "Video", "gif" to "GIF").forEach { (key, label) ->
                        val active = settings.tab == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSettingsChange(settings.copy(tab = key)) }
                                .padding(vertical = 14.dp)
                                .then(
                                    if (active) Modifier.border(
                                        width = 2.dp,
                                        color = CyanPrimary,
                                        shape = RoundedCornerShape(0.dp)
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (active) Color.White else TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // CONTENT
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    if (settings.tab == "video") {

                        // AI Ultra HD Toggle section
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("AI ultra HD", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text("💎", fontSize = 16.sp)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Make your video clearer and smoother with AI enhancement.",
                                    color = Color(0xFF666666),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Light,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Divider(color = Color(0xFF222222))

                        // RESOLUTION — padding:16dp 0
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
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
                                        "Ultra high definition 💎"
                                    )[settings.resolution],
                                    color = Color(0xFF666666),
                                    fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth(0.55f)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
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
                                Text("480p", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                Text("720p", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("1080p", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                    Text("💎", fontSize = 10.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("2K/4K", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                    Text("💎", fontSize = 10.sp)
                                }
                            }
                        }

                        Divider(color = Color(0xFF222222))

                        // QUALITY BOXES
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                            Text("Quality", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("480p" to "Small size", "720p" to "Recommended", "1080p" to "High quality").forEach { (res, desc) ->
                                    val selected = settings.quality == res
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
                                            .border(
                                                width = if (selected) 1.dp else 1.dp,
                                                color = if (selected) Color.White else Color(0xFF2A2A2A),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { onSettingsChange(settings.copy(quality = res)) }
                                            .padding(vertical = 13.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(res, color = Color(0xFF888888), fontSize = 13.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(desc, color = Color(0xFF555555), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Divider(color = Color(0xFF222222))

                        // FRAME RATE
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Frame rate", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOf("Cinematic feel", "Standard", "Smoother playback", "Very smooth", "Ultra smooth")[settings.fps],
                                    color = Color(0xFF666666), fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
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
                                listOf("24","25","30","50","60").forEach {
                                    Text(it, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                }
                            }
                        }

                        Divider(color = Color(0xFF222222))

                        // BITRATE
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Bitrate (Mbps)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Recommended for this video(${listOf("5","10","20","50","100")[settings.bitrate]})",
                                    color = Color(0xFF666666), fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
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
                                listOf("5","10","20","50","100").forEach {
                                    Text(it, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                }
                            }
                        }

                        Divider(color = Color(0xFF222222))

                        // WATERMARK
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Watermark", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("VELLORA CUT", color = Color(0xFF666666), fontSize = 13.sp)
                                Text("›", color = Color(0xFF666666), fontSize = 16.sp)
                            }
                        }

                        // FILE SIZE
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Estimated file size: 6 MB", color = Color(0xFF666666), fontSize = 13.sp)
                        }

                    } else {
                        // GIF TAB
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("240P" to "Standard", "320P" to "High 💎", "640P" to "Ultra high 💎").forEachIndexed { idx, (res, desc) ->
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
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Watermark", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("VELLORA CUT", color = Color(0xFF666666), fontSize = 13.sp)
                                Text("›", color = Color(0xFF666666), fontSize = 16.sp)
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Size of exported GIF is about 3MB", color = Color(0xFF666666), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
