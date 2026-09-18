package com.vellora.cut.autogen.render

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Pulls just the audio track out of any picked Gallery video via real
 * FFmpeg (`-vn`, no video re-encode work at all) — used by the Shorts
 * Metadata tool so a video's spoken content can be fed to Whisper the same
 * way AutoGen's own voice-over already is.
 */
object VideoAudioExtractor {

    suspend fun extract(context: Context, videoUri: String): File? =
        suspendCancellableCoroutine { cont ->
            val workDir = File(context.cacheDir, "shorts_audio").apply { mkdirs() }
            val sourceFile = try {
                resolveToLocalFile(context, videoUri, workDir)
            } catch (e: Exception) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val outputFile = File(workDir, "extracted_${System.currentTimeMillis()}.m4a")

            val arguments = arrayOf(
                "-y",
                "-i", sourceFile.absolutePath,
                "-vn", // no video — audio only, cheap and fast
                "-acodec", "aac", "-b:a", "128k",
                outputFile.absolutePath
            )

            FFmpegKit.executeWithArgumentsAsync(arguments) { session ->
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
                    cont.resume(outputFile)
                } else {
                    cont.resume(null)
                }
            }
        }

    private fun resolveToLocalFile(context: Context, uriString: String, workDir: File): File {
        val uri = Uri.parse(uriString)
        if (uri.scheme == null || uri.scheme == "file") {
            return File(uri.path ?: uriString)
        }
        val dest = File(workDir, "source_video")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Video URI khol nahi saka")
        return dest
    }
}
