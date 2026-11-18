package com.vellora.cut.autogen.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * "Who is winning on this topic right now?" — uses the real YouTube Data API v3:
 *  - search.list (order = viewCount, last 30 days)  → the most-watched recent videos for the topic
 *  - videos.list                                      → their real view counts
 *  - channels.list                                    → the real subscriber count of each channel
 * From that it works out a VIRAL SCORE per video (views ÷ subscribers: a small channel whose video got many
 * times its subscriber count is the real signal that the topic/title pulls viewers) and a list of the big
 * channels active on the topic.
 *
 * Quota: about 102 units per research (search 100 + two 1-unit calls), against the free 10,000/day.
 */
object YouTubeViralResearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class ViralVideo(
        val id: String,
        val title: String,
        val channelId: String,
        val channelTitle: String,
        val views: Long,
        /** null when the channel hides its subscriber count */
        val subscribers: Long?,
        /** views ÷ subscribers (subscribers floored at 100); null when subscribers are hidden */
        val score: Double?
    )

    data class BigChannel(
        val id: String,
        val title: String,
        val subscribers: Long,
        /** how many of the found videos belong to this channel */
        val videosFound: Int
    )

    data class Result(val videos: List<ViralVideo>, val channels: List<BigChannel>)

    /** Either [result] or a short human-readable [error] (Roman Urdu), never both. */
    class Outcome(val result: Result?, val error: String?)

    fun research(topic: String, apiKey: String, days: Int = 30, maxVideos: Int = 25): Outcome {
        if (apiKey.isBlank()) return Outcome(null, "Settings mein YouTube API Key lagayen")
        if (topic.isBlank()) return Outcome(null, "Topic likhein")
        return try {
            val ids = searchTopVideos(topic.take(100), apiKey, days, maxVideos)
            if (ids.isEmpty()) return Outcome(null, "Is topic par pichle $days din ki koi video nahi mili")

            val videoJson = get(
                "https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&id=${ids.joinToString(",")}&key=$apiKey"
            )
            data class Raw(val id: String, val title: String, val channelId: String, val channelTitle: String, val views: Long)
            val raws = ArrayList<Raw>()
            val vItems = videoJson.optJSONArray("items")
            if (vItems != null) {
                for (i in 0 until vItems.length()) {
                    val it = vItems.optJSONObject(i) ?: continue
                    val sn = it.optJSONObject("snippet") ?: continue
                    raws.add(
                        Raw(
                            id = it.optString("id"),
                            title = sn.optString("title"),
                            channelId = sn.optString("channelId"),
                            channelTitle = sn.optString("channelTitle"),
                            views = it.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L
                        )
                    )
                }
            }
            if (raws.isEmpty()) return Outcome(null, "Videos ka data nahi mila")

            val channelIds = raws.map { it.channelId }.filter { it.isNotBlank() }.distinct().take(50)
            val subs = HashMap<String, Long?>()
            val chJson = get(
                "https://www.googleapis.com/youtube/v3/channels?part=statistics&id=${channelIds.joinToString(",")}&key=$apiKey"
            )
            val cItems = chJson.optJSONArray("items")
            if (cItems != null) {
                for (i in 0 until cItems.length()) {
                    val it = cItems.optJSONObject(i) ?: continue
                    val st = it.optJSONObject("statistics")
                    subs[it.optString("id")] =
                        if (st == null || st.optBoolean("hiddenSubscriberCount", false)) null
                        else st.optString("subscriberCount").toLongOrNull()
                }
            }

            val videos = raws.map { r ->
                val s = subs[r.channelId]
                ViralVideo(
                    id = r.id, title = r.title, channelId = r.channelId, channelTitle = r.channelTitle,
                    views = r.views, subscribers = s,
                    score = if (s == null) null else r.views.toDouble() / maxOf(s, 100L).toDouble()
                )
            }
            // Real viral signal first (most views per subscriber); videos with hidden subscriber counts after those.
            val sortedVideos = videos.sortedWith(
                compareByDescending<ViralVideo> { it.score != null }.thenByDescending { it.score ?: 0.0 }.thenByDescending { it.views }
            )

            val channels = videos.groupBy { it.channelId }.mapNotNull { (id, vs) ->
                val s = subs[id] ?: return@mapNotNull null
                BigChannel(id = id, title = vs.first().channelTitle, subscribers = s, videosFound = vs.size)
            }.sortedByDescending { it.subscribers }

            Outcome(Result(sortedVideos, channels), null)
        } catch (e: ApiError) {
            Outcome(null, e.message)
        } catch (e: Exception) {
            Outcome(null, "Internet ya YouTube se rabta nahi hua")
        }
    }

    private class ApiError(message: String) : Exception(message)

    private fun searchTopVideos(topic: String, apiKey: String, days: Int, maxVideos: Int): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val after = fmt.format(Date(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000))
        val url = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&type=video&order=viewCount&maxResults=$maxVideos" +
            "&publishedAfter=${URLEncoder.encode(after, "UTF-8")}" +
            "&q=${URLEncoder.encode(topic, "UTF-8")}&key=$apiKey"
        val items = get(url).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
        }
    }

    private fun get(url: String): JSONObject {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = try { JSONObject(body).optJSONObject("error") } catch (e: Exception) { null }
                val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                throw ApiError(
                    when {
                        reason.contains("quota", ignoreCase = true) -> "YouTube API ka aaj ka quota khatam ho gaya — kal dobara try karein"
                        response.code == 400 || response.code == 403 -> "YouTube API key galat ya band hai (Settings check karein)"
                        else -> "YouTube se jawab nahi aaya (${response.code})"
                    }
                )
            }
            return JSONObject(body)
        }
    }
}
