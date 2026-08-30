package com.vellora.cut.autogen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vellora.cut.autogen.data.AutoGenDao
import com.vellora.cut.autogen.data.PromptEntity
import com.vellora.cut.autogen.data.PromptStatus
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.work.GenerateImagesWorker
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Bulk prompt entry. Format per line: "001 | prompt text".
 * Saving replaces this project's prompt list and gives every prompt a
 * `pending` status — the field the Phase D resume system will read.
 */
@Composable
fun PromptPasteScreen(
    db: AppDatabase,
    projectId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTimeline: () -> Unit
) {
    val context = LocalContext.current
    val dao = db.autoGenDao()
    val scope = rememberCoroutineScope()
    val credentials = remember { SecureCredentialStore(context) }
    val workManager = remember { WorkManager.getInstance(context) }

    val existingPrompts by dao.observePrompts(projectId).collectAsState(initial = emptyList())

    var rawText by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<List<ParsedPrompt>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = BackgroundDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text(text = "← Projects", color = TextSecondary, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Prompts", color = CyanPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            if (existingPrompts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusSummary(existingPrompts)

                Spacer(modifier = Modifier.height(12.dp))

                val remainingCount = existingPrompts.count { it.status != PromptStatus.DONE }
                val workInfos by workManager
                    .getWorkInfosForUniqueWorkLiveData(GenerateImagesWorker.uniqueWorkName(projectId))
                    .observeAsState(initial = emptyList())
                val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

                // Auto-jump to Timeline the moment generation finishes, so there's
                // no need to manually tap "Continue" every time after generating.
                var wasRunning by remember { mutableStateOf(false) }
                LaunchedEffect(isRunning, remainingCount) {
                    if (wasRunning && !isRunning && remainingCount == 0) {
                        onOpenTimeline()
                    }
                    wasRunning = isRunning
                }

                if (!credentials.hasCredentials()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⚠️ Cloudflare credentials set نہیں ہیں — ", color = TextSecondary, fontSize = 12.sp)
                        TextButton(onClick = onOpenSettings) {
                            Text(text = "Settings کھولیں", color = CyanPrimary, fontSize = 12.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val request = OneTimeWorkRequestBuilder<GenerateImagesWorker>()
                                .setInputData(workDataOf(GenerateImagesWorker.KEY_PROJECT_ID to projectId))
                                .build()
                            workManager.enqueueUniqueWork(
                                GenerateImagesWorker.uniqueWorkName(projectId),
                                ExistingWorkPolicy.KEEP,
                                request
                            )
                        },
                        enabled = remainingCount > 0 && !isRunning,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text(
                            text = when {
                                isRunning -> "Generating…"
                                remainingCount == 0 -> "All images done ✅"
                                else -> "Generate All ($remainingCount remaining)"
                            },
                            color = BackgroundDark
                        )
                    }
                    if (isRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "پس منظر میں چل رہا ہے — آپ اس اسکرین سے جا بھی سکتے ہیں",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    if (remainingCount == 0 && !isRunning) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onOpenTimeline,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Continue → Timeline")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it; savedMessage = null },
                placeholder = {
                    Text(
                        text = "001 | A historical city at sunset...\n002 | An old traveler walking through...",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = TextSecondary,
                    cursorColor = CyanPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { parsed = parsePrompts(rawText) },
                    enabled = rawText.isNotBlank()
                ) {
                    Text(text = "Parse / Preview")
                }

                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            dao.deletePromptsForProject(projectId)
                            dao.insertPrompts(
                                parsed.map {
                                    PromptEntity(
                                        projectId = projectId,
                                        orderIndex = it.order,
                                        label = it.label,
                                        promptText = it.text,
                                        status = PromptStatus.PENDING
                                    )
                                }
                            )
                            saving = false
                            savedMessage = "${parsed.size} prompts محفوظ ہو گئے"
                            rawText = ""
                            parsed = emptyList()
                        }
                    },
                    enabled = parsed.isNotEmpty() && !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = if (saving) "Saving…" else "Save Prompts", color = BackgroundDark)
                }
            }

            savedMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = TextSecondary, fontSize = 12.sp)
            }

            if (parsed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "${parsed.size} prompts detected", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(parsed, key = { it.order }) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark)
                                .padding(10.dp)
                        ) {
                            Text(text = p.label, color = CyanPrimary, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = p.text,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else if (existingPrompts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Saved prompts (${existingPrompts.size})", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(existingPrompts, key = { it.id }) { p ->
                        SavedPromptRow(p)
                    }
                }
            }
        }
    }
}

private data class ParsedPrompt(val order: Int, val label: String, val text: String)

private fun parsePrompts(raw: String): List<ParsedPrompt> =
    raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapIndexed { index, line ->
            val separatorIndex = line.indexOf('|')
            if (separatorIndex != -1) {
                val label = line.substring(0, separatorIndex).trim()
                val text = line.substring(separatorIndex + 1).trim()
                ParsedPrompt(order = index, label = label.ifEmpty { "%03d".format(index + 1) }, text = text)
            } else {
                ParsedPrompt(order = index, label = "%03d".format(index + 1), text = line)
            }
        }
        .filter { it.text.isNotEmpty() }

@Composable
private fun StatusSummary(prompts: List<PromptEntity>) {
    val done = prompts.count { it.status == PromptStatus.DONE }
    val failed = prompts.count { it.status == PromptStatus.FAILED }
    val pending = prompts.size - done - failed
    Text(
        text = "${prompts.size} saved  ·  ✅ $done  ·  ❌ $failed  ·  ⏳ $pending",
        color = TextSecondary,
        fontSize = 12.sp
    )
}

@Composable
private fun SavedPromptRow(p: PromptEntity) {
    val icon = when (p.status) {
        PromptStatus.DONE -> "✅"
        PromptStatus.FAILED -> "❌"
        PromptStatus.GENERATING -> "⏳"
        else -> "•"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .padding(10.dp)
    ) {
        Text(text = icon, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = p.label, color = CyanPrimary, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = p.promptText, color = TextPrimary, fontSize = 12.sp, maxLines = 1)
    }
}
