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
    val credStore = remember { SecureCredentialStore(context) }
    var autoUploadEnabled by remember { mutableStateOf(credStore.autoUploadEnabled) }

    suspend fun uploadToYouTube(entity: ShortMetadataEntity): ShortMetadataEntity {
        val account = YouTubeAuthManager.getCurrentAccount(context)
            ?: return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Pehle Settings mein YouTube account connect karein")

        val tokenResult = YouTubeAuthManager.getFreshAccessToken(context, account)
        val accessToken = when (tokenResult) {
            is YouTubeAuthManager.TokenResult.Success -> tokenResult.accessToken
            is YouTubeAuthManager.TokenResult.NeedsUserAction ->
                return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "YouTube permission dobara consent chahti hai — Settings mein Disconnect kar ke dobara Connect karein")
            is YouTubeAuthManager.TokenResult.Error ->
                return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = tokenResult.message)
        }

        val videoFile = withContext(Dispatchers.IO) {
            resolveVideoToLocalFile(context, entity.videoUri)
        } ?: return entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Video file nahi mili upload ke liye")

        val tags = entity.generatedTags.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
        val fullDescription = buildString {
            append(entity.generatedDescription.orEmpty())
            if (!entity.generatedHashtags.isNullOrBlank()) {
                append("\n\n")
                append(entity.generatedHashtags)
            }
        }

        val uploadResult = withContext(Dispatchers.IO) {
            YouTubeUploader.upload(
                accessToken = accessToken,
                videoFile = videoFile,
                title = entity.generatedTitle ?: entity.videoFileName,
                description = fullDescription,
                tags = tags
            )
        }

        return uploadResult.fold(
            onSuccess = { r -> entity.copy(status = ShortMetadataStatus.UPLOADED, youtubeVideoUrl = r.videoUrl) },
            onFailure = { e -> entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Upload fail: ${e.message}") }
        )
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
        val fileName = queryDisplayName(context, uri) ?: "video"
        scope.launch {
            val accounts = SecureCredentialStore(context).accounts
            if (accounts.isEmpty()) {
                errorMessage = "Pehle Settings mein Cloudflare account add karein"
                return@launch
            }
            var entity = ShortMetadataEntity(
                videoUri = uri.toString(),
                videoFileName = fileName,
                createdAt = System.currentTimeMillis(),
                status = ShortMetadataStatus.EXTRACTING_AUDIO
            )
            val id = dao.insert(entity)
            entity = entity.copy(id = id)
            activeProject = entity
            isWorking = true

            val result = ShortsMetadataGenerator.generate(
                context = context,
                videoUri = uri.toString(),
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

                if (autoUploadEnabled) {
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
            TextButton(onClick = onBack) {
                Text(text = "← Back", color = TextPrimary)
            }
            Text(text = "Shorts Metadata", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(60.dp))
        }

        Text(
            text = "Gallery se koi bhi video chunein — real transcript + YouTube keyword research se Title/Description/Tags/Hashtags banega",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

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

        Button(
            onClick = { videoPicker.launch("video/*") },
            enabled = !isWorking,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
        ) {
            Text(text = "📁 Gallery se Video Chunein", color = BackgroundDark, fontWeight = FontWeight.Bold)
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
        if (shownProject != null && shownProject.status in setOf(
                ShortMetadataStatus.DONE, ShortMetadataStatus.UPLOADING, ShortMetadataStatus.UPLOADED
            )
        ) {
            ResultCard(shownProject)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Pichli metadata",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(projects) { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeProject = p; errorMessage = null }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = p.generatedTitle ?: p.videoFileName,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        Text(text = p.status, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(project: ShortMetadataEntity) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(14.dp)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        CopyableField(context, "Title", project.generatedTitle.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Description", project.generatedDescription.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Tags", project.generatedTags.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Hashtags", project.generatedHashtags.orEmpty())

        if (project.youtubeVideoUrl != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "✅ YouTube پر upload ہو گئی", color = CyanPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(text = project.youtubeVideoUrl, color = TextSecondary, fontSize = 12.sp)
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
