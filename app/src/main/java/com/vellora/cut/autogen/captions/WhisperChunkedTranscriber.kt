package com.vellora.cut.autogen.captions

import android.content.Context
import com.vellora.cut.autogen.data.CaptionSegment
import com.vellora.cut.autogen.data.CloudflareAccount
import com.vellora.cut.autogen.network.CloudflareAiClient
import com.vellora.cut.autogen.network.CloudflareApiException
import com.vellora.cut.autogen.render.AudioChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The actual fix for "only the first few words get transcribed" — per
 * Cloudflare's own Workers AI documentation, Whisper needs long audio
 * split into chunks; sending a whole multi-minute file in one request
 * silently returns only the opening portion instead of failing loudly.
 * This splits first (~60s pieces, see [AudioChunker]), transcribes each
 * chunk with the same pooled-account fallback as before, and stitches the
 * results back into one timeline with correctly offset timestamps.
 */
object WhisperChunkedTranscriber {

    suspend fun transcribe(
        context: Context,
        audioFile: File,
        accounts: List<CloudflareAccount>,
        onChunkProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<List<CaptionSegment>> = withContext(Dispatchers.IO) {
        if (accounts.isEmpty()) {
            return@withContext Result.failure(Exception("Koi Cloudflare account save nahi hai"))
        }

        val chunks = try {
            AudioChunker.split(context, audioFile)
        } catch (e: Exception) {
            listOf(audioFile) // splitting failed — try the whole file rather than giving up
        }

        val client = CloudflareAiClient()
        val allSegments = mutableListOf<CaptionSegment>()
        var lastError: String? = null

        chunks.forEachIndexed { index, chunk ->
            val chunkOffsetMs = index * AudioChunker.CHUNK_SECONDS * 1000L
            val chunkBytes = try {
                chunk.readBytes()
            } catch (e: Exception) {
                lastError = "Chunk ${index + 1} parhi nahi ja saki: ${e.message}"
                return@forEachIndexed
            }

            var chunkTranscribed = false
            for (account in accounts) {
                try {
                    val segments = client.transcribeAudio(chunkBytes, account.accountId, account.apiToken)
                    segments.forEach { seg ->
                        allSegments += seg.copy(
                            startMs = seg.startMs + chunkOffsetMs,
                            endMs = seg.endMs + chunkOffsetMs
                        )
                    }
                    chunkTranscribed = true
                    break
                } catch (e: CloudflareApiException) {
                    lastError = e.message
                    if (e.isQuotaExceeded) continue // try the next pooled account for THIS chunk
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown error"
                    break
                }
            }
            onChunkProgress(index + 1, chunks.size)
            // A single chunk failing (e.g. a brief network blip) shouldn't
            // throw away every other chunk's real transcription — it just
            // leaves a gap in the timeline for that ~60s window.
        }

        if (allSegments.isEmpty()) {
            Result.failure(Exception(lastError ?: "Transcription fail hui"))
        } else {
            Result.success(allSegments)
        }
    }
}
