package com.vellora.cut.autogen.shorts

import android.content.Context
import com.vellora.cut.autogen.data.CloudflareAccount
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.network.CloudflareAiClient
import com.vellora.cut.autogen.network.CloudflareApiException
import com.vellora.cut.autogen.network.GeminiApiException
import com.vellora.cut.autogen.network.GeminiClient
import com.vellora.cut.autogen.network.YouTubeCompetitorResearch
import com.vellora.cut.autogen.network.YouTubeSuggest
import com.vellora.cut.autogen.render.VideoAudioExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Full pipeline for the Shorts Metadata tool:
 * 1. The video's audio track is pulled out (a video with NO audio is fine — it just has no speech).
 * 2. Cloudflare Whisper makes a machine transcript (best effort; it can be wrong for Quran recitation).
 * 3. The real topic is decided: the creator's own HINT wins; otherwise the transcript with opening
 *    formulas (Audhu billah / Bismillah) removed; for silent videos Gemini looks at frames to name the topic.
 * 4. Keyword research on that real topic (YouTube competitor tags, or free autocomplete).
 * 5. Writing: Gemini keys first (each key gets 2 tries before the next key), it can LISTEN to the audio and
 *    SEE video frames; only if every Gemini key fails (or none is saved) the old Cloudflare Llama path runs.
 * 6. The channel's fixed tags are added to every video.
 */
object ShortsMetadataGenerator {

    /** Gemini inline media limit is ~20 MB per request; keep the audio well below that. */
    private const val MAX_INLINE_AUDIO_BYTES = 10L * 1024 * 1024
    /** Fewer spoken words than this = treat the video as (nearly) silent. */
    private const val MIN_SPOKEN_WORDS = 12

