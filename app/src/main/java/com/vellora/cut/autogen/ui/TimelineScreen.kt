package com.vellora.cut.autogen.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.data.PromptStatus
import com.vellora.cut.autogen.data.TimelineMode
import com.vellora.cut.autogen.timeline.TimelineImage
import com.vellora.cut.autogen.timeline.computeTimeline
import com.vellora.cut.autogen.timeline.totalDurationMs
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TimelineScreen(
    db: AppDatabase,
    projectId: Long,
    onBack: () -> Unit
) {
    val dao = db.autoGenDao()
    val scope = rememberCoroutineScope()

    var project by remember { mutableStateOf<AutoGenProjectEntity?>(null) }
    val allPrompts by dao.observePrompts(projectId).collectAsState(initial = emptyList())
    val doneImages = remember(allPrompts) {
        allPrompts.filter { it.status == PromptStatus.DONE }.sortedBy { it.orderIndex }
    }

    LaunchedEffect(projectId) {
        project = dao.getProject(projectId)
    }

    val currentProject = project

    Scaffold(containerColor = BackgroundDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            TextButton(onClick = onBack) {
                Text(text = "← Prompts", color = TextSecondary, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Timeline", color = CyanPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            if (currentProject == null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = "Loading…", color = TextSecondary, fontSize = 13.sp)
                return@Column
            }

            if (doneImages.isEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "ابھی کوئی image تیار نہیں — پہلے Prompts screen پر جا کر Generate All چلائیں",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                return@Column
            }

            val baseDurationMs = currentProject.imageDurationSec * 1000L
            val voiceOverMs = currentProject.voiceOverDurationMs

            val timeline = remember(doneImages, currentProject.timelineMode, voiceOverMs, baseDurationMs) {
                computeTimeline(doneImages, voiceOverMs, baseDurationMs, currentProject.timelineMode)
            }
            val totalMs = totalDurationMs(timeline)

            Spacer(modifier = Modifier.height(14.dp))

            SummaryCard(
                imageCount = doneImages.size,
                totalMs = totalMs,
                voiceOverMs = voiceOverMs
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(text = "Sync Mode", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeChip(
                    label = "Scale images",
                    selected = currentProject.timelineMode == TimelineMode.SCALE,
                    onClick = {
                        scope.launch {
                            val updated = currentProject.copy(timelineMode = TimelineMode.SCALE)
                            dao.updateProject(updated)
                            project = updated
                        }
                    }
                )
                ModeChip(
                    label = "Hold last image",
                    selected = currentProject.timelineMode == TimelineMode.HOLD_LAST,
                    onClick = {
                        scope.launch {
                            val updated = currentProject.copy(timelineMode = TimelineMode.HOLD_LAST)
                            dao.updateProject(updated)
                            project = updated
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(timeline, key = { it.prompt.id }) { item ->
                    TimelineImageRow(item)
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Render (Phase F) اگلا مرحلہ ہے — یہاں سے FFmpeg سے mp4 بنے گا",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SummaryCard(imageCount: Int, totalMs: Long, voiceOverMs: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "$imageCount images", color = TextPrimary, fontSize = 13.sp)
            Text(text = "Timeline: ${formatMs(totalMs)}", color = CyanPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (voiceOverMs > 0) "Voice-over: ${formatMs(voiceOverMs)} (master)" else "کوئی voice-over duration نہیں ملی",
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) CyanPrimary else SurfaceDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) BackgroundDark else TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun TimelineImageRow(item: TimelineImage) {
    var thumbnail by remember(item.prompt.imagePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }

    LaunchedEffect(item.prompt.imagePath) {
        val path = item.prompt.imagePath ?: return@LaunchedEffect
        thumbnail = try {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(path, options)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BackgroundDark),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text(text = "🖼️", fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.prompt.label, color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = item.prompt.promptText, color = TextPrimary, fontSize = 12.sp, maxLines = 1)
        }

        Spacer(modifier = Modifier.width(8.dp))
        Text(text = formatMs(item.durationMs), color = TextSecondary, fontSize = 12.sp)
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000.0
    return "%.1fs".format(totalSec)
}
