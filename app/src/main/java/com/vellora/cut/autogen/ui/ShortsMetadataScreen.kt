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
import com.vellora.cut.autogen.shorts.ShortsMetadataGenerator
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.BackgroundDark
import com.vellora.cut.ui.theme.CyanPrimary
import com.vellora.cut.ui.theme.SurfaceDark
import com.vellora.cut.ui.theme.SurfaceVariant
import com.vellora.cut.ui.theme.TextPrimary
import com.vellora.cut.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ShortsMetadataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(context).shortMetadataDao() }
    val projects by dao.observeAll().collectAsState(initial = emptyList())

    var activeProject by remember { mutableStateOf<ShortMetadataEntity?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                onStatusChange = { statusText = it }
            )
            isWorking = false

            result.onSuccess { meta ->
                val updated = entity.copy(
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
        if (shownProject != null && shownProject.status == ShortMetadataStatus.DONE) {
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
            .verticalScroll(rememberScrollState())
            .heightIn(max = 320.dp)
    ) {
        CopyableField(context, "Title", project.generatedTitle.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Description", project.generatedDescription.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Tags", project.generatedTags.orEmpty())
        Spacer(modifier = Modifier.height(10.dp))
        CopyableField(context, "Hashtags", project.generatedHashtags.orEmpty())
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
    else -> "Kaam ho raha hai…"
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