    suspend fun generate(
        context: Context,
        videoUri: String?,
        manualTopic: String? = null,
        accounts: List<CloudflareAccount>,
        language: String,
        topicHint: String? = null,
        onStatusChange: (String) -> Unit
    ): Result<ShortsMetadataResult> = withContext(Dispatchers.IO) {
        val store = SecureCredentialStore(context)
        val geminiKeys = store.geminiKeys
        if (accounts.isEmpty() && geminiKeys.isEmpty()) {
            return@withContext Result.failure(
                Exception("Koi Gemini key ya Cloudflare account save nahi hai — pehle Settings mein add karein")
            )
        }
        val hint = topicHint?.trim().orEmpty()

        var transcript = ""
        var audioFile: File? = null
        var frames: List<ByteArray> = emptyList()
        if (videoUri != null) {
            // ---- Step 1: audio (null = the video has no usable audio track; that is NOT an error) ----
            onStatusChange("extracting_audio")
            audioFile = VideoAudioExtractor.extract(context, videoUri)

            // ---- Step 2: machine transcript, best effort only ----
            if (audioFile != null && accounts.isNotEmpty()) {
                onStatusChange("transcribing")
                transcript = try {
                    com.vellora.cut.autogen.captions.WhisperChunkedTranscriber.transcribe(context, audioFile, accounts)
                        .getOrNull()
                        ?.sortedBy { it.startMs }
                        ?.joinToString(" ") { it.text }
                        .orEmpty()
                } catch (e: Exception) {
                    ""
                }
            }
            // A few frames let Gemini SEE the video (essential when nobody speaks).
            if (geminiKeys.isNotEmpty()) frames = VideoFrameSampler.sample(context, videoUri)
        } else if (!manualTopic.isNullOrBlank()) {
            // Person typed their own topic — it is used exactly like a transcript from here on.
            transcript = manualTopic
        } else {
            return@withContext Result.failure(Exception("Video ya topic mein se koi ek dein"))
        }

        val audioForGemini = audioFile?.takeIf { it.length() in 1..MAX_INLINE_AUDIO_BYTES }
        val canGeminiPerceive = geminiKeys.isNotEmpty() && (frames.isNotEmpty() || audioForGemini != null)
        if (transcript.isBlank() && hint.isBlank() && !canGeminiPerceive) {
            return@withContext Result.failure(
                Exception("Video mein awaz nahi mili aur koi topic hint bhi nahi — upar hint likh kar dobara try karein")
            )
        }

        val spokenText = stripOpeners(transcript)
        val hasSpeech = spokenText.split(Regex("\\s+")).count { it.isNotBlank() } >= MIN_SPOKEN_WORDS

        // ---- Step 3: decide the REAL topic used for keyword research ----
        var seed: String = when {
            hint.isNotBlank() -> hint.split(Regex("\\s+")).take(8).joinToString(" ")
            else -> spokenText.split(Regex("\\s+")).filter { it.isNotBlank() }.take(6).joinToString(" ")
        }
        if (hint.isBlank() && !hasSpeech && canGeminiPerceive) {
            onStatusChange("researching_keywords")
            val topicPrompt = "Look at the attached video frames (and listen to the audio if it is attached). " +
                "Reply with ONLY a short search topic of 3 to 6 words that a YouTube viewer would type to find " +
                "this video. No quotes, no explanation, no punctuation."
            val topicOut = runGemini(geminiKeys, topicPrompt, audioForGemini, frames)
            topicOut.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(80)?.let { if (it.isNotBlank()) seed = it }
        }

        // ---- Step 4: real keyword research on that topic ----
        onStatusChange("researching_keywords")
        val youtubeApiKey = store.youtubeApiKey
        val competitor = if (seed.isNotBlank() && youtubeApiKey.isNotBlank()) {
            try {
                YouTubeCompetitorResearch.research(seed, youtubeApiKey)
            } catch (e: Exception) {
                null
            }
        } else null

        val keywords: List<String>
        val competitorTitles: List<String>
        if (competitor != null && competitor.rankedKeywords.isNotEmpty()) {
            keywords = filterCompetitorNoise(competitor.rankedKeywords, "$transcript $hint")
            competitorTitles = competitor.competitorTitles
        } else {
            keywords = if (seed.isBlank()) emptyList() else try {
                YouTubeSuggest.fetch(seed)
            } catch (e: Exception) {
                emptyList()
            }
            competitorTitles = emptyList()
        }

        // ---- Step 5: writing. Gemini keys first, old Cloudflare system as the backup ----
        onStatusChange("generating")
        val channelName = com.vellora.cut.autogen.network.YouTubeAccounts.selected(context)?.channelTitle?.takeIf { it.isNotBlank() }
            ?: store.channelName
        val style = store.channelStyle.trim()
        var rawResponse: String? = null
        var lastError: String? = null

        if (geminiKeys.isNotEmpty()) {
            val geminiPrompt = buildPrompt(
                transcript, keywords, competitorTitles, language, channelName,
                hint, style, hasSpeech, hasAudio = audioForGemini != null, hasVisuals = frames.isNotEmpty()
            )
            val out = runGemini(geminiKeys, geminiPrompt, audioForGemini, frames)
            rawResponse = out.text
            lastError = out.error
        }

        if (rawResponse.isNullOrBlank() && accounts.isNotEmpty()) {
            // Cloudflare cannot hear/see the video, so its prompt has no audio/frames parts.
            val cfPrompt = buildPrompt(
                transcript, keywords, competitorTitles, language, channelName,
                hint, style, hasSpeech, hasAudio = false, hasVisuals = false
            )
            val client = CloudflareAiClient()
            // Every account gets up to 2 tries (short pause between) before the next account is used.
            outer@ for (account in accounts) {
                var succeeded = false
                repeat(2) { attempt ->
                    if (succeeded) return@repeat
                    try {
                        rawResponse = client.generateText(cfPrompt, account.accountId, account.apiToken)
                        succeeded = true
                    } catch (e: CloudflareApiException) {
                        lastError = e.message
                        if (!e.isQuotaExceeded && attempt == 0) delay(1500)
                    } catch (e: Exception) {
                        lastError = e.message ?: "Unknown error"
                        if (attempt == 0) delay(1500)
                    }
                }
                if (succeeded) break@outer
            }
        }

        val finalRaw: String = rawResponse?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(Exception(lastError ?: "Title/Description generate nahi ho saki"))

        val parsed = parseResponse(finalRaw)
        val title = if (parsed.first == "Untitled" && hint.isNotBlank()) hint.take(100) else parsed.first
        val descriptionWithHashtags = appendTopHashtagsToDescription(parsed.second, parsed.fourth)
        Result.success(
            ShortsMetadataResult(
                transcript = transcript,
                researchedKeywords = keywords,
                title = title,
                description = descriptionWithHashtags,
                tags = mergeFixedTags(parsed.third, store.fixedTags),
                hashtags = parsed.fourth
            )
        )
    }

    private data class GeminiOutcome(val text: String?, val error: String?)

    /**
     * Tries every saved Gemini key in order. Each key gets 2 real tries (short pause between them) before the
     * next key is used — a single hiccup never skips a key. If a try fails with "bad request" the second try
     * leaves the audio out (the audio part is the usual cause), so the frames/text can still go through.
     */
    private suspend fun runGemini(keys: List<String>, prompt: String, audio: File?, frames: List<ByteArray>): GeminiOutcome {
        val gemini = GeminiClient()
        var lastError: String? = null
        for (key in keys) {
            var audioForTry = audio
            for (attempt in 0..1) {
                try {
                    return GeminiOutcome(gemini.generate(key, prompt, audioForTry, frames), null)
                } catch (e: GeminiApiException) {
                    lastError = "Gemini: ${e.message}"
                    if (e.isBadRequest) audioForTry = null
                } catch (e: Exception) {
                    lastError = "Gemini: ${e.message ?: "Unknown error"}"
                }
                if (attempt == 0) delay(1500)
            }
        }
        return GeminiOutcome(null, lastError)
    }

