package com.vellora.cut.autogen.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.vellora.cut.R
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
import com.vellora.cut.autogen.timeline.timelineStartOffsets
import com.vellora.cut.autogen.timeline.totalDurationMs
import com.vellora.cut.autogen.ui.reference.BottomToolbarReference
import com.vellora.cut.autogen.ui.reference.EditorTopBarReference
import com.vellora.cut.autogen.ui.reference.PreviewMiddleControlsReference
import com.vellora.cut.autogen.ui.reference.ToolbarAction
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.delay
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

    // Hoisted so both the Controls row and the Preview player share one play/pause state,
    // and so the Top Bar's Export action can trigger the same render logic as the Timeline's Render button.
    var isPlaying by remember { mutableStateOf(false) }
    var togglePlayPause by remember { mutableStateOf<(() -> Unit)?>(null) }
    var startRender by remember { mutableStateOf<(() -> Unit)?>(null) }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val proj = project
        if (uri != null && proj != null) {
            val durationMs = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                0L
            }
            scope.launch {
                val updated = proj.copy(voiceOverUri = uri.toString(), voiceOverDurationMs = durationMs)
                dao.updateProject(updated)
                project = updated
            }
        }
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
        ) {
            // ---- TOP BAR (~6.8%) — extracted from the old Editor (EditorControlsReference) ----
            Box(modifier = Modifier.fillMaxWidth().weight(0.068f)) {
                EditorTopBarReference(
                    onClose = onBack,
                    onSearch = { },
                    trailingActions = {
                        Button(
                            onClick = { startRender?.invoke() },
                            enabled = startRender != null && renderState !is RenderUiState.Rendering && doneImages.isNotEmpty(),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text(text = "Export", color = BackgroundDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            if (currentProject == null) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = "Loading…", color = TextSecondary, fontSize = 13.sp)
                }
                return@Column
            }

            // (no standalone notice here — folded into the Timeline section below,
            // right above the images list, so the 5-section proportions stay exact)

            val baseDurationMs = currentProject.imageDurationSec * 1000L
            val voiceOverMs = currentProject.voiceOverDurationMs

            val timeline = remember(doneImages, currentProject.timelineMode, voiceOverMs, baseDurationMs) {
                computeTimeline(doneImages, voiceOverMs, baseDurationMs, currentProject.timelineMode)
            }
            val totalMs = totalDurationMs(timeline)

            // ---- PREVIEW (~48.7%) ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.487f)
            ) {
                PreviewPlayer(
                    project = currentProject,
                    timeline = timeline,
                    totalMs = totalMs,
                    isPlaying = isPlaying,
                    onIsPlayingChange = { isPlaying = it },
                    onTogglePlayPauseReady = { togglePlayPause = it }
                )
            }

            // ---- CONTROLS (~4.9%) — extracted from the old Editor; Play/Pause wired, rest still layout-only ----
            Box(modifier = Modifier.fillMaxWidth().weight(0.049f)) {
                PreviewMiddleControlsReference(
                    isPlaying = isPlaying,
                    onFullscreen = { },
                    onPlayPause = { togglePlayPause?.invoke() },
                    onUndo = { },
                    onRedo = { }
                )
            }

            // ---- TIMELINE (~30%) — content untouched, only resized to fit the new layout ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.30f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                SummaryCard(imageCount = doneImages.size, totalMs = totalMs, voiceOverMs = voiceOverMs)

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

                Text(text = "Images (${timeline.size})", color = TextSecondary, fontSize = 12.sp)
                if (doneImages.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ابھی کوئی image تیار نہیں — پہلے Prompts screen پر جا کر Generate All چلائیں",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    timeline.forEach { item ->
                        TimelineImageRow(item)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val triggerRender: () -> Unit = {
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
                }
                SideEffect { startRender = triggerRender }

                RenderSection(
                    state = renderState,
                    onRenderClick = triggerRender,
                    enabled = doneImages.isNotEmpty(),
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
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ---- NAVIGATION (~9.8%) — extracted from the old Editor; layout only, no button functions wired yet ----
            Box(modifier = Modifier.fillMaxWidth().weight(0.098f)) {
                BottomToolbarReference(
                    actions = listOf(
                        ToolbarAction(R.drawable.ic_trim, "Split") { },
                        ToolbarAction(R.drawable.ic_text, "Text") { },
                        ToolbarAction(R.drawable.ic_audio, "Audio") { audioLauncher.launch("audio/*") },
                        ToolbarAction(R.drawable.ic_volume, "Volume") { },
                        ToolbarAction(R.drawable.ic_noise, "Noise") { },
                        ToolbarAction(R.drawable.ic_speed, "Speed") { },
                        ToolbarAction(R.drawable.ic_filter, "Filter") { },
                        ToolbarAction(R.drawable.ic_rotate, "Rotate") { },
                        ToolbarAction(R.drawable.ic_overlay, "Overlay") { },
                        ToolbarAction(R.drawable.ic_ratio, "Ratio") {
                            scope.launch {
                                val updated = currentProject.copy(
                                    resolution = if (currentProject.resolution == "youtube") "tiktok" else "youtube"
                                )
                                dao.updateProject(updated)
                                project = updated
                            }
                        },
                        ToolbarAction(R.drawable.ic_background, "Background") { }
                    )
                )
            }
        }
    }
}

/**
 * Preview: black stage showing whichever timeline image corresponds to the
 * voice-over's current playback position, plus play/pause + a scrub slider.
 * Uses android.media.MediaPlayer (built into Android, no extra dependency)
 * since it can read the voice-over's content:// URI directly.
 */
@Composable
private fun PreviewPlayer(
    project: AutoGenProjectEntity,
    timeline: List<TimelineImage>,
    totalMs: Long,
    isPlaying: Boolean,
    onIsPlayingChange: (Boolean) -> Unit,
    onTogglePlayPauseReady: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }

    DisposableEffect(project.voiceOverUri) {
        val uriString = project.voiceOverUri
        val mp = if (uriString != null) {
            try {
                MediaPlayer().apply {
                    setDataSource(context, Uri.parse(uriString))
                    setOnPreparedListener { isPrepared = true }
                    setOnCompletionListener {
                        onIsPlayingChange(false)
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                null
            }
        } else null
        mediaPlayer = mp
        onDispose {
            mp?.release()
            mediaPlayer = null
            isPrepared = false
        }
    }

    // Play/Pause works whether or not a voice-over is attached: with audio, the
    // MediaPlayer drives it (and the slideshow follows its position); without
    // audio, this runs off its own wall-clock timer so preview never depends
    // on audio being present.
    val togglePlayPause: () -> Unit = {
        val mp = mediaPlayer
        if (isPlaying) {
            mp?.takeIf { isPrepared }?.pause()
            onIsPlayingChange(false)
        } else {
            if (positionMs >= totalMs) {
                positionMs = 0L
            }
            mp?.takeIf { isPrepared }?.let { it.seekTo(positionMs.toInt()); it.start() }
            onIsPlayingChange(true)
        }
    }
    SideEffect { onTogglePlayPauseReady(togglePlayPause) }

    // Advance the position while playing — synced to the audio player's
    // position when one exists and is ready, otherwise driven by elapsed
    // real time so the image slideshow always plays.
    LaunchedEffect(isPlaying) {
        var lastTickMs = System.currentTimeMillis()
        while (isPlaying) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTickMs
            lastTickMs = now
            if (!isScrubbing) {
                val mp = mediaPlayer
                positionMs = if (mp != null && isPrepared) {
                    mp.currentPosition.toLong()
                } else {
                    (positionMs + elapsed).coerceAtMost(totalMs)
                }
                if (positionMs >= totalMs) {
                    onIsPlayingChange(false)
                    positionMs = 0L
                }
            }
            delay(40)
        }
    }

    val offsets = remember(timeline) { timelineStartOffsets(timeline) }
    val currentIndex = remember(positionMs, offsets) {
        var idx = 0
        for (i in offsets.indices) {
            if (positionMs >= offsets[i]) idx = i else break
        }
        idx
    }
    val currentImage = timeline.getOrNull(currentIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = currentImage?.let { rememberDecodedBitmap(it.prompt.imagePath) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(text = "🖼️", fontSize = 40.sp)
            }
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, max(totalMs, 1L).toFloat()),
                valueRange = 0f..max(totalMs, 1L).toFloat(),
                onValueChange = { value ->
                    isScrubbing = true
                    positionMs = value.toLong()
                },
                onValueChangeFinished = {
                    mediaPlayer?.takeIf { isPrepared }?.seekTo(positionMs.toInt())
                    isScrubbing = false
                },
                colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun rememberDecodedBitmap(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = try {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, options)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    return bitmap
}

private fun max(a: Long, b: Long): Long = if (a > b) a else b

@Composable
private fun RenderSection(
    state: RenderUiState,
    onRenderClick: () -> Unit,
    onShareClick: (File) -> Unit,
    enabled: Boolean = true
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
                    enabled = enabled,
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
    val bitmap = rememberDecodedBitmap(item.prompt.imagePath)

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
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
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
