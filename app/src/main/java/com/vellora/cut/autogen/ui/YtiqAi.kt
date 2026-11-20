package com.vellora.cut.autogen.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.network.CloudflareAiClient
import com.vellora.cut.autogen.network.GeminiClient
import com.vellora.cut.autogen.network.YtChannel
import com.vellora.cut.autogen.network.YtVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

sealed class YtiqAiResult {
    data class Ok(val text: String) : YtiqAiResult()
    data class Fail(val message: String) : YtiqAiResult()
}

/**
 * One place for every AI call in YTIQ (Coach, Daily Ideas, Learn, Generate, Thumbnails).
 * Uses the Cloudflare accounts saved in Settings and tries the next account when one has no quota left —
 * the same behaviour as Shorts Metadata.
 */
object YtiqAi {
    private val client = CloudflareAiClient()

    suspend fun text(context: Context, prompt: String): YtiqAiResult = withContext(Dispatchers.IO) {
        val store = try {
            SecureCredentialStore(context.applicationContext)
        } catch (e: Exception) {
            null
        }
        val accounts = try { store?.accounts.orEmpty() } catch (e: Exception) { emptyList() }
        val geminiKeys = try { store?.geminiKeys.orEmpty() } catch (e: Exception) { emptyList() }
        if (accounts.isEmpty() && geminiKeys.isEmpty()) {
            return@withContext YtiqAiResult.Fail("First add a Gemini key or a Cloudflare account in Settings")
        }
        var last = "The AI could not answer"
        // Gemini first: every key gets 2 real tries before the next key; Cloudflare accounts are the backup.
        if (geminiKeys.isNotEmpty()) {
            val gemini = GeminiClient()
            for (key in geminiKeys) {
                for (attempt in 0..1) {
                    try {
                        return@withContext YtiqAiResult.Ok(gemini.generate(key, prompt).trim())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        last = "Gemini: " + (e.message ?: "error")
                    }
                    if (attempt == 0) delay(1500)
                }
            }
        }
        for (a in accounts) {
            try {
                return@withContext YtiqAiResult.Ok(client.generateText(prompt, a.accountId, a.apiToken).trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e.message ?: last
            }
        }
        YtiqAiResult.Fail(last)
    }

    /** Returns the picture, or null plus a readable reason. */
    suspend fun image(context: Context, prompt: String): Pair<Bitmap?, String?> = withContext(Dispatchers.IO) {
        val store = try {
            SecureCredentialStore(context.applicationContext)
        } catch (e: Exception) {
            return@withContext Pair(null, "Secure storage could not be opened")
        }
        val accounts = store.accounts
        if (accounts.isEmpty()) {
            return@withContext Pair(null, "First add a Cloudflare account in Settings")
        }
        val model = store.imageModel
        var last = "Image was not generated"
        for (a in accounts) {
            try {
                val bytes = client.generateImage(prompt, a.accountId, a.apiToken, model)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return@withContext Pair(bmp, null)
                last = "Image could not be decoded"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e.message ?: last
            }
        }
        Pair(null, last)
    }
}

/** The real channel numbers, written as text, so the AI answers from facts and not guesses. */
fun ytiqChannelContext(channel: YtChannel?, videos: List<YtVideo>): String {
    if (channel == null) return "Channel data is not available yet."
    val subs = if (channel.subsHidden) "hidden" else formatCount(channel.subscribers)
    val sb = StringBuilder()
    sb.append("Channel: ").append(channel.title).append("\n")
    sb.append("Subscribers: ").append(subs)
    sb.append(", Total views: ").append(formatCount(channel.views))
    sb.append(", Videos: ").append(channel.videoCount).append("\n")
    if (videos.isNotEmpty()) {
        sb.append("Recent videos (title - views):\n")
        videos.take(10).forEach { sb.append("- ").append(it.title).append(" - ").append(formatCount(it.views)).append("\n") }
    }
    return sb.toString()
}

fun ytiqToolPrompt(task: String, channel: YtChannel?, videos: List<YtVideo>): String =
    "You are a YouTube growth expert. Answer in English only, practical and brief. " +
        "Give advice based only on the data below.\n\n" +
        ytiqChannelContext(channel, videos) + "\nKaam: " + task

/** Rough title quality guess (length, number, punctuation, shouting). Not a YouTube number. */
fun ytiqTitleScore(title: String): Int {
    val t = title.trim()
    var s = 40
    val n = t.length
    s += when {
        n in 40..70 -> 25
        n in 25..39 || n in 71..90 -> 12
        else -> 0
    }
    if (t.any { it.isDigit() }) s += 10
    if (t.contains('?') || t.contains('!')) s += 5
    val tags = t.count { it == '#' }
    if (tags in 1..3) s += 5
    if (tags > 5) s -= 10
    if (t.any { it.isLetter() } && t == t.uppercase()) s -= 10
    return s.coerceIn(0, 100)
}

class YtiqCoachMsg(val fromUser: Boolean, val text: String)

/** Coach chat, kept alive while the app is open so switching screens does not lose it. */
class YtiqCoachState {
    val msgs = mutableStateListOf<YtiqCoachMsg>()
    var busy by mutableStateOf(false)

    init {
        reset()
    }

    fun reset() {
        msgs.clear()
        msgs.add(
            YtiqCoachMsg(
                false,
                "Hello! I'm your channel coach. Ask me about titles, tags, ideas or views."
            )
        )
    }

    suspend fun send(context: Context, dash: YtiqDashboard, text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        msgs.add(YtiqCoachMsg(true, q))
        busy = true
        try {
            val history = msgs.dropLast(1).takeLast(6)
                .joinToString("\n") { (if (it.fromUser) "User: " else "Coach: ") + it.text.take(500) }
            val prompt = "You are a friendly YouTube growth coach. Answer in English only, direct and practical, " +
                "at most 8 short lines or bullets. Give advice based only on the data below; " +
                "do not guess about data that is not given.\n\n" +
                ytiqChannelContext(dash.channel, dash.videos) +
                com.vellora.cut.autogen.network.NicheInsights.coachBlock(context) +
                "\nPichli baat-cheet:\n" + history + "\n\nUser: " + q + "\nCoach:"
            when (val r = YtiqAi.text(context, prompt)) {
                is YtiqAiResult.Ok -> msgs.add(YtiqCoachMsg(false, r.text))
                is YtiqAiResult.Fail -> msgs.add(YtiqCoachMsg(false, "Problem: " + r.message))
            }
        } finally {
            busy = false
        }
    }
}
