package com.vellora.cut.autogen.shorts

import android.content.Context
import com.vellora.cut.autogen.data.CloudflareAccount
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.network.CloudflareAiClient
import com.vellora.cut.autogen.network.CloudflareApiException
import com.vellora.cut.autogen.network.YouTubeCompetitorResearch
import com.vellora.cut.autogen.network.YouTubeSuggest
import com.vellora.cut.autogen.render.VideoAudioExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Final generated metadata for one video. */
data class ShortsMetadataResult(
    val transcript: String,
    val researchedKeywords: List<String>,
    val title: String,
    val description: String,
    val tags: String,
    val hashtags: String
)

/**
 * Full pipeline for the Shorts Metadata tool. Every step is real:
 * 1. FFmpeg pulls the actual audio track out of the picked video.
 * 2. Cloudflare Whisper transcribes what's actually said.
 * 3. Real keyword research — if a YouTube Data API key is saved, this
 *    looks at the videos CURRENTLY ranking for this topic and combines
 *    their actual tags (competitor research, not a guess). Without a key,
 *    it falls back to YouTube's free autocomplete suggestions instead.
 * 4. The transcript + those real keywords are handed to an LLM, which
 *    writes the Title/Description/Tags/Hashtags grounded in both, in
 *    whichever language ([language]) was requested.
 */
object ShortsMetadataGenerator {

    suspend fun generate(
        context: Context,
        videoUri: String,
        accounts: List<CloudflareAccount>,
        language: String,
        onStatusChange: (String) -> Unit
    ): Result<ShortsMetadataResult> = withContext(Dispatchers.IO) {
        if (accounts.isEmpty()) {
            return@withContext Result.failure(
                Exception("Koi Cloudflare account save nahi hai — pehle Settings mein add karein")
            )
        }

        // ---- Step 1: extract audio ----
        onStatusChange("extracting_audio")
        val audioFile: File = VideoAudioExtractor.extract(context, videoUri)
            ?: return@withContext Result.failure(Exception("Video se audio nahi nikal saka"))

        // ---- Step 2: transcribe (real Whisper, pooled accounts) ----
        onStatusChange("transcribing")
        val client = CloudflareAiClient()
        val audioBytes = try {
            audioFile.readBytes()
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Extracted audio parhi nahi ja saki: ${e.message}"))
        }

        var transcript: String? = null
        var lastError: String? = null
        for (account in accounts) {
            try {
                val segments = client.transcribeAudio(audioBytes, account.accountId, account.apiToken)
                transcript = segments.joinToString(" ") { it.text }
                break
            } catch (e: CloudflareApiException) {
                lastError = e.message
                if (e.isQuotaExceeded) continue
                break
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                break
            }
        }
        if (transcript.isNullOrBlank()) {
            return@withContext Result.failure(Exception(lastError ?: "Transcription fail hui — video mein awaz nahi mili?"))
        }

        // ---- Step 3: real keyword research ----
        onStatusChange("researching_keywords")
        // Seed the search with the first few words of the transcript — a
        // cheap, real proxy for "what is this video about" without needing
        // a separate AI call just to guess a topic.
        val seed = transcript.split(" ").take(6).joinToString(" ")
        val youtubeApiKey = SecureCredentialStore(context).youtubeApiKey

        val competitor = if (youtubeApiKey.isNotBlank()) {
            try {
                YouTubeCompetitorResearch.research(seed, youtubeApiKey)
            } catch (e: Exception) {
                null
            }
        } else null

        val keywords: List<String>
        val competitorTitles: List<String>
        if (competitor != null && competitor.rankedKeywords.isNotEmpty()) {
            // Real competitor research worked: tags pooled from several
            // currently-ranking videos on this exact topic, most-shared first.
            keywords = competitor.rankedKeywords
            competitorTitles = competitor.competitorTitles
        } else {
            // Fallback: YouTube's own free autocomplete — still real search
            // data, just from one signal instead of several competing videos.
            keywords = try {
                YouTubeSuggest.fetch(seed)
            } catch (e: Exception) {
                emptyList()
            }
            competitorTitles = emptyList()
        }

        // ---- Step 4: AI generation grounded in transcript + real keywords ----
        onStatusChange("generating")
        val prompt = buildPrompt(transcript, keywords, competitorTitles, language)
        var rawResponse: String? = null
        lastError = null
        for (account in accounts) {
            try {
                rawResponse = client.generateText(prompt, account.accountId, account.apiToken)
                break
            } catch (e: CloudflareApiException) {
                lastError = e.message
                if (e.isQuotaExceeded) continue
                break
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                break
            }
        }
        if (rawResponse.isNullOrBlank()) {
            return@withContext Result.failure(Exception(lastError ?: "Title/Description generate nahi ho saki"))
        }

        val parsed = parseResponse(rawResponse)
        Result.success(
            ShortsMetadataResult(
                transcript = transcript,
                researchedKeywords = keywords,
                title = parsed.first,
                description = parsed.second,
                tags = parsed.third,
                hashtags = parsed.fourth
            )
        )
    }

