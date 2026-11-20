package com.vellora.cut.autogen.render

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Gives a video a meaningful file name (made from its generated title) right before upload,
 * the same as renaming it by hand in the Gallery. Everything here is best-effort: if a rename is not
 * possible, the upload simply carries on with the old name.
 */
object VideoRenamer {

    /** "5 Tips: Workout at Home!" -> "5-Tips-Ghar-par-Workout". Letters of any language (Urdu too) are kept. Null when nothing usable is left. */
    fun fileNameFromTitle(title: String): String? {
        val cleaned = buildString {
            title.forEach { ch ->
                append(if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') ch else ' ')
            }
        }.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("-")
        val limited = cleaned.take(80).trim('-', '_')
        return limited.takeIf { it.isNotBlank() }
    }

    /** Renames the copy that is about to be uploaded (only inside the app's own cache, never someone's real file). */
    fun renameLocalCopy(context: Context, file: File, baseName: String): File {
        return try {
            if (!file.absolutePath.startsWith(context.cacheDir.absolutePath)) return file
            val ext = file.extension.ifBlank { "mp4" }
            val target = File(file.parentFile, "$baseName.$ext")
            if (target.exists()) target.delete()
            if (file.renameTo(target)) target else file
        } catch (e: Exception) {
            file
        }
    }

    /**
     * Renames the video where it lives (Gallery / Files). Returns the video's new Uri, or null when
     * the phone does not allow this app to rename that file (some pickers hand out read-only access).
     */
    fun renameInGallery(context: Context, uriString: String, newFullName: String): Uri? {
        if (uriString.isBlank()) return null
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        return try {
            when {
                uri.scheme == "file" -> {
                    val f = File(uri.path ?: return null)
                    val target = File(f.parentFile, newFullName)
                    if (target.exists()) null else if (f.renameTo(target)) Uri.fromFile(target) else null
                }
                DocumentsContract.isDocumentUri(context, uri) ->
                    DocumentsContract.renameDocument(resolver, uri, newFullName)
                else -> {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newFullName) }
                    if (resolver.update(uri, values, null, null) > 0) uri else null
                }
            }
        } catch (e: Exception) {
            null // includes SecurityException: no permission to rename this file
        }
    }
}
