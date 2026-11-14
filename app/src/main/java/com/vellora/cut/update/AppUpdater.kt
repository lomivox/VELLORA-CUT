package com.vellora.cut.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vellora.cut.R
import com.vellora.cut.ui.MainActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.vellora.cut.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates straight from the project's GitHub Releases (every push builds a signed APK there, versionCode = build number).
 *
 * - A background job asks GitHub every few hours (and the app asks whenever it opens). A newer release raises a
 *   phone notification once and a red dot on the Settings tab.
 * - The Update page downloads the APK with the phone's own download manager, so it carries on if the app is closed,
 *   and the finished file is KEPT: if the person did not press Install, the next visit shows "Install" at once
 *   instead of downloading again.
 * - Android itself checks the signature, so only an APK signed with our key can replace the app.
 */
object AppUpdater {
    /** The PUBLIC GitHub repository the APK releases are published in, as "owner/name" (CI publishes there). */
    const val REPO = "lomivox/VELLORA-CUT-releases"

    const val EXTRA_OPEN = "open_page"
    const val OPEN_UPDATE = "update"

    /** [sha256] is the fingerprint GitHub publishes for the APK (empty when it does not give one). */
    data class Info(val code: Long, val name: String, val apkUrl: String, val sizeBytes: Long, val sha256: String = "", val notes: String = "")

    sealed class DownloadState {
        object Idle : DownloadState()
        /** Queued or paused by the phone: [reasonRes] says why (no internet, waiting for Wi-Fi, retrying...). */
        data class Waiting(val reasonRes: Int) : DownloadState()
        /** Really downloading: the bytes that have arrived so far and the full size (0 = not known yet). */
        data class Running(val percent: Int, val gotBytes: Long, val totalBytes: Long) : DownloadState()
        /** Completely downloaded AND checked (size, fingerprint, package). Only now may it be installed. */
        object Ready : DownloadState()
        /** [code] is the download manager's error code, or [FAIL_BAD_FILE] when the file did not pass the check. */
        data class Failed(val code: Int) : DownloadState()
    }

    enum class InstallResult { STARTED, NEED_PERMISSION, NOT_READY, BAD_FILE }

    private const val PREFS = "app_update"
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 7003
    private const val CHECK_EVERY_MS = 10 * 60_000L   // at most one internet check per 10 minutes
    private const val FILE_PREFIX = "VelloraCut-update-"

    /** Failed(code) value for a finished download whose file was damaged or was not the expected app. */
    const val FAIL_BAD_FILE = -1

    /** The newest release when it is newer than the installed app, else null. Screens read this directly. */
    var info by mutableStateOf<Info?>(null)
        private set

    /** The release number the person chose to ignore (no dot, no notification for it). */
    var ignoredCode by mutableStateOf(0L)
        private set

    /** Raised when the notification is tapped; the app opens the Update page once and lowers it again. */
    var openRequest by mutableStateOf(false)

