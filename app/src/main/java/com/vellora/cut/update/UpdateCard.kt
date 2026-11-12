package com.vellora.cut.update

import android.app.DownloadManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.R
import com.vellora.cut.ui.theme.BackgroundDark
import com.vellora.cut.ui.theme.CyanPrimary
import com.vellora.cut.ui.theme.SurfaceDark
import com.vellora.cut.ui.theme.TextPrimary
import com.vellora.cut.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Red = Color(0xFFFF6B6B)

/** "App update" card: installed version, Check / Update now / Ignore, live download progress, Install. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val info = AppUpdater.info
    val ignored = info != null && info.code == AppUpdater.ignoredCode
    var download by remember { mutableStateOf<AppUpdater.DownloadState>(AppUpdater.DownloadState.Idle) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun toast(res: Int) = Toast.makeText(context, context.getString(res), Toast.LENGTH_LONG).show()

    // Real download state from the phone's DownloadManager (it keeps going when the app is closed).
    LaunchedEffect(info?.code) {
        while (true) {
            val now = withContext(Dispatchers.IO) { AppUpdater.downloadState(context) }
            download = now
            delay(
                when (now) {
                    is AppUpdater.DownloadState.Running, is AppUpdater.DownloadState.Waiting -> 500L
                    else -> 2_000L
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.update_title), color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.update_installed, AppUpdater.installedName(context)), color = TextSecondary, fontSize = 12.sp)

        if (info == null) {
            Text(stringResource(R.string.update_latest), color = TextPrimary, fontSize = 13.sp)
            if (message.isNotEmpty()) Text(message, color = TextSecondary, fontSize = 12.sp)
            OutlinedButton(
                onClick = {
                    checking = true
                    message = ""
                    AppUpdater.checkAsync(context, force = true) { result ->
                        checking = false
                        message = context.getString(
                            when (result) {
                                "network" -> R.string.update_no_internet
                                "error" -> R.string.update_check_failed
                                else -> R.string.update_latest
                            }
                        )
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(if (checking) R.string.update_checking else R.string.update_check), color = CyanPrimary) }
        } else {
            val sizeMb = if (info.sizeBytes > 0) String.format(Locale.US, " (%.1f MB)", info.sizeBytes / 1_048_576.0) else ""
            Text(stringResource(R.string.update_available, info.name) + sizeMb, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (info.notes.isNotBlank()) {
                Text(stringResource(R.string.update_whats_new), color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(info.notes, color = TextSecondary, fontSize = 12.sp)
            }
            when (val d = download) {
                is AppUpdater.DownloadState.Running -> {
                    if (d.totalBytes > 0) {
                        LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = CyanPrimary)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CyanPrimary)
                    }
                    Text(stringResource(R.string.update_downloading, d.percent), color = TextPrimary, fontSize = 13.sp)
                    if (d.totalBytes > 0) {
                        Text(
                            stringResource(R.string.update_size_of, megabytes(d.gotBytes), megabytes(d.totalBytes)),
                            color = TextSecondary, fontSize = 12.sp
                        )
                    }
                    Text(stringResource(R.string.update_keep_going), color = TextSecondary, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { scope.launch(Dispatchers.IO) { AppUpdater.cancelDownload(context); download = AppUpdater.DownloadState.Idle } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.update_cancel), color = Red) }
                }
                is AppUpdater.DownloadState.Waiting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CyanPrimary)
                    Text(stringResource(d.reasonRes), color = TextPrimary, fontSize = 13.sp)
                    Text(stringResource(R.string.update_keep_going), color = TextSecondary, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { scope.launch(Dispatchers.IO) { AppUpdater.cancelDownload(context); download = AppUpdater.DownloadState.Idle } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.update_cancel), color = Red) }
                }
                AppUpdater.DownloadState.Ready -> {
                    Text(stringResource(R.string.update_ready), color = TextSecondary, fontSize = 12.sp)
                    Button(
                        onClick = {
                            when (AppUpdater.install(context)) {
                                AppUpdater.InstallResult.NEED_PERMISSION -> toast(R.string.update_allow_hint)
                                AppUpdater.InstallResult.BAD_FILE -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.update_bad_file) + "\n" + AppUpdater.lastBadReason,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    scope.launch { download = withContext(Dispatchers.IO) { AppUpdater.downloadState(context) } }
                                }
                                else -> {}
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) { Text(stringResource(R.string.update_install), color = BackgroundDark, fontWeight = FontWeight.Bold) }
                }
                else -> {
                    if (d is AppUpdater.DownloadState.Failed) {
                        Text(stringResource(R.string.update_failed), color = TextSecondary, fontSize = 12.sp)
                        Text(failReason(d.code), color = Red, fontSize = 12.sp)
                    }
                    if (ignored) Text(stringResource(R.string.update_ignored), color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val started = withContext(Dispatchers.IO) { AppUpdater.startDownload(context) }
                                    if (started) download = AppUpdater.DownloadState.Waiting(R.string.update_starting)
                                    else toast(R.string.update_failed)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) { Text(stringResource(R.string.update_now), color = BackgroundDark, fontWeight = FontWeight.Bold) }
                        if (!ignored) {
                            OutlinedButton(
                                onClick = { AppUpdater.ignore(context) },
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.update_ignore), color = TextSecondary) }
                        }
                    }
                }
            }
        }
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)

@Composable
private fun failReason(code: Int): String = when {
    code == AppUpdater.FAIL_BAD_FILE -> stringResource(R.string.update_fail_bad)
    code == DownloadManager.ERROR_INSUFFICIENT_SPACE || code == DownloadManager.ERROR_DEVICE_NOT_FOUND ->
        stringResource(R.string.update_fail_space)
    code == DownloadManager.ERROR_HTTP_DATA_ERROR || code == DownloadManager.ERROR_CANNOT_RESUME ->
        stringResource(R.string.update_fail_net)
    code in 400..599 -> stringResource(R.string.update_fail_server, code)
    else -> stringResource(R.string.update_fail_other, code)
}