    private fun buildPrompt(
        transcript: String,
        keywords: List<String>,
        competitorTitles: List<String>,
        language: String
    ): String {
        val keywordsBlock = if (keywords.isNotEmpty()) {
            "Real keywords/tags pooled from currently top-ranking YouTube videos on this exact topic " +
                "(most commonly used first — favor the ones near the top): ${keywords.take(25).joinToString(", ")}"
        } else {
            "(no extra keyword data available)"
        }
        val competitorBlock = if (competitorTitles.isNotEmpty()) {
            "\nTitles of those currently top-ranking competitor videos (for tone/style reference only — do not copy):\n" +
                competitorTitles.take(8).joinToString("\n") { "- $it" }
        } else ""

        val languageInstruction = if (language.equals("English", ignoreCase = true)) {
            "Write the Title, Description, Tags and Hashtags in English."
        } else {
            "Write the Title, Description, Tags and Hashtags in Urdu (اردو), using Urdu script — not Roman Urdu."
        }

        return """
            You are a YouTube SEO expert. Based on the video transcript below, write metadata for this video.
            $languageInstruction
            $keywordsBlock
            $competitorBlock

            Transcript:
            "${transcript.take(2000)}"

            Reply in EXACTLY this format, nothing else, no extra commentary:
            TITLE: <a punchy, clickable, SEO-friendly title, under 70 characters>
            DESCRIPTION: <a 2-3 sentence description that naturally includes relevant keywords>
            TAGS: <8-12 comma-separated tags, no # symbol>
            HASHTAGS: <5-8 space-separated #hashtags>
        """.trimIndent()
    }

    /** (title, description, tags, hashtags) */
    private data class Parsed(val first: String, val second: String, val third: String, val fourth: String)

    private fun parseResponse(raw: String): Parsed {
        fun extract(marker: String, nextMarkers: List<String>): String {
            val startIdx = raw.indexOf(marker)
            if (startIdx == -1) return ""
            val afterMarker = startIdx + marker.length
            var endIdx = raw.length
            for (next in nextMarkers) {
                val idx = raw.indexOf(next, afterMarker)
                if (idx != -1 && idx < endIdx) endIdx = idx
            }
            return raw.substring(afterMarker, endIdx).trim()
        }

        val allMarkers = listOf("TITLE:", "DESCRIPTION:", "TAGS:", "HASHTAGS:")
        val title = extract("TITLE:", allMarkers - "TITLE:")
        val description = extract("DESCRIPTION:", allMarkers - "DESCRIPTION:")
        val tags = extract("TAGS:", allMarkers - "TAGS:")
        val hashtags = extract("HASHTAGS:", allMarkers - "HASHTAGS:")

        return Parsed(
            first = title.ifBlank { "Untitled" },
            second = description,
            third = tags,
            fourth = hashtags
        )
    }
}
