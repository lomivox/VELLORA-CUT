package com.vellora.cut.autogen.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class YouTubeUploadResult(val videoId: String, val videoUrl: String, val thumbnailNote: String? = null)

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

    /** Thrown when the person cancelled (or the work was stopped) in the middle of an upload. */
    class UploadCancelledException : IOException("Upload cancel ho gayi")

    /** Where an interrupted upload can continue from (so a retry / app restart does not start from zero). */
    interface SessionStore {
        fun load(): String?
        fun save(sessionUrl: String)
        fun clear()
    }

    private const val CHUNK_BYTES = 8 * 1024 * 1024 // must be a multiple of 256 KiB
    private const val WRITE_STEP = 64 * 1024
    private const val MAX_FAILURES_IN_A_ROW = 6

    /**
     * Resumable upload with live progress.
     *
     * - The video is sent in 8 MB pieces; after a network drop it asks YouTube how much arrived and carries on
     *   from there, so nothing is re-sent.
     * - [sessions] remembers the upload address, so even after the app/process was killed the same upload can
     *   be continued instead of restarted.
     * - [onProgress] gets (bytes sent so far, total bytes) very often; [isCancelled] is checked every 64 KB.
     * - [tokenProvider] is only needed to start the upload and for the thumbnail at the end.
     */
    suspend fun uploadResumable(
        tokenProvider: suspend () -> String,
        videoFile: File,
        title: String,
        description: String,
        tags: List<String>,
        privacyStatus: String,
        thumbnailFile: File?,
        sessions: SessionStore,
        onProgress: (sent: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): Result<YouTubeUploadResult> {
        return try {
            val total = videoFile.length()
            if (total <= 0L) return Result.failure(Exception("Video file khali hai"))

            var sessionUrl = sessions.load()
            var offset = 0L
            if (sessionUrl != null) {
                val status = queryStatus(sessionUrl, total)
                when {
                    status is Status.Done -> return finish(tokenProvider, status.videoId, thumbnailFile, sessions)
                    status is Status.Partial -> offset = status.next
                    else -> { sessions.clear(); sessionUrl = null } // session expired — start again
                }
            }
            if (sessionUrl == null) {
                val token = tokenProvider()
                sessionUrl = initSession(token, videoFile, title, description, tags, privacyStatus)
                    ?: return Result.failure(Exception("Upload session shuru nahi ho saki (YouTube ne mana kar diya)"))
                sessions.save(sessionUrl)
                offset = 0L
            }

            onProgress(offset, total)
            var failures = 0
            while (true) {
                if (isCancelled()) throw UploadCancelledException()
                val end = minOf(offset + CHUNK_BYTES, total) // exclusive
                val outcome = try {
                    putChunk(sessionUrl, videoFile, offset, end, total, onProgress, isCancelled)
                } catch (e: UploadCancelledException) {
                    throw e
                } catch (e: IOException) {
                    null // network problem — find out below how far YouTube got
                }
                when (outcome) {
                    is Status.Done -> return finish(tokenProvider, outcome.videoId, thumbnailFile, sessions)
                    is Status.Partial -> { offset = outcome.next; failures = 0 }
                    else -> {
                        failures++
                        if (failures > MAX_FAILURES_IN_A_ROW) {
                            return Result.failure(IOException("Internet ka masla — upload ruk gayi (jahan tak hui wahan se dobara chalegi)"))
                        }
                        sleepCancellable(minOf(60_000L, 2_000L shl failures), isCancelled)
                        val status = try { queryStatus(sessionUrl, total) } catch (e: IOException) { null }
                        when (status) {
                            is Status.Done -> return finish(tokenProvider, status.videoId, thumbnailFile, sessions)
                            is Status.Partial -> offset = status.next
                            is Status.Expired -> {
                                sessions.clear()
                                return Result.failure(Exception("Upload ka session khatam ho gaya — dobara koshish karein"))
                            }
                            else -> { /* still offline — loop and try again */ }
                        }
                    }
                }
                onProgress(offset, total)
            }
            Result.failure(Exception("unreachable"))
        } catch (e: UploadCancelledException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private sealed class Status {
        data class Done(val videoId: String) : Status()
        data class Partial(val next: Long) : Status()
        object Expired : Status()
        object Retry : Status()
    }

    private suspend fun finish(
        tokenProvider: suspend () -> String,
        videoId: String,
        thumbnailFile: File?,
        sessions: SessionStore
    ): Result<YouTubeUploadResult> {
        sessions.clear()
        var note: String? = null
        if (thumbnailFile != null && thumbnailFile.exists()) {
            note = try {
                setThumbnailChecked(tokenProvider(), videoId, thumbnailFile)
            } catch (e: Exception) {
                "Thumbnail nahi lag saka: ${e.message}"
            }
        }
        return Result.success(YouTubeUploadResult(videoId, "https://youtube.com/watch?v=$videoId", note))
    }

    private fun sleepCancellable(ms: Long, isCancelled: () -> Boolean) {
        var left = ms
        while (left > 0) {
            if (isCancelled()) throw UploadCancelledException()
            val step = minOf(500L, left)
            Thread.sleep(step)
            left -= step
        }
    }

    /** Asks YouTube how many bytes of this upload it already has. */
    private fun queryStatus(sessionUrl: String, total: Long): Status {
        val request = Request.Builder()
            .url(sessionUrl)
            .addHeader("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { r -> return readStatus(r) }
    }

    private fun readStatus(r: okhttp3.Response): Status {
        val code = r.code
        if (code == 200 || code == 201) {
            val id = try { JSONObject(r.body?.string().orEmpty()).optString("id") } catch (e: Exception) { "" }
            return if (id.isNotBlank()) Status.Done(id) else Status.Retry
        }
        if (code == 308) {
            // "Range: bytes=0-12345" means 12346 bytes are safe; no header means nothing arrived yet.
            val last = r.header("Range")?.substringAfter("-", "")?.toLongOrNull()
            return Status.Partial(if (last == null) 0L else last + 1)
        }
        if (code == 404 || code == 410) return Status.Expired
        return Status.Retry
    }

    private fun putChunk(
        sessionUrl: String,
        file: File,
        start: Long,
        end: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): Status {
        val length = end - start
        val body = object : RequestBody() {
            override fun contentType() = "video/*".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(WRITE_STEP)
                    var sent = 0L
                    while (sent < length) {
                        if (isCancelled()) throw UploadCancelledException()
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), length - sent).toInt())
                        if (n <= 0) throw IOException("Video file parhte waqt khatam ho gayi")
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(start + sent, total)
                    }
                }
            }
        }
        val request = Request.Builder()
            .url(sessionUrl)
            .addHeader("Content-Range", "bytes $start-${end - 1}/$total")
            .put(body)
            .build()
        client.newCall(request).execute().use { r -> return readStatus(r) }
    }

    /** Sets the thumbnail; returns null when it worked, otherwise a readable reason (the video itself is already up). */
    private fun setThumbnailChecked(accessToken: String, videoId: String, thumbnailFile: File): String? {
        val mediaType = if (thumbnailFile.extension.lowercase() == "png") "image/png" else "image/jpeg"
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=$videoId")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(thumbnailFile.asRequestBody(mediaType.toMediaType()))
            .build()
        client.newCall(request).execute().use { r ->
            if (r.isSuccessful) return null
            val msg = try {
                JSONObject(r.body?.string().orEmpty()).optJSONObject("error")?.optString("message")
            } catch (e: Exception) { null }
            return when {
                r.code == 403 -> "Thumbnail ke liye YouTube channel ka phone verification chahiye (youtube.com/verify)"
                !msg.isNullOrBlank() -> "Thumbnail nahi lag saka: $msg"
                else -> "Thumbnail nahi lag saka (HTTP ${r.code})"
            }
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
