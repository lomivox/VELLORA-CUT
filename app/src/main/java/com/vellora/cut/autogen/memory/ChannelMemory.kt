package com.vellora.cut.autogen.memory

import android.content.Context
import com.vellora.cut.autogen.network.YouTubeAccounts
import com.vellora.cut.autogen.network.YtVideo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * What the AI remembers about each connected channel, kept on the phone (one small JSON file per channel):
 *  - searches   : what was researched, and when
 *  - generations: every title/description/tags the AI wrote (language, engine)
 *  - edits      : what the creator changed in the AI's text (the strongest hint of what was wrong)
 *  - uploads    : videos uploaded through the app, with their views after 3 / 7 / 28 days
 *  - videos     : the channel's recent videos with their views (which titles really worked)
 * [playbook] turns all of that into a short note that is given to the AI before it writes.
 */
object ChannelMemory {

    @Volatile private var appContext: Context? = null
    private val lock = Any()

    private const val DAY_MS = 86_400_000L
    private val MARK_DAYS = listOf(3, 7, 28)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun context(): Context? = appContext

    fun currentKey(): String? =
        appContext?.let { YouTubeAccounts.selectedEmail(it) }?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ storage

    private fun dir(): File = File(appContext!!.filesDir, "channel_memory").apply { mkdirs() }

    private fun fileFor(key: String): File =
        File(dir(), key.replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".json")

    private fun load(key: String): JSONObject = try {
        val f = fileFor(key)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(key: String, root: JSONObject) {
        try {
            root.put("key", key)
            fileFor(key).writeText(root.toString())
        } catch (e: Exception) {
            // memory is best-effort; never break the app because of it
        }
    }

    private fun list(root: JSONObject, name: String): JSONArray =
        root.optJSONArray(name) ?: JSONArray().also { root.put(name, it) }

    private fun cap(a: JSONArray, max: Int) {
        while (a.length() > max) a.remove(0)
    }

    private fun mutate(key: String?, block: (JSONObject) -> Unit) {
        if (key == null || appContext == null) return
        synchronized(lock) {
            val root = load(key)
            block(root)
            save(key, root)
        }
    }

    private fun read(key: String?): JSONObject? {
        if (key == null || appContext == null) return null
        return synchronized(lock) { load(key) }
    }

    private fun clean(s: String?, max: Int = 90): String =
        (s ?: "").replace("\"", "'").replace(Regex("\\s+"), " ").trim().take(max)

    // ------------------------------------------------------------------ 1. searches

    fun recordSearch(query: String, kind: String) {
        val q = clean(query, 120)
        if (q.isBlank()) return
        mutate(currentKey()) { root ->
            val a = list(root, "searches")
            a.put(JSONObject().put("q", q).put("kind", kind).put("ts", System.currentTimeMillis()))
            cap(a, 300)
        }
    }

    // ------------------------------------------------------------------ 2. what the AI wrote, and what the creator changed

    fun recordGeneration(entityId: Long, language: String, engine: String, title: String, description: String, tags: String) {
        mutate(currentKey()) { root ->
            val a = list(root, "generations")
            a.put(
                JSONObject().put("id", entityId).put("ts", System.currentTimeMillis()).put("lang", language)
                    .put("engine", engine).put("title", title).put("description", description.take(600)).put("tags", tags.take(300))
            )
            cap(a, 200)
        }
    }

    private fun aiVersion(root: JSONObject, entityId: Long): JSONObject? {
        val a = root.optJSONArray("generations") ?: return null
        for (i in a.length() - 1 downTo 0) {
            val g = a.optJSONObject(i) ?: continue
            if (g.optLong("id") == entityId) return g
        }
        return null
    }

    /** [beforeTitle] etc. are what was on screen before saving; the AI's own version wins when it is on record. */
    fun recordEdit(
        entityId: Long,
        beforeTitle: String?, newTitle: String?,
        beforeDescription: String?, newDescription: String?,
        beforeTags: String?, newTags: String?
    ) {
        mutate(currentKey()) { root ->
            val ai = aiVersion(root, entityId)
            val aiTitle = ai?.optString("title") ?: (beforeTitle ?: "")
            val aiDesc = ai?.optString("description") ?: (beforeDescription ?: "")
            val aiTags = ai?.optString("tags") ?: (beforeTags ?: "")
            val titleChanged = clean(aiTitle, 200) != clean(newTitle, 200)
            val descChanged = clean(aiDesc, 600) != clean(newDescription, 600)
            val tagsChanged = clean(aiTags, 300) != clean(newTags, 300)
            if (!titleChanged && !descChanged && !tagsChanged) return@mutate
            val a = list(root, "edits")
            a.put(
                JSONObject().put("ts", System.currentTimeMillis()).put("aiTitle", clean(aiTitle)).put("title", clean(newTitle))
                    .put("titleChanged", titleChanged).put("descChanged", descChanged).put("tagsChanged", tagsChanged)
            )
            cap(a, 100)
        }
    }

    // ------------------------------------------------------------------ 3. results of uploaded videos

    fun recordUpload(channelKey: String, entityId: Long, videoId: String, title: String) {
        mutate(channelKey) { root ->
            val a = list(root, "uploads")
            for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("videoId") == videoId) return@mutate
            a.put(
                JSONObject().put("entityId", entityId).put("videoId", videoId).put("title", clean(title))
                    .put("ts", System.currentTimeMillis()).put("snaps", JSONArray())
            )
            cap(a, 100)
        }
    }

