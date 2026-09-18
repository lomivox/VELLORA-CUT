package com.vellora.cut.autogen.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * YouTube's own search-suggest endpoint — the same autocomplete list you'd
 * see typing into the YouTube search box. No API key, no login: it's a
 * public GET endpoint. Using it (rather than an AI guessing hashtags) is
 * what makes the generated keywords "real, researchable" instead of made up.
 */
object YouTubeSuggest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Returns up to ~10 real YouTube search suggestions for [seed], or an
     * empty list if the request fails — this is a nice-to-have enrichment
     * for the AI prompt, not something worth failing the whole generation
     * over if it's unreachable. */
    fun fetch(seed: String): List<String> {
        if (seed.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(seed.take(80), "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                if (!response.isSuccessful) return emptyList()
                // Response shape: ["seed term", ["suggestion one", "suggestion two", ...]]
                val outer = JSONArray(body)
                val suggestions = outer.optJSONArray(1) ?: return emptyList()
                (0 until suggestions.length()).mapNotNull { i ->
                    suggestions.optString(i).takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
