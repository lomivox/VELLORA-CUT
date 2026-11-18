package com.vellora.cut.autogen.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Remembers each connected channel's logo (by account key) so the channel list can show every channel's logo,
 * not only the selected one. Logos are filled in two ways: when a channel is selected (its logo is saved), and
 * for the others through one public YouTube channels.list call that uses the YouTube API key from Settings.
 */
object YouTubeAvatarCache {
    private const val PREFS = "ytiq_avatars"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, accountKey: String): String? = prefs(c).getString(accountKey, null)

    fun put(c: Context, accountKey: String, url: String) {
        if (url.isNotBlank() && get(c, accountKey) != url) prefs(c).edit().putString(accountKey, url).apply()
    }

    /** Fetches logos for accounts that have none saved yet. Blocking — call from a background thread. True if any logo was added. */
    fun fetchMissing(c: Context, accountKeys: List<String>, apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        // account key = "gmail#channelId"; old plain-Gmail keys have no channel id and are skipped.
        val missing = accountKeys.filter { get(c, it) == null && it.indexOf(ChannelAuth.KEY_SEPARATOR) > 0 }
        if (missing.isEmpty()) return false
        val byChannelId = missing.associateBy { it.substringAfter(ChannelAuth.KEY_SEPARATOR) }.filterKeys { it.isNotBlank() }
        if (byChannelId.isEmpty()) return false
        return try {
            val url = "https://www.googleapis.com/youtube/v3/channels?part=snippet&id=${byChannelId.keys.joinToString(",")}&key=$apiKey"
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return false
                val items = JSONObject(response.body?.string().orEmpty()).optJSONArray("items") ?: return false
                var added = false
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val thumbs = item.optJSONObject("snippet")?.optJSONObject("thumbnails")
                    val logo = (thumbs?.optJSONObject("default") ?: thumbs?.optJSONObject("medium"))?.optString("url").orEmpty()
                    val key = byChannelId[item.optString("id")]
                    if (logo.isNotBlank() && key != null) {
                        put(c, key, logo)
                        added = true
                    }
                }
                added
            }
        } catch (e: Exception) {
            false
        }
    }
}
