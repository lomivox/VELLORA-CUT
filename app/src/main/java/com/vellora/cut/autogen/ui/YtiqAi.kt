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
import com.vellora.cut.autogen.network.YtChannel
import com.vellora.cut.autogen.network.YtVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val accounts = try {
            SecureCredentialStore(context.applicationContext).accounts
        } catch (e: Exception) {
            emptyList()
        }
        if (accounts.isEmpty()) {
            return@withContext YtiqAiResult.Fail("Pehle Settings mein Cloudflare account jodein")
        }
        var last = "AI jawab nahi de saka"
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
            return@withContext Pair(null, "Secure storage nahi khuli")
        }
        val accounts = store.accounts
        if (accounts.isEmpty()) {
            return@withContext Pair(null, "Pehle Settings mein Cloudflare account jodein")
        }
        val model = store.imageModel
        var last = "Image nahi bani"
        for (a in accounts) {
            try {
                val bytes = client.generateImage(prompt, a.accountId, a.apiToken, model)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return@withContext Pair(bmp, null)
                last = "Image decode nahi hui"
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
    if (channel == null) return "Channel ka data abhi available nahi hai."
    val subs = if (channel.subsHidden) "hidden" else formatCount(channel.subscribers)
    val sb = StringBuilder()
    sb.append("Channel: ").append(channel.title).append("\n")
    sb.append("Subscribers: ").append(subs)
    sb.append(", Total views: ").append(formatCount(channel.views))
    sb.append(", Videos: ").append(channel.videoCount).append("\n")
    if (videos.isNotEmpty()) {
        sb.append("Haal ki videos (title - views):\n")
        videos.take(10).forEach { sb.append("- ").append(it.title).append(" - ").append(formatCount(it.views)).append("\n") }
    }
    return sb.toString()
}

fun ytiqToolPrompt(task: String, channel: YtChannel?, videos: List<YtVideo>): String =
    "Tum ek YouTube growth expert ho. Jawab sirf Roman Urdu mein do, amli aur mukhtasar. " +
        "Sirf neeche diye gaye data par mabni salah do.\n\n" +
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
                "Assalam o Alaikum! Main aapka channel coach hun. Title, tags, ideas ya views ke bare mein poochiye."
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
            val prompt = "Tum ek mehrbaan YouTube growth coach ho. Jawab sirf Roman Urdu mein do, seedha aur amli, " +
                "zyada se zyada 8 chhoti lines ya bullets. Sirf neeche diye data par mabni salah do; " +
                "jo data nahi hai uska andaza mat lagao.\n\n" +
                ytiqChannelContext(dash.channel, dash.videos) +
                "\nPichli baat-cheet:\n" + history + "\n\nUser: " + q + "\nCoach:"
            when (val r = YtiqAi.text(context, prompt)) {
                is YtiqAiResult.Ok -> msgs.add(YtiqCoachMsg(false, r.text))
                is YtiqAiResult.Fail -> msgs.add(YtiqCoachMsg(false, "Masla: " + r.message))
            }
        } finally {
            busy = false
        }
    }
}
