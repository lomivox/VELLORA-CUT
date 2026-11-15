package com.vellora.cut.autogen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    var outputLanguage by remember { mutableStateOf("Urdu") } // "Urdu" or "English"
    var isWorking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var classificationHint by remember { mutableStateOf<String?>(null) }
    var uploadTarget by remember { mutableStateOf(com.vellora.cut.autogen.data.UploadTarget.AUTO) }
    var manualTopicText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
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

    suspend fun runGenerationFlow(entity: ShortMetadataEntity, videoUriForGen: String?, manualTopic: String?) {
        val accounts = SecureCredentialStore(context).accounts
        if (accounts.isEmpty()) {
            errorMessage = "Pehle Settings mein Cloudflare account add karein"
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
            activeProject = entity
            runGenerationFlow(entity, videoUriForGen = uri.toString(), manualTopic = null)
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
                runGenerationFlow(entity, videoUriForGen = entity.videoUri, manualTopic = null)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { if (showHistory) showHistory = false else onBack() }) {
                Text(text = "← Back", color = TextPrimary)
            }
            Text(
                text = if (showHistory) "History (${projects.size})" else "Shorts Metadata",
                color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(60.dp))
        }

        if (showHistory) {
            // Full page, nothing else sharing the space — this used to be a
            // few cramped rows squeezed at the bottom of the main screen
            // where a long list, or a tall result card above it, could push
            // it out of view entirely. Now it's its own screen.
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(projects) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activeProject = p; errorMessage = null; showHistory = false }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Column {
                            Text(
                                text = p.generatedTitle ?: p.videoFileName,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 2
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = p.status, color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = SurfaceVariant)
                }
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {

        Text(
            text = "Gallery se koi bhi video chunein — real transcript + YouTube keyword research se Title/Description/Tags/Hashtags banega",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(false to "🔍 SEO Auto", true to "✍️ Manual Title").forEach { (value, label) ->
                val selected = manualMode == value
                Surface(
                    onClick = { manualMode = value; errorMessage = null },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) CyanPrimary else SurfaceVariant,
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
        Text(
            text = if (manualMode) "Aap khud title/description/tags likhte hain — video bina AI ke usi par upload hogi."
                   else "Video sun kar transcript + YouTube keywords se SEO Title/Description/Tags khud banenge.",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (manualMode) {
            val fc = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = CyanPrimary
            )
            OutlinedTextField(
                value = manualTitle, onValueChange = { manualTitle = it },
                label = { Text("Title (zaroori)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = fc
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = manualDescription, onValueChange = { manualDescription = it },
                label = { Text("Description (optional)") }, minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = fc
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = manualTags, onValueChange = { manualTags = it },
                label = { Text("Tags (comma se alag, optional)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = fc
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { saveManualDraft() },
                enabled = !isWorking && manualTitle.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(text = "💾 Sirf Draft save karein (video baad mein)", color = TextPrimary)
            }
            Spacer(modifier = Modifier.height(12.dp))
        } else {
        Text(
            text = "Language",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf("Urdu", "English").forEach { lang ->
                val selected = outputLanguage == lang
                Surface(
                    onClick = { outputLanguage = lang },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) CyanPrimary else SurfaceVariant
                ) {
                    Text(
                        text = lang,
                        color = if (selected) BackgroundDark else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Auto-upload YouTube پر", color = TextPrimary, fontSize = 13.sp)
                Text(
                    text = "Metadata مکمل ہوتے ہی خودکار اپلوڈ ہو جائے (اپنے connected account میں)",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = autoUploadEnabled,
                onCheckedChange = { autoUploadEnabled = it; credStore.autoUploadEnabled = it },
                colors = SwitchDefaults.colors(checkedTrackColor = CyanPrimary)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        }

        UploadAccountSelector(
            modifier = Modifier.padding(horizontal = 16.dp),
            onSelected = { uploadAccountEmail = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Video ka naam title par rakhein", color = TextPrimary, fontSize = 13.sp)
                Text(
                    text = "Upload se pehle video ka naam (gallery mein) title ke mutabiq badal jayega",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = renameToTitle,
                onCheckedChange = { renameToTitle = it; credStore.renameVideoToTitle = it },
                colors = SwitchDefaults.colors(checkedTrackColor = CyanPrimary)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Short/Long reshaping needs FFmpeg, which only VELLORA CUT has; YTIQ uploads the video as it is.
        if (!com.vellora.cut.BuildConfig.IS_YTIQ) {
        Text(
            text = "Upload Shape",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                com.vellora.cut.autogen.data.UploadTarget.AUTO to "Auto (jaisi hai)",
                com.vellora.cut.autogen.data.UploadTarget.SHORT to "Short",
                com.vellora.cut.autogen.data.UploadTarget.LONG to "Long"
            ).forEach { (value, label) ->
                val selected = uploadTarget == value
                Surface(
                    onClick = { uploadTarget = value },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) CyanPrimary else SurfaceVariant
                ) {
                    Text(
                        text = label,
                        color = if (selected) BackgroundDark else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
        if (uploadTarget != com.vellora.cut.autogen.data.UploadTarget.AUTO) {
            Text(
                text = "Agar zaroorat pari to real FFmpeg se video crop/trim ho kar upload hogi",
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { thumbnailPicker.launch("image/*") },
                enabled = !isWorking
            ) {
                Text(text = if (thumbnailUri == null) "🖼 Thumbnail chunein (optional)" else "🖼 Thumbnail badlein", color = TextPrimary, fontSize = 12.sp)
            }
            if (thumbnailUri != null) {
                Text(text = "✅ chuna gaya", color = CyanPrimary, fontSize = 12.sp)
                TextButton(onClick = { thumbnailUri = null }) {
                    Text(text = "Hatayen", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { videoPicker.launch("video/*") },
            enabled = !isWorking && (!manualMode || manualTitle.isNotBlank()),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
        ) {
            Text(text = if (manualMode) "📁 Video chunein aur Upload karein" else "📁 Gallery se Video Chunein", color = BackgroundDark, fontWeight = FontWeight.Bold)
        }

        classificationHint?.let {
            Text(
                text = it,
                color = CyanPrimary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        if (!manualMode) {
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Ya apna topic khud likhein (video ke bagair)",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = manualTopicText,
            onValueChange = { manualTopicText = it },
            placeholder = { Text("jaise: 5 tips ghar par workout karne ke liye") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = CyanPrimary
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { generateFromTopic() },
            enabled = !isWorking && manualTopicText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
        ) {
            Text(text = "✍️ Topic se Generate Karein", color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isWorking) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = statusLabel(statusText), color = TextSecondary, fontSize = 12.sp)
            }
        }

        if (errorMessage != null && !isWorking) {
            Text(
                text = "⚠ $errorMessage",
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
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

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = { showHistory = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(text = "📋 History (${projects.size})", color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(20.dp))
        } // end scrollable content Column
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
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(14.dp)
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
