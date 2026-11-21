package com.vellora.cut.autogen.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One of the channel's own videos with everything needed to edit it. */
data class ManagedVideo(
    val id: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val categoryId: String,
    val defaultLanguage: String?,
    val thumbUrl: String?,
    val publishedAt: String,
    val views: Long,
    /** public, unlisted or private */
    val privacy: String,
    /** length of the video in seconds (0 if unknown) */
    val durationSec: Long = 0L
)

/**
 * Lists, edits and deletes the signed-in channel's OWN videos through the YouTube Data API (OAuth, not the API key).
 * Editing and deleting need the full "youtube" permission — channels connected before it was added must be removed
 * and connected again once.
 */
object YouTubeVideoManager {
    private const val BASE = "https://www.googleapis.com/youtube/v3"
    private const val MAX_VIDEOS = 500

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private class Reply(val code: Int, val body: String)

    private fun errorMessage(r: Reply): String {
        val err = try { JSONObject(r.body).optJSONObject("error") } catch (e: Exception) { null }
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val msg = err?.optString("message").orEmpty()
        return when {
            reason.contains("quota", ignoreCase = true) -> "YouTube daily quota used up — resets about 12–1 PM Pakistan time"
            r.code == 401 -> "Login expired — reconnect the channel in Settings"
            reason.equals("insufficientPermissions", ignoreCase = true) || msg.contains("insufficient", ignoreCase = true) ->
                "Edit permission missing — reconnect the channel in Settings"
            reason.equals("forbidden", ignoreCase = true) -> "No permission to change this video (YouTube refused)"
            else -> YouTubeErrors.describe(r.code, r.body)
        }
    }

    private fun call(request: Request): Reply =
        http.newCall(request).execute().use { Reply(it.code, it.body?.string().orEmpty()) }

    /** All videos of the channel (newest first, up to 500), including unlisted and private ones. */
    suspend fun listAll(token: String, uploadsPlaylistId: String): YtResult<List<ManagedVideo>> =
        withContext(Dispatchers.IO) {
            try {
                val ids = ArrayList<String>()
                var pageToken: String? = null
                do {
                    val url = "$BASE/playlistItems?part=contentDetails&maxResults=50&playlistId=$uploadsPlaylistId" +
                        (if (pageToken != null) "&pageToken=$pageToken" else "")
                    val r = call(Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build())
                    if (r.code !in 200..299) return@withContext YtResult.Fail(errorMessage(r))
                    val json = JSONObject(r.body)
                    val items = json.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            items.optJSONObject(i)?.optJSONObject("contentDetails")?.optString("videoId")
                                ?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                        }
                    }
                    pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                } while (pageToken != null && ids.size < MAX_VIDEOS)

                val byId = HashMap<String, ManagedVideo>()
                ids.distinct().chunked(50).forEach { chunk ->
                    val url = "$BASE/videos?part=snippet,statistics,status,contentDetails&id=${chunk.joinToString(",")}"
                    val r = call(Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build())
                    if (r.code !in 200..299) return@withContext YtResult.Fail(errorMessage(r))
                    val items = JSONObject(r.body).optJSONArray("items") ?: return@forEach
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val sn = item.optJSONObject("snippet") ?: continue
                        val tags = sn.optJSONArray("tags")
                        val thumbs = sn.optJSONObject("thumbnails")
                        val thumb = (thumbs?.optJSONObject("medium") ?: thumbs?.optJSONObject("default"))?.optString("url")
                        val id = item.optString("id")
                        byId[id] = ManagedVideo(
                            id = id,
                            title = sn.optString("title"),
                            description = sn.optString("description"),
                            tags = if (tags == null) emptyList() else (0 until tags.length()).map { tags.optString(it) },
                            categoryId = sn.optString("categoryId", "22"),
                            defaultLanguage = sn.optString("defaultLanguage").takeIf { it.isNotBlank() },
                            thumbUrl = thumb,
                            publishedAt = sn.optString("publishedAt"),
                            views = item.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L,
                            privacy = item.optJSONObject("status")?.optString("privacyStatus").orEmpty(),
                            durationSec = parseDuration(item.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                        )
                    }
                }
                // keep the playlist order (newest first)
                YtResult.Ok(ids.distinct().mapNotNull { byId[it] })
            } catch (e: java.io.IOException) {
                YtResult.Fail("No internet connection")
            } catch (e: Exception) {
                YtResult.Fail(e.message ?: "Could not load the videos")
            }
        }

    /** ISO 8601 length like PT1H2M3S → seconds. */
    private fun parseDuration(iso: String): Long {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(iso) ?: return 0L
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val sec = m.groupValues[3].toLongOrNull() ?: 0L
        return h * 3600 + min * 60 + sec
    }

    /** Checks YouTube's own limits before anything is sent. Returns an error text or null when everything is fine. */
    fun validate(title: String, description: String, tags: List<String>): String? {
        if (title.isBlank()) return "Title cannot be empty"
        if (title.length > 100) return "Title cannot exceed 100 characters (now ${title.length})"
        if (title.contains('<') || title.contains('>')) return "Title cannot contain < or >"
        if (description.toByteArray(Charsets.UTF_8).size > 5000) return "Description is too long (YouTube's limit is 5000 bytes — about 2500 characters in Urdu)"
        if (description.contains('<') || description.contains('>')) return "Description cannot contain < or >"
        if (tags.joinToString(",").length > 500) return "Tags cannot exceed 500 characters"
        return null
    }

    /** Saves a new title/description/tags. All other parts of the snippet are sent back unchanged (YouTube would clear them otherwise). */
    suspend fun update(
        token: String,
        video: ManagedVideo,
        newTitle: String,
        newDescription: String,
        newTags: List<String>
    ): YtResult<ManagedVideo> = withContext(Dispatchers.IO) {
        try {
            val snippet = JSONObject()
                .put("title", newTitle)
                .put("description", newDescription)
                .put("tags", JSONArray(newTags))
                .put("categoryId", video.categoryId)
            video.defaultLanguage?.let { snippet.put("defaultLanguage", it) }
            val body = JSONObject().put("id", video.id).put("snippet", snippet).toString()
                .toRequestBody("application/json".toMediaType())
            val r = call(
                Request.Builder().url("$BASE/videos?part=snippet")
                    .addHeader("Authorization", "Bearer $token").put(body).build()
            )
            if (r.code !in 200..299) YtResult.Fail(errorMessage(r))
            else YtResult.Ok(video.copy(title = newTitle, description = newDescription, tags = newTags))
        } catch (e: java.io.IOException) {
            YtResult.Fail("No internet connection")
        } catch (e: Exception) {
            YtResult.Fail(e.message ?: "Could not save the video")
        }
    }

    /** Deletes the video from YouTube for good. */
    suspend fun delete(token: String, videoId: String): YtResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val r = call(
                Request.Builder().url("$BASE/videos?id=$videoId")
                    .addHeader("Authorization", "Bearer $token").delete().build()
            )
            if (r.code in 200..299) YtResult.Ok(Unit) else YtResult.Fail(errorMessage(r))
        } catch (e: java.io.IOException) {
            YtResult.Fail("No internet connection")
        } catch (e: Exception) {
            YtResult.Fail(e.message ?: "Could not delete the video")
        }
    }
}
