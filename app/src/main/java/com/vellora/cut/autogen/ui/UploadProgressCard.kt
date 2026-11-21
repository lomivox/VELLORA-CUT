package com.vellora.cut.autogen.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vellora.cut.autogen.work.UploadFormat
import com.vellora.cut.autogen.work.YouTubeUploadWorker
import com.vellora.cut.ui.theme.*

/**
 * Live state of one background YouTube upload: percent, MB sent / total / left, speed, time left, and Cancel.
 * Reads WorkManager's progress, so it is right even after the screen or the whole app was closed and reopened.
 */
@Composable
fun UploadProgressCard(entityId: Long, onCancel: () -> Unit) {
    val context = LocalContext.current
    val flow = remember(entityId) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(YouTubeUploadWorker.uniqueName(entityId))
    }
    val infos by flow.collectAsState(initial = emptyList())
    val info = infos.lastOrNull()

    val sent = info?.progress?.getLong(YouTubeUploadWorker.KEY_SENT, 0L) ?: 0L
    val total = info?.progress?.getLong(YouTubeUploadWorker.KEY_TOTAL, 0L) ?: 0L
    val speed = info?.progress?.getLong(YouTubeUploadWorker.KEY_SPEED, 0L) ?: 0L
    val eta = info?.progress?.getLong(YouTubeUploadWorker.KEY_ETA, -1L) ?: -1L
    val fraction = if (total > 0) (sent.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val percent = (fraction * 100).toInt()

    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            info == null || info.state.isFinished && info.state != WorkInfo.State.SUCCEEDED -> {
                // An "uploading" entry with no upload behind it (old build, or it was stopped): let the person reset it.
                Text(text = "No running upload found.", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) { Text(text = "Reset", color = TextPrimary) }
            }
            info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED -> {
                Text(text = "⏳ Waiting for internet…", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) { Text(text = "✖ Cancel upload", color = Color(0xFFFF6B6B)) }
            }
            else -> {
                Text(
                    text = if (total > 0 && sent > 0) "⬆️ Uploading — $percent%" else "⬆️ Upload is starting…",
                    color = CyanPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanPrimary
                )
                if (total > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "${UploadFormat.bytes(sent)} / ${UploadFormat.bytes(total)}", color = TextPrimary, fontSize = 13.sp)
                    Text(text = "Remaining: ${UploadFormat.bytes((total - sent).coerceAtLeast(0L))}", color = TextSecondary, fontSize = 12.sp)
                    if (speed > 0) {
                        Text(
                            text = "${UploadFormat.bytes(speed)}/s  •  ${UploadFormat.duration(eta)} left",
                            color = TextSecondary, fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "Keeps running if you close the app.", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) { Text(text = "✖ Cancel upload", color = Color(0xFFFF6B6B)) }
            }
        }
    }
}
