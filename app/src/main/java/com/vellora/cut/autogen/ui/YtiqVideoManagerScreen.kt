package com.vellora.cut.autogen.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vellora.cut.autogen.network.ManagedVideo
import com.vellora.cut.autogen.network.YouTubeAuthManager
import com.vellora.cut.autogen.network.YouTubeVideoManager
import com.vellora.cut.autogen.network.YtResult
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun normalize(s: String): String = s.lowercase()

/** True when EVERY word typed matches somewhere in the video's title, description or tags. */
private fun matches(v: ManagedVideo, query: String): Boolean {
    val words = normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val hay = normalize(v.title + "\n" + v.description + "\n" + v.tags.joinToString(" "))
    return words.all { hay.contains(it) }
}

private fun normText(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun fmtDuration(sec: Long): String {
    if (sec <= 0) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

private data class DuplicateGroup(val reasons: List<String>, val videos: List<ManagedVideo>)

/**
 * Finds videos that look like repeats of each other: same title, nearly the same title, the same description,
 * and — when the title also matches — the same length (a strong hint that one video was uploaded twice).
 * YouTube gives no way to compare the pictures/sound of two videos, so the last check is always the person's own:
 * play both and decide.
 */
private fun findDuplicates(videos: List<ManagedVideo>): List<DuplicateGroup> {
    val n = videos.size
    if (n < 2) return emptyList()
    val titles = videos.map { normText(it.title) }
    val descs = videos.map { normText(it.description) }
    val tokenSets = titles.map { t -> t.split(' ').filter { it.isNotBlank() }.toSet() }
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }
    class PairInfo(val i: Int, val reasons: Set<String>)
    val pairs = ArrayList<PairInfo>()
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val sameTitle = titles[i].isNotBlank() && titles[i] == titles[j]
            val sameDesc = descs[i].length >= 40 && descs[i] == descs[j]
            var similar = false
            if (!sameTitle && tokenSets[i].size >= 3 && tokenSets[j].size >= 3) {
                val inter = tokenSets[i].count { it in tokenSets[j] }
                val union = tokenSets[i].size + tokenSets[j].size - inter
                similar = union > 0 && inter.toDouble() / union >= 0.8
            }
            if (!(sameTitle || sameDesc || similar)) continue
            val sameLength = videos[i].durationSec > 0 && Math.abs(videos[i].durationSec - videos[j].durationSec) <= 2 && (sameTitle || similar)
            val reasons = LinkedHashSet<String>()
            if (sameTitle) reasons.add("Title bilkul ek jaisa")
            if (similar) reasons.add("Title milta-julta")
            if (sameDesc) reasons.add("Description ek jaisi")
            if (sameLength) reasons.add("Length bhi ek jaisi (shayad ek hi video dobara upload hui)")
            val ri = find(i)
            val rj = find(j)
            if (ri != rj) parent[ri] = rj
            pairs.add(PairInfo(i, reasons))
        }
    }
    val reasonsByRoot = HashMap<Int, LinkedHashSet<String>>()
    pairs.forEach { reasonsByRoot.getOrPut(find(it.i)) { LinkedHashSet() }.addAll(it.reasons) }
    val members = HashMap<Int, MutableList<ManagedVideo>>()
    for (i in 0 until n) members.getOrPut(find(i)) { ArrayList() }.add(videos[i])
    return members.entries
        .filter { it.value.size >= 2 }
        .map { e -> DuplicateGroup(reasonsByRoot[e.key]?.toList().orEmpty(), e.value.sortedBy { it.publishedAt }) }
        .sortedByDescending { it.videos.size }
}

