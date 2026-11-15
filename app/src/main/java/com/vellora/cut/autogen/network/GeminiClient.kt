package com.vellora.cut.autogen.network

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** An error from the Gemini API. [httpCode] is 0 for network problems. */
class GeminiApiException(message: String, val httpCode: Int = 0) : Exception(message) {
    val isBadRequest: Boolean get() = httpCode == 400
}

/**
 * Minimal Gemini (generateContent) client: one text prompt, optionally with the video's audio and a few
 * frames attached, so Gemini can actually listen to / look at the video. Plain REST over OkHttp.
 */
class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns Gemini's text reply, or throws [GeminiApiException]. */
    fun generate(
        apiKey: String,
        prompt: String,
        audioFile: File? = null,
        frames: List<ByteArray> = emptyList()
    ): String {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))
        if (audioFile != null && audioFile.exists()) {
            val b64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "audio/mp4").put("data", b64)
                )
            )
        }
        frames.forEach { jpeg ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.7)
                    .put("maxOutputTokens", 4096)
                    // No hidden "thinking" tokens eating the output budget.
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            )
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = try {
                        JSONObject(text).optJSONObject("error")?.optString("message")
                    } catch (e: Exception) { null }
                    throw GeminiApiException(msg?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}", response.code)
                }
                val json = JSONObject(text)
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?: throw GeminiApiException("Gemini ne jawab nahi diya (blocked ya khaali)")
                val outParts = candidate.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (outParts != null) {
                    for (i in 0 until outParts.length()) sb.append(outParts.optJSONObject(i)?.optString("text").orEmpty())
                }
                return sb.toString().trim().ifBlank { throw GeminiApiException("Gemini ka jawab khaali aaya") }
            }
        } catch (e: GeminiApiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiApiException(e.message ?: "Network error", 0)
        }
    }

    companion object {
        const val MODEL = "gemini-2.5-flash"
    }
}
