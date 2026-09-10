package com.vellora.cut.autogen.captions

import android.content.Context
import android.net.Uri
import com.vellora.cut.autogen.data.CaptionSegment
import com.vellora.cut.autogen.data.CloudflareAccount
import com.vellora.cut.autogen.network.CloudflareAiClient
import com.vellora.cut.autogen.network.CloudflareApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcribes the project's voice-over into real, timestamped captions via
 * Cloudflare's Whisper model — reusing the exact same pooled-account
 * fallback [GenerateImagesWorker] uses for images: try each saved account
 * in order, skip ones that report today's quota is used up.
 */
object CaptionGenerator {

    suspend fun generate(
        context: Context,
        voiceOverUri: String,
        processedAudioPath: String?,
        accounts: List<CloudflareAccount>
    ): Result<List<CaptionSegment>> = withContext(Dispatchers.IO) {
        if (accounts.isEmpty()) {
            return@withContext Result.failure(
                Exception("Koi Cloudflare account save nahi hai — pehle Settings mein add karein")
            )
        }

        val audioBytes = try {
            resolveAudioBytes(context, processedAudioPath ?: voiceOverUri)
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Voice-over file open nahi ho saka: ${e.message}"))
        }

        val client = CloudflareAiClient()
        var lastError: String? = null

        for (account in accounts) {
            try {
                val segments = client.transcribeAudio(audioBytes, account.accountId, account.apiToken)
                return@withContext Result.success(segments)
            } catch (e: CloudflareApiException) {
                lastError = e.message
                if (e.isQuotaExceeded) continue // try the next pooled account
                break // a real error — no point burning through every account for it
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                break
            }
        }

        Result.failure(Exception(lastError ?: "Transcription fail hui"))
    }

    private fun resolveAudioBytes(context: Context, pathOrUri: String): ByteArray {
        val uri = Uri.parse(pathOrUri)
        return if (uri.scheme == null || uri.scheme == "file") {
            File(uri.path ?: pathOrUri).readBytes()
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Audio URI khol nahi saka")
        }
    }
}
