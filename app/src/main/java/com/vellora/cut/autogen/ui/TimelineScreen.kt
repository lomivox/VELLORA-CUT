package com.vellora.cut.autogen.ui

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.vellora.cut.autogen.playback.ImageBitmapCache
import com.vellora.cut.autogen.playback.WaveformExtractor
import com.vellora.cut.autogen.render.AudioProcessor
import com.vellora.cut.autogen.render.RenderEngine
import com.vellora.cut.autogen.render.RenderResult
import com.vellora.cut.autogen.timeline.TimelineImage
import com.vellora.cut.autogen.timeline.Timeline
import com.vellora.cut.autogen.timeline.TimelineClip
import com.vellora.cut.autogen.timeline.TimelineView
import com.vellora.cut.autogen.timeline.computeTimeline
import com.vellora.cut.autogen.timeline.timelineStartOffsets
import com.vellora.cut.autogen.timeline.totalDurationMs
import com.vellora.cut.autogen.ui.reference.BottomToolbarReference
import com.vellora.cut.autogen.ui.reference.EditorTopBarReference
import com.vellora.cut.autogen.ui.reference.PreviewMiddleControlsReference
import com.vellora.cut.autogen.ui.reference.ToolbarAction
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** UI state for the Phase F render flow. */
private sealed class RenderUiState {
    object Idle : RenderUiState()
    data class Rendering(val progress: Float) : RenderUiState()
    data class Done(val file: File) : RenderUiState()
    data class Error(val message: String) : RenderUiState()
}

/** Shared decode cache for this screen's image thumbnails/preview — see
 * [ImageBitmapCache]'s doc comment (adapted from VELLORA-ENGINE's
 * BitmapLoader) for why this avoids re-decoding the same file on every
 * recomposition/scroll. */
