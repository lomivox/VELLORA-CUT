package com.vellora.cut.autogen.ui

import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.data.AutoGenProjectStatus
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun NewAutoGenProjectScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onCreated: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var voiceOverUri by remember { mutableStateOf<String?>(null) }
    var voiceOverDurationMs by remember { mutableLongStateOf(0L) }
    var imageDurationSec by remember { mutableStateOf("5") }
    var resolution by remember { mutableStateOf("youtube") }
    var saving by remember { mutableStateOf(false) }

    val voiceOverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            voiceOverUri = uri.toString()
            voiceOverDurationMs = try {
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
        }
    }

    val canSave = name.isNotBlank() && voiceOverUri != null &&
        (imageDurationSec.toIntOrNull() ?: 0) > 0 && !saving

    Scaffold(containerColor = BackgroundDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text(text = "← Cancel", color = TextSecondary, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "New Auto Project",
                color = CyanPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            FieldLabel("Project Name")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = velloraTextFieldColors()
            )

            Spacer(modifier = Modifier.height(20.dp))

            FieldLabel("Voice-over")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceDark)
                    .clickable { voiceOverLauncher.launch("audio/*") }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🎙️", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (voiceOverUri != null) "Voice-over selected" else "Tap to select audio file",
                        color = if (voiceOverUri != null) TextPrimary else TextSecondary,
                        fontSize = 13.sp
                    )
                    if (voiceOverDurationMs > 0) {
                        Text(
                            text = "Duration: ${formatDuration(voiceOverDurationMs)}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            FieldLabel("Image Duration (seconds)")
            OutlinedTextField(
                value = imageDurationSec,
                onValueChange = { value -> if (value.all { it.isDigit() }) imageDurationSec = value },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(0.4f),
                colors = velloraTextFieldColors()
            )

            Spacer(modifier = Modifier.height(20.dp))

            FieldLabel("Video Size")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "youtube" to "YouTube (1920×1080)",
                    "tiktok" to "TikTok (1080×1920)"
                ).forEach { (value, label) ->
                    ResolutionChip(
                        label = label,
                        selected = resolution == value,
                        onClick = { resolution = value }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val id = db.autoGenDao().insertProject(
                            AutoGenProjectEntity(
                                name = name.trim(),
                                voiceOverUri = voiceOverUri,
                                voiceOverDurationMs = voiceOverDurationMs,
                                imageDurationSec = imageDurationSec.toIntOrNull() ?: 5,
                                resolution = resolution,
                                status = AutoGenProjectStatus.DRAFT,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        saving = false
                        onCreated(id)
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(text = if (saving) "Saving…" else "Continue → Add Prompts", color = BackgroundDark)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, color = TextSecondary, fontSize = 12.sp)
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun ResolutionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) CyanPrimary else SurfaceDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = if (selected) BackgroundDark else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun velloraTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = CyanPrimary,
    unfocusedBorderColor = TextSecondary,
    cursorColor = CyanPrimary
)

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
