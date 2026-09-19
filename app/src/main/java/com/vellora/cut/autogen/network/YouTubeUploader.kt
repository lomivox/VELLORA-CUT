package com.vellora.cut.autogen.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class YouTubeUploadResult(val videoId: String, val videoUrl: String)

/**
 * Real resumable upload to the YouTube Data API v3 — the same protocol
 * YouTube's own official clients use (init session -> PUT the file bytes
 * -> optional thumbnail set), not a wrapper around some third-party service.
 */
object YouTubeUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES) // large video bodies
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /** privacyStatus: "public", "unlisted", or "private". */
    fun upload(
        accessToken: String,
        videoFile: File,
        title: String,
        description: String,
        tags: List<String>,
        privacyStatus: String = "public",
        thumbnailFile: File? = null
    ): Result<YouTubeUploadResult> {
        return try {
            val uploadUrl = initSession(accessToken, videoFile, title, description, tags, privacyStatus)
                ?: return Result.failure(Exception("Upload session shuru nahi ho saki"))

            val videoId = putVideoBytes(uploadUrl, videoFile)
                ?: return Result.failure(Exception("Video upload fail ho gaya"))

            if (thumbnailFile != null && thumbnailFile.exists()) {
                // Thumbnail failure shouldn't fail the whole upload — the
                // video itself already succeeded and is on YouTube.
                try {
                    setThumbnail(accessToken, videoId, thumbnailFile)
                } catch (e: Exception) { /* non-fatal */ }
            }

            Result.success(YouTubeUploadResult(videoId, "https://youtube.com/watch?v=$videoId"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun initSession(
        accessToken: String,
        videoFile: File,
        title: String,
        description: String,
        tags: List<String>,
        privacyStatus: String
    ): String? {
        val tagsArray = JSONArray()
        tags.forEach { tagsArray.put(it) }

        val metadata = JSONObject().apply {
            put("snippet", JSONObject().apply {
                put("title", title.take(100))
                put("description", description.take(5000))
                put("tags", tagsArray)
                put("categoryId", "22") // People & Blogs — a safe generic default
            })
            put("status", JSONObject().apply {
                put("privacyStatus", privacyStatus)
                put("selfDeclaredMadeForKids", false)
            })
        }

        val body = metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("X-Upload-Content-Type", "video/*")
            .addHeader("X-Upload-Content-Length", videoFile.length().toString())
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.header("Location")
        }
    }

    private fun putVideoBytes(uploadUrl: String, videoFile: File): String? {
        val body: RequestBody = videoFile.asRequestBody("video/*".toMediaType())
        val request = Request.Builder()
            .url(uploadUrl)
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            return JSONObject(responseBody).optString("id").takeIf { it.isNotBlank() }
        }
    }

    private fun setThumbnail(accessToken: String, videoId: String, thumbnailFile: File) {
        val mediaType = if (thumbnailFile.extension.lowercase() == "png") "image/png" else "image/jpeg"
        val body: RequestBody = thumbnailFile.asRequestBody(mediaType.toMediaType())
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=$videoId")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        client.newCall(request).execute().close()
    }
}
