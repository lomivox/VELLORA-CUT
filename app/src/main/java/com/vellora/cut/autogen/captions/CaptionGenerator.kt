package com.vellora.cut.autogen.captions

import android.content.Context
import android.net.Uri
import com.vellora.cut.autogen.data.CaptionSegment
import com.vellora.cut.autogen.data.CloudflareAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Transcribes the project's voice-over into real, timestamped captions via
 * Cloudflare's Whisper model. Delegates to [WhisperChunkedTranscriber],
 * which splits long audio into chunks first — sending a whole multi-minute
 * file in one request silently only transcribes the first portion instead
 * of failing loudly, which chunking fixes.
 */
object CaptionGenerator {

    suspend fun generate(
        context: Context,
        voiceOverUri: String,
        processedAudioPath: String?,
        accounts: List<CloudflareAccount>,
        language: String = "ur"
    ): Result<List<CaptionSegment>> = withContext(Dispatchers.IO) {
        if (accounts.isEmpty()) {
            return@withContext Result.failure(
                Exception("Koi Cloudflare account save nahi hai — pehle Settings mein add karein")
            )
        }

        val audioFile = try {
            resolveAudioFile(context, processedAudioPath ?: voiceOverUri)
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Voice-over file open nahi ho saka: ${e.message}"))
        }

        WhisperChunkedTranscriber.transcribe(context, audioFile, accounts, language)
    }

    private fun resolveAudioFile(context: Context, pathOrUri: String): File {
        val uri = Uri.parse(pathOrUri)
        return if (uri.scheme == null || uri.scheme == "file") {
            File(uri.path ?: pathOrUri)
        } else {
            val dir = File(context.cacheDir, "caption_audio").apply { mkdirs() }
            val dest = File(dir, "source_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Audio URI khol nahi saka")
            dest
        }
    }
}

