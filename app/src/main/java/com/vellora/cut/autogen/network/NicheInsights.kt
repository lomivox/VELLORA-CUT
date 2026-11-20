package com.vellora.cut.autogen.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app has learned about the competition in THIS channel's niche: the topic worked out from the channel's
 * own recent titles, the titles that are going viral on that topic and the big channels active there.
 * It is saved on the phone so the AI (title writer and coach) can use it every time, even without a new search.
 */
object NicheInsights {
    private const val PREFS = "ytiq_niche"
    private const val KEY = "snapshot"

    data class Snapshot(val savedAt: Long, val niche: String, val titles: List<String>, val channels: List<String>)

    private val STOP = setOf(
        "video", "videos", "official", "latest", "full", "with", "from", "this", "that", "your", "what", "when",
        "kaise", "liye", "aur", "hain", "wala", "wali", "karna", "karein", "kare", "kyun", "kitni", "bhi", "story"
    )

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9\\u0600-\\u06FF]+")).filter { it.isNotBlank() }

    /** The 3 words that appear in the most of the channel's own recent titles = the channel's topic. Blank if nothing usable. */
    fun nicheQuery(ownTitles: List<String>, ownChannelTitle: String?): String {
        val skip = words(ownChannelTitle.orEmpty()).toSet()
        val freq = HashMap<String, Int>()
        ownTitles.take(15).forEach { title ->
            words(title).filter { it.length >= 4 && it !in STOP && it !in skip }.distinct()
                .forEach { freq[it] = (freq[it] ?: 0) + 1 }
        }
        return freq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .take(3).joinToString(" ") { it.key }
    }

    fun save(c: Context, s: Snapshot) {
        val json = JSONObject()
            .put("savedAt", s.savedAt)
            .put("niche", s.niche)
            .put("titles", JSONArray(s.titles))
            .put("channels", JSONArray(s.channels))
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    fun load(c: Context): Snapshot? {
        val raw = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val j = JSONObject(raw)
            fun list(name: String): List<String> {
                val a = j.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }
            Snapshot(j.optLong("savedAt"), j.optString("niche"), list("titles"), list("channels"))
        } catch (e: Exception) {
            null
        }
    }

    /** A short block for the AI Coach prompt, or "" when nothing has been learned yet. */
    fun coachBlock(c: Context): String {
        val s = load(c) ?: return ""
        if (s.titles.isEmpty() && s.channels.isEmpty()) return ""
        val sb = StringBuilder("\nCompetitors right now on this channel's topic (${s.niche}):\n")
        if (s.titles.isNotEmpty()) {
            sb.append("Viral video titles:\n")
            s.titles.take(6).forEach { sb.append("- ").append(it).append("\n") }
        }
        if (s.channels.isNotEmpty()) sb.append("Bade channels: ").append(s.channels.take(6).joinToString(", ")).append("\n")
        return sb.toString()
    }
}
