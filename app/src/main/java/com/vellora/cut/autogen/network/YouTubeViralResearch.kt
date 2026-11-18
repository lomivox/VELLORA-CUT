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
 * "Who is winning on this topic right now?" — real YouTube Data API v3 data:
 *  - search.list   → recent videos for the channel's own search phrases (filtered to the channel's country/language)
 *  - videos.list   → their real view counts
 *  - channels.list → the real subscriber count of each channel
 * From that: a VIRAL SCORE per video (views ÷ subscribers — a small channel whose video got many times its
 * subscriber count is the real signal), and a list of competitor channels that are close to the own channel's size.
 *
 * Results that do not belong to the channel's world are dropped: titles written in foreign scripts (Hindi/Devanagari,
 * Chinese, Thai, Cyrillic ...) and videos whose title shares nothing with the channel's topic phrases.
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
        /** how many of the found videos belong to this channel (0 for channels found by channel search) */
        val videosFound: Int
    )

    data class Result(val videos: List<ViralVideo>, val channels: List<BigChannel>)

    /** Either [result] or a short human-readable [error] (Roman Urdu), never both. */
    class Outcome(val result: Result?, val error: String?)

    /** Where and when to look. [regionCode] = country (PK), [relevanceLanguage] = ISO 639-1 (ur), both optional. */
    data class Options(
        val regionCode: String? = null,
        val relevanceLanguage: String? = null,
        val days: Int = 90,
        val order: String = "relevance"
    )

    /** Old single-topic entry point (used while writing SEO): most-viewed recent videos on the topic. */
    fun research(topic: String, apiKey: String, days: Int = 30, maxVideos: Int = 25): Outcome =
        researchQueries(listOf(topic), apiKey, null, Options(days = days, order = "viewCount"), emptyList(), maxVideos)

    fun researchQueries(
        queries: List<String>,
        apiKey: String,
        ownSubs: Long?,
        options: Options,
        keywords: List<String>,
        maxTotal: Int = 45
    ): Outcome {
        if (apiKey.isBlank()) return Outcome(null, "Settings mein YouTube API Key lagayen")
        val qs = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
        if (qs.isEmpty()) return Outcome(null, "Topic likhein")
        return try {
            val perQuery = maxOf(10, maxTotal / qs.size)
            val ids = LinkedHashSet<String>()
            qs.forEach { q -> ids.addAll(searchVideoIds(q.take(100), apiKey, options, perQuery)) }
            if (ids.isEmpty()) return Outcome(null, "Is topic par ${options.days} din ki koi video nahi mili")

            data class Raw(val id: String, val title: String, val channelId: String, val channelTitle: String, val views: Long)
            val raws = ArrayList<Raw>()
            ids.toList().take(50).chunked(50).forEach { chunk ->
                val videoJson = get("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&id=${chunk.joinToString(",")}&key=$apiKey")
                val vItems = videoJson.optJSONArray("items")
                if (vItems != null) {
                    for (i in 0 until vItems.length()) {
                        val item = vItems.optJSONObject(i) ?: continue
                        val sn = item.optJSONObject("snippet") ?: continue
                        raws.add(
                            Raw(
                                id = item.optString("id"),
                                title = sn.optString("title"),
                                channelId = sn.optString("channelId"),
                                channelTitle = sn.optString("channelTitle"),
                                views = item.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L
                            )
                        )
                    }
                }
            }
            if (raws.isEmpty()) return Outcome(null, "Videos ka data nahi mila")

            // Drop foreign-script titles, then drop videos that share nothing with the channel's own topic words.
            val notForeign = raws.filter { !isForeignScript(it.title) }
            val topicWords = tokens((qs + keywords).joinToString(" "))
            val relevant = notForeign.filter { r -> tokens(r.title).any { it in topicWords } }
            val kept = if (relevant.size >= 5) relevant else notForeign
            if (kept.isEmpty()) return Outcome(null, "Is topic par apne channel jaisi koi video nahi mili")

            val subs = channelSubscribers(kept.map { it.channelId }, apiKey)
            val videos = kept.map { r ->
                val s = subs[r.channelId]
                ViralVideo(
                    id = r.id, title = r.title, channelId = r.channelId, channelTitle = r.channelTitle,
                    views = r.views, subscribers = s,
                    score = if (s == null) null else r.views.toDouble() / maxOf(s, 100L).toDouble()
                )
            }.sortedWith(
                compareByDescending<ViralVideo> { it.score != null }.thenByDescending { it.score ?: 0.0 }.thenByDescending { it.views }
            )

            val channels = videos.groupBy { it.channelId }.mapNotNull { (id, vs) ->
                val s = subs[id] ?: return@mapNotNull null
                BigChannel(id = id, title = vs.first().channelTitle, subscribers = s, videosFound = vs.size)
            }.sortedByDescending { it.subscribers }

            Outcome(Result(videos, channels), null)
        } catch (e: ApiError) {
            Outcome(null, e.message)
        } catch (e: Exception) {
            Outcome(null, "Internet ya YouTube se rabta nahi hua")
        }
    }

    /**
     * Channels that really work on the same topic: a CHANNEL search for the channel's own phrase, kept to the channel's
     * world (no foreign scripts), those close to the own size first (about 0.3x to 30x), then the bigger ones.
     */
    fun similarChannels(query: String, apiKey: String, ownSubs: Long?, options: Options): Outcome {
        if (apiKey.isBlank()) return Outcome(null, "Settings mein YouTube API Key lagayen")
        return try {
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&order=relevance&maxResults=25" +
                "&q=${URLEncoder.encode(query.take(100), "UTF-8")}" + regionLang(options) + "&key=$apiKey"
            val items = get(url).optJSONArray("items")
            val ids = ArrayList<String>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.optJSONObject("id")?.optString("channelId")?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
            }
            if (ids.isEmpty()) return Outcome(null, "Is topic par koi channel nahi mila")
            val chJson = get("https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&id=${ids.distinct().take(50).joinToString(",")}&key=$apiKey")
            val list = ArrayList<BigChannel>()
            val cItems = chJson.optJSONArray("items")
            if (cItems != null) {
                for (i in 0 until cItems.length()) {
                    val c = cItems.optJSONObject(i) ?: continue
                    val st = c.optJSONObject("statistics") ?: continue
                    if (st.optBoolean("hiddenSubscriberCount", false)) continue
                    val subs = st.optString("subscriberCount").toLongOrNull() ?: continue
                    val title = c.optJSONObject("snippet")?.optString("title").orEmpty()
                    if (title.isBlank() || isForeignScript(title) || subs < 100) continue
                    list.add(BigChannel(id = c.optString("id"), title = title, subscribers = subs, videosFound = 0))
                }
            }
            val sorted = if (ownSubs != null && ownSubs > 0) {
                val near = list.filter { it.subscribers >= ownSubs * 0.3 && it.subscribers <= ownSubs * 30 }
                    .sortedBy { Math.abs(Math.log(it.subscribers.toDouble() / ownSubs.toDouble())) }
                val rest = list.filter { it !in near }.sortedBy { it.subscribers }
                near + rest
            } else list.sortedByDescending { it.subscribers }
            Outcome(Result(emptyList(), sorted), null)
        } catch (e: ApiError) {
            Outcome(null, e.message)
        } catch (e: Exception) {
            Outcome(null, "Internet ya YouTube se rabta nahi hua")
        }
    }

    private class ApiError(message: String) : Exception(message)

    private fun regionLang(o: Options): String =
        (if (!o.regionCode.isNullOrBlank()) "&regionCode=${o.regionCode}" else "") +
            (if (!o.relevanceLanguage.isNullOrBlank()) "&relevanceLanguage=${o.relevanceLanguage}" else "")

    private fun searchVideoIds(topic: String, apiKey: String, o: Options, max: Int): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val after = fmt.format(Date(System.currentTimeMillis() - o.days * 24L * 60 * 60 * 1000))
        val url = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&type=video&order=${o.order}&maxResults=$max" +
            "&publishedAfter=${URLEncoder.encode(after, "UTF-8")}" +
            "&q=${URLEncoder.encode(topic, "UTF-8")}" + regionLang(o) + "&key=$apiKey"
        val items = get(url).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
        }
    }

    private fun channelSubscribers(channelIds: List<String>, apiKey: String): Map<String, Long?> {
        val ids = channelIds.filter { it.isNotBlank() }.distinct().take(50)
        val out = HashMap<String, Long?>()
        if (ids.isEmpty()) return out
        val items = get("https://www.googleapis.com/youtube/v3/channels?part=statistics&id=${ids.joinToString(",")}&key=$apiKey").optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val c = items.optJSONObject(i) ?: continue
                val st = c.optJSONObject("statistics")
                out[c.optString("id")] =
                    if (st == null || st.optBoolean("hiddenSubscriberCount", false)) null else st.optString("subscriberCount").toLongOrNull()
            }
        }
        return out
    }

    private fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9\\u0600-\\u06FF]+")).filter { it.length >= 3 }.toSet()

    /** True when a large part of the letters belong to scripts that are not Latin/Arabic (Hindi, Chinese, Thai, Cyrillic ...). */
    private fun isForeignScript(text: String): Boolean {
        var letters = 0
        var foreign = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            val c = ch.code
            if (c in 0x0400..0x04FF || c in 0x0900..0x0DFF || c in 0x0E00..0x0EFF || c in 0x3040..0x30FF ||
                c in 0x4E00..0x9FFF || c in 0xAC00..0xD7AF
            ) foreign++
        }
        return letters > 0 && foreign.toDouble() / letters >= 0.3
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
