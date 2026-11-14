package com.vellora.cut.autogen.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vellora.cut.autogen.network.YouTubeAuthManager
import com.vellora.cut.autogen.network.YouTubeChannelApi
import com.vellora.cut.autogen.network.YtChannel
import com.vellora.cut.autogen.network.YtResult
import com.vellora.cut.autogen.network.YtVideo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** What the YTIQ Home / Videos screens show for the selected channel (last result is also kept on the phone). */
class YtiqDashboard {
    var channel by mutableStateOf<YtChannel?>(null)
    var videos by mutableStateOf<List<YtVideo>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var current: String? = null

    suspend fun load(context: Context, email: String?) {
        current = email
        error = null
        if (email == null) {
            channel = null
            videos = emptyList()
            loading = false
            return
        }
        readCache(context, email)
        loading = true
        try {
            val token = when (val t = YouTubeAuthManager.getFreshAccessToken(context, email)) {
                is YouTubeAuthManager.TokenResult.Success -> t.accessToken
                is YouTubeAuthManager.TokenResult.NeedsUserAction -> {
                    fail(email, "Is account ki YouTube permission dobara chahiye — Settings mein Remove kar ke dobara jodein")
                    return
                }
                is YouTubeAuthManager.TokenResult.Error -> {
                    fail(email, t.message)
                    return
                }
            }
            val ch = when (val r = YouTubeChannelApi.channel(token)) {
                is YtResult.Fail -> { fail(email, r.message); return }
                is YtResult.Ok -> r.value
            }
            if (current != email) return
            channel = ch
            val uploads = ch.uploadsPlaylistId
            if (uploads != null) {
                when (val v = YouTubeChannelApi.recentVideos(token, uploads)) {
                    is YtResult.Fail -> { fail(email, v.message); return }
                    is YtResult.Ok -> if (current == email) videos = v.value
                }
            }
            writeCache(context, email)
        } finally {
            if (current == email) loading = false
        }
    }

    private fun fail(email: String, message: String) {
        if (current == email) error = message
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ytiq_dashboard", Context.MODE_PRIVATE)

    private fun readCache(c: Context, email: String) {
        try {
            val p = prefs(c)
            val ch = p.getString("channel_$email", null)?.let { JSONObject(it) }
            channel = ch?.let {
                YtChannel(
                    title = it.optString("title"),
                    avatarUrl = it.optString("avatar").takeIf { s -> s.isNotBlank() },
                    subscribers = it.optLong("subs"),
                    views = it.optLong("views"),
                    videoCount = it.optLong("count"),
                    subsHidden = it.optBoolean("hidden"),
                    uploadsPlaylistId = it.optString("uploads").takeIf { s -> s.isNotBlank() }
                )
            }
            val arr = p.getString("videos_$email", null)?.let { JSONArray(it) }
            videos = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr?.optJSONObject(i)?.let {
                    YtVideo(
                        id = it.optString("id"),
                        title = it.optString("title"),
                        thumbUrl = it.optString("thumb").takeIf { s -> s.isNotBlank() },
                        publishedAt = it.optString("at"),
                        views = it.optLong("views"),
                        likes = it.optLong("likes"),
                        comments = it.optLong("comments")
                    )
                }
            }
        } catch (e: Exception) {
            channel = null
            videos = emptyList()
        }
    }

    private fun writeCache(c: Context, email: String) {
        try {
            val ch = channel ?: return
            val chJson = JSONObject()
                .put("title", ch.title).put("avatar", ch.avatarUrl.orEmpty()).put("subs", ch.subscribers)
                .put("views", ch.views).put("count", ch.videoCount).put("hidden", ch.subsHidden)
                .put("uploads", ch.uploadsPlaylistId.orEmpty())
            val arr = JSONArray()
            videos.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("title", it.title).put("thumb", it.thumbUrl.orEmpty())
                        .put("at", it.publishedAt).put("views", it.views).put("likes", it.likes).put("comments", it.comments)
                )
            }
            prefs(c).edit().putString("channel_$email", chJson.toString()).putString("videos_$email", arr.toString()).apply()
        } catch (e: Exception) {
            // cache is only a convenience
        }
    }
}

data class YtPerformance(val percent: Int, val rank: Int, val outOf: Int)

/** Latest video's views against the middle (median) of the 10 videos before it; rank among the last 10. */
fun computePerformance(videos: List<YtVideo>): YtPerformance? {
    if (videos.size < 2) return null
    val latest = videos[0]
    val others = videos.drop(1).take(10).map { it.views }.sorted()
    val baseline = if (others.size % 2 == 1) others[others.size / 2].toDouble()
    else (others[others.size / 2 - 1] + others[others.size / 2]) / 2.0
    if (baseline <= 0.0) return null
    val percent = ((latest.views - baseline) * 100.0 / baseline).roundToInt()
    val recent = videos.take(10)
    val rank = 1 + recent.count { it.views > latest.views }
    return YtPerformance(percent, rank, recent.size)
}

fun formatCount(n: Long): String {
    fun trim(d: Double, unit: String): String {
        val s = String.format(Locale.US, "%.1f", d)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + unit
    }
    return when {
        n >= 1_000_000_000L -> trim(n / 1_000_000_000.0, "B")
        n >= 1_000_000L -> trim(n / 1_000_000.0, "M")
        n >= 1_000L -> trim(n / 1_000.0, "K")
        else -> n.toString()
    }
}

private val MILESTONES = listOf(
    100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 25_000L, 50_000L, 100_000L, 250_000L, 500_000L,
    1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L
)

/** (previous goal, next goal) around [value], e.g. 2.7K -> 2.5K .. 5K. */
fun milestoneRange(value: Long): Pair<Long, Long> {
    val next = MILESTONES.firstOrNull { it > value } ?: (value * 2).coerceAtLeast(1L)
    val prev = MILESTONES.lastOrNull { it <= value } ?: 0L
    return prev to next
}

fun timeAgo(iso: String): String {
    return try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val t = f.parse(iso.take(19))?.time ?: return ""
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        when {
            s < 3600 -> "${(s / 60).coerceAtLeast(1)} min pehle"
            s < 86_400 -> "${s / 3600} ghante pehle"
            s < 7 * 86_400 -> "${s / 86_400} din pehle"
            s < 30 * 86_400 -> "${s / (7 * 86_400)} hafte pehle"
            s < 365 * 86_400 -> "${s / (30 * 86_400)} mahine pehle"
            else -> "${s / (365 * 86_400)} saal pehle"
        }
    } catch (e: Exception) {
        ""
    }
}