    /** Lower-cases and unifies Arabic/Urdu letter variants and drops diacritics, so openers match however they were typed. */
    private fun normalizeAr(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when (ch) {
                in '\u064B'..'\u065F', '\u0670', '\u0640' -> {} // diacritics, tatweel
                '\u0623', '\u0625', '\u0622', '\u0671' -> sb.append('\u0627') // alef variants
                '\u064A', '\u0649' -> sb.append('\u06CC') // yeh variants
                '\u0643' -> sb.append('\u06A9') // kaf
                '\u06C1', '\u06BE', '\u06D5' -> sb.append('\u0647') // heh variants
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val openerRegex = Regex(
        listOf(
            "اعوذ بالله من الشیطان الرجیم", "من الشیطان الرجیم", "اعوذ بالله", "اعوذ",
            "بسم الله الرحمن الرحیم", "الرحمن الرحیم", "بسم الله",
            "audhu billah", "auzubillah", "bismillah", "assalam o alaikum", "assalamualaikum"
        ).joinToString("|") { Regex.escape(normalizeAr(it)) }
    )

    /** The transcript without the usual opening formulas, so the topic is what is really said afterwards. */
    private fun stripOpeners(transcript: String): String =
        openerRegex.replace(normalizeAr(transcript), " ").replace(Regex("\\s+"), " ").trim()

    /** AI tags first, then the channel's fixed tags; duplicates removed; kept under YouTube's ~500 character limit. */
    private fun mergeFixedTags(aiTags: String, fixedTags: String): String {
        fun split(s: String) = s.split(',', '\u060C', '\n').map { it.trim().trimStart('#').trim() }.filter { it.isNotBlank() }
        val fixed = split(fixedTags).distinctBy { it.lowercase() }
        if (fixed.isEmpty()) return aiTags
        val fixedLower = fixed.map { it.lowercase() }.toSet()
        var budget = 480 - fixed.sumOf { it.length + 2 }
        val kept = ArrayList<String>()
        for (tag in split(aiTags).distinctBy { it.lowercase() }) {
            if (tag.lowercase() in fixedLower) continue
            if (tag.length + 2 > budget) break
            kept.add(tag)
            budget -= tag.length + 2
        }
        return (kept + fixed).joinToString(", ")
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
        channelName: String,
        hint: String,
        channelStyle: String,
        hasSpeech: Boolean,
        hasAudio: Boolean,
        hasVisuals: Boolean
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

        val hintBlock = if (hint.isNotBlank()) {
            "CREATOR'S TOPIC HINT (authoritative — this is what the video is really about; it overrides anything " +
                "the transcript or the keyword list suggests): \"$hint\". If this hint already reads like a title, " +
                "keep its exact wording as the core of the TITLE and only add to it — never shorten or rephrase it."
        } else ""

        val styleBlock = if (channelStyle.isNotBlank()) {
            "CHANNEL STYLE RULES (set by the channel owner — follow them exactly for Title, Description and Tags):\n$channelStyle"
        } else ""

        val mediaBlock = buildString {
            if (hasAudio) append(
                "The video's audio track is attached. Listen to it yourself — it is the most reliable source. " +
                    "The transcript below was made by a machine and may be wrong, especially for Quran recitation " +
                    "or any non-English speech: trust your own listening over it. "
            )
            if (hasVisuals) append("Sample frames from the video are attached; use them to understand what the video shows. ")
            if (!hasSpeech) append(
                "This video has little or no speech, so the transcript is nearly empty. Build the metadata from what " +
                    "the video shows plus the creator's hint, and do not mention that it has no speech. "
            )
        }.trim()

        return """
            You are a professional YouTube SEO strategist who writes complete, publish-ready metadata that
            actually ranks — not a rough draft. Based on the video (and transcript) below, write full metadata
            for this video, matching the depth and polish of a top-performing channel's uploads.
            $languageInstruction
            $hintBlock
            $styleBlock
            $mediaBlock
            $keywordsBlock
            $competitorBlock

            FIRST decide the real subject of the video. Ignore opening formulas such as "Audhu billah", "Bismillah",
            greetings or intros when deciding the topic — for example in a Quran recitation video, identify which
            Surah/Ayat is actually being recited and make THAT the topic. Never use an opening formula or a greeting as the Title.

            CRITICAL RULES — read carefully, these keywords/titles came from OTHER people's videos on a similar topic, not necessarily this one:
            - Do NOT name any specific person (doctor, expert, influencer, creator) anywhere in the Title, Description, Tags, or Hashtags UNLESS that exact person is named in the Transcript or the creator's hint.
            - Do NOT mention any specific country, city, or region anywhere UNLESS it is explicitly mentioned in the Transcript or the creator's hint.
            - The keyword list above is inspiration for GENERAL topic phrasing only — any keyword that names a specific person or place must be dropped unless it also appears in the Transcript or hint.
            - Do not use teaching-academy wording ("learn with us", course/class promotion) unless the video itself is a lesson.
            - When in doubt, leave it out — a shorter, accurate tag list beats a longer one with details that don't belong to this video.

            Transcript (machine-made, may contain errors):
            "${transcript.take(6000).ifBlank { "(none)" }}"

            Write each field to this exact standard — a thin, one-line answer is a FAILED response:

            TITLE — 40 to 70 characters, punchy and clickable, leads with the primary keyword, creates
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
