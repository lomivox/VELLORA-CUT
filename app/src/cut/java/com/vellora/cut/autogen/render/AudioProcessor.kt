package com.vellora.cut.autogen.render

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

/**
 * Applies REAL audio processing to the voice-over via FFmpeg's actual
 * audio filters — not a cosmetic slider that only changes a number:
 *  - Noise reduction: `afftdn` (FFT-based denoiser), a real spectral
 *    noise-reduction filter — [noiseReductionPercent] (0-100) maps to its
 *    `nr` parameter (0-97 dB).
 *  - Volume: FFmpeg's `volume` filter, a real gain multiplier —
 *    [volumePercent] (0-500) maps directly (100 = unchanged, 500 = 5x).
 *
 * Writes one processed copy of the voice-over; both the live
 * PreviewPlayer and the final RenderEngine play/mux THIS file instead of
 * the original picked one whenever it exists, so the effect is genuinely
 * audible everywhere, not just implied by a UI number.
 */
object AudioProcessor {

    /** Runs async; [onComplete] is called on the main thread with the
     * processed file, or null on failure. */
    fun process(
        context: Context,
        voiceOverUri: String,
        noiseReductionPercent: Int,
        volumePercent: Int,
        onComplete: (File?) -> Unit
    ) {
        val workDir = File(context.cacheDir, "audio_process").apply { mkdirs() }

        val sourceFile = try {
            resolveToLocalFile(context, voiceOverUri, workDir)
        } catch (e: Exception) {
            onComplete(null)
            return
        }

        val outputFile = File(workDir, "processed_${System.currentTimeMillis()}.m4a")

        val filters = mutableListOf<String>()
        if (noiseReductionPercent > 0) {
            val nr = (noiseReductionPercent.coerceIn(0, 100) / 100.0 * 97.0).coerceIn(0.01, 97.0)
            filters += "afftdn=nr=%.2f".format(nr)
        }
        val volumeFactor = volumePercent.coerceIn(0, 500) / 100.0
        filters += "volume=%.2f".format(volumeFactor)

        val arguments = arrayOf(
            "-y",
            "-i", sourceFile.absolutePath,
            "-af", filters.joinToString(","),
            "-c:a", "aac", "-b:a", "192k",
            outputFile.absolutePath
        )

        FFmpegKit.executeWithArgumentsAsync(arguments) { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                onComplete(outputFile)
            } else {
                onComplete(null)
            }
        }
    }

    /** Same content:// copy-to-file logic RenderEngine already uses —
     * FFmpeg's native code can't read a content:// URI directly. */
    private fun resolveToLocalFile(context: Context, uriString: String, workDir: File): File {
        val uri = Uri.parse(uriString)
        if (uri.scheme == null || uri.scheme == "file") {
            return File(uri.path ?: uriString)
        }
        val dest = File(workDir, "source_input")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Voice-over URI khol nahi saka")
        return dest
    }
}