/**
 * "My Videos": every video of the channel in one list. Search shows all videos that match what was typed; tap a
 * video to change its title / description / tags (saved straight to YouTube) or to delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtiqVideoManagerScreen(
    email: String?,
    uploadsId: String?,
    onPlay: (videoId: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<ManagedVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ManagedVideo?>(null) }
    var showDuplicates by remember { mutableStateOf(false) }
    var confirmFix by remember { mutableStateOf<FixRequest?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var fixOpen by remember { mutableStateOf(false) }
    var fixRunning by remember { mutableStateOf(false) }
    var fixText by remember { mutableStateOf("") }
    var fixCancel by remember { mutableStateOf(false) }
    var backupCount by remember { mutableStateOf(TitleBackup.count(context)) }

    /** A fresh login token for the selected channel, or null (the reason goes into [error]). */
    suspend fun token(): String? {
        if (email == null) {
            error = "Pehle koi channel jodein"
            return null
        }
        return when (val t = YouTubeAuthManager.getFreshAccessToken(context, email)) {
            is YouTubeAuthManager.TokenResult.Success -> t.accessToken
            is YouTubeAuthManager.TokenResult.NeedsUserAction -> {
                error = "Is channel ki permission dobara chahiye — Settings mein Remove kar ke dobara jodein"
                null
            }
            is YouTubeAuthManager.TokenResult.Error -> {
                error = t.message
                null
            }
        }
    }

    fun load() {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            val t = token()
            if (t != null) {
                if (uploadsId == null) {
                    error = "Channel ki videos ki list nahi mili — Home khol kar dobara aayein"
                } else {
                    when (val r = YouTubeVideoManager.listAll(t, uploadsId)) {
                        is YtResult.Ok -> videos = r.value
                        is YtResult.Fail -> error = r.message
                    }
                }
            }
            loading = false
        }
    }

    fun startFix(req: FixRequest) {
        scope.launch {
            fixOpen = true
            fixRunning = true
            fixCancel = false
            fixText = "Shuru ho raha hai…"
            com.vellora.cut.autogen.render.RenderKeepAliveService.start(context, "Titles badal rahi hai…")
            try {
                val t = token()
                if (t == null) {
                    fixText = "⚠ ${error ?: "Login nahi mila — Settings mein channel Remove kar ke dobara jodein"}"
                } else {
                    val report = autoRetitle(
                        context, req.groups, req.keepOldest, videos, t,
                        isCancelled = { fixCancel },
                        onProgress = { fixText = it },
                        onSaved = { updated -> videos = videos.map { if (it.id == updated.id) updated else it } }
                    )
                    fixText = report.summary()
                    backupCount = TitleBackup.count(context)
                }
            } finally {
                com.vellora.cut.autogen.render.RenderKeepAliveService.stop(context)
                fixRunning = false
            }
        }
    }

    fun startRestore() {
        scope.launch {
            fixOpen = true
            fixRunning = true
            fixCancel = false
            fixText = "Purane titles wapas lag rahe hain…"
            com.vellora.cut.autogen.render.RenderKeepAliveService.start(context, "Purane titles wapas lag rahe hain…")
            try {
                val t = token()
                if (t == null) {
                    fixText = "⚠ ${error ?: "Login nahi mila — Settings mein channel Remove kar ke dobara jodein"}"
                } else {
                    val backup = TitleBackup.load(context)
                    var restored = 0
                    var firstErr: String? = null
                    val doneIds = HashSet<String>()
                    for ((i, b) in backup.withIndex()) {
                        if (fixCancel) break
                        val cur = videos.firstOrNull { it.id == b.id } ?: continue
                        fixText = "Wapas lag rahi hain… ${i + 1}/${backup.size}"
                        when (val res = YouTubeVideoManager.update(t, cur, b.title, b.description, cur.tags)) {
                            is YtResult.Ok -> {
                                restored++
                                doneIds.add(b.id)
                                videos = videos.map { if (it.id == res.value.id) res.value else it }
                            }
                            is YtResult.Fail -> {
                                if (firstErr == null) firstErr = res.message
                                val m = res.message.lowercase()
                                if ("quota" in m || "ijazat" in m || "login" in m) break
                            }
                        }
                    }
                    TitleBackup.remove(context, doneIds)
                    backupCount = TitleBackup.count(context)
                    fixText = "↩ $restored videos ke purane title wapas lag gaye." + (firstErr?.let { "\n⚠ $it" } ?: "")
                }
            } finally {
                com.vellora.cut.autogen.render.RenderKeepAliveService.stop(context)
                fixRunning = false
            }
        }
    }

    LaunchedEffect(email, uploadsId) {
        videos = emptyList()
        if (email != null && uploadsId != null) load()
    }

    val filtered = remember(videos, query) { videos.filter { matches(it, query) } }
    val groups = remember(videos, showDuplicates) { if (showDuplicates) findDuplicates(videos) else emptyList() }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { load() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip("Sab videos", !showDuplicates) { showDuplicates = false }
                ModeChip("🔁 Duplicates", showDuplicates) { showDuplicates = true }
            }
            if (!showDuplicates) OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Apni videos mein dhoondein (title, description, tag)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = YtText, unfocusedTextColor = YtText,
                    focusedBorderColor = YtAccent, unfocusedBorderColor = YtCard,
                    focusedContainerColor = YtCard, unfocusedContainerColor = YtCard
                )
            )
            Text(
                text = when {
                    showDuplicates && groups.isEmpty() -> "Koi duplicate nahi mila ✅ (${videos.size} videos check hui)"
                    showDuplicates -> "${groups.size} group mile • ${groups.sumOf { it.videos.size }} videos ek jaisi lag rahi hain. " +
                        "Pehle dono chala kar dekh lein, phir jo faltu ho use delete karein."
                    query.isBlank() -> "${videos.size} videos"
                    else -> "${filtered.size} / ${videos.size} videos mile"
                },
                color = YtMuted, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            error?.let {
                Text(text = "⚠ $it", color = YtText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (videos.isEmpty() && !loading && error == null) {
                Text(text = "Abhi koi video nahi.", color = YtMuted, fontSize = 13.sp)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showDuplicates) {
                    if (groups.isNotEmpty() || backupCount > 0) {
                        item(key = "fixAll") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (groups.isNotEmpty()) {
                                    ActionButton(
                                        "⚡ Ek button: sab duplicates ke naye titles lagayein (${groups.sumOf { it.videos.size - 1 }} videos)",
                                        primary = true
                                    ) { confirmFix = FixRequest(groups.map { it.videos }, true) }
                                }
                                if (backupCount > 0) {
                                    ActionButton("↩ Pichli tabdeeli wapas karein ($backupCount videos)") { confirmRestore = true }
                                }
                            }
                        }
                    }
                    groups.forEachIndexed { gi, g ->
                        item(key = "header$gi") {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Text(
                                    text = "Group ${gi + 1} • ${g.videos.size} videos\n" + g.reasons.joinToString(" • "),
                                    color = YtText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "⚡ Sirf is group ko theek karein",
                                    color = YtAccent, fontSize = 13.sp,
                                    modifier = Modifier.clickable { confirmFix = FixRequest(listOf(g.videos), true) }.padding(vertical = 6.dp)
                                )
                            }
                        }
                        val mostViews = g.videos.maxByOrNull { it.views }
                        val oldest = g.videos.minByOrNull { it.publishedAt }
                        items(g.videos, key = { "g$gi-${it.id}" }) { v ->
                            val badge = listOfNotNull(
                                if (v === mostViews) "⭐ sab se zyada views" else null,
                                if (v === oldest) "🕒 sab se purani" else null
                            ).joinToString(" • ")
                            ManagedVideoRow(v, badge.ifBlank { null }) { editing = v }
                        }
                    }
                } else {
                    if (query.isNotBlank() && filtered.size >= 2) {
                        item(key = "fixSearch") {
                            ActionButton("⚡ In ${filtered.size} videos ke titles naye banayein", primary = true) {
                                confirmFix = FixRequest(listOf(filtered.sortedBy { it.publishedAt }), false)
                            }
                        }
                    }
                    items(filtered, key = { it.id }) { v ->
                        ManagedVideoRow(v, null) { editing = v }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    confirmFix?.let { req ->
        val n = req.groups.sumOf { if (req.keepOldest) maxOf(it.size - 1, 0) else it.size }
        AlertDialog(
            onDismissRequest = { confirmFix = null },
            title = { Text("$n videos ke titles badlein?") },
            text = {
                Text(
                    (if (req.keepOldest) "Har group ki sab se purani video jaisi hai waisi rahegi. " else "") +
                        "Baaqi $n videos ke title aur description AI se naye ban kar seedhe YouTube par save honge. " +
                        "Purane title phone mein save rahenge, 'Wapas' button se laut sakte hain. " +
                        "Isme kuch minute lag sakte hain, app khuli rakhein."
                )
            },
            confirmButton = { TextButton(onClick = { confirmFix = null; startFix(req) }) { Text("Haan, shuru karein") } },
            dismissButton = { TextButton(onClick = { confirmFix = null }) { Text("Nahi") } }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Purane titles wapas lagayein?") },
            text = { Text("Pichli tabdeeli ke $backupCount videos ke purane title aur description wapas YouTube par lag jayenge.") },
            confirmButton = { TextButton(onClick = { confirmRestore = false; startRestore() }) { Text("Haan, wapas karein") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Nahi") } }
        )
    }

    if (fixOpen) {
        Dialog(
            onDismissRequest = { if (!fixRunning) fixOpen = false },
            properties = DialogProperties(dismissOnBackPress = !fixRunning, dismissOnClickOutside = false)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(YtCard).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(text = fixText, color = YtText, fontSize = 14.sp)
                if (fixRunning) {
                    ActionButton("⏹ Rok dein") { fixCancel = true }
                } else {
                    ActionButton("Theek hai", primary = true) { fixOpen = false }
                }
            }
        }
    }

    editing?.let { current ->
        EditVideoDialog(
            video = current,
            tokenProvider = { token() },
            onPlay = onPlay,
            onSaved = { updated ->
                videos = videos.map { if (it.id == updated.id) updated else it }
                editing = null
            },
            onDeleted = { id ->
                videos = videos.filter { it.id != id }
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}


private fun parseBulk(text: String): Map<String, Pair<String, String>> {
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyMap()
    return try {
        val arr = JSONArray(text.substring(start, end + 1))
        val out = HashMap<String, Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) out[id] = o.optString("title").trim().take(100) to o.optString("description").trim()
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }
}

private class FixRequest(val groups: List<List<ManagedVideo>>, val keepOldest: Boolean)

private class FixReport {
    var changed = 0
    var skipped = 0
    var firstError: String? = null
    var cancelled = false
    fun summary(): String = buildString {
        if (cancelled) append("⏹ Roka gaya. ")
        append("✅ $changed videos ke title badal gaye.")
        if (skipped > 0) append("\n⚠ $skipped videos nahi badli (naya title ek jaisa aaya ya AI se nahi mila) — button dobara dabayein, jo bachi hain wo theek ho jayengi.")
        firstError?.let { append("\n⚠ $it") }
        if (changed > 0) append("\n↩ Purane titles phone mein save hain, 'Wapas' button se laut sakte hain.")
    }
}

private class BackupEntry(val id: String, val title: String, val description: String)

/** The old title/description of every video changed by the LAST one-button run, kept on the phone so it can be undone. */
private object TitleBackup {
    private const val PREFS = "ytiq_title_backup"
    private const val KEY = "batch"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readArray(c: Context): JSONArray =
        try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }

    fun clear(c: Context) { prefs(c).edit().remove(KEY).apply() }

    fun add(c: Context, v: ManagedVideo) {
        val arr = readArray(c)
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("id") == v.id) return
        arr.put(JSONObject().put("id", v.id).put("title", v.title).put("description", v.description))
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun load(c: Context): List<BackupEntry> {
        val arr = readArray(c)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BackupEntry(o.optString("id"), o.optString("title"), o.optString("description"))
        }
    }

    fun count(c: Context): Int = readArray(c).length()

    fun remove(c: Context, ids: Set<String>) {
        val arr = readArray(c)
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") !in ids) keep.put(o)
        }
        prefs(c).edit().putString(KEY, keep.toString()).apply()
    }
}

/**
 * The one-button job: for every duplicate group the AI writes a different, related title (and description) for each
 * video, in chunks of 6, and every video is saved to YouTube straight away. A title that would again match another
 * video's title is skipped. The old text of each video is stored first so the whole run can be undone.
 */
private suspend fun autoRetitle(
    context: Context,
    groups: List<List<ManagedVideo>>,
    keepOldest: Boolean,
    allVideos: List<ManagedVideo>,
    token: String,
    isCancelled: () -> Boolean,
    onProgress: (String) -> Unit,
    onSaved: (ManagedVideo) -> Unit
): FixReport {
    val report = FixReport()
    TitleBackup.clear(context)
    val taken = HashSet<String>(allVideos.map { normText(it.title) })
    val total = groups.sumOf { if (keepOldest) maxOf(it.size - 1, 0) else it.size }
    var done = 0
    outer@ for ((gi, group) in groups.withIndex()) {
        val keptTitle = if (keepOldest) group.firstOrNull()?.title else null
        val targets = if (keepOldest) group.drop(1) else group
        val newInGroup = ArrayList<String>()
        for (chunk in targets.chunked(6)) {
            if (isCancelled()) {
                report.cancelled = true
                break@outer
            }
            onProgress("Group ${gi + 1}/${groups.size} • $done/$total videos • AI likh raha hai…")
            val prompt = buildString {
                append("Neeche ek hi video ki kai copies (duplicate videos) hain jin ke title ya description ek jaise hain. ")
                append("Har video ke liye ALAG naya title aur ALAG nayi description likho, taake koi bhi do video ek jaisi na rahein, ")
                append("lekin sab video ke asal topic se related rahein.\n")
                append("Qaide:\n")
                append("- Har title 100 huroof se kam, clickable, usi zaban aur usi script mein jis mein purane titles hain.\n")
                append("- Har video ka title alag pehlu (angle) se ho aur alag lafz se shuru ho; purane title ya ek doosre ki nakal na ho.\n")
                append("- Asal topic mat badlo. Koi nayi kahani, naam, dawa ya wada mat jodo jo purane title/description mein nahi.\n")
                append("- Har description alag ho: 2 chhote paragraphs, kul 500 huroof se kam, aur kisi doosri description ke jumle dohrao mat.\n")
                val avoid = (listOfNotNull(keptTitle) + newInGroup).take(12)
                if (avoid.isNotEmpty()) {
                    append("- Ye titles pehle se maujood hain, naye titles in jaise na hon:\n")
                    avoid.forEach { append("  * ").append(it).append("\n") }
                }
                append("\nVideos jin ke liye likhna hai:\n")
                chunk.forEachIndexed { i, v ->
                    append("${i + 1}) ID: ${v.id} | Title: ${v.title} | Description: ${v.description.take(300).replace('\n', ' ')} | Tags: ${v.tags.take(6).joinToString(", ")}\n")
                }
                append("\nJawab sirf JSON array mein do, koi aur lafz nahi:\n")
                append("[{\"id\":\"<ID>\",\"title\":\"...\",\"description\":\"...\"}]")
            }
            val parsed = when (val ai = YtiqAi.text(context, prompt)) {
                is YtiqAiResult.Ok -> parseBulk(ai.text)
                is YtiqAiResult.Fail -> {
                    report.firstError = ai.message
                    break@outer
                }
            }
            for (v in chunk) {
                if (isCancelled()) {
                    report.cancelled = true
                    break@outer
                }
                val p = parsed[v.id]
                val newTitle = p?.first?.trim().orEmpty()
                val norm = normText(newTitle)
                if (newTitle.isBlank() || YouTubeVideoManager.validate(newTitle, v.description, v.tags) != null || norm in taken) {
                    report.skipped++
                    done++
                    continue
                }
                val newDesc = p?.second?.takeIf { it.isNotBlank() && YouTubeVideoManager.validate(newTitle, it, v.tags) == null } ?: v.description
                TitleBackup.add(context, v)
                onProgress("Group ${gi + 1}/${groups.size} • ${done + 1}/$total videos • YouTube par save ho raha hai…")
                when (val res = YouTubeVideoManager.update(token, v, newTitle, newDesc, v.tags)) {
                    is YtResult.Ok -> {
                        taken.add(norm)
                        newInGroup.add(newTitle)
                        report.changed++
                        onSaved(res.value)
                    }
                    is YtResult.Fail -> {
                        report.skipped++
                        if (report.firstError == null) report.firstError = res.message
                        val m = res.message.lowercase()
                        if ("quota" in m || "ijazat" in m || "login" in m) break@outer
                    }
                }
                done++
            }
        }
    }
    return report
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) YtAccent else YtCard
    ) {
        Text(text = label, color = YtText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun ManagedVideoRow(v: ManagedVideo, badge: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(YtCard)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteImage(
            url = v.thumbUrl,
            modifier = Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(10.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = v.title, color = YtText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val privacy = when (v.privacy) {
                "public" -> "Public"
                "unlisted" -> "Unlisted"
                "private" -> "Private"
                else -> v.privacy
            }
            Text(
                text = "${formatCount(v.views)} views • $privacy • ${v.publishedAt.take(10)} • ${fmtDuration(v.durationSec)}",
                color = YtMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
            Text(text = "ID: ${v.id}", color = YtMuted, fontSize = 11.sp)
            if (badge != null) Text(text = badge, color = YtText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EditVideoDialog(
    video: ManagedVideo,
    tokenProvider: suspend () -> String?,
    onPlay: (String, String) -> Unit,
    onSaved: (ManagedVideo) -> Unit,
    onDeleted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(video.id) { mutableStateOf(video.title) }
    var description by remember(video.id) { mutableStateOf(video.description) }
    var tagsText by remember(video.id) { mutableStateOf(video.tags.joinToString(", ")) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun parsedTags(): List<String> =
        tagsText.split(',', '\u060C', '\n').map { it.trim().trimStart('#').trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    fun save() {
        if (busy) return
        val tags = parsedTags()
        val problem = YouTubeVideoManager.validate(title.trim(), description, tags)
        if (problem != null) {
            message = "⚠ $problem"
            return
        }
        scope.launch {
            busy = true
            message = null
            val t = tokenProvider()
            if (t == null) {
                message = "⚠ Login nahi mila — Settings mein channel Remove kar ke dobara jodein"
            } else {
                when (val r = YouTubeVideoManager.update(t, video, title.trim(), description, tags)) {
                    is YtResult.Ok -> {
                        busy = false
                        onSaved(r.value)
                        return@launch
                    }
                    is YtResult.Fail -> message = "⚠ ${r.message}"
                }
            }
            busy = false
        }
    }

    fun improveWithAi() {
        if (busy) return
        scope.launch {
            busy = true
            message = "AI likh raha hai…"
            val prompt = "Neeche ek YouTube video ka title aur description hai. Inhe behtar SEO ke liye dobara likho: " +
                "title clickable aur 100 huroof se kam, description 2-3 chhote paragraphs mein, 10 se 15 tags. " +
                "Usi zaban aur usi script mein likho jis mein asal title hai, aur video ka asal topic mat badlo, " +
                "koi nayi kahani ya dawa mat jodo.\n\nTitle: ${title.trim()}\n\nDescription:\n${description.take(1500)}\n\n" +
                "Jawab bilkul is format mein do, aur kuch nahi:\nTITLE: <title>\nDESCRIPTION: <description>\nTAGS: <comma se alag tags>"
            when (val ai = YtiqAi.text(context, prompt)) {
                is YtiqAiResult.Fail -> message = "⚠ ${ai.message}"
                is YtiqAiResult.Ok -> {
                    val m = Regex("(?is)TITLE:\\s*(.*?)\\s*DESCRIPTION:\\s*(.*?)\\s*TAGS:\\s*(.*)").find(ai.text)
                    if (m == null) {
                        message = "⚠ AI ka jawab samajh nahi aaya — dobara try karein"
                    } else {
                        title = m.groupValues[1].trim().take(100)
                        description = m.groupValues[2].trim()
                        tagsText = m.groupValues[3].trim()
                        message = "AI ne naya likha — dekh kar Save dabayein"
                    }
                }
            }
            busy = false
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(YtBg)
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Video badlein", color = YtText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕", color = YtText, fontSize = 20.sp,
                    modifier = Modifier.clickable(enabled = !busy) { onDismiss() }.padding(8.dp)
                )
            }
            val clipboard = LocalClipboardManager.current
            Text(
                text = "ID: ${video.id}  •  ${fmtDuration(video.durationSec)}  •  tap = ID copy",
                color = YtMuted, fontSize = 12.sp,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(video.id))
                    message = "ID copy ho gayi: ${video.id}"
                }
            )
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = YtText, unfocusedTextColor = YtText,
                focusedBorderColor = YtAccent, unfocusedBorderColor = YtCard,
                focusedContainerColor = YtCard, unfocusedContainerColor = YtCard,
                focusedLabelColor = YtMuted, unfocusedLabelColor = YtMuted
            )
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title (${title.length}/100)") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, enabled = !busy, colors = fieldColors
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 14, enabled = !busy, colors = fieldColors
            )
            OutlinedTextField(
                value = tagsText, onValueChange = { tagsText = it },
                label = { Text("Tags (comma se alag)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !busy, colors = fieldColors
            )
            message?.let { Text(text = it, color = YtText, fontSize = 13.sp) }

            ActionButton(if (busy) "Ruko…" else "💾 Save (YouTube par)", enabled = !busy, primary = true) { save() }
            ActionButton("🤖 AI se behtar banao", enabled = !busy) { improveWithAi() }
            ActionButton("▶ Video chalayein", enabled = !busy) { onPlay(video.id, video.title) }
            ActionButton("🗑 Video delete karein", enabled = !busy) { confirmDelete = true }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Video delete karein?") },
            text = { Text("\"${video.title}\" YouTube se hamesha ke liye hat jayegi aur wapas nahi aa sakti.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        message = null
                        val t = tokenProvider()
                        if (t == null) {
                            message = "⚠ Login nahi mila — Settings mein channel Remove kar ke dobara jodein"
                        } else {
                            when (val r = YouTubeVideoManager.delete(t, video.id)) {
                                is YtResult.Ok -> {
                                    busy = false
                                    onDeleted(video.id)
                                    return@launch
                                }
                                is YtResult.Fail -> message = "⚠ ${r.message}"
                            }
                        }
                        busy = false
                    }
                }) { Text("Haan, delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Nahi") } }
        )
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = if (primary) YtAccent else YtCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(text = label, color = YtText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
