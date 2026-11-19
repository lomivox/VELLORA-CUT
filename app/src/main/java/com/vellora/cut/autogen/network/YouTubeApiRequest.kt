package com.vellora.cut.autogen.network

import com.vellora.cut.BuildConfig
import com.vellora.cut.autogen.data.SecureCredentialStore
import okhttp3.Request

/**
 * One place that builds a public YouTube Data API request. With a typed API key it goes straight to YouTube; in
 * server mode (the key is the stand-in [SecureCredentialStore.WORKER_YT_KEY]) it goes through the Pages backend,
 * which adds the real key itself. [query] is the query string WITHOUT the key.
 */
object YouTubeApiRequest {
    fun build(path: String, query: String, apiKey: String): Request =
        if (apiKey == SecureCredentialStore.WORKER_YT_KEY) {
            Request.Builder()
                .url("${BuildConfig.WORKER_URL.trimEnd('/')}/yt/$path?$query")
                .addHeader("X-App-Key", BuildConfig.WORKER_APP_KEY)
                .build()
        } else {
            Request.Builder().url("https://www.googleapis.com/youtube/v3/$path?$query&key=$apiKey").build()
        }
}
