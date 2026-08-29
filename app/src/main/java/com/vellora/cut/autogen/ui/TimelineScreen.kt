package com.vellora.cut.autogen.ui

import android.content.Intent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.data.AutoGenProjectStatus
import com.vellora.cut.autogen.data.MotionEffect
import com.vellora.cut.autogen.data.PromptStatus
import com.vellora.cut.autogen.data.TimelineMode
import com.vellora.cut.autogen.data.TransitionType
import com.vellora.cut.autogen.render.RenderEngine
import com.vellora.cut.autogen.render.RenderResult
import com.vellora.cut.autogen.timeline.TimelineImage
import com.vellora.cut.autogen.timeline.computeTimeline
import com.vellora.cut.autogen.timeline.totalDurationMs
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

/** UI state for the Phase F render flow. */
private sealed class RenderUiState {
    object Idle : RenderUiState()
    data class Rendering(val progress: Float) : RenderUiState()
    data class Done(val file: File) : RenderUiState()
    data class Error(val message: String) : RenderUiState()
}

@Composable
fun TimelineScreen(
    db: AppDatabase,
    projectId: Long,
    onBack: () -> Unit
) {
    val dao = db.autoGenDao()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var project by remember { mutableStateOf<AutoGenProjectEntity?>(null) }
    var renderState by remember { mutableStateOf<RenderUiState>(RenderUiState.Idle) }
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

            Text(text = "Transition", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeChip(
                    label = "Crossfade",
                    selected = currentProject.transitionType == TransitionType.CROSSFADE,
                    onClick = {
                        scope.launch {
                            val updated = currentProject.copy(transitionType = TransitionType.CROSSFADE)
                            dao.updateProject(updated)
                            project = updated
                        }
                    }
                )
                ModeChip(
                    label = "Slide",
                    selected = currentProject.transitionType == TransitionType.SLIDE,
                    onClick = {
                        scope.launch {
                            val updated = currentProject.copy(transitionType = TransitionType.SLIDE)
                            dao.updateProject(updated)
                            project = updated
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Motion Effect", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeChip(
                    label = "Zoom-In",
                    selected = currentProject.motionEffect == MotionEffect.ZOOM_IN,
                    onClick = {
                        scope.launch {
                            val updated = currentProject.copy(motionEffect = MotionEffect.ZOOM_IN)
                            dao.updateProject(updated)
                            project = updated
                        }
                    }
                )
                ModeChip(
                    label = "Pan",
                    selected = currentProject.motionEffect == MotionEffect.PAN,
                    onClick = {
                        scope.launch {
                            val updated = currentProject.copy(motionEffect = MotionEffect.PAN)
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

            Spacer(modifier = Modifier.height(12.dp))
            RenderSection(
                state = renderState,
                onRenderClick = {
                    renderState = RenderUiState.Rendering(0f)
                    RenderEngine.render(
                        context = context,
                        project = currentProject,
                        timeline = timeline,
                        totalDurationMs = totalMs,
                        onProgress = { fraction ->
                            renderState = RenderUiState.Rendering(fraction)
                        },
                        onComplete = { result ->
                            when (result) {
                                is RenderResult.Success -> {
                                    renderState = RenderUiState.Done(result.outputFile)
                                    scope.launch {
                                        val updated = currentProject.copy(
                                            status = AutoGenProjectStatus.RENDERED,
                                            renderedFilePath = result.outputFile.absolutePath
                                        )
                                        dao.updateProject(updated)
                                        project = updated
                                    }
                                }
                                is RenderResult.Failed -> {
                                    renderState = RenderUiState.Error(result.message)
                                }
                            }
                        }
                    )
                },
                onShareClick = { file ->
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Episode share karein"))
                }
            )
        }
    }
}

@Composable
private fun RenderSection(
    state: RenderUiState,
    onRenderClick: () -> Unit,
    onShareClick: (File) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(14.dp)
    ) {
        when (state) {
            is RenderUiState.Idle -> {
                Button(
                    onClick = onRenderClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = "Render → mp4", color = BackgroundDark, fontWeight = FontWeight.Bold)
                }
            }
            is RenderUiState.Rendering -> {
                Text(text = "Render ho raha hai…", color = TextPrimary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanPrimary,
                    trackColor = BackgroundDark
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            is RenderUiState.Done -> {
                Text(text = "✅ Render مکمل", color = CyanPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = state.file.name, color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onShareClick(state.file) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text(text = "Share", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onRenderClick) {
                        Text(text = "دوبارہ Render", color = TextPrimary)
                    }
                }
            }
            is RenderUiState.Error -> {
                Text(text = "❌ Render fail ہوا", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = state.message, color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onRenderClick,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = "دوبارہ کوشش کریں", color = BackgroundDark, fontWeight = FontWeight.Bold)
                }
            }
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
