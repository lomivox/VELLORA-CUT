package com.vellora.cut.autogen.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class YtChannel(
    val title: String,
    val avatarUrl: String?,
    val subscribers: Long,
    val views: Long,
    val videoCount: Long,
    val subsHidden: Boolean,
    val uploadsPlaylistId: String?
)

data class YtVideo(
    val id: String,
    val title: String,
    val thumbUrl: String?,
    val publishedAt: String,
    val views: Long,
    val likes: Long,
    val comments: Long
)

sealed class YtResult<out T> {
    data class Ok<T>(val value: T) : YtResult<T>()
    data class Fail(val message: String) : YtResult<Nothing>()
}

/** Reads the signed-in person's own channel numbers and latest videos (YouTube Data API, read-only). */
object YouTubeChannelApi {
    private const val BASE = "https://www.googleapis.com/youtube/v3"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun getJson(url: String, token: String): YtResult<JSONObject> {
        return try {
            val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    YtResult.Ok(JSONObject(body))
                } else {
                    YtResult.Fail(YouTubeErrors.describe(r.code, body))
                }
            }
        } catch (e: IOException) {
            YtResult.Fail("No internet connection")
        } catch (e: Exception) {
            YtResult.Fail(e.message ?: "Could not read the data")
        }
    }

    private fun thumb(snippet: JSONObject?): String? {
        val t = snippet?.optJSONObject("thumbnails") ?: return null
        for (k in listOf("medium", "high", "standard", "default")) {
            val u = t.optJSONObject(k)?.optString("url")
            if (!u.isNullOrBlank()) return u
        }
        return null
    }

    private fun JSONObject.long(key: String): Long = optString(key, "0").toLongOrNull() ?: 0L

    suspend fun channel(token: String): YtResult<YtChannel> = withContext(Dispatchers.IO) {
        when (val r = getJson("$BASE/channels?part=snippet,statistics,contentDetails&mine=true", token)) {
            is YtResult.Fail -> r
            is YtResult.Ok -> {
                val item = r.value.optJSONArray("items")?.optJSONObject(0)
                    ?: return@withContext YtResult.Fail("No YouTube channel found on this account")
                val snippet = item.optJSONObject("snippet")
                val stats = item.optJSONObject("statistics") ?: JSONObject()
                YtResult.Ok(
                    YtChannel(
                        title = snippet?.optString("title").orEmpty(),
                        avatarUrl = thumb(snippet),
                        subscribers = stats.long("subscriberCount"),
                        views = stats.long("viewCount"),
                        videoCount = stats.long("videoCount"),
                        subsHidden = stats.optBoolean("hiddenSubscriberCount", false),
                        uploadsPlaylistId = item.optJSONObject("contentDetails")
                            ?.optJSONObject("relatedPlaylists")?.optString("uploads")?.takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    /** Newest first. */
    suspend fun recentVideos(token: String, uploadsId: String, max: Int = 25): YtResult<List<YtVideo>> =
        withContext(Dispatchers.IO) {
            val playlist = getJson("$BASE/playlistItems?part=contentDetails&maxResults=$max&playlistId=$uploadsId", token)
            val ids = when (playlist) {
                is YtResult.Fail -> return@withContext playlist
                is YtResult.Ok -> {
                    val arr = playlist.value.optJSONArray("items")
                    (0 until (arr?.length() ?: 0)).mapNotNull {
                        arr?.optJSONObject(it)?.optJSONObject("contentDetails")?.optString("videoId")?.takeIf { v -> v.isNotBlank() }
                    }
                }
            }
            if (ids.isEmpty()) return@withContext YtResult.Ok(emptyList<YtVideo>())

            when (val details = getJson("$BASE/videos?part=snippet,statistics&id=${ids.joinToString(",")}", token)) {
                is YtResult.Fail -> details
                is YtResult.Ok -> {
                    val arr = details.value.optJSONArray("items")
                    val byId = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }.associate { item ->
                        val snippet = item.optJSONObject("snippet")
                        val stats = item.optJSONObject("statistics") ?: JSONObject()
                        val id = item.optString("id")
                        id to YtVideo(
                            id = id,
                            title = snippet?.optString("title").orEmpty(),
                            thumbUrl = thumb(snippet),
                            publishedAt = snippet?.optString("publishedAt").orEmpty(),
                            views = stats.long("viewCount"),
                            likes = stats.long("likeCount"),
                            comments = stats.long("commentCount")
                        )
                    }
                    YtResult.Ok(ids.mapNotNull { byId[it] })
                }
            }
        }
}
