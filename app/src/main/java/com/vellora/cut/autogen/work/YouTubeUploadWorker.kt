package com.vellora.cut.autogen.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vellora.cut.R
import com.vellora.cut.autogen.data.ShortMetadataStatus
import com.vellora.cut.autogen.network.YouTubeAuthManager
import com.vellora.cut.autogen.network.YouTubeUploader
import com.vellora.cut.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Uploads one video to YouTube in the background.
 *
 * - Runs as a foreground work item (notification with progress + Cancel), so it keeps going when the app
 *   is closed or the screen is off, and WorkManager restarts it if the process is killed.
 * - Uses a resumable upload: after a network drop or a restart it continues from the byte YouTube already has.
 * - Reports bytes sent / total / speed / time left (see the KEY_* names) for the progress card.
 * - Writes the result (UPLOADED / ERROR) into the database itself, so it does not need the screen to be open.
 */
class YouTubeUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entityId = inputData.getLong(KEY_ENTITY_ID, -1L)
        val videoFile = File(inputData.getString(KEY_VIDEO_PATH).orEmpty())
        val thumbFile = inputData.getString(KEY_THUMB_PATH)?.takeIf { it.isNotBlank() }?.let { File(it) }
        val accountKey = inputData.getString(KEY_ACCOUNT).orEmpty()

        val dao = AppDatabase.getInstance(applicationContext).shortMetadataDao()
        val entity = dao.getById(entityId) ?: return Result.failure()

        if (!videoFile.exists()) {
            dao.update(entity.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Upload ke liye video file nahi mili — dobara Upload Now dabayein"))
            return Result.failure()
        }

        val title = entity.generatedTitle ?: entity.videoFileName
        val tags = entity.generatedTags.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
        val description = buildString {
            append(entity.generatedDescription.orEmpty())
            if (!entity.generatedHashtags.isNullOrBlank()) {
                append("\n\n")
                append(entity.generatedHashtags)
            }
        }

        val sent = AtomicLong(0L)
        val total = AtomicLong(videoFile.length())
        trySetForeground(foregroundInfo(entityId, title, 0L, total.get(), 0.0, -1L))

        val sessions = PrefsSessionStore(applicationContext, entityId, videoFile)
        val tokenProvider: suspend () -> String = {
            when (val t = YouTubeAuthManager.getFreshAccessToken(applicationContext, accountKey)) {
                is YouTubeAuthManager.TokenResult.Success -> t.accessToken
                is YouTubeAuthManager.TokenResult.NeedsUserAction ->
                    throw IllegalStateException("YouTube permission dobara chahiye — Settings mein is channel ko Remove kar ke dobara jodein")
                is YouTubeAuthManager.TokenResult.Error -> throw IOException(t.message)
            }
        }

        val result: kotlin.Result<com.vellora.cut.autogen.network.YouTubeUploadResult> = try {
            coroutineScope {
                val meter = SpeedMeter()
                val ticker = launch {
                    while (isActive) {
                        delay(1000)
                        val s = sent.get()
                        val t = total.get()
                        val speed = meter.update(System.currentTimeMillis(), s)
                        val eta = if (speed > 1024.0) ((t - s) / speed).toLong() else -1L
                        trySetForeground(foregroundInfo(entityId, title, s, t, speed, eta))
                        try {
                            setProgress(workDataOf(KEY_SENT to s, KEY_TOTAL to t, KEY_SPEED to speed.toLong(), KEY_ETA to eta))
                        } catch (e: Exception) { /* not important */ }
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        val job = this.coroutineContext.job
                        YouTubeUploader.uploadResumable(
                            tokenProvider = tokenProvider,
                            videoFile = videoFile,
                            title = title,
                            description = description,
                            tags = tags,
                            privacyStatus = "public",
                            thumbnailFile = thumbFile,
                            sessions = sessions,
                            onProgress = { s, t -> sent.set(s); total.set(t) },
                            isCancelled = { !job.isActive }
                        )
                    }
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            // Stopped. Either the person cancelled (then the work is marked CANCELLED), or Android paused it
            // (no internet etc.) and WorkManager will start it again by itself — in that case leave everything.
            withContext(NonCancellable + Dispatchers.IO) {
                val cancelledByPerson = try {
                    WorkManager.getInstance(applicationContext).getWorkInfoById(id).get().state == WorkInfo.State.CANCELLED
                } catch (x: Exception) {
                    false
                }
                if (cancelledByPerson) {
                    val latest = dao.getById(entityId)
                    if (latest != null && latest.status == ShortMetadataStatus.UPLOADING) {
                        dao.update(latest.copy(status = ShortMetadataStatus.DONE, errorMessage = null))
                    }
                    UploadStaging.cleanup(applicationContext, entityId)
                }
            }
            throw e
        }

        val latest = dao.getById(entityId) ?: entity
        val ok = result.getOrNull()
        if (ok != null) {
            dao.update(latest.copy(status = ShortMetadataStatus.UPLOADED, youtubeVideoUrl = ok.videoUrl, errorMessage = ok.thumbnailNote))
            UploadStaging.cleanup(applicationContext, entityId)
            notifyFinished(entityId, "Upload ho gayi ✅", title + (ok.thumbnailNote?.let { "\n$it" } ?: ""))
            return Result.success()
        }

        val error = result.exceptionOrNull()
        val network = error is IOException && error !is YouTubeUploader.UploadCancelledException
        if (network && runAttemptCount < 3) {
            // Keep the video and the upload address: the next attempt continues where this one stopped.
            return Result.retry()
        }
        val message = error?.message ?: "Upload nahi ho saki"
        dao.update(latest.copy(status = ShortMetadataStatus.ERROR, errorMessage = "Upload fail: $message"))
        UploadStaging.cleanup(applicationContext, entityId)
        notifyFinished(entityId, "Upload fail ❌", message)
        return Result.failure()
    }

    // ------------------------------------------------------------------ notification

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "YouTube upload", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun foregroundInfo(entityId: Long, title: String, sent: Long, total: Long, speed: Double, eta: Long): ForegroundInfo {
        ensureChannel()
        val percent = if (total > 0) (sent * 100 / total).toInt().coerceIn(0, 100) else 0
        val text = if (sent <= 0L) {
            "Shuru ho rahi hai…"
        } else {
            "$percent% • ${UploadFormat.bytes(sent)} / ${UploadFormat.bytes(total)}" +
                if (eta >= 0) " • ${UploadFormat.duration(eta)} baaqi" else ""
        }
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("YouTube upload: $title")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, sent <= 0L)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
        val notificationId = NOTIFICATION_BASE + (entityId % 1000).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            // Android may refuse a foreground start from the background; the upload still runs.
        }
    }

    private fun notifyFinished(entityId: Long, heading: String, body: String) {
        try {
            ensureChannel()
            val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(heading)
                .setContentText(body.lines().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_BASE + 1000 + (entityId % 1000).toInt(), n)
        } catch (e: SecurityException) {
            // notification permission not granted — fine
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Smoothed bytes/second over the last few seconds. */
    private class SpeedMeter {
        private var lastTime = 0L
        private var lastBytes = 0L
        private var smooth = 0.0

        fun update(now: Long, bytes: Long): Double {
            if (lastTime == 0L) {
                lastTime = now
                lastBytes = bytes
                return 0.0
            }
            val seconds = (now - lastTime) / 1000.0
            if (seconds < 0.8) return smooth
            val instant = (bytes - lastBytes) / seconds
            smooth = if (smooth == 0.0) instant else smooth * 0.7 + instant * 0.3
            lastTime = now
            lastBytes = bytes
            return smooth
        }
    }

    /** Remembers the upload address; only valid while the video file is the very same file. */
    private class PrefsSessionStore(context: Context, entityId: Long, private val file: File) : YouTubeUploader.SessionStore {
        private val prefs = context.getSharedPreferences(UploadStaging.SESSION_PREFS, Context.MODE_PRIVATE)
        private val key = "s_$entityId"

        override fun load(): String? {
            val parts = prefs.getString(key, null)?.split("|") ?: return null
            if (parts.size != 3) return null
            if (parts[1].toLongOrNull() != file.length() || parts[2].toLongOrNull() != file.lastModified()) return null
            return parts[0]
        }

        override fun save(sessionUrl: String) {
            prefs.edit().putString(key, "$sessionUrl|${file.length()}|${file.lastModified()}").apply()
        }

        override fun clear() {
            prefs.edit().remove(key).apply()
        }
    }

    companion object {
        const val KEY_ENTITY_ID = "entity_id"
        const val KEY_VIDEO_PATH = "video_path"
        const val KEY_THUMB_PATH = "thumb_path"
        const val KEY_ACCOUNT = "account_key"

        // progress data (read by the progress card)
        const val KEY_SENT = "sent"
        const val KEY_TOTAL = "total"
        const val KEY_SPEED = "speed"
        const val KEY_ETA = "eta"

        private const val CHANNEL_ID = "vellora_youtube_upload"
        private const val NOTIFICATION_BASE = 6000

        fun uniqueName(entityId: Long) = "yt_upload_$entityId"

        fun enqueue(context: Context, entityId: Long, videoPath: String, thumbPath: String?, accountKey: String) {
            val data: Data = workDataOf(
                KEY_ENTITY_ID to entityId,
                KEY_VIDEO_PATH to videoPath,
                KEY_THUMB_PATH to (thumbPath ?: ""),
                KEY_ACCOUNT to accountKey
            )
            val request = OneTimeWorkRequestBuilder<YouTubeUploadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(entityId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, entityId: Long) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(entityId))
        }
    }
}
