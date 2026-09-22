package com.vellora.cut.autogen.render

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vellora.cut.R

/**
 * Does nothing but hold a foreground-service notification for as long as
 * it's running.
 *
 * WHY THIS EXISTS: video export (RenderEngine, via FFmpegKit) and Shorts
 * Metadata generation both run for real minutes, as plain function calls
 * inside the app's own process — not a separate OS process. With nothing
 * in that process marked as a foreground service, most Android builds
 * (this is especially aggressive on MIUI/ColorOS/etc-style battery
 * managers) kill the WHOLE APP PROCESS within seconds of the screen
 * locking or the app going to background — silently cutting off whatever
 * FFmpeg render or network call was mid-flight, with no crash and no
 * error surfaced (the process is just gone). That's the actual cause of
 * "screen band ho ya app background jaye to kaam ruk jata hai".
 *
 * Starting this service right before that work begins, and stopping it
 * right after (success OR failure), tells Android "this process is doing
 * real foreground-priority work right now" for exactly as long as
 * needed — the export/generation code itself is completely unchanged.
 */
class RenderKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Kaam ho raha hai…"
        startForeground(NOTIFICATION_ID, buildNotification(label))
        // If the system still kills us under extreme memory pressure,
        // don't auto-restart into a re-created service with no task to
        // resume — RenderResult.Failed / the worker's own failure path
        // already handles "it stopped" on its own; restarting an empty
        // service here would just show a stuck notification forever.
        return START_NOT_STICKY
    }

    private fun buildNotification(label: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VELLORA CUT — Processing", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VELLORA CUT")
            .setContentText(label)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val EXTRA_LABEL = "label"
        private const val CHANNEL_ID = "vellora_render_channel"
        private const val NOTIFICATION_ID = 4821

        /** Call right before starting a long export/generation. Safe to
         * call again with a new [label] while already running — Android
         * just updates the same notification. */
        fun start(context: Context, label: String) {
            val intent = Intent(context, RenderKeepAliveService::class.java)
                .putExtra(EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Call as soon as the work finishes — success OR failure — so
         * the notification doesn't linger and the process drops back to
         * normal (killable-when-idle) priority. */
        fun stop(context: Context) {
            context.stopService(Intent(context, RenderKeepAliveService::class.java))
        }
    }
}
