package com.vellora.cut.autogen.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Uses the real YouTube Data API v3 (search.list + videos.list) to look at
 * the videos CURRENTLY ranking for a topic, pull their actual tags/titles,
 * and rank keywords by how many of those real, competing videos use them —
 * "look at everyone else's real keywords/hashtags for this topic, then
 * combine them" is exactly what this does, not a single guess.
 *
 * Needs a YouTube Data API v3 key (Settings → YouTube API Key). Cheap: one
 * search.list call (100 quota units) + one videos.list call (1 unit) per
 * generation, well inside the free daily 10,000-unit quota.
 */
object YouTubeCompetitorResearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Result(
        /** Real tags/title-words from top-ranking videos, ranked by how many
         * of those videos used them (most-shared first). */
        val rankedKeywords: List<String>,
        /** Titles of the videos this was drawn from, for context in the AI prompt. */
        val competitorTitles: List<String>
    )

    /** Returns null (never throws) if the API key is missing/invalid or the
     * request fails — caller falls back to [YouTubeSuggest] in that case. */
    fun research(seed: String, apiKey: String, maxVideos: Int = 20): Result? {
        if (apiKey.isBlank() || seed.isBlank()) return null
        return try {
            val videoIds = searchTopVideos(seed, apiKey, maxVideos)
            if (videoIds.isEmpty()) return null
            fetchTagsAndTitles(videoIds, apiKey)
        } catch (e: Exception) {
            null
        }
    }

    private fun searchTopVideos(seed: String, apiKey: String, maxVideos: Int): List<String> {
        val encoded = URLEncoder.encode(seed.take(100), "UTF-8")
        val url = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&type=video&order=relevance&maxResults=$maxVideos" +
            "&q=$encoded&key=$apiKey"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return emptyList()
            if (!response.isSuccessful) return emptyList()
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                items.optJSONObject(i)?.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun fetchTagsAndTitles(videoIds: List<String>, apiKey: String): Result {
        val idsParam = videoIds.joinToString(",")
        val url = "https://www.googleapis.com/youtube/v3/videos" +
            "?part=snippet&id=$idsParam&key=$apiKey"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return Result(emptyList(), emptyList())
            if (!response.isSuccessful) return Result(emptyList(), emptyList())
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return Result(emptyList(), emptyList())

            val keywordCounts = LinkedHashMap<String, Int>()
            val titles = mutableListOf<String>()

            for (i in 0 until items.length()) {
                val snippet = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                snippet.optString("title").takeIf { it.isNotBlank() }?.let { titles += it }

                val tagsArray = snippet.optJSONArray("tags")
                if (tagsArray != null) {
                    for (t in 0 until tagsArray.length()) {
                        val tag = tagsArray.optString(t).trim().lowercase()
                        if (tag.isNotBlank()) {
                            keywordCounts[tag] = (keywordCounts[tag] ?: 0) + 1
                        }
                    }
                }
            }

            val ranked = keywordCounts.entries
                .sortedByDescending { it.value }
                .map { it.key }

            return Result(rankedKeywords = ranked, competitorTitles = titles)
        }
    }
}