private val LocalImageBitmapCache = compositionLocalOf<ImageBitmapCache?> { null }

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
    val imageBitmapCache = remember { ImageBitmapCache(context) }

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
    var showNoiseSheet by remember { mutableStateOf(false) }
    var showVolumeSheet by remember { mutableStateOf(false) }
    var showSyncSheet by remember { mutableStateOf(false) }
    var showTransitionSheet by remember { mutableStateOf(false) }
    var showMotionSheet by remember { mutableStateOf(false) }
    var audioProcessing by remember { mutableStateOf(false) }
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
    var seekPreviewTo by remember { mutableStateOf<((Long) -> Unit)?>(null) }
    var previewPositionMs by remember { mutableStateOf(0L) }
    var startRender by remember { mutableStateOf<(() -> Unit)?>(null) }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val proj = project
        if (uri != null && proj != null) {
            // Without this, the read permission GetContent() grants is only
            // valid for this app session — reopening the project later
            // (or just restarting the app) throws a SecurityException when
            // MediaMetadataRetriever/WaveformExtractor/MediaPlayer try to
            // read the same content:// uri again, and every catch block
            // around those calls silently swallows it — which is what made
            // the audio track look "empty" (no waveform) intermittently.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers (e.g. certain file managers) don't grant
                // persistable permission — audio still works this session.
            }
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
    CompositionLocalProvider(LocalImageBitmapCache provides imageBitmapCache) {
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
                    onTogglePlayPauseReady = { togglePlayPause = it },
                    positionMs = previewPositionMs,
                    onPositionChange = { previewPositionMs = it },
                    onSeekReady = { seekPreviewTo = it }
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

            // ---- TIMELINE (~30%) — FIXED area, no scroll: only the
            // video+audio timeline lives here now, exactly like the
            // reference (Preview above, timeline below, nothing else
            // sharing this space so nothing can push it into a scroll).
            // The old SummaryCard + "Timeline (N images)" label + the
            // RenderSection duplicate (progress/preview/share) used to sit
            // in this same scrollable Column — RenderSection was fully
            // redundant anyway (the top-bar Export button + its
            // ExportProgressOverlay already show the same progress/preview
            // /share), so it's removed here rather than kept and hidden.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.30f)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                if (doneImages.isEmpty()) {
                    Text(
                        text = "ابھی کوئی image تیار نہیں — پہلے Prompts screen پر جا کر Generate All چلائیں",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    VideoAudioTimelineView(
                        project = currentProject,
                        timeline = timeline,
                        playheadMs = previewPositionMs,
                        onScrubStart = { if (isPlaying) togglePlayPause?.invoke() },
                        onScrub = { ms -> seekPreviewTo?.invoke(ms) },
                        onClipTapped = { clipId ->
                            editingDurationFor = timeline.find { it.prompt.id.toString() == clipId }?.prompt
                        },
                        onJoinTapped = { showTransitionSheet = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Render is triggered from the top-bar Export button, not from
            // here — this just defines what it runs. No UI of its own, so
            // it doesn't affect the fixed timeline area above.
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

            // ---- NAVIGATION (~9.8%) — extracted from the old Editor; layout only, no button functions wired yet ----
            Box(modifier = Modifier.fillMaxWidth().weight(0.098f)) {
                BottomToolbarReference(
                    iconSize = navIconSize,
                    actions = listOf(
                        ToolbarAction(R.drawable.ic_trim, "Split") { },
                        ToolbarAction(R.drawable.ic_text, "Text") { },
                        ToolbarAction(R.drawable.ic_audio, "Audio") { audioLauncher.launch("audio/*") },
                        ToolbarAction(R.drawable.ic_volume, "Volume") { showVolumeSheet = true },
                        ToolbarAction(R.drawable.ic_noise, "Noise") { showNoiseSheet = true },
                        ToolbarAction(R.drawable.ic_speed, "Speed") { },
                        ToolbarAction(R.drawable.ic_filter, "Transition") { showTransitionSheet = true },
                        ToolbarAction(R.drawable.ic_rotate, "Motion") { showMotionSheet = true },
                        ToolbarAction(R.drawable.ic_overlay, "Sync") { showSyncSheet = true },
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

        if (showNoiseSheet) {
            currentProject?.let { proj ->
                SmallSliderSheet(
                    title = "Noise Reduction",
                    subtitle = "اصل FFmpeg denoiser (afftdn) — 0% مطلب کچھ نہیں لگا",
                    value = proj.noiseReductionPercent,
                    valueRange = 0..100,
                    valueLabel = { "$it%" },
                    isProcessing = audioProcessing,
                    onDismiss = { showNoiseSheet = false },
                    onApply = { percent ->
                        val voiceOverUri = proj.voiceOverUri
                        if (voiceOverUri == null) {
                            showNoiseSheet = false
                            return@SmallSliderSheet
                        }
                        audioProcessing = true
                        AudioProcessor.process(
                            context = context,
                            voiceOverUri = voiceOverUri,
                            noiseReductionPercent = percent,
                            volumePercent = proj.volumePercent,
                            onComplete = { file ->
                                audioProcessing = false
                                showNoiseSheet = false
                                scope.launch {
                                    val updated = proj.copy(
                                        noiseReductionPercent = percent,
                                        processedAudioPath = file?.absolutePath ?: proj.processedAudioPath
                                    )
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        )
                    }
                )
            }
        }

        if (showVolumeSheet) {
            currentProject?.let { proj ->
                SmallSliderSheet(
                    title = "Volume",
                    subtitle = "اصل FFmpeg gain (volume filter) — 100% مطلب اصل volume",
                    value = proj.volumePercent,
                    valueRange = 0..500,
                    valueLabel = { "$it%" },
                    isProcessing = audioProcessing,
                    onDismiss = { showVolumeSheet = false },
                    onApply = { percent ->
                        val voiceOverUri = proj.voiceOverUri
                        if (voiceOverUri == null) {
                            showVolumeSheet = false
                            return@SmallSliderSheet
                        }
                        audioProcessing = true
                        AudioProcessor.process(
                            context = context,
                            voiceOverUri = voiceOverUri,
                            noiseReductionPercent = proj.noiseReductionPercent,
                            volumePercent = percent,
                            onComplete = { file ->
                                audioProcessing = false
                                showVolumeSheet = false
                                scope.launch {
                                    val updated = proj.copy(
                                        volumePercent = percent,
                                        processedAudioPath = file?.absolutePath ?: proj.processedAudioPath
                                    )
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        )
                    }
                )
            }
        }

        if (showSyncSheet) {
            currentProject?.let { proj ->
                ChoiceBottomSheet(
                    title = "Sync Mode",
                    subtitle = "Images duration ke sath kaise sync hongi",
                    options = listOf(
                        ChoiceOption(
                            label = "Scale images",
                            selected = proj.timelineMode == TimelineMode.SCALE,
                            onSelect = {
                                scope.launch {
                                    val updated = proj.copy(timelineMode = TimelineMode.SCALE)
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        ),
                        ChoiceOption(
                            label = "Hold last image",
                            selected = proj.timelineMode == TimelineMode.HOLD_LAST,
                            onSelect = {
                                scope.launch {
                                    val updated = proj.copy(timelineMode = TimelineMode.HOLD_LAST)
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        )
                    ),
                    onDismiss = { showSyncSheet = false }
                )
            }
        }

        if (showTransitionSheet) {
            currentProject?.let { proj ->
                ChoiceBottomSheet(
                    title = "Transition",
                    subtitle = "Do images ke darmiyan cut ka style",
                    options = listOf(
                        ChoiceOption(
                            label = "Crossfade",
                            selected = proj.transitionType == TransitionType.CROSSFADE,
                            onSelect = {
                                scope.launch {
                                    val updated = proj.copy(transitionType = TransitionType.CROSSFADE)
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        ),
                        ChoiceOption(
                            label = "Slide",
                            selected = proj.transitionType == TransitionType.SLIDE,
                            onSelect = {
                                scope.launch {
                                    val updated = proj.copy(transitionType = TransitionType.SLIDE)
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        )
                    ),
                    onDismiss = { showTransitionSheet = false }
                )
            }
        }

        if (showMotionSheet) {
            currentProject?.let { proj ->
                ChoiceBottomSheet(
                    title = "Motion Effect",
                    subtitle = "Har image par live camera movement",
                    options = listOf(
                        ChoiceOption(
                            label = "Zoom-In",
                            selected = proj.motionEffect == MotionEffect.ZOOM_IN,
                            onSelect = {
                                scope.launch {
                                    val updated = proj.copy(motionEffect = MotionEffect.ZOOM_IN)
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        ),
                        ChoiceOption(
                            label = "Pan",
                            selected = proj.motionEffect == MotionEffect.PAN,
                            onSelect = {
                                scope.launch {
                                    val updated = proj.copy(motionEffect = MotionEffect.PAN)
                                    dao.updateProject(updated)
                                    project = updated
                                }
                            }
                        )
                    ),
                    onDismiss = { showMotionSheet = false }
                )
            }
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
    onTogglePlayPauseReady: (() -> Unit) -> Unit,
    positionMs: Long,
    onPositionChange: (Long) -> Unit,
    onSeekReady: ((Long) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var waveform by remember(project.voiceOverUri) { mutableStateOf(FloatArray(0)) }

    // Real decoded-audio waveform (see WaveformExtractor's doc comment,
    // ported from VELLORA-ENGINE) — purely decorative under the scrub
    // slider, computed once per voice-over file.
    LaunchedEffect(project.voiceOverUri, project.processedAudioPath) {
        val processedPath = project.processedAudioPath
        waveform = try {
            if (processedPath != null && File(processedPath).exists()) {
                WaveformExtractor(context).extract(Uri.fromFile(File(processedPath)))
            } else {
                project.voiceOverUri?.let { WaveformExtractor(context).extract(Uri.parse(it)) } ?: FloatArray(0)
            }
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    DisposableEffect(project.voiceOverUri, project.processedAudioPath) {
        val uriString = project.voiceOverUri
        val processedPath = project.processedAudioPath
        val mp = if (uriString != null) {
            try {
                MediaPlayer().apply {
                    // Prefer the noise-reduced/volume-adjusted file (real
                    // FFmpeg processing) so Preview actually plays what the
                    // person just set, not the untouched original.
                    if (processedPath != null && File(processedPath).exists()) {
                        setDataSource(processedPath)
                    } else {
                        setDataSource(context, Uri.parse(uriString))
                    }
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
            val resumeFrom = if (positionMs >= totalMs) 0L else positionMs
            if (resumeFrom != positionMs) onPositionChange(resumeFrom)
            mp?.takeIf { isPrepared }?.let { it.seekTo(resumeFrom.toInt()); it.start() }
            onIsPlayingChange(true)
        }
    }
    SideEffect { onTogglePlayPauseReady(togglePlayPause) }

    // Lets anything outside this composable (the new dual-track timeline's
    // scrub gesture) move playback to an exact position — same seek the
    // slider below already does, just reachable from a sibling composable.
    val seekTo: (Long) -> Unit = { newPositionMs ->
        val clamped = newPositionMs.coerceIn(0L, totalMs)
        mediaPlayer?.takeIf { isPrepared }?.seekTo(clamped.toInt())
        onPositionChange(clamped)
    }
    SideEffect { onSeekReady(seekTo) }

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
                val next = if (mp != null && isPrepared) {
                    mp.currentPosition.toLong()
                } else {
                    (positionMs + elapsed).coerceAtMost(totalMs)
                }
                if (next >= totalMs) {
                    onIsPlayingChange(false)
                    onPositionChange(0L)
                } else {
                    onPositionChange(next)
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
    val nextImage = timeline.getOrNull(currentIndex + 1)

    // Same math RenderEngine actually uses, so the live preview's motion
    // effect (zoom-in/pan) and transition (crossfade/slide) match the real
    // rendered mp4 as closely as a static-image Compose layer can — no more
    // waiting for a full FFmpeg render just to see how a choice looks.
    val currentStartMs = offsets.getOrNull(currentIndex) ?: 0L
    val currentDurationMs = currentImage?.durationMs?.coerceAtLeast(1L) ?: 1L
    val localProgress = ((positionMs - currentStartMs).toFloat() / currentDurationMs.toFloat()).coerceIn(0f, 1f)
    val transitionDurationMs = (RenderEngine.TRANSITION_DURATION_SEC * 1000).toLong()
    val transitionWindowStart = (1f - transitionDurationMs.toFloat() / currentDurationMs.toFloat()).coerceIn(0f, 1f)
    val inTransition = nextImage != null && localProgress >= transitionWindowStart
    val transitionT = if (inTransition) {
        ((localProgress - transitionWindowStart) / (1f - transitionWindowStart).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    } else 0f

    val aspectRatio = if (project.resolution == "tiktok") 9f / 16f else 16f / 9f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // BoxWithConstraints so the actual available width/height are known
        // — needed to correctly fit a 9:16 (TikTok) or 16:9 (YouTube) frame
        // inside whatever space this preview area has, exactly like the
        // real render's frame, instead of stretching to fill a fixed
        // rectangle regardless of the project's chosen resolution.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val fittedWidth = minOf(maxWidth, maxHeight * aspectRatio)
            val fittedHeight = fittedWidth / aspectRatio

            Box(
                modifier = Modifier
                    .width(fittedWidth)
                    .height(fittedHeight)
                    // Zoom-In motion effect scales the image up beyond 1.0x
                    // via graphicsLayer — without clipping, the scaled-up
                    // image rendered right past this frame's edges and
                    // overlapped whatever was above/below (top bar,
                    // controls, timeline). clipToBounds() confines it
                    // strictly inside the frame, same as a real video
                    // player would.
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
            val bitmap = currentImage?.let { rememberDecodedBitmap(it.prompt.imagePath) }
            val nextBitmap = if (inTransition) nextImage?.let { rememberDecodedBitmap(it.prompt.imagePath) } else null

            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            applyLiveMotionAndTransition(
                                scope = this,
                                motionEffect = project.motionEffect,
                                motionProgress = localProgress,
                                transitionType = project.transitionType,
                                transitionT = transitionT,
                                isIncomingLayer = false,
                                inTransition = inTransition
                            )
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(text = "🖼️", fontSize = 40.sp)
            }

            if (nextBitmap != null) {
                Image(
                    bitmap = nextBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            applyLiveMotionAndTransition(
                                scope = this,
                                motionEffect = project.motionEffect,
                                motionProgress = 0f, // incoming image starts its own motion effect fresh
                                transitionType = project.transitionType,
                                transitionT = transitionT,
                                isIncomingLayer = true,
                                inTransition = true
                            )
                        },
                    contentScale = ContentScale.Fit
                )
            }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (waveform.isNotEmpty()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    val barCount = waveform.size
                    val barWidth = size.width / barCount
                    val playedFraction = (positionMs.toFloat() / max(totalMs, 1L).toFloat()).coerceIn(0f, 1f)
                    val playedBars = (barCount * playedFraction).toInt()
                    waveform.forEachIndexed { index, amplitude ->
                        val barHeight = (amplitude * size.height).coerceAtLeast(2f)
                        drawRect(
                            color = if (index <= playedBars) CyanPrimary else Color.White.copy(alpha = 0.25f),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                index * barWidth,
                                (size.height - barHeight) / 2f
                            ),
                            size = androidx.compose.ui.geometry.Size(barWidth * 0.7f, barHeight)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Slider(
                value = positionMs.toFloat().coerceIn(0f, max(totalMs, 1L).toFloat()),
                valueRange = 0f..max(totalMs, 1L).toFloat(),
                onValueChange = { value ->
                    isScrubbing = true
                    onPositionChange(value.toLong())
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
    val cache = LocalImageBitmapCache.current
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            try {
                (cache?.load(path) ?: run {
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(path, options)
                })?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
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

/**
 * Wraps the ported VELLORA-ENGINE [TimelineView] (a Canvas-based
 * dual-track timeline: video/image clips on top, the voice-over's real
 * waveform underneath) inside Compose via AndroidView — the same pattern
 * VELLORA-ENGINE's own MainActivity already uses. Auto-builds its
 * [Timeline] straight from the already-computed [timeline] (images) and
 * [project]'s voice-over — nothing here is manually placed, so every
 * newly generated image appears on it automatically, in order, each
 * clip's width proportional to its computed duration exactly like the
 * cuts in Preview/RenderEngine (same [TimelineImage] data, same
 * durations).
 *
 * Phase 1 (this pass): visual placement + real thumbnails + real
 * waveform + the same cut boundaries Preview/RenderEngine use. Scrubbing
 * this timeline to drive Preview's playhead, and tap-to-edit a clip's
 * duration (previously [TimelineImageRow]'s pencil icon), are follow-up
 * wiring — not done here yet.
 */
@Composable
private fun VideoAudioTimelineView(
    project: AutoGenProjectEntity,
    timeline: List<TimelineImage>,
    playheadMs: Long,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onClipTapped: (String) -> Unit,
    onJoinTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cache = LocalImageBitmapCache.current

    val modelTimeline = remember(timeline, project.voiceOverUri, project.voiceOverDurationMs, project.processedAudioPath) {
        val offsets = timelineStartOffsets(timeline)
        val videoClips = timeline.mapIndexed { index, item ->
            TimelineClip(
                id = item.prompt.id.toString(),
                startMs = offsets[index],
                durationMs = item.durationMs,
                label = "%03d".format(index + 1),
                sourceUri = item.prompt.imagePath,
                isVideo = false
            )
        }
        val audioUri = project.processedAudioPath ?: project.voiceOverUri
        val audioClips = if (audioUri != null && project.voiceOverDurationMs > 0) {
            listOf(
                TimelineClip(
                    id = "audio_track",
                    startMs = 0L,
                    durationMs = project.voiceOverDurationMs,
                    label = "Voice-over",
                    sourceUri = audioUri,
                    isVideo = false
                )
            )
        } else emptyList()
        Timeline(clips = videoClips, audioClips = audioClips)
    }

    // Real decoded waveform for the audio track — same extractor/logic
    // PreviewPlayer already uses, prefers the processed (noise/volume)
    // file when one exists so the two views never disagree.
    var waveform by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(project.voiceOverUri, project.processedAudioPath) {
        val processedPath = project.processedAudioPath
        waveform = try {
            if (processedPath != null && File(processedPath).exists()) {
                WaveformExtractor(context).extract(Uri.fromFile(File(processedPath)))
            } else {
                project.voiceOverUri?.let { WaveformExtractor(context).extract(Uri.parse(it)) } ?: FloatArray(0)
            }
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    // Real decoded image thumbnails (one static frame per clip is enough
    // for a still image — see TimelineView's drawFilmstrip) via the same
    // cache Preview already uses, so this never re-decodes a file Preview
    // just decoded a moment ago.
    var thumbnails by remember { mutableStateOf<Map<String, List<Bitmap>>>(emptyMap()) }
    LaunchedEffect(timeline) {
        thumbnails = withContext(Dispatchers.IO) {
            timeline.mapNotNull { item ->
                val path = item.prompt.imagePath ?: return@mapNotNull null
                val bmp = cache?.load(path)
                    ?: try {
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
                    } catch (e: Exception) {
                        null
                    }
                bmp?.let { item.prompt.id.toString() to listOf(it) }
            }.toMap()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> TimelineView(ctx) },
        update = { view ->
            view.trackHeightPx = with(density) { 70.dp.toPx() }
            view.waveformHeightPx = with(density) { 40.dp.toPx() }
            view.timeline = modelTimeline
            view.waveform = waveform
            view.audioDurationMs = project.voiceOverDurationMs
            view.clipThumbnails = thumbnails
            view.playheadMs = playheadMs
            view.onScrubStart = onScrubStart
            view.onScrub = onScrub
            view.onClipSelected = { clipId -> if (clipId != null && clipId != "audio_track") onClipTapped(clipId) }
            view.onJoinTapped = { _, _ -> onJoinTapped() }
        }
    )
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
 * One small bottom card with a title + slider + Apply button — used for
 * both Noise Reduction and Volume so far. Deliberately compact (a card
 * anchored to the bottom, not a full page) per explicit feedback that an
 * earlier full-screen sheet was too much for a single slider control.
 */
@Composable
private fun SmallSliderSheet(
    title: String,
    subtitle: String,
    value: Int,
    valueRange: IntRange,
    valueLabel: (Int) -> String,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit
) {
    var sliderValue by remember { mutableStateOf(value.toFloat()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(SurfaceDark)
                .clickable(enabled = false) { } // absorbs taps so they don't fall through to onDismiss
                .padding(20.dp)
        ) {
            Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, color = TextSecondary, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = valueLabel(sliderValue.toInt()), color = CyanPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = sliderValue,
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                onValueChange = { sliderValue = it },
                colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isProcessing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Processing (real FFmpeg)…", color = TextSecondary, fontSize = 12.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onApply(sliderValue.toInt()) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text(text = "Apply", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text(text = "Cancel", color = TextPrimary)
                    }
                }
            }
        }
    }
}

/** One tappable choice inside a [ChoiceBottomSheet] — e.g. "Crossfade". */
private data class ChoiceOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit
)

/**
 * Same small bottom-card look as [SmallSliderSheet] (anchored to the
 * bottom, not full-screen) but for chip-style choices instead of a
 * slider — used for Sync Mode, Transition, and Motion Effect, which used
 * to sit inline in the scrollable timeline column. Picking an option
 * applies immediately (same behaviour the inline chips always had); this
 * sheet is just a compact place to reach them from the toolbar instead of
 * scrolling past them.
 */
@Composable
private fun ChoiceBottomSheet(
    title: String,
    subtitle: String,
    options: List<ChoiceOption>,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(SurfaceDark)
                .clickable(enabled = false) { } // absorbs taps so they don't fall through to onDismiss
                .padding(20.dp)
        ) {
            Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, color = TextSecondary, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { option ->
                    ModeChip(
                        label = option.label,
                        selected = option.selected,
                        onClick = option.onSelect
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Done", color = TextPrimary)
            }
        }
    }
}

/**
 * Applies the SAME motion-effect (zoom-in/pan) and transition
 * (crossfade/slide) math RenderEngine bakes into the real rendered mp4 —
 * but as a live Compose graphicsLayer transform, so the preview shows a
 * close visual approximation without running FFmpeg. [motionProgress] is
 * 0f..1f through this specific layer's own on-screen duration.
 * [isIncomingLayer] is the "next" image fading/sliding IN during a
 * transition; the "current" image is always the outgoing layer.
 */
private fun applyLiveMotionAndTransition(
    scope: GraphicsLayerScope,
    motionEffect: String,
    motionProgress: Float,
    transitionType: String,
    transitionT: Float,
    isIncomingLayer: Boolean,
    inTransition: Boolean
) {
    when (motionEffect) {
        MotionEffect.PAN -> {
            scope.scaleX = 1.15f
            scope.scaleY = 1.15f
            scope.translationX = (motionProgress - 0.5f) * scope.size.width * 0.18f
        }
        else -> { // ZOOM_IN
            val scale = 1f + 0.3f * motionProgress
            scope.scaleX = scale
            scope.scaleY = scale
        }
    }

    if (inTransition) {
        when (transitionType) {
            TransitionType.SLIDE -> {
                scope.translationX += if (isIncomingLayer) {
                    scope.size.width * (1f - transitionT)
                } else {
                    -scope.size.width * transitionT
                }
            }
            else -> { // CROSSFADE
                scope.alpha = if (isIncomingLayer) transitionT else 1f - transitionT
            }
        }
    }
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
