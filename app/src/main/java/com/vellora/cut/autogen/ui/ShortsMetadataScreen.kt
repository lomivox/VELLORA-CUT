package com.vellora.cut.autogen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.data.ShortMetadataEntity
import com.vellora.cut.autogen.data.ShortMetadataStatus
import com.vellora.cut.autogen.network.YouTubeAuthManager
import com.vellora.cut.autogen.network.YouTubeUploader
import com.vellora.cut.autogen.shorts.ShortsMetadataGenerator
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.BackgroundDark
import com.vellora.cut.ui.theme.CyanPrimary
import com.vellora.cut.ui.theme.SurfaceDark
import com.vellora.cut.ui.theme.SurfaceVariant
import com.vellora.cut.ui.theme.TextPrimary
import com.vellora.cut.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun ShortsMetadataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(context).shortMetadataDao() }
    val projects by dao.observeAll().collectAsState(initial = emptyList())

    var activeProject by remember { mutableStateOf<ShortMetadataEntity?>(null) }
    var outputLanguage by remember { mutableStateOf("Urdu") } // "Urdu", "English" or "Roman Urdu"
    var isWorking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var classificationHint by remember { mutableStateOf<String?>(null) }
    var uploadTarget by remember { mutableStateOf(com.vellora.cut.autogen.data.UploadTarget.AUTO) }
    var manualTopicText by remember { mutableStateOf("") }
    // Optional hint typed BEFORE picking a video: tells the AI what the video is really about (decides keywords + title).
    var videoHintText by remember { mutableStateOf("") }
    // The hint each video was generated with, so Retry uses the same hint (it is cleared from the field after use).
    val hintByEntity = remember { mutableMapOf<Long, String>() }
    var showHistory by remember { mutableStateOf(false) }
    // Back closes the History list first, before leaving this page.
    androidx.activity.compose.BackHandler(enabled = showHistory) { showHistory = false }
    val credStore = remember { SecureCredentialStore(context) }
    var autoUploadEnabled by remember { mutableStateOf(credStore.autoUploadEnabled) }
    var renameToTitle by remember { mutableStateOf(credStore.renameVideoToTitle) }
    // false = SEO Auto (video is analysed, AI writes title/description/tags); true = Manual (you write them).
    var manualMode by remember { mutableStateOf(false) }
    var manualTitle by remember { mutableStateOf("") }
    var manualDescription by remember { mutableStateOf("") }
    var manualTags by remember { mutableStateOf("") }
    // The saved draft that a video is being attached to (draft made earlier, video picked now).
    var attachTarget by remember { mutableStateOf<ShortMetadataEntity?>(null) }
    // The connected YouTube account (channel) this upload goes to; picked right on this screen.
    var uploadAccountEmail by remember { mutableStateOf(com.vellora.cut.autogen.network.YouTubeAccounts.selectedEmail(context)) }
    // Optional custom thumbnail for the next upload (set on YouTube after the video is up).
    var thumbnailUri by remember { mutableStateOf<Uri?>(null) }
    val thumbnailPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) thumbnailUri = uri }

    // A background upload writes its result straight into the database; follow it so the card updates itself.
    LaunchedEffect(projects) {
        val current = activeProject ?: return@LaunchedEffect
        val fresh = projects.firstOrNull { it.id == current.id } ?: return@LaunchedEffect
        if (fresh.status != current.status || fresh.youtubeVideoUrl != current.youtubeVideoUrl || fresh.errorMessage != current.errorMessage) {
            activeProject = fresh
        }
    }

    suspend fun uploadToYouTube(entity0: ShortMetadataEntity): ShortMetadataEntity {
        var entity = entity0
        if (entity.videoUri.isBlank()) {
            return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Is entry ke sath koi video nahi hai (sirf topic se bana tha) — upload nahi ho sakti")
        }
        val accountEmail = uploadAccountEmail
            ?: com.vellora.cut.autogen.network.YouTubeAccounts.selectedEmail(context)
            ?: return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Pehle koi YouTube account connect karein (neeche \"Connect Account\" ya Settings)")

        // Before uploading, rename the video after its generated title (like renaming it in the Gallery),
        // so the file itself says what the video is about. Best-effort: if the phone refuses, upload goes on.
        val newBase = if (renameToTitle) com.vellora.cut.autogen.render.VideoRenamer.fileNameFromTitle(entity.generatedTitle.orEmpty()) else null
        if (newBase != null) {
            val ext = entity.videoFileName.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "mp4"
            val newName = "$newBase.$ext"
            if (!entity.videoFileName.equals(newName, ignoreCase = true)) {
                val newUri = withContext(Dispatchers.IO) {
                    com.vellora.cut.autogen.render.VideoRenamer.renameInGallery(context, entity.videoUri, newName)
                }
                if (newUri != null) entity = entity.copy(videoUri = newUri.toString(), videoFileName = newName)
            }
        }

        val videoFile = withContext(Dispatchers.IO) {
            resolveVideoToLocalFile(context, entity.videoUri)
        } ?: return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Video file nahi mili upload ke liye")

        val reshapedFile = if (entity.uploadTarget != com.vellora.cut.autogen.data.UploadTarget.AUTO) {
            val target = if (entity.uploadTarget == com.vellora.cut.autogen.data.UploadTarget.SHORT) {
                com.vellora.cut.autogen.render.VideoReshaper.Target.SHORT
            } else {
                com.vellora.cut.autogen.render.VideoReshaper.Target.LONG
            }
            // reshapeIfNeeded used to have no fallback here — one failed encode (device
            // encoder hiccup, odd source file, etc.) hard-failed the whole upload with
            // no retry path, unlike "Auto" below which always has something to fall back
            // to. Now it falls back the same way: try normalize (plain re-encode, no
            // crop/trim) and only fall back to the untouched original if that also fails,
            // so the person still gets an upload instead of a dead end.
            com.vellora.cut.autogen.render.VideoReshaper.reshapeIfNeeded(context, videoFile, target)
                ?: com.vellora.cut.autogen.render.VideoReshaper.normalize(context, videoFile)
                ?: videoFile
        } else {
            // "Auto" used to mean "send the original bytes untouched" — but
            // an original file with any unusual codec/container quirk can
            // then get permanently stuck on YouTube's "Processing will
            // begin shortly" (the SAME file fails the same way every retry,
            // since the file itself is the problem, not the network or a
            // transient server issue). Normalizing to plain H.264/AAC first
            // is the standard fix, and costs little for a file that was
            // already fine.
            com.vellora.cut.autogen.render.VideoReshaper.normalize(context, videoFile) ?: videoFile
        }

        val tags = entity.generatedTags.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
        val fullDescription = buildString {
            append(entity.generatedDescription.orEmpty())
            if (!entity.generatedHashtags.isNullOrBlank()) {
                append("\n\n")
                append(entity.generatedHashtags)
            }
        }

        val uploadFile = if (newBase != null) {
            withContext(Dispatchers.IO) { com.vellora.cut.autogen.render.VideoRenamer.renameLocalCopy(context, reshapedFile, newBase) }
        } else reshapedFile

        // The long part — sending the file — runs in a background worker (notification, progress, Cancel, resume),
        // so it keeps going when the app is closed. Here we only stage the files and start it.
        val stagedVideo = withContext(Dispatchers.IO) {
            com.vellora.cut.autogen.work.UploadStaging.stageVideo(context, uploadFile, entity.id)
        } ?: return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Video upload ke liye tayyar nahi ho saki (jagah kam ho sakti hai)")

        val stagedThumbnail = thumbnailUri?.let { picked ->
            withContext(Dispatchers.IO) {
                com.vellora.cut.autogen.work.UploadStaging.stageThumbnail(context, picked, entity.id)
            }
        }

        entity = entity.copy(status = ShortMetadataStatus.UPLOADING, errorMessage = null)
        dao.update(entity)
        com.vellora.cut.autogen.work.YouTubeUploadWorker.enqueue(
            context = context,
            entityId = entity.id,
            videoPath = stagedVideo.absolutePath,
            thumbPath = stagedThumbnail?.absolutePath,
            accountKey = accountEmail
        )
        thumbnailUri = null
        return entity
    }

    /** Uploads [entity] (its title/description/tags as they are now) and saves the result. Keeps the process alive while it runs. */
    suspend fun uploadAndSave(entity: ShortMetadataEntity) {
        isWorking = true
        statusText = "uploading"
        com.vellora.cut.autogen.render.RenderKeepAliveService.start(context, "YouTube par upload ho rahi hai…")
        try {
            var updated = entity.copy(status = ShortMetadataStatus.UPLOADING, errorMessage = null)
            activeProject = updated
            updated = uploadToYouTube(updated)
            dao.update(updated)
            activeProject = updated
            if (updated.status == ShortMetadataStatus.ERROR) errorMessage = updated.errorMessage
        } finally {
            isWorking = false
            com.vellora.cut.autogen.render.RenderKeepAliveService.stop(context)
        }
    }

    suspend fun runGenerationFlow(entity: ShortMetadataEntity, videoUriForGen: String?, manualTopic: String?, topicHint: String? = null) {
        val accounts = SecureCredentialStore(context).accounts
        if (accounts.isEmpty() && SecureCredentialStore(context).geminiKeys.isEmpty()) {
            errorMessage = "Pehle Settings mein Gemini key ya Cloudflare account add karein"
            val updated = entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = errorMessage)
            dao.update(updated)
            activeProject = updated
            return
        }
        isWorking = true
        com.vellora.cut.autogen.render.RenderKeepAliveService.start(context, "Shorts Metadata ban rahi hai…")
        try {
            val result = ShortsMetadataGenerator.generate(
                context = context,
                videoUri = videoUriForGen,
                manualTopic = manualTopic,
                accounts = accounts,
                language = outputLanguage,
                topicHint = topicHint,
                onStatusChange = { statusText = it }
            )
            isWorking = false

            result.onSuccess { meta ->
                var updated = entity.copy(
                    status = ShortMetadataStatus.DONE,
                    transcript = meta.transcript,
                    researchedKeywords = meta.researchedKeywords.joinToString(","),
                    generatedTitle = meta.title,
                    generatedDescription = meta.description,
                    generatedTags = meta.tags,
                    generatedHashtags = meta.hashtags
                )
                dao.update(updated)
                activeProject = updated

                if (autoUploadEnabled && updated.videoUri.isNotBlank()) {
                    isWorking = true
                    statusText = "uploading"
                    updated = updated.copy(status = ShortMetadataStatus.UPLOADING)
                    activeProject = updated
                    updated = uploadToYouTube(updated)
                    dao.update(updated)
                    activeProject = updated
                    isWorking = false
                    if (updated.status == ShortMetadataStatus.ERROR) {
                        errorMessage = updated.errorMessage
                    }
                }
            }.onFailure { e ->
                val updated = entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = e.message)
                dao.update(updated)
                activeProject = updated
                errorMessage = e.message
            }
        } finally {
            // Covers the generation call AND the optional YouTube upload
            // that can follow it — the process needs to survive screen-off
            // for either, and this fires whichever branch above ran, or
            // even if something throws unexpectedly.
            isWorking = false
            com.vellora.cut.autogen.render.RenderKeepAliveService.stop(context)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) { /* some providers don't support it — fine for this session */ }

        errorMessage = null
        classificationHint = predictYouTubeClassification(context, uri)
        val fileName = queryDisplayName(context, uri) ?: "video"
        if (manualMode) {
            // Manual: the person's own title/description/tags go straight to YouTube — no AI step.
            val title = manualTitle.trim()
            if (title.isBlank()) { errorMessage = "Pehle apna title likhein"; return@rememberLauncherForActivityResult }
            scope.launch {
                var entity = ShortMetadataEntity(
                    videoUri = uri.toString(),
                    videoFileName = fileName,
                    createdAt = System.currentTimeMillis(),
                    status = ShortMetadataStatus.DONE,
                    generatedTitle = title,
                    generatedDescription = manualDescription.trim(),
                    generatedTags = manualTags.trim(),
                    generatedHashtags = "",
                    uploadTarget = uploadTarget
                )
                val id = dao.insert(entity)
                entity = entity.copy(id = id)
                activeProject = entity
                uploadAndSave(entity)
            }
            return@rememberLauncherForActivityResult
        }
        val hintForThisVideo = videoHintText.trim()
        videoHintText = "" // used once — never leaks into the next video
        scope.launch {
            var entity = ShortMetadataEntity(
                videoUri = uri.toString(),
                videoFileName = fileName,
                createdAt = System.currentTimeMillis(),
                status = ShortMetadataStatus.EXTRACTING_AUDIO,
                uploadTarget = uploadTarget
            )
            val id = dao.insert(entity)
            entity = entity.copy(id = id)
            if (hintForThisVideo.isNotBlank()) hintByEntity[id] = hintForThisVideo
            activeProject = entity
            runGenerationFlow(entity, videoUriForGen = uri.toString(), manualTopic = null, topicHint = hintForThisVideo)
        }
    }

    // A saved draft (title/description/tags made earlier) gets its video now, then uploads with that exact metadata.
    val attachPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val target = attachTarget
        attachTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) { /* fine for this session */ }
        errorMessage = null
        classificationHint = predictYouTubeClassification(context, uri)
        val attached = target.copy(
            videoUri = uri.toString(),
            videoFileName = queryDisplayName(context, uri) ?: "video",
            status = ShortMetadataStatus.DONE,
            errorMessage = null
        )
        scope.launch {
            dao.update(attached)
            activeProject = attached
            uploadAndSave(attached)
        }
    }

    fun saveManualDraft() {
        val title = manualTitle.trim()
        if (title.isBlank()) { errorMessage = "Pehle apna title likhein"; return }
        errorMessage = null
        scope.launch {
            var entity = ShortMetadataEntity(
                videoUri = "",
                videoFileName = title.take(40),
                createdAt = System.currentTimeMillis(),
                status = ShortMetadataStatus.DONE,
                transcript = title,
                generatedTitle = title,
                generatedDescription = manualDescription.trim(),
                generatedTags = manualTags.trim(),
                generatedHashtags = ""
            )
            val id = dao.insert(entity)
            entity = entity.copy(id = id)
            activeProject = entity
        }
    }

    fun generateFromTopic() {
        val topic = manualTopicText.trim()
        if (topic.isBlank()) return
        errorMessage = null
        scope.launch {
            var entity = ShortMetadataEntity(
                videoUri = "",
                videoFileName = topic.take(40),
                createdAt = System.currentTimeMillis(),
                status = ShortMetadataStatus.RESEARCHING_KEYWORDS
            )
            val id = dao.insert(entity)
            entity = entity.copy(id = id)
            activeProject = entity
            runGenerationFlow(entity, videoUriForGen = null, manualTopic = topic)
        }
    }

    fun retry(entity: ShortMetadataEntity) {
        errorMessage = null
        scope.launch {
            if (entity.generatedTitle != null) {
                // Metadata already exists — only the upload step failed.
                uploadAndSave(entity)
            } else if (entity.videoUri.isNotBlank()) {
                runGenerationFlow(entity, videoUriForGen = entity.videoUri, manualTopic = null, topicHint = hintByEntity[entity.id])
            } else {
                // Topic-only entry — entity.transcript holds the originally typed topic.
                runGenerationFlow(entity, videoUriForGen = null, manualTopic = entity.transcript)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // ------------------------------------------------------------ top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // YTIQ uses the phone's own back button (it also closes History); the arrow stays only in VELLORA CUT.
            if (!com.vellora.cut.BuildConfig.IS_YTIQ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(SurfaceDark)
                        .clickable { if (showHistory) showHistory = false else onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "←", color = TextPrimary, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (showHistory) "History" else "Shorts Metadata",
                    color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                if (showHistory) {
                    Text(text = "${projects.size} entries", color = TextSecondary, fontSize = 11.sp)
                }
            }
            if (!showHistory) {
                Surface(
                    onClick = { showHistory = true },
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceDark
                ) {
                    Text(
                        text = "📋  ${projects.size}",
                        color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (showHistory) {
            if (projects.isEmpty()) {
                Text(
                    text = "Abhi koi entry nahi hai.",
                    color = TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(projects) { p ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .clickable { activeProject = p; errorMessage = null; showHistory = false }
                            .padding(14.dp)
                    ) {
                        Text(
                            text = p.generatedTitle ?: p.videoFileName,
                            color = TextPrimary, fontSize = 14.sp, maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (p.youtubeVideoUrl != null) "✅ Uploaded" else p.status,
                            color = if (p.youtubeVideoUrl != null) CyanPrimary else TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            return@Column
        }

        // ------------------------------------------------------------ main page
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            ModeToggle(manualMode = manualMode, onChange = { manualMode = it; errorMessage = null })
            Text(
                text = if (manualMode) "Aap khud likhein — video bina AI ke upload hogi."
                       else "Video sun kar SEO Title/Description/Tags khud banenge.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )

            SectionCard(title = "Upload kis channel par?") {
                UploadAccountSelector(
                    modifier = Modifier,
                    showLabel = false,
                    onSelected = { uploadAccountEmail = it }
                )
            }

            if (manualMode) {
                val fc = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = CyanPrimary
                )
                SectionCard(title = "Title aur details", subtitle = "Title zaroori hai, baaki optional") {
                    OutlinedTextField(
                        value = manualTitle, onValueChange = { manualTitle = it },
                        label = { Text("Title (zaroori)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), colors = fc
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualDescription, onValueChange = { manualDescription = it },
                        label = { Text("Description (optional)") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth(), colors = fc
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualTags, onValueChange = { manualTags = it },
                        label = { Text("Tags (comma se alag, optional)") },
                        modifier = Modifier.fillMaxWidth(), colors = fc
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { saveManualDraft() },
                        enabled = !isWorking && manualTitle.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(text = "💾 Sirf Draft save karein (video baad mein)", color = TextPrimary, fontSize = 13.sp)
                    }
                }
            } else {
                SectionCard(title = "Title / Description ki zaban") {
                    ChoiceChips(
                        options = listOf("Urdu" to "Urdu", "English" to "English", "Roman Urdu" to "Roman Urdu"),
                        selected = outputLanguage,
                        onSelect = { outputLanguage = it }
                    )
                }
            }

            SectionCard(title = "Upload options") {
                if (!manualMode) {
                    SettingSwitchRow(
                        title = "Auto-upload YouTube پر",
                        description = "Metadata مکمل ہوتے ہی خودکار اپلوڈ ہو جائے (اپنے connected account میں)",
                        checked = autoUploadEnabled,
                        onChange = { autoUploadEnabled = it; credStore.autoUploadEnabled = it }
                    )
                    HorizontalDivider(color = SurfaceVariant)
                }
                SettingSwitchRow(
                    title = "Video ka naam title par rakhein",
                    description = "Upload se pehle video ka naam (gallery mein) title ke mutabiq badal jayega",
                    checked = renameToTitle,
                    onChange = { renameToTitle = it; credStore.renameVideoToTitle = it }
                )
                // Short/Long reshaping needs FFmpeg, which only VELLORA CUT has; YTIQ uploads the video as it is.
                if (!com.vellora.cut.BuildConfig.IS_YTIQ) {
                    HorizontalDivider(color = SurfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "Upload Shape", color = TextPrimary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    ChoiceChips(
                        options = listOf(
                            com.vellora.cut.autogen.data.UploadTarget.AUTO to "Auto (jaisi hai)",
                            com.vellora.cut.autogen.data.UploadTarget.SHORT to "Short",
                            com.vellora.cut.autogen.data.UploadTarget.LONG to "Long"
                        ),
                        selected = uploadTarget,
                        onSelect = { uploadTarget = it }
                    )
                    if (uploadTarget != com.vellora.cut.autogen.data.UploadTarget.AUTO) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Agar zaroorat pari to real FFmpeg se video crop/trim ho kar upload hogi",
                            color = TextSecondary, fontSize = 10.sp
                        )
                    }
                }
            }

            SectionCard(
                title = "Thumbnail",
                subtitle = "Optional — video upload hone ke baad lagegi"
            ) {
                val picked = thumbnailUri
                if (picked == null) {
                    OutlinedButton(
                        onClick = { thumbnailPicker.launch("image/*") },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(text = "🖼  Thumbnail chunein", color = TextPrimary, fontSize = 13.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThumbnailPreview(uri = picked, modifier = Modifier.width(120.dp).height(68.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(text = "✅ Thumbnail chuna gaya", color = CyanPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Row {
                                TextButton(onClick = { thumbnailPicker.launch("image/*") }, enabled = !isWorking) {
                                    Text(text = "Badlein", color = TextPrimary, fontSize = 12.sp)
                                }
                                TextButton(onClick = { thumbnailUri = null }) {
                                    Text(text = "Hatayen", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (!manualMode) {
                SectionCard(
                    title = "Video ka topic (hint)",
                    subtitle = "Optional — video chunne se pehle likhein ke video kis baare mein hai"
                ) {
                    OutlinedTextField(
                        value = videoHintText,
                        onValueChange = { videoHintText = it },
                        placeholder = { Text("jaise: Surah An-Nasr tilawat") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                        enabled = !isWorking,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CyanPrimary
                        )
                    )
                }
            }

            // ---------------------------------------------------------- main action
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { videoPicker.launch("video/*") },
                enabled = !isWorking && (!manualMode || manualTitle.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    text = if (manualMode) "📁  Video chunein aur Upload karein" else "📁  Gallery se Video chunein",
                    color = BackgroundDark, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            }
            if (manualMode && manualTitle.isBlank()) {
                Text(
                    text = "Pehle upar Title likhein",
                    color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            classificationHint?.let {
                Text(
                    text = it,
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            if (!manualMode) {
                SectionCard(title = "Ya sirf topic se banayein", subtitle = "Video ke bagair — apna topic likhein") {
                    OutlinedTextField(
                        value = manualTopicText,
                        onValueChange = { manualTopicText = it },
                        placeholder = { Text("jaise: 5 tips ghar par workout karne ke liye") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CyanPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { generateFromTopic() },
                        enabled = !isWorking && manualTopicText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
                    ) {
                        Text(text = "✍️  Topic se Generate karein", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // ---------------------------------------------------------- status / result
            if (isWorking) {
                SectionCard(title = "Kaam ho raha hai") {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = statusLabel(statusText), color = TextSecondary, fontSize = 12.sp)
                }
            }

            if (errorMessage != null && !isWorking) {
                Text(
                    text = "⚠ $errorMessage",
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x22FF6B6B))
                        .padding(14.dp)
                )
            }

            val shownProject = activeProject
            if (shownProject != null) {
                ResultCard(
                    project = shownProject,
                    onRetry = { retry(shownProject) },
                    onSaveEdit = { edited -> scope.launch { dao.update(edited); activeProject = edited } },
                    onAttachVideo = { attachTarget = it; attachPicker.launch("video/*") },
                    onCancelUpload = {
                        scope.launch {
                            com.vellora.cut.autogen.work.YouTubeUploadWorker.cancel(context, shownProject.id)
                            withContext(Dispatchers.IO) { com.vellora.cut.autogen.work.UploadStaging.cleanup(context, shownProject.id) }
                            val latest = dao.getById(shownProject.id) ?: shownProject
                            val reset = latest.copy(status = ShortMetadataStatus.DONE, errorMessage = null)
                            dao.update(reset)
                            activeProject = reset
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        } // end scrollable content Column
    }
}

// ---------------------------------------------------------------------- page building blocks

/** A rounded card with a heading — every block of the page uses this so the page looks consistent. */
@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(SurfaceDark)
            .border(1.dp, SurfaceVariant, shape)
            .padding(14.dp)
    ) {
        Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

/** SEO Auto / Manual switch, drawn as one rounded bar with two segments. */
@Composable
private fun ModeToggle(manualMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(4.dp)
    ) {
        listOf(false to "🔍 SEO Auto", true to "✍️ Manual Title").forEach { (value, label) ->
            val selected = manualMode == value
            Surface(
                onClick = { onChange(value) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) CyanPrimary else Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = label,
                    color = if (selected) BackgroundDark else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) CyanPrimary else SurfaceVariant
            ) {
                Text(
                    text = label,
                    color = if (isSelected) BackgroundDark else TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = TextPrimary, fontSize = 13.sp)
            Text(text = description, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CyanPrimary)
        )
    }
}

/** Small preview of the picked thumbnail (decoded smaller, off the main thread). */
@Composable
private fun ThumbnailPreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    android.graphics.BitmapFactory.decodeStream(stream, null, options)
                        ?.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(text = "🖼", fontSize = 20.sp)
        }
    }
}

@Composable
private fun ResultCard(
    project: ShortMetadataEntity,
    onRetry: () -> Unit,
    onSaveEdit: (ShortMetadataEntity) -> Unit,
    onAttachVideo: (ShortMetadataEntity) -> Unit,
    onCancelUpload: () -> Unit
) {
    val context = LocalContext.current
    var editing by remember(project.id) { mutableStateOf(false) }
    var editTitle by remember(project.id, project.generatedTitle) { mutableStateOf(project.generatedTitle.orEmpty()) }
    var editDesc by remember(project.id, project.generatedDescription) { mutableStateOf(project.generatedDescription.orEmpty()) }
    var editTags by remember(project.id, project.generatedTags) { mutableStateOf(project.generatedTags.orEmpty()) }
    val canEdit = project.youtubeVideoUrl == null && project.status != ShortMetadataStatus.UPLOADING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, SurfaceVariant, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        if (project.status == ShortMetadataStatus.ERROR) {
            Text(text = "⚠ ${project.errorMessage ?: "Kuch ghalat ho gaya"}", color = Color(0xFFFF6B6B), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(text = "🔁 Retry", color = BackgroundDark, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (project.status == ShortMetadataStatus.UPLOADING) {
            UploadProgressCard(entityId = project.id, onCancel = onCancelUpload)
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (project.generatedTitle == null) {
            Text(
                text = if (project.status == ShortMetadataStatus.ERROR) "Abhi tak koi metadata nahi bani." else "Kaam ho raha hai…",
                color = TextSecondary,
                fontSize = 12.sp
            )
            return@Column
        }

        CopyableField(context, "Title", project.generatedTitle.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Description", project.generatedDescription.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Tags", project.generatedTags.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Hashtags", project.generatedHashtags.orEmpty())

        if (canEdit) {
            Spacer(modifier = Modifier.height(10.dp))
            if (!editing) {
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "✏️ Title / Description / Tags khud theek karein", color = TextPrimary, fontSize = 12.sp)
                }
            } else {
                val fc = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = CyanPrimary
                )
                OutlinedTextField(
                    value = editTitle, onValueChange = { editTitle = it },
                    label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), colors = fc
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editDesc, onValueChange = { editDesc = it },
                    label = { Text("Description") }, minLines = 3, modifier = Modifier.fillMaxWidth(), colors = fc
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editTags, onValueChange = { editTags = it },
                    label = { Text("Tags (comma se alag)") }, modifier = Modifier.fillMaxWidth(), colors = fc
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (editTitle.isNotBlank()) {
                                onSaveEdit(project.copy(generatedTitle = editTitle.trim(), generatedDescription = editDesc.trim(), generatedTags = editTags.trim()))
                                editing = false
                            }
                        },
                        enabled = editTitle.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) { Text(text = "Save", color = BackgroundDark, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { editing = false }) { Text(text = "Cancel", color = TextSecondary) }
                }
            }
        }

        if (project.youtubeVideoUrl != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "✅ YouTube پر upload ہو گئی", color = CyanPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(text = project.youtubeVideoUrl, color = TextSecondary, fontSize = 12.sp)
            if (!project.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "ℹ ${project.errorMessage}", color = Color(0xFFFFB84D), fontSize = 12.sp)
            }
        } else if (project.videoUri.isNotBlank() && project.status == ShortMetadataStatus.DONE) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRetry, // same path: uploadToYouTube runs since generatedTitle != null
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(text = "⬆️ Upload Now", color = BackgroundDark, fontWeight = FontWeight.Bold)
            }
        } else if (project.videoUri.isBlank() && project.status != ShortMetadataStatus.UPLOADING) {
            // A draft with no video yet: pick the video now and it uploads with exactly this title/description/tags.
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onAttachVideo(project) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(text = "📁 Video chunein aur isi title par Upload karein", color = BackgroundDark, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CopyableField(context: Context, label: String, value: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { copyToClipboard(context, label, value) }) {
                Text(text = "Copy", color = TextPrimary, fontSize = 11.sp)
            }
        }
        Text(text = value, color = TextPrimary, fontSize = 13.sp)
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun statusLabel(status: String): String = when (status) {
    ShortMetadataStatus.EXTRACTING_AUDIO -> "Video se audio nikala ja raha hai…"
    ShortMetadataStatus.TRANSCRIBING -> "Sun kar likha ja raha hai (real transcription)…"
    ShortMetadataStatus.RESEARCHING_KEYWORDS -> "YouTube se real keywords dhoonde ja rahe hain…"
    ShortMetadataStatus.GENERATING -> "AI Title/Description/Tags bana raha hai…"
    "generating_gemini" -> "Gemini Title/Description/Tags bana raha hai…"
    "generating_cloudflare" -> "Gemini nahi chala — backup (Cloudflare) se ban raha hai…"
    ShortMetadataStatus.UPLOADING -> "YouTube par upload ho raha hai…"
    else -> "Kaam ho raha hai…"
}

/**
 * YouTube decides Short vs regular video ENTIRELY automatically — vertical
 * or square AND ≤3 minutes = Short; anything wider or longer = a regular
 * video. There is no API flag to override this (confirmed against current
 * YouTube Data API behaviour), so this only predicts/warns what will
 * actually happen — it can't force either outcome.
 */
private fun predictYouTubeClassification(context: Context, uri: Uri): String? {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val widthRaw = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val heightRaw = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null

        // Rotation metadata means the raw width/height are swapped relative
        // to how the video actually displays — account for that first.
        val (width, height) = if (rotation == 90 || rotation == 270) heightRaw to widthRaw else widthRaw to heightRaw
        val isVerticalOrSquare = height >= width
        val underThreeMin = durationMs <= 180_000L
        val durationLabel = "%.0fs".format(durationMs / 1000.0)

        when {
            isVerticalOrSquare && underThreeMin ->
                "📱 Ye video YouTube پر Short بنے گی (vertical/square + $durationLabel, 3min سے کم)"
            !isVerticalOrSquare ->
                "🎬 Ye video normal (Long) video بنے گی — wide/16:9 shape ہے، Short نہیں بن سکتی"
            else ->
                "🎬 Ye video normal (Long) video بنے گی — 3min سے لمبی ہے ($durationLabel)"
        }
    } catch (e: Exception) {
        null
    } finally {
        try { retriever.release() } catch (e: Exception) { }
    }
}

private fun resolveVideoToLocalFile(context: Context, uriString: String): File? {
    return try {
        val uri = Uri.parse(uriString)
        if (uri.scheme == null || uri.scheme == "file") {
            File(uri.path ?: uriString)
        } else {
            val dir = File(context.cacheDir, "shorts_upload").apply { mkdirs() }
            val dest = File(dir, "upload_${System.currentTimeMillis()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            dest
        }
    } catch (e: Exception) {
        null
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        null
    }
}
