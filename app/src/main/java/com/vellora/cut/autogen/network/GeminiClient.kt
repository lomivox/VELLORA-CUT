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
class GeminiApiException(message: String, val httpCode: Int = 0, val rawMessage: String? = null) : Exception(message) {
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

        var lastError: GeminiApiException? = null
        for (model in MODELS) {
            for (withThinking in listOf(true, false)) {
                try {
                    return call(model, apiKey, parts, withThinking)
                } catch (e: GeminiApiException) {
                    lastError = e
                    // Model not available for this key / shut down: try the next model.
                    if (e.httpCode == 404) break
                    // The thinking setting was rejected: try the same model without it.
                    if (withThinking && e.httpCode == 400 && (e.rawMessage ?: "").contains("think", ignoreCase = true)) continue
                    throw e
                }
            }
        }
        throw lastError ?: GeminiApiException("Gemini failed")
    }

    private fun call(model: String, apiKey: String, parts: JSONArray, withThinking: Boolean): String {
        val config = JSONObject()
            .put("temperature", 0.7)
            .put("maxOutputTokens", 4096)
        if (withThinking) {
            // Keep hidden "thinking" from eating the output budget. Gemini 3 uses levels; 2.5 uses a budget.
            config.put(
                "thinkingConfig",
                if (model.startsWith("gemini-3")) JSONObject().put("thinkingLevel", "minimal")
                else JSONObject().put("thinkingBudget", 0)
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", config)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val raw = try {
                        JSONObject(text).optJSONObject("error")?.optString("message")
                    } catch (e: Exception) { null }
                    throw GeminiApiException(describe(response.code, raw), response.code, raw)
                }
                val json = JSONObject(text)
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?: throw GeminiApiException("Gemini gave no answer (blocked or empty)")
                val outParts = candidate.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (outParts != null) {
                    for (i in 0 until outParts.length()) sb.append(outParts.optJSONObject(i)?.optString("text").orEmpty())
                }
                return sb.toString().trim().ifBlank { throw GeminiApiException("Gemini's answer was empty") }
            }
        } catch (e: GeminiApiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiApiException(e.message ?: "Network error", 0)
        }
    }

    /** One short, exact reason for the screen. */
    private fun describe(code: Int, raw: String?): String {
        val m = raw.orEmpty()
        return when {
            code == 400 && m.contains("API key", ignoreCase = true) -> "Gemini key is invalid"
            code == 403 && m.contains("has not been used", ignoreCase = true) -> "Generative Language API is not enabled for this key's project"
            code == 403 -> "Gemini key has no access (HTTP 403)"
            code == 404 -> "Gemini model not available for this key"
            code == 429 -> "Gemini quota or rate limit reached — try again later"
            code >= 500 -> "Gemini server error (HTTP $code)"
            m.isNotBlank() -> m.take(120)
            else -> "Gemini error (HTTP $code)"
        }
    }

    companion object {
        /** Tried in this order: the newer model first; the older one only if the newer is not available. */
        val MODELS = listOf("gemini-3.5-flash", "gemini-2.5-flash")
    }
}
