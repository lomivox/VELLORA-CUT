package com.vellora.cut.autogen.render

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Copies a rendered mp4 into the device's public video collection —
 * automatically, right after a successful render, no button needed — so it
 * shows up in the phone's Gallery/Photos app like any other video. Always
 * goes into its own "VELLORA-CUT" folder rather than dumping loose files
 * into the general Movies folder.
 */
object GallerySaver {

    private const val FOLDER_NAME = "VELLORA-CUT"

    /** Returns the new Uri (or file path) on success, null on failure. */
    fun saveVideoToGallery(context: Context, file: File): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreScoped(context, file)
        } else {
            saveViaLegacyPublicDirectory(context, file)
        }
    }

    /** Android 10+ (API 29+): scoped storage, no permission needed — the
     * system creates Movies/VELLORA-CUT on our behalf if it doesn't exist. */
    private fun saveViaMediaStoreScoped(context: Context, file: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$FOLDER_NAME")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Android 9 (API 28) and older: write directly into the public Movies
     * directory's own VELLORA-CUT sub-folder, then ask the media scanner to
     * index it so it shows up in Gallery apps immediately. Requires
     * WRITE_EXTERNAL_STORAGE (declared maxSdkVersion=28 in the manifest —
     * the caller must have that permission granted before calling this). */
    private fun saveViaLegacyPublicDirectory(context: Context, file: File): Uri? {
        return try {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val targetDir = File(moviesDir, FOLDER_NAME).apply { mkdirs() }
            val targetFile = File(targetDir, file.name)

            FileInputStream(file).use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }

            MediaScannerConnection.scanFile(
                context, arrayOf(targetFile.absolutePath), arrayOf("video/mp4"), null
            )

            Uri.fromFile(targetFile)
        } catch (e: Exception) {
            null
        }
    }
}