    /** True while the red dot should show. */
    val showDot: Boolean
        get() {
            val i = info
            return i != null && i.code != ignoredCode
        }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedCode(c: Context): Long =
        PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))

    fun installedName(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""

    /** Reads what was found last time (no internet). Call when the app starts and after every check. */
    fun load(c: Context) {
        val p = prefs(c)
        ignoredCode = p.getLong("ignored", 0L)
        val code = p.getLong("code", 0L)
        val found = if (code > installedCode(c)) {
            Info(code, p.getString("name", "") ?: "", p.getString("url", "") ?: "", p.getLong("size", 0L), p.getString("sha", "") ?: "", p.getString("notes", "") ?: "")
        } else null
        info = found
        cleanDownloads(c, keepCode = found?.code)
    }

    /** Asks GitHub for the newest release. Blocking: only call it on a background thread. */
    fun fetchBlocking(c: Context): String {
        val app = c.applicationContext
        return try {
            val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "VelloraCut-app")
                if (conn.responseCode != 200) return "error"
                // One releases repository can hold several apps: only this app's tags count (see TAG_PREFIX in build.gradle.kts).
                val list = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                val json = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull {
                    !it.optBoolean("draft") && !it.optBoolean("prerelease") &&
                        it.optString("tag_name").startsWith(BuildConfig.TAG_PREFIX)
                } ?: return "current" // nothing published for this app yet
                val tag = json.optString("tag_name").substringAfterLast('v')
                val code = tag.substringAfterLast('.').toLongOrNull() ?: return "error"
                var url = ""
                var size = 0L
                var sha = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size")
                            sha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:") ?: ""
                            break
                        }
                    }
                }
                if (url.isEmpty()) return "error"
                // The release text written by the build: "Platform: PHONE ..." and what changed.
                val notes = json.optString("body").trim().take(1500)
                prefs(app).edit()
                    .putLong("code", code).putString("name", tag).putString("url", url).putLong("size", size).putString("sha", sha).putString("notes", notes)
                    .putLong("checked", System.currentTimeMillis())
                    .apply()
                Handler(Looper.getMainLooper()).post { load(app) }
                if (code > installedCode(app)) "new" else "current"
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            "network"
        } catch (_: Exception) {
            "error"
        }
    }

    /**
     * Checks in the background; [onDone] gets "new", "current", "network" or "error" on the main thread.
     * Without [force] it does nothing when the last check was less than 30 minutes ago.
     */
    fun checkAsync(c: Context, force: Boolean = false, onDone: (String) -> Unit = {}) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - prefs(app).getLong("checked", 0L) < CHECK_EVERY_MS) return
        prefs(app).edit().putLong("checked", now).apply()
        Thread {
            val result = fetchBlocking(app)
            notifyIfNew(app)
            Handler(Looper.getMainLooper()).post { onDone(result) }
        }.start()
    }

    /** "Ignore": no red dot and no notification for this release (a newer one will show again). */
    fun ignore(c: Context) {
        val i = info ?: return
        ignoredCode = i.code
        prefs(c).edit().putLong("ignored", i.code).apply()
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /* ---------------------------- download ---------------------------- */

    private fun dir(c: Context): File? = c.applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    private fun fileName(code: Long) = "$FILE_PREFIX$code.apk"

    private fun cleanDownloads(c: Context, keepCode: Long?) {
        val keep = keepCode?.let { fileName(it) }
        dir(c)?.listFiles()?.forEach { f ->
            if (f.name.startsWith(FILE_PREFIX) && f.name != keep) f.delete()
        }
    }

    /**
     * The APK of the offered release, but ONLY once it has been checked as completely downloaded.
     * (The phone's download manager reserves the file at its FULL size at the very start, so the size of the file
     * alone says nothing about how much has really arrived. That used to make a download that had only just begun
     * look finished after a second.)
     */
    private fun verifiedFile(c: Context): File? {
        val i = info ?: return null
        val f = File(dir(c) ?: return null, fileName(i.code))
        if (!f.exists() || f.length() <= 0L) return null
        if (prefs(c).getString("verified", null) != "${i.code}:${f.length()}") return null
        return f
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks a finished file against the release: its size, GitHub's SHA-256 fingerprint (when there is one) and
     * that it is this app in a newer version. Remembers a pass, so the file is only read once.
     * [managerSaidDone] = the phone's download manager itself reported the download as successful.
     */
    private fun verifyAndMark(c: Context, managerSaidDone: Boolean): Boolean {
        val i = info ?: return false
        val f = File(dir(c) ?: return false, fileName(i.code))
        if (!f.exists()) return false
        if (i.sizeBytes > 0 && f.length() != i.sizeBytes) return false
        if (i.sha256.isNotEmpty() && !sha256(f).equals(i.sha256, ignoreCase = true)) return false
        val archive = c.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
        if (archive != null) {
            if (archive.packageName != c.packageName) return false
            if (PackageInfoCompat.getLongVersionCode(archive) <= installedCode(c)) return false
        } else if (i.sha256.isEmpty() && !managerSaidDone) {
            return false   // nothing at all proves that this file is whole
        }
        prefs(c).edit().putString("verified", "${i.code}:${f.length()}").apply()
        return true
    }

    /** Throws the download away (file and the phone's record), so that the next try starts clean. */
    private fun discard(c: Context) {
        val app = c.applicationContext
        val id = prefs(app).getLong("dl_id", -1L)
        if (id >= 0) runCatching { (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        prefs(app).edit().remove("dl_id").remove("verified").apply()
        val folder = dir(app)
        val i = info
        if (folder != null && i != null) File(folder, fileName(i.code)).delete()
    }

    /**
     * Where the download really is. Blocking, because the first look at a finished file reads all of it once to
     * check it: call this on a background thread.
     */
    fun downloadState(c: Context): DownloadState {
        val app = c.applicationContext
        if (info == null) return DownloadState.Idle
        if (verifiedFile(app) != null) return DownloadState.Ready
        val id = prefs(app).getLong("dl_id", -1L)
        if (id >= 0) {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use { cur ->
                if (cur.moveToFirst()) {
                    val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    return when (status) {
                        DownloadManager.STATUS_FAILED -> DownloadState.Failed(reason)
                        DownloadManager.STATUS_SUCCESSFUL ->
                            if (verifyAndMark(app, managerSaidDone = true)) DownloadState.Ready
                            else { discard(app); DownloadState.Failed(FAIL_BAD_FILE) }
                        DownloadManager.STATUS_PAUSED -> DownloadState.Waiting(
                            when (reason) {
                                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> R.string.update_wait_wifi
                                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> R.string.update_wait_network
                                else -> R.string.update_wait_retry
                            }
                        )
                        DownloadManager.STATUS_PENDING -> DownloadState.Waiting(R.string.update_starting)
                        else -> DownloadState.Running(
                            if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0,
                            got.coerceAtLeast(0L), total.coerceAtLeast(0L)
                        )
                    }
                }
            }
        }
        // Nothing in the phone's download list: a file kept from an earlier visit still counts, but only when it
        // checks out. A file that does not is removed, so it is not checked again and again.
        if (verifyAndMark(app, managerSaidDone = false)) return DownloadState.Ready
        dir(app)?.let { folder -> info?.let { File(folder, fileName(it.code)).delete() } }
        return DownloadState.Idle
    }

    /** Starts the download (does nothing if it is already running or already finished and checked). */
    fun startDownload(c: Context): Boolean {
        val app = c.applicationContext
        val i = info ?: return false
        if (verifiedFile(app) != null) return true
        val folder = dir(app) ?: return false
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val oldId = prefs(app).getLong("dl_id", -1L)
        if (oldId >= 0) {
            val stillGoing = dm.query(DownloadManager.Query().setFilterById(oldId)).use { cur ->
                cur.moveToFirst() && when (cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> true
                    else -> false
                }
            }
            if (stillGoing) return true
            dm.remove(oldId)
        }
        File(folder, fileName(i.code)).delete()
        prefs(app).edit().remove("verified").apply()
        val request = DownloadManager.Request(Uri.parse(i.apkUrl))
            .setTitle(app.getString(R.string.app_name) + " " + i.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName(i.code))
            .addRequestHeader("User-Agent", "VelloraCut-app")
        prefs(app).edit().putLong("dl_id", dm.enqueue(request)).apply()
        return true
    }

    /** Stops a running download and removes what was downloaded so far. */
    fun cancelDownload(c: Context) = discard(c)

    /** Why the last install check failed (shown in the error message). */
    var lastBadReason: String = ""

    /** Opens Android's install screen for the downloaded file. */
    fun install(c: Context): InstallResult {
        val app = c.applicationContext
        val f = verifiedFile(app) ?: return InstallResult.NOT_READY
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(f.absolutePath, 0)
        if (archive == null) {
            lastBadReason = "archive unreadable by getPackageArchiveInfo (trying installer anyway)"
        } else {
            val wrongPackage = archive.packageName != app.packageName
            val notNewer = PackageInfoCompat.getLongVersionCode(archive) <= installedCode(app)
            if (wrongPackage || notNewer) {
                lastBadReason = "pkg=${archive.packageName} " +
                    "code=${PackageInfoCompat.getLongVersionCode(archive)} " +
                    "installed=${installedCode(app)}"
                f.delete()
                return InstallResult.BAD_FILE
            }
        }
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return InstallResult.NEED_PERMISSION
        }
        // Preferred: hand the file straight to Android's PackageInstaller (no content:// link to parse).
        try {
            installViaSession(app, f)
            return InstallResult.STARTED
        } catch (e: Exception) {
            lastBadReason = "session: $e"
        }
        // Fallback: the old way (system installer opens the file through our FileProvider).
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return InstallResult.STARTED
    }

    private fun installViaSession(app: Context, f: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(f.length())
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                f.inputStream().use { input ->
                    session.openWrite("update.apk", 0, f.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            // Do not leave a half-written session behind; the caller then falls back to the old installer.
            try { installer.abandonSession(id) } catch (_: Exception) {}
            throw e
        }
    }

    /* -------------------------- notification --------------------------- */

    /** Shows the phone notification once per release (not for an ignored one). */
    fun notifyIfNew(c: Context) {
        val app = c.applicationContext
        val p = prefs(app)
        val code = p.getLong("code", 0L)
        if (code <= installedCode(app)) return
        if (p.getLong("ignored", 0L) == code || p.getLong("notified", 0L) == code) return
        if (!canNotify(app)) return
        post(app, p.getString("name", "") ?: "")
        p.edit().putLong("notified", code).apply()
    }

    private fun canNotify(app: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    private fun post(app: Context, versionName: String) {
        val res = app.resources
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, res.getString(R.string.update_notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN, OPEN_UPDATE)
        }
        val tap = PendingIntent.getActivity(app, 71, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = res.getString(R.string.update_notif_text, versionName)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(0xFF00C8C8.toInt())
            .setContentTitle(res.getString(R.string.app_name) + " update")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /** Background check every ~3 hours while the phone is online (safe to call on every app start). */
    fun schedule(c: Context) {
        WorkManager.getInstance(c.applicationContext).cancelUniqueWork("app_update_check")   // the old 6-hour job
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniquePeriodicWork("app_update_check_3h", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** The background job: ask GitHub, then show the notification if a new release is waiting. */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AppUpdater.fetchBlocking(applicationContext)
        AppUpdater.notifyIfNew(applicationContext)
        return Result.success()
    }
}
