package com.vellora.cut.autogen.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    // Icon sizes computed from each bar's REAL on-screen height (screen
    // height × that bar's own weight %), not a fixed dp number — same
    // formula as VELLORA-ENGINE's reference layout, so icons stay
    // proportional to their bar on every device instead of guessing one
    // fixed size. Fractions (45% / 68% / 40%) are VELLORA-ENGINE's tuned
    // values after an on-device check found 45% too small for Controls.
    val topBarIconSize = screenHeightDp * 0.068f * 0.45f
    val controlsIconSize = screenHeightDp * 0.049f * 0.68f
    val navIconSize = screenHeightDp * 0.098f * 0.40f

    var project by remember { mutableStateOf<AutoGenProjectEntity?>(null) }
    var renderState by remember { mutableStateOf<RenderUiState>(RenderUiState.Idle) }
    var previewingFile by remember { mutableStateOf<File?>(null) }
    var editingDurationFor by remember { mutableStateOf<com.vellora.cut.autogen.data.PromptEntity?>(null) }
    var showExportOverlay by remember { mutableStateOf(false) }
    var pendingGallerySaveFile by remember { mutableStateOf<File?>(null) }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingGallerySaveFile
        pendingGallerySaveFile = null
        if (granted && file != null) {
            val uri = com.vellora.cut.autogen.render.GallerySaver.saveVideoToGallery(context, file)
            android.widget.Toast.makeText(
                context,
                if (uri != null) "✅ Gallery mein save ho gayi" else "❌ Save nahi ho saki",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (!granted) {
            android.widget.Toast.makeText(context, "Storage permission chahiye", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val onSaveToGallery: (File) -> Unit = { file ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val uri = com.vellora.cut.autogen.render.GallerySaver.saveVideoToGallery(context, file)
            android.widget.Toast.makeText(
                context,
                if (uri != null) "✅ Gallery mein save ho gayi (Movies/VELLORA-CUT)" else "❌ Save nahi ho saki",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            pendingGallerySaveFile = file
            galleryPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    iconSize = topBarIconSize,
                    trailingActions = {
                        Button(
                            onClick = {
                                if (renderState is RenderUiState.Done) {
                                    previewingFile = (renderState as RenderUiState.Done).file
                                } else {
                                    showExportOverlay = true
                                    startRender?.invoke()
                                }
                            },
                            enabled = startRender != null && doneImages.isNotEmpty() && renderState !is RenderUiState.Rendering,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            // Shows the render's actual live status right where the
                            // person tapped — previously this button gave no feedback
                            // of its own, so pressing it looked like nothing happened
                            // while the real progress/result only appeared scrolled
                            // out of view, far below in the timeline section.
                            val label = when (val s = renderState) {
                                is RenderUiState.Idle -> "Export"
                                is RenderUiState.Rendering -> "Exporting ${(s.progress * 100).toInt()}%"
                                is RenderUiState.Done -> "✓ Preview"
                                is RenderUiState.Error -> "⚠ Retry"
                            }
                            Text(text = label, color = BackgroundDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                    onRedo = { },
                    iconSize = controlsIconSize
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
                        TimelineImageRow(item, onEditDuration = { editingDurationFor = item.prompt })
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val triggerRender: () -> Unit = {
                    showExportOverlay = true
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
                                    onSaveToGallery(result.outputFile) // automatic — no button needed
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
                    onPreviewClick = { file -> previewingFile = file },
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
                    iconSize = navIconSize,
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

        if (showExportOverlay) {
            ExportProgressOverlay(
                renderState = renderState,
                aspectRatio = if (currentProject?.resolution == "tiktok") 9f / 16f else 16f / 9f,
                onClose = { showExportOverlay = false },
                onPreview = { file -> previewingFile = file; showExportOverlay = false },
                onShare = { file ->
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Episode share karein"))
                },
                onRetry = { startRender?.invoke() }
            )
        }

        if (previewingFile != null) {
            RenderedVideoPreviewOverlay(
                file = previewingFile!!,
                onClose = { previewingFile = null }
            )
        }

        editingDurationFor?.let { prompt ->
            EditDurationDialog(
                prompt = prompt,
                onDismiss = { editingDurationFor = null },
                onSave = { newDurationMs ->
                    scope.launch {
                        dao.updatePrompt(prompt.copy(manualDurationMs = newDurationMs))
                    }
                    editingDurationFor = null
                }
            )
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
    onPreviewClick: (File) -> Unit,
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
                Text(text = "💾 Gallery (Movies/VELLORA-CUT) میں محفوظ ہو گئی", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onPreviewClick(state.file) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text(text = "▶ Preview", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }
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
private fun TimelineImageRow(item: TimelineImage, onEditDuration: () -> Unit) {
    val bitmap = rememberDecodedBitmap(item.prompt.imagePath)
    val isManual = item.prompt.manualDurationMs != null

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

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onEditDuration)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = formatMs(item.durationMs),
                color = if (isManual) CyanPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isManual) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = if (isManual) "manual" else "auto",
                color = TextSecondary,
                fontSize = 9.sp
            )
        }
    }
}

/** Dialog to set (or clear) one image's manual duration override. */
@Composable
private fun EditDurationDialog(
    prompt: com.vellora.cut.autogen.data.PromptEntity,
    onDismiss: () -> Unit,
    onSave: (Long?) -> Unit
) {
    var text by remember {
        mutableStateOf(prompt.manualDurationMs?.let { (it / 1000.0).toString() } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Duration — ${prompt.label}", color = TextPrimary, fontSize = 15.sp) },
        text = {
            Column {
                Text(
                    text = "سیکنڈز میں لکھیں (مثلاً 4، 6، 10) — خالی چھوڑیں تو یہ image واپس 'auto' ہو جائے گی",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' } },
                    label = { Text("Seconds") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = TextSecondary,
                        cursorColor = CyanPrimary
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 6, 8, 10).forEach { seconds ->
                        OutlinedButton(onClick = { text = seconds.toString() }) {
                            Text(text = "${seconds}s", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val seconds = text.toDoubleOrNull()
                onSave(if (seconds != null && seconds > 0) (seconds * 1000).toLong() else null)
            }) {
                Text(text = "Save", color = CyanPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = TextSecondary)
            }
        },
        containerColor = SurfaceDark
    )
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000.0
    return "%.1fs".format(totalSec)
}

/**
 * Full-screen export/render status page. Shows a box shaped like the
 * output video (portrait for TikTok, landscape for YouTube) with a blue
 * progress stroke that fills around its border as rendering proceeds, and
 * the percentage centered inside — like a rectangular version of a
 * circular loading ring. Reused for the Done (green ring, Preview/Share)
 * and Error (red ring, retry) outcomes too, so the page never just
 * vanishes without telling the person what happened.
 */
@Composable
private fun ExportProgressOverlay(
    renderState: RenderUiState,
    aspectRatio: Float,
    onClose: () -> Unit,
    onPreview: (File) -> Unit,
    onShare: (File) -> Unit,
    onRetry: () -> Unit
) {
    val progress = when (renderState) {
        is RenderUiState.Rendering -> renderState.progress
        is RenderUiState.Done -> 1f
        else -> 0f
    }
    val ringColor = when (renderState) {
        is RenderUiState.Error -> Color(0xFFFF6B6B)
        is RenderUiState.Done -> Color(0xFF4CD964)
        else -> CyanPrimary
    }
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress,
        label = "export_progress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark.copy(alpha = 0.98f))
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Text(text = "✕", color = TextPrimary, fontSize = 22.sp)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (renderState) {
                    is RenderUiState.Done -> "✅ Export مکمل"
                    is RenderUiState.Error -> "⚠ Export ناکام"
                    else -> "Exporting…"
                },
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .aspectRatio(aspectRatio),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidthPx = 8.dp.toPx()
                    val cornerRadiusPx = 20.dp.toPx()
                    val inset = strokeWidthPx / 2
                    val outlinePath = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = inset,
                                top = inset,
                                right = size.width - inset,
                                bottom = size.height - inset,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx)
                            )
                        )
                    }

                    // Dim background track showing the full border shape.
                    drawPath(
                        outlinePath,
                        color = Color.White.copy(alpha = 0.12f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidthPx,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )

                    // Progress stroke: only the first `progress` fraction of
                    // the border's perimeter, via PathMeasure (Android
                    // interop — Compose's own Path has no length/segment
                    // API), so it visibly "fills around" the box.
                    val androidPath = outlinePath.asAndroidPath()
                    val measure = android.graphics.PathMeasure(androidPath, true)
                    val totalLength = measure.length
                    if (totalLength > 0f && animatedProgress > 0f) {
                        val segmentPath = android.graphics.Path()
                        measure.getSegment(0f, totalLength * animatedProgress.coerceIn(0f, 1f), segmentPath, true)
                        drawPath(
                            segmentPath.asComposePath(),
                            color = ringColor,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidthPx,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                    }
                }

                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    color = TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            when (renderState) {
                is RenderUiState.Done -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onPreview(renderState.file) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Text(text = "▶ Preview", color = BackgroundDark, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onShare(renderState.file) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Text(text = "Share", color = BackgroundDark, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "💾 Gallery میں خود بخود محفوظ ہو گئی", color = TextSecondary, fontSize = 11.sp)
                }
                is RenderUiState.Error -> {
                    Text(text = renderState.message, color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text(text = "دوبارہ کوشش کریں", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Text(text = "Screen بند نہ کریں — رینڈر جاری ہے", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Full-screen overlay that plays the actual rendered mp4 — so transitions,
 * motion effects, and audio sync can be checked exactly as FFmpeg produced
 * them (the live PreviewPlayer above only shows raw images switching, not
 * the real baked-in transitions/zoompan).
 */
@Composable
private fun RenderedVideoPreviewOverlay(file: File, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI(Uri.fromFile(file))
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { it.isLooping = false; start() }
                }
            }
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text(text = "✕", color = Color.White, fontSize = 26.sp)
        }
    }
}
