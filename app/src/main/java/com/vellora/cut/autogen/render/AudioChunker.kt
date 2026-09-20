package com.vellora.cut.autogen.render

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Splits a (usually already-extracted) audio file into ~60-second chunks.
 * Cloudflare's own Workers AI documentation for Whisper explicitly says
 * long audio must be split into chunks before transcription — sending the
 * whole file in one request silently only transcribes the first portion
 * instead of failing loudly, which is exactly the bug this fixes (a full
 * Surah recitation coming back as just the opening words).
 */
object AudioChunker {

    /** Exposed so callers (chunk-timestamp offsetting) use the exact same
     * value this actually split with, rather than a hardcoded duplicate. */
    const val CHUNK_SECONDS = 60

    suspend fun split(context: Context, audioFile: File): List<File> =
        suspendCancellableCoroutine { cont ->
            val dir = File(context.cacheDir, "whisper_chunks/${System.currentTimeMillis()}").apply { mkdirs() }
            val pattern = File(dir, "chunk_%03d.m4a").absolutePath

            val args = arrayOf(
                "-y", "-i", audioFile.absolutePath,
                "-f", "segment", "-segment_time", CHUNK_SECONDS.toString(),
                "-c", "copy", "-reset_timestamps", "1",
                pattern
            )

            FFmpegKit.executeWithArgumentsAsync(args) { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val chunks = dir.listFiles { f -> f.name.startsWith("chunk_") }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                    if (chunks.isNotEmpty()) {
                        cont.resume(chunks)
                    } else {
                        // Segmenting produced nothing (e.g. audio shorter than
                        // one chunk) — treat the whole file as a single chunk.
                        cont.resume(listOf(audioFile))
                    }
                } else {
                    // Splitting failed for some reason — fall back to the
                    // original whole file rather than losing the transcription
                    // entirely (it'll just be subject to the old single-shot
                    // limitation for this one case).
                    cont.resume(listOf(audioFile))
                }
            }
        }
}