    fun channelsWithUploads(): List<String> {
        if (appContext == null) return emptyList()
        return (dir().listFiles() ?: emptyArray()).mapNotNull { f ->
            try {
                val j = JSONObject(f.readText())
                if ((j.optJSONArray("uploads")?.length() ?: 0) > 0) j.optString("key").takeIf { it.isNotBlank() } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Videos (id, age in days) that have passed a 3/7/28-day mark without a saved result yet. */
    fun dueUploads(channelKey: String): List<Pair<String, Double>> {
        val root = read(channelKey) ?: return emptyList()
        val a = root.optJSONArray("uploads") ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<Pair<String, Double>>()
        for (i in 0 until a.length()) {
            val u = a.optJSONObject(i) ?: continue
            val age = (now - u.optLong("ts")) / DAY_MS.toDouble()
            if (age > 45) continue
            val done = HashSet<Int>()
            val snaps = u.optJSONArray("snaps")
            if (snaps != null) for (s in 0 until snaps.length()) done.add(snaps.optJSONObject(s)?.optInt("day") ?: -1)
            if (MARK_DAYS.any { age >= it && it !in done }) out.add(u.optString("videoId") to age)
        }
        return out
    }

    fun addSnapshot(channelKey: String, videoId: String, day: Int, views: Long) {
        mutate(channelKey) { root ->
            val a = root.optJSONArray("uploads") ?: return@mutate
            for (i in 0 until a.length()) {
                val u = a.optJSONObject(i) ?: continue
                if (u.optString("videoId") != videoId) continue
                val snaps = u.optJSONArray("snaps") ?: JSONArray().also { u.put("snaps", it) }
                for (s in 0 until snaps.length()) if (snaps.optJSONObject(s)?.optInt("day") == day) return@mutate
                snaps.put(JSONObject().put("day", day).put("views", views))
            }
        }
    }

    // ------------------------------------------------------------------ the channel's own recent videos

    private fun parseIso(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    fun recordChannelVideos(channelKey: String?, videos: List<YtVideo>) {
        if (videos.isEmpty()) return
        val now = System.currentTimeMillis()
        mutate(channelKey) { root ->
            val a = JSONArray()
            videos.take(40).forEach { v ->
                val published = parseIso(v.publishedAt)
                if (published > 0) {
                    a.put(JSONObject().put("t", clean(v.title)).put("v", v.views).put("age", (now - published) / DAY_MS.toDouble()))
                }
            }
            root.put("videos", a)
        }
    }

    // ------------------------------------------------------------------ 4. the playbook

    private data class Vid(val title: String, val perDay: Double, val age: Double)

    fun playbook(): String {
        val root = read(currentKey()) ?: return ""
        val lines = ArrayList<String>()

        // What really worked on this channel (views per day since upload)
        val vids = ArrayList<Vid>()
        root.optJSONArray("videos")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val age = o.optDouble("age", 0.0)
                if (age >= 3) vids.add(Vid(o.optString("t"), o.optLong("v") / maxOf(1.0, age), age))
            }
        }
        if (vids.size >= 6) {
            val sorted = vids.sortedByDescending { it.perDay }
            lines.add("Best-performing titles on this channel (views/day): " +
                sorted.take(5).joinToString(" | ") { "\"${it.title}\" (${it.perDay.toInt()})" })
            val weak = sorted.filter { it.age >= 14 }.takeLast(4).reversed()
            if (weak.isNotEmpty()) {
                lines.add("Weakest titles on this channel (avoid this style or angle): " +
                    weak.joinToString(" | ") { "\"${it.title}\" (${it.perDay.toInt()})" })
            }
        }

        // Results of videos made with this app
        val results = ArrayList<Pair<String, Long>>()
        root.optJSONArray("uploads")?.let { a ->
            for (i in 0 until a.length()) {
                val u = a.optJSONObject(i) ?: continue
                val snaps = u.optJSONArray("snaps") ?: continue
                if (snaps.length() == 0) continue
                val last = snaps.optJSONObject(snaps.length() - 1) ?: continue
                results.add("${u.optString("title")} (${last.optInt("day")}d)" to last.optLong("views"))
            }
        }
        if (results.size >= 2) {
            val sorted = results.sortedByDescending { it.second }
            lines.add("Results of videos made with this tool: best \"${sorted.first().first}\" = ${sorted.first().second} views; " +
                "weakest \"${sorted.last().first}\" = ${sorted.last().second} views.")
        }

        // What the creator corrected
        val examples = ArrayList<String>()
        root.optJSONArray("edits")?.let { a ->
            for (i in a.length() - 1 downTo 0) {
                val e = a.optJSONObject(i) ?: continue
                if (e.optBoolean("titleChanged") && e.optString("title").isNotBlank() && e.optString("aiTitle").isNotBlank()) {
                    examples.add("\"${e.optString("aiTitle")}\" -> \"${e.optString("title")}\"")
                }
                if (examples.size >= 4) break
            }
        }
        if (examples.isNotEmpty()) {
            lines.add("The creator corrects the AI's titles — write in the creator's style, not the AI's. Examples (AI -> creator): " +
                examples.joinToString(" ; "))
        }

        // What the creator researches most (last 60 days)
        val cutoff = System.currentTimeMillis() - 60 * DAY_MS
        val counts = LinkedHashMap<String, Int>()
        root.optJSONArray("searches")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                if (s.optLong("ts") < cutoff) continue
                val q = s.optString("q").lowercase()
                counts[q] = (counts[q] ?: 0) + 1
            }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(6).map { it.key }
        if (top.isNotEmpty()) lines.add("Topics this creator researches most: " + top.joinToString(", "))

        // Language habit
        val langs = HashMap<String, Int>()
        root.optJSONArray("generations")?.let { a ->
            for (i in 0 until a.length()) {
                val l = a.optJSONObject(i)?.optString("lang").orEmpty()
                if (l.isNotBlank()) langs[l] = (langs[l] ?: 0) + 1
            }
        }
        langs.maxByOrNull { it.value }?.let { if (it.value >= 3) lines.add("Most used output language: ${it.key}") }

        return lines.joinToString("\n").take(1800)
    }

    // ------------------------------------------------------------------ for the Memory screen

    data class Summary(
        val searches: Int,
        val generations: Int,
        val edits: Int,
        val uploadsTracked: Int,
        val withResults: Int,
        val recentSearches: List<String>,
        val playbook: String
    )

    fun summary(): Summary {
        val root = read(currentKey()) ?: return Summary(0, 0, 0, 0, 0, emptyList(), "")
        val searches = root.optJSONArray("searches")
        val uploads = root.optJSONArray("uploads")
        var withResults = 0
        if (uploads != null) for (i in 0 until uploads.length()) {
            if ((uploads.optJSONObject(i)?.optJSONArray("snaps")?.length() ?: 0) > 0) withResults++
        }
        val recent = ArrayList<String>()
        if (searches != null) {
            var i = searches.length() - 1
            while (i >= 0 && recent.size < 12) {
                val q = searches.optJSONObject(i)?.optString("q").orEmpty()
                if (q.isNotBlank() && q !in recent) recent.add(q)
                i--
            }
        }
        return Summary(
            searches = searches?.length() ?: 0,
            generations = root.optJSONArray("generations")?.length() ?: 0,
            edits = root.optJSONArray("edits")?.length() ?: 0,
            uploadsTracked = uploads?.length() ?: 0,
            withResults = withResults,
            recentSearches = recent,
            playbook = playbook()
        )
    }

    fun clear() {
        val key = currentKey() ?: return
        synchronized(lock) {
            try { fileFor(key).delete() } catch (e: Exception) { /* nothing to delete */ }
        }
    }
}
