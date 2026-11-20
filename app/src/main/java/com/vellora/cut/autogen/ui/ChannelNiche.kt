package com.vellora.cut.autogen.ui

import android.content.Context
import com.vellora.cut.autogen.network.ChannelAuth
import com.vellora.cut.autogen.network.NicheInsights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What this channel is ABOUT, worked out from its own recent videos by the AI (Gemini first, Cloudflare as backup):
 * a one-line niche, the exact search phrases its audience types, and the core keywords. Plus the channel's country,
 * so competitor search stays inside the channel's own world. Remembered for a day (and rebuilt when the channel's
 * recent titles change), so it costs nothing on later visits.
 */
object ChannelNiche {

    data class Profile(
        val niche: String,
        val queries: List<String>,
        val keywords: List<String>,
        val country: String?,
        /** "arabic" when the channel's titles are mostly Urdu script, otherwise "latin" */
        val script: String
    ) {
        /** ISO 639-1 language for the search, only when it is certain (Urdu script titles). */
        val relevanceLanguage: String? get() = if (script == "arabic") "ur" else null
    }

    private const val PREFS = "ytiq_niche_profile"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun signature(ownTitles: List<String>) = ownTitles.take(6).joinToString("|").hashCode()

    private fun scriptOf(titles: List<String>): String {
        var arabic = 0
        var letters = 0
        titles.forEach { t ->
            t.forEach { ch ->
                if (ch.isLetter()) {
                    letters++
                    if (ch.code in 0x0600..0x06FF) arabic++
                }
            }
        }
        return if (letters > 0 && arabic.toDouble() / letters >= 0.3) "arabic" else "latin"
    }

    suspend fun load(
        context: Context,
        accountKey: String?,
        channelTitle: String?,
        ownTitles: List<String>,
        apiKey: String,
        forceRefresh: Boolean = false
    ): Profile = withContext(Dispatchers.IO) {
        val cacheKey = accountKey ?: "default"
        val sig = signature(ownTitles)
        if (!forceRefresh) {
            val raw = prefs(context).getString(cacheKey, null)
            if (raw != null) {
                try {
                    val j = JSONObject(raw)
                    if (System.currentTimeMillis() - j.optLong("savedAt") < TTL_MS && j.optInt("sig") == sig) {
                        return@withContext Profile(
                            niche = j.optString("niche"),
                            queries = j.optJSONArray("queries").toList(),
                            keywords = j.optJSONArray("keywords").toList(),
                            country = j.optString("country").takeIf { it.isNotBlank() },
                            script = j.optString("script", "latin")
                        )
                    }
                } catch (e: Exception) {
                    // rebuild below
                }
            }
        }

        var niche = ""
        var queries: List<String> = emptyList()
        var keywords: List<String> = emptyList()
        val titles = ownTitles.map { it.trim() }.filter { it.isNotBlank() }.take(15)
        if (titles.isNotEmpty()) {
            val prompt = buildString {
                append("Below are the titles of a YouTube channel's recent videos. From them, work out the channel's real topic (niche) ")
                append("and give YouTube search phrases to find competitors.\n")
                if (!channelTitle.isNullOrBlank()) append("Channel: $channelTitle\n")
                append("Titles:\n")
                titles.forEach { append("- ").append(it).append("\n") }
                append("\nReturn JSON only, no other words:\n")
                append("{\"niche\":\"<one sentence, in English>\",\"queries\":[\"<phrase1>\",\"<phrase2>\",\"<phrase3>\"],\"keywords\":[\"<6 to 8 real words>\"]}\n")
                append("Rules: queries must be what this channel's audience types into YouTube (2 to 5 words), ")
                append("in the same language/script as the channel's titles; each query must cover a different angle; do not give a generic single-word query ")
                append("(like \"motivation\"); do not name any channel or person.")
            }
            val ai = YtiqAi.text(context, prompt)
            if (ai is YtiqAiResult.Ok) {
                try {
                    val text = ai.text
                    val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
                    niche = json.optString("niche").trim()
                    queries = json.optJSONArray("queries").toList().map { it.take(60) }.filter { it.length >= 3 }.distinct().take(3)
                    keywords = json.optJSONArray("keywords").toList().map { it.take(30) }.distinct().take(8)
                } catch (e: Exception) {
                    // fall back below
                }
            }
        }
        if (queries.isEmpty()) {
            // No AI available: the most repeated words of the channel's own titles.
            val q = NicheInsights.nicheQuery(ownTitles, channelTitle)
            if (q.isNotBlank()) queries = listOf(q)
        }

        val country = fetchCountry(accountKey, apiKey)
        val profile = Profile(niche, queries, keywords, country, scriptOf(titles))
        if (queries.isNotEmpty()) {
            prefs(context).edit().putString(
                cacheKey,
                JSONObject()
                    .put("savedAt", System.currentTimeMillis())
                    .put("sig", sig)
                    .put("niche", niche)
                    .put("queries", JSONArray(queries))
                    .put("keywords", JSONArray(keywords))
                    .put("country", country ?: "")
                    .put("script", profile.script)
                    .toString()
            ).apply()
        }
        profile
    }

    private fun JSONArray?.toList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it).trim() }.filter { it.isNotBlank() }

    /** The channel's country (ISO code like PK) from the public channels.list, or null. */
    private fun fetchCountry(accountKey: String?, apiKey: String): String? {
        if (apiKey.isBlank() || accountKey == null) return null
        val channelId = accountKey.substringAfter(ChannelAuth.KEY_SEPARATOR, "")
        if (channelId.isBlank()) return null
        return try {
            val url = "https://www.googleapis.com/youtube/v3/channels?part=snippet&id=$channelId&key=$apiKey"
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string().orEmpty()).optJSONArray("items")?.optJSONObject(0)
                    ?.optJSONObject("snippet")?.optString("country")?.takeIf { it.length == 2 }
            }
        } catch (e: Exception) {
            null
        }
    }
}
