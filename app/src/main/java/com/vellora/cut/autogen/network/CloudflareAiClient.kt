package com.vellora.cut.autogen.network

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thrown when Cloudflare's API responds with an error for a given prompt. */
class CloudflareApiException(message: String) : Exception(message)

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
}
