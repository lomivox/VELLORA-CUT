package com.vellora.cut.autogen.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Keeps everything a background upload needs inside the app's own storage, so the upload can carry on
 * (and be retried) even after the app is closed and the picked gallery file / cache is gone.
 */
object UploadStaging {

    const val SESSION_PREFS = "yt_upload_sessions"

    private const val THUMB_LIMIT_BYTES = 1_900_000 // YouTube allows 2 MB

    fun dir(context: Context): File = File(context.filesDir, "pending_uploads").apply { mkdirs() }

    /** Moves (temp copy) or copies (anything else) the video next to the app's data. Null if that failed. */
    fun stageVideo(context: Context, source: File, entityId: Long): File? {
        return try {
            val ext = source.extension.lowercase().takeIf { it.length in 2..4 } ?: "mp4"
            val dest = File(dir(context), "video_$entityId.$ext")
            if (source.absolutePath == dest.absolutePath) return dest
            dest.delete()
            val isTemp = source.absolutePath.startsWith(context.cacheDir.absolutePath)
            if (isTemp && source.renameTo(dest)) {
                dest
            } else {
                source.copyTo(dest, overwrite = true)
                dest
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Turns the picked picture into a JPEG under YouTube's 2 MB limit. Null if it cannot be read. */
    fun stageThumbnail(context: Context, uri: Uri, entityId: Long): File? {
        return try {
            val dest = File(dir(context), "thumb_$entityId.jpg")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: return null
            var sample = 1
            while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
            var bmp: Bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null

            var quality = 92
            while (true) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= THUMB_LIMIT_BYTES) {
                    dest.writeBytes(out.toByteArray())
                    return dest
                }
                if (quality > 60) {
                    quality -= 8
                } else {
                    if (bmp.width < 700) return null // cannot get it small enough without ruining it
                    val smaller = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.85f).toInt(), (bmp.height * 0.85f).toInt(), true)
                    if (smaller !== bmp) bmp.recycle()
                    bmp = smaller
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Forget the saved upload address (so the next upload of this entry starts fresh). */
    fun clearSession(context: Context, entityId: Long) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().remove("s_$entityId").apply()
    }

    /** Deletes the staged video/thumbnail of one entry and forgets its upload session. */
    fun cleanup(context: Context, entityId: Long) {
        dir(context).listFiles()
            ?.filter { it.name.startsWith("video_$entityId.") || it.name.startsWith("thumb_$entityId.") }
            ?.forEach { it.delete() }
        clearSession(context, entityId)
    }
}

/** Human-readable numbers for the progress card and the notification. */
object UploadFormat {
    fun bytes(b: Long): String {
        val mb = b / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
    }

    fun duration(seconds: Long): String {
        if (seconds < 0) return "calculating…"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h} ghanta ${m} min"
            m > 0 -> "${m} min ${s} sec"
            else -> "${s} sec"
        }
    }
}
