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
        videoUri: String?,
        manualTopic: String? = null,
        accounts: List<CloudflareAccount>,
        language: String,
        onStatusChange: (String) -> Unit
    ): Result<ShortsMetadataResult> = withContext(Dispatchers.IO) {
        if (accounts.isEmpty()) {
            return@withContext Result.failure(
                Exception("Koi Cloudflare account save nahi hai — pehle Settings mein add karein")
            )
        }

        val transcript: String
        if (videoUri != null) {
            // ---- Step 1: extract audio ----
            onStatusChange("extracting_audio")
            val audioFile: File = VideoAudioExtractor.extract(context, videoUri)
                ?: return@withContext Result.failure(Exception("Video se audio nahi nikal saka"))

            // ---- Step 2: transcribe (chunked — see WhisperChunkedTranscriber's
            // doc comment for why: a whole multi-minute file in one Whisper
            // request silently returns only the opening portion) ----
            onStatusChange("transcribing")
            val transcribeResult = com.vellora.cut.autogen.captions.WhisperChunkedTranscriber.transcribe(
                context, audioFile, accounts
            )
            val segments = transcribeResult.getOrElse { e ->
                return@withContext Result.failure(Exception(e.message ?: "Transcription fail hui — video mein awaz nahi mili?"))
            }
            transcript = segments.sortedBy { it.startMs }.joinToString(" ") { it.text }
            if (transcript.isBlank()) {
                return@withContext Result.failure(Exception("Transcription khaali aayi — video mein awaz nahi mili?"))
            }
        } else if (!manualTopic.isNullOrBlank()) {
            // Person typed their own topic — skip audio/transcription
            // entirely and use it exactly like a transcript from here on.
            transcript = manualTopic
        } else {
            return@withContext Result.failure(Exception("Video ya topic mein se koi ek dein"))
        }
        val client = CloudflareAiClient()

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
            keywords = filterCompetitorNoise(competitor.rankedKeywords, transcript)
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
        val channelName = SecureCredentialStore(context).channelName
        val prompt = buildPrompt(transcript, keywords, competitorTitles, language, channelName)
        var rawResponse: String? = null
        var lastError: String? = null
        // Every account gets up to 2 tries (with a short pause between
        // them) before moving on, and EVERY account is tried regardless of
        // what kind of error the previous one hit — a one-off "Khaali
        // response mila" or a transient DNS/network hiccup on a single
        // attempt used to permanently fail the whole operation right there
        // (the old code `break`-ed out on the very first non-quota error
        // instead of retrying or trying the next account), which is why
        // this sometimes failed almost immediately with no real chance to
        // succeed. A genuinely bad prompt/account will still fail the same
        // way every time and correctly surface its real error at the end.
        outer@ for (account in accounts) {
            var succeeded = false
            repeat(2) { attempt ->
                if (succeeded) return@repeat
                try {
                    rawResponse = client.generateText(prompt, account.accountId, account.apiToken)
                    succeeded = true
                } catch (e: CloudflareApiException) {
                    lastError = e.message
                    if (!e.isQuotaExceeded && attempt == 0) kotlinx.coroutines.delay(1500)
                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown error"
                    if (attempt == 0) kotlinx.coroutines.delay(1500)
                }
            }
            if (succeeded) break@outer
        }
        if (rawResponse.isNullOrBlank()) {
            return@withContext Result.failure(Exception(lastError ?: "Title/Description generate nahi ho saki"))
        }

        val parsed = parseResponse(rawResponse)
        val descriptionWithHashtags = appendTopHashtagsToDescription(parsed.second, parsed.fourth)
        Result.success(
            ShortsMetadataResult(
                transcript = transcript,
                researchedKeywords = keywords,
                title = parsed.first,
                description = descriptionWithHashtags,
                tags = parsed.third,
                hashtags = parsed.fourth
            )
        )
    }

    /**
     * Real YouTube behavior: the first 3 hashtags found in a video's
     * DESCRIPTION (not the separate hashtags field alone) become clickable
     * above the title. Appending them here guarantees that happens instead
     * of relying on the model to remember to do it inside the description
     * text itself.
     */
    private fun appendTopHashtagsToDescription(description: String, hashtags: String): String {
        val topTags = hashtags.split(Regex("\\s+"))
            .filter { it.startsWith("#") && it.length > 1 }
            .take(3)
        if (topTags.isEmpty() || description.isBlank()) return description
        return description.trimEnd() + "\n\n" + topTags.joinToString(" ")
    }

    /**
     * Competitor tags come from OTHER people's videos — some of them are
     * specific to THAT video (a named doctor/expert, a specific country the
     * competitor made their video for) and have no business appearing in
     * OUR metadata. This drops the two most common offenders — a named
     * person's title/name, and a country/region name — unless that exact
     * word genuinely appears in OUR OWN transcript, meaning it really is
     * relevant here too. Generic topical multi-word phrases (which won't
     * literally appear in the transcript verbatim, and that's fine) are
     * left alone — this only targets named-entity-shaped keywords.
     */
    private fun filterCompetitorNoise(keywords: List<String>, transcript: String): List<String> {
        val transcriptWords = transcript.lowercase()
            .split(Regex("[^a-z0-9\\u0600-\\u06FF]+"))
            .filter { it.isNotBlank() }
            .toSet()

        val namePrefixes = setOf("dr", "dr.", "mr", "mr.", "mrs", "mrs.", "ms", "ms.", "prof", "prof.", "sheikh", "sir")
        val commonCountriesAndRegions = setOf(
            "india", "pakistan", "usa", "america", "united states", "uk", "united kingdom",
            "uae", "bangladesh", "china", "canada", "australia", "saudi arabia", "egypt",
            "turkey", "indonesia", "nigeria", "south africa", "russia", "germany", "france"
        )

        return keywords.filter { keyword ->
            val kw = keyword.lowercase().trim()
            val words = kw.split(Regex("\\s+"))

            val looksLikeNamedPerson = words.firstOrNull() in namePrefixes
            // The SPECIFIC country/region word(s) inside this keyword — not
            // the whole phrase. e.g. for "screen time india" this is just
            // ["india"], not ["screen","time","india"].
            val matchedCountryWords = words.filter { it in commonCountriesAndRegions }

            if (!looksLikeNamedPerson && matchedCountryWords.isEmpty()) return@filter true // ordinary topical keyword — keep

            // Named-person keyword: keep only if a real name word (not the
            // title, not generic filler) genuinely shows up in OUR transcript.
            if (looksLikeNamedPerson) {
                val nameWords = words.filter { it !in namePrefixes && it.length > 2 }
                if (nameWords.isNotEmpty() && nameWords.any { it in transcriptWords }) return@filter true
            }

            // Country/region keyword: keep only if that SPECIFIC country word
            // itself is in our transcript — checking any other word in the
            // phrase (like "screen"/"time") is exactly the bug that let
            // "screen time india" slip through when the video just happened
            // to also say "screen" and "time" naturally.
            if (matchedCountryWords.isNotEmpty() && matchedCountryWords.any { it in transcriptWords }) return@filter true

            false
        }
    }

    private fun buildPrompt(
        transcript: String,
        keywords: List<String>,
        competitorTitles: List<String>,
        language: String,
        channelName: String
    ): String {
        val keywordsBlock = if (keywords.isNotEmpty()) {
            "Real keywords/tags pooled from currently top-ranking YouTube videos on this exact topic " +
                "(most commonly used first — favor the ones near the top): ${keywords.take(35).joinToString(", ")}"
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
            "Write the Title, Description, Tags and Hashtags in natural, fluent, GRAMMATICALLY CORRECT " +
                "Urdu (اردو), using proper Urdu script — never Roman Urdu, never English words unless there " +
                "is no natural Urdu equivalent (e.g. a proper noun). Write the way a professional Urdu " +
                "YouTuber actually talks to their audience — natural spoken-register Urdu, not a stiff " +
                "word-for-word translation from English. Read your own Urdu output back before finishing " +
                "and fix anything that sounds unnatural or grammatically awkward."
        }

        val channelInstruction = if (channelName.isNotBlank()) {
            " Naturally weave in a mention of the channel name \"$channelName\" here " +
                "(e.g. \"Subscribe to $channelName for more\") — phrase it naturally in " +
                "whichever language is being used, don't just paste it awkwardly."
        } else ""

        return """
            You are a professional YouTube SEO strategist who writes complete, publish-ready metadata that
            actually ranks — not a rough draft. Based on the video transcript below, write full metadata
            for this video, matching the depth and polish of a top-performing channel's uploads.
            $languageInstruction
            $keywordsBlock
            $competitorBlock

            CRITICAL RULES — read carefully, these keywords/titles came from OTHER people's videos on a similar topic, not necessarily this one:
            - Do NOT name any specific person (doctor, expert, influencer, creator) anywhere in the Title, Description, Tags, or Hashtags UNLESS that exact person is named in the Transcript below.
            - Do NOT mention any specific country, city, or region anywhere UNLESS it is explicitly mentioned in the Transcript below.
            - The keyword list above is inspiration for GENERAL topic phrasing only (e.g. "mobile addiction in children" is fine even if not verbatim in the transcript) — but any keyword that names a specific person or place must be dropped unless it also appears in the Transcript.
            - When in doubt, leave it out — a shorter, accurate tag list beats a longer one with details that don't belong to this video.

            Transcript:
            "${transcript.take(6000)}"

            Write each field to this exact standard — a thin, one-line answer is a FAILED response:

            TITLE — under 70 characters, punchy and clickable, leads with the primary keyword, creates
            curiosity or urgency without being clickbait-fake to the actual content.

            DESCRIPTION — a full, complete, professional YouTube description, 150-300 words across
            SEVERAL SHORT PARAGRAPHS (never a single line or a single sentence):
              1. Opening hook (1-2 sentences) that restates the core promise/topic and naturally includes
                 the primary keyword in the first 25 words (this is what shows before "Show more").
              2. A body paragraph (2-4 sentences) that expands on what the video actually covers, naturally
                 weaving in 3-5 of the researched keywords/phrases without keyword-stuffing.
              3. A short call-to-action paragraph (1-2 sentences) inviting the viewer to like/comment/
                 subscribe/share, phrased naturally for this topic and language.$channelInstruction

            TAGS — 12-15 comma-separated tags, no # symbol, ordered from most to least important, mixing
            short broad tags (2-3 words) with longer specific long-tail phrases (4-6 words).

            HASHTAGS — 6-10 space-separated #hashtags, no spaces inside a tag, mixing broad and specific.

            Reply in EXACTLY this format, nothing else, no extra commentary, no markdown, no asterisks:
            TITLE: <title>
            DESCRIPTION: <the full multi-paragraph description>
            TAGS: <tags>
            HASHTAGS: <hashtags>
        """.trimIndent()
    }

    /** (title, description, tags, hashtags) */
    private data class Parsed(val first: String, val second: String, val third: String, val fourth: String)

    private fun parseResponse(raw: String): Parsed {
        fun extract(marker: String, nextMarkers: List<String>): String {
            // Case-insensitive search — the model may occasionally emit
            // "Title:" instead of "TITLE:" despite the prompt's instruction.
            val startIdx = raw.indexOf(marker, ignoreCase = true)
            if (startIdx == -1) return ""
            val afterMarker = startIdx + marker.length
            var endIdx = raw.length
            for (next in nextMarkers) {
                val idx = raw.indexOf(next, afterMarker, ignoreCase = true)
                if (idx != -1 && idx < endIdx) endIdx = idx
            }
            return raw.substring(afterMarker, endIdx).trim().trim('*', '#', ' ')
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
