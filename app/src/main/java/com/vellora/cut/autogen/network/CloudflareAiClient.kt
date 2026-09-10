package com.vellora.cut.autogen.network

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thrown when Cloudflare's API responds with an error for a given prompt. */
class CloudflareApiException(
    message: String,
    /** True for "daily free quota used up" style errors — the caller should
     * try the next pooled account rather than mark the whole prompt failed. */
    val isQuotaExceeded: Boolean = false
) : Exception(message)

class CloudflareAiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Generates one image for [prompt]. Returns raw PNG/JPEG bytes on
     * success. Throws [CloudflareApiException] with a readable message on
     * any failure (bad credentials, rate limit, network error) — the
     * caller is expected to catch this per-prompt and mark it `failed`
     * without aborting the rest of the batch.
     */
    fun generateImage(
        prompt: String,
        accountId: String,
        apiToken: String,
        model: String
    ): ByteArray {
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val body = JSONObject().apply {
            put("prompt", prompt)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiToken")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw CloudflareApiException("Empty response from Cloudflare")

            // Cloudflare returns HTTP 429 (rate limit) or its own error code
            // 4006 ("daily free allocation used up") when Workers AI quota
            // for THIS account is exhausted for the day.
            if (response.code == 429 || responseBody.contains("\"code\":4006")) {
                throw CloudflareApiException(
                    "Is account ka aaj ka quota khatam ho chuka hai",
                    isQuotaExceeded = true
                )
            }

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody)
                        .optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                } catch (e: Exception) { null }
                throw CloudflareApiException(
                    errorMsg ?: "HTTP ${response.code}: ${response.message}"
                )
            }

            val json = JSONObject(responseBody)
            if (!json.optBoolean("success", false)) {
                val errorMsg = json.optJSONArray("errors")
                    ?.optJSONObject(0)?.optString("message")
                throw CloudflareApiException(errorMsg ?: "Cloudflare reported failure")
            }

            val base64Image = json.optJSONObject("result")?.optString("image")
                ?: throw CloudflareApiException("No image in response")

            return Base64.decode(base64Image, Base64.DEFAULT)
        }
    }

    /**
     * Transcribes [audioBytes] via Cloudflare's real Whisper model —
     * returns sentence-level segments with actual spoken timing (seconds,
     * converted to ms here), not a fake/estimated split. Throws
     * [CloudflareApiException] the same way [generateImage] does, so it
     * can reuse the exact same multi-account pooling logic.
     */
    fun transcribeAudio(
        audioBytes: ByteArray,
        accountId: String,
        apiToken: String
    ): List<com.vellora.cut.autogen.data.CaptionSegment> {
        val model = "@cf/openai/whisper-large-v3-turbo"
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("audio", base64Audio)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiToken")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw CloudflareApiException("Empty response from Cloudflare")

            if (response.code == 429 || responseBody.contains("\"code\":4006")) {
                throw CloudflareApiException(
                    "Is account ka aaj ka quota khatam ho chuka hai",
                    isQuotaExceeded = true
                )
            }

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody)
                        .optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                } catch (e: Exception) { null }
                throw CloudflareApiException(errorMsg ?: "HTTP ${response.code}: ${response.message}")
            }

            val json = JSONObject(responseBody)
            if (!json.optBoolean("success", false)) {
                val errorMsg = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                throw CloudflareApiException(errorMsg ?: "Cloudflare reported failure")
            }

            val result = json.optJSONObject("result") ?: throw CloudflareApiException("No result in response")
            val segmentsArray = result.optJSONArray("segments")
                ?: throw CloudflareApiException("Whisper response mein segments nahi mile")

            return (0 until segmentsArray.length()).map { i ->
                val seg = segmentsArray.getJSONObject(i)
                com.vellora.cut.autogen.data.CaptionSegment(
                    text = seg.optString("text").trim(),
                    startMs = (seg.optDouble("start", 0.0) * 1000).toLong(),
                    endMs = (seg.optDouble("end", 0.0) * 1000).toLong()
                )
            }.filter { it.text.isNotBlank() }
        }
    }
}
