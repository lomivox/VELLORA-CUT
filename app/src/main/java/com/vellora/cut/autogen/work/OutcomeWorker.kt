package com.vellora.cut.autogen.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vellora.cut.autogen.memory.ChannelMemory
import com.vellora.cut.autogen.network.YouTubeAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Once a day: looks at the videos uploaded through the app and saves their views at the 3, 7 and 28 day marks,
 * so the AI can learn which titles really worked. Costs 1 YouTube quota unit per 50 videos.
 */
class OutcomeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        ChannelMemory.init(applicationContext)
        for (key in ChannelMemory.channelsWithUploads()) {
            val due = ChannelMemory.dueUploads(key)
            if (due.isEmpty()) continue
            val token = YouTubeAuthManager.getFreshAccessToken(applicationContext, key)
            if (token !is YouTubeAuthManager.TokenResult.Success) continue
            for (chunk in due.chunked(50)) {
                try {
                    val ids = chunk.joinToString(",") { it.first }
                    val request = Request.Builder()
                        .url("https://www.googleapis.com/youtube/v3/videos?part=statistics&id=$ids")
                        .addHeader("Authorization", "Bearer ${token.accessToken}")
                        .build()
                    val body = client.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        r.body?.string()
                    } ?: continue
                    val items = JSONObject(body).optJSONArray("items") ?: continue
                    val views = HashMap<String, Long>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        views[item.optString("id")] = item.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L
                    }
                    for ((videoId, age) in chunk) {
                        val v = views[videoId] ?: continue
                        for (day in listOf(3, 7, 28)) {
                            if (age >= day) ChannelMemory.addSnapshot(key, videoId, day, v)
                        }
                    }
                } catch (e: Exception) {
                    // try again tomorrow
                }
            }
        }
        Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OutcomeWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork("ytiq_outcomes", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
