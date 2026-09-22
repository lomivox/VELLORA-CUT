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
     * Turns one spoken narration segment (a chunk of the voice-over's own
     * transcript) into a descriptive, professional image-generation prompt
     * — the core of the "audio se khudkar prompts" pipeline (see
     * AutoPromptGenerator). Real Cloudflare text-generation call
     * (llama-3.1-8b), same pooled-account/retry story as
     * [classifyImageEnergy].
     *
     * Throws [CloudflareApiException] on failure — caller falls back to
     * using [narrationText] itself as the prompt rather than blocking the
     * whole pipeline on one bad AI call (never returns null/empty silently).
     */
    fun generateImagePromptFromNarration(
        narrationText: String,
        accountId: String,
        apiToken: String
    ): String {
        val model = "@cf/meta/llama-3.1-8b-instruct"
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val systemInstruction =
            "You convert one segment of a spoken video narration into a single " +
                "professional AI image-generation prompt in English. The image will " +
                "be shown on screen while this line is spoken. Describe a concrete, " +
                "cinematic visual scene that matches the meaning and mood of the " +
                "narration — do not describe text, subtitles, or a person talking " +
                "into a microphone. Always include a visual style phrase (e.g. " +
                "'cinematic lighting, photorealistic, 4k'). Reply with ONLY the " +
                "prompt itself, one paragraph, no quotes, no preamble, no labels."

        val body = JSONObject().apply {
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemInstruction) })
                put(JSONObject().apply { put("role", "user"); put("content", narrationText) })
            })
            put("max_tokens", 200)
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

            val prompt = json.optJSONObject("result")?.optString("response")?.trim()
            if (prompt.isNullOrBlank()) throw CloudflareApiException("Empty prompt in response")
            return prompt
        }
    }

    /**
     * Classifies a single image's mood from its own generation [prompt] —
     * returns one of [com.vellora.cut.autogen.data.EnergyLevel]'s three
     * values, via a real Cloudflare text-generation call (llama-3.1-8b),
     * asked to answer with exactly one word. Called ONCE per image, right
     * after that image is generated (see GenerateImagesWorker) — the
     * result is cached on the PromptEntity, never re-requested for the
     * same image once it succeeds.
     *
     * Throws [CloudflareApiException] on any failure (bad credentials,
     * rate limit, network error, or an unparseable reply) — same as
     * [generateImage]. The caller marks that image's classification
     * `failed` and moves on; it is retried automatically on the next
     * worker run rather than blocking generation or render.
     */
    fun classifyImageEnergy(
        prompt: String,
        accountId: String,
        apiToken: String
    ): String {
        val model = "@cf/meta/llama-3.1-8b-instruct"
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val systemInstruction =
            "You classify the MOOD of a single video slide from its image description. " +
                "Reply with EXACTLY ONE WORD, nothing else: calm, neutral, or energetic. " +
                "calm = slow, emotional, peaceful, reflective, sad, quiet. " +
                "energetic = fast-paced, action, excitement, triumph, urgency, motivation-peak. " +
                "neutral = anything that is neither clearly calm nor clearly energetic."

        val body = JSONObject().apply {
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemInstruction) })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("max_tokens", 5)
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

            val raw = json.optJSONObject("result")?.optString("response")
                ?: throw CloudflareApiException("No classification in response")

            val cleaned = raw.trim().lowercase().filter { it.isLetter() }
            return when {
                cleaned.contains("calm") -> com.vellora.cut.autogen.data.EnergyLevel.CALM
                cleaned.contains("energetic") -> com.vellora.cut.autogen.data.EnergyLevel.ENERGETIC
                cleaned.contains("neutral") -> com.vellora.cut.autogen.data.EnergyLevel.NEUTRAL
                // Model replied with something unparseable — treat as a
                // failure (not a silent neutral) so the caller retries this
                // exact image later instead of permanently locking in a
                // guess that was never really "neutral".
                else -> throw CloudflareApiException("Unparseable classification: '$raw'")
            }
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
        apiToken: String,
        language: String? = null
    ): List<com.vellora.cut.autogen.data.CaptionSegment> {
        val model = "@cf/openai/whisper-large-v3-turbo"
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("audio", base64Audio)
            // Whisper auto-detects language when this is omitted, but for
            // Urdu specifically it often defaults to Hindi/Devanagari script
            // instead (the two languages are spoken almost identically) —
            // passing the code explicitly is what actually gets Urdu
            // (Nastaliq/Arabic script) output instead of Devanagari.
            if (language != null) put("language", language)
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

    /**
     * Runs a text prompt through Cloudflare's Llama instruct model and
     * returns the raw text reply — used by ShortsMetadataGenerator to turn
     * (transcript + real YouTube keywords) into a Title/Description/Tags/
     * Hashtags block. Same pooled-account error handling as the other two.
     */
    fun generateText(
        prompt: String,
        accountId: String,
        apiToken: String
    ): String {
        val model = "@cf/qwen/qwen3.8-27b"
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val messages = org.json.JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }
        )
        val body = JSONObject().apply {
            put("messages", messages)
            // Without this, Cloudflare defaults to just 256 tokens (confirmed
            // in their own April 2025 changelog) — nowhere near enough for a
            // full Title+Description+Tags+Hashtags reply, so the response was
            // silently cut off after the title (or partway through the
            // description) with Tags/Hashtags missing entirely.
            put("max_tokens", 2600)
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
            return result.optString("response").ifBlank {
                throw CloudflareApiException("Khaali response mila")
            }
        }
    }
}
