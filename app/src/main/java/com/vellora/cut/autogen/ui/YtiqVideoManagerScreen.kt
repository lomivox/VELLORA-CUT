package com.vellora.cut.autogen.ui

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
            if (sameLength) reasons.add("Length is also the same (maybe the same video was uploaded again)")
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
    var bulkGroups by remember { mutableStateOf<List<List<ManagedVideo>>?>(null) }

    /** A fresh login token for the selected channel, or null (the reason goes into [error]). */
    suspend fun token(): String? {
        if (email == null) {
            error = "Connect a channel first"
            return null
        }
        return when (val t = YouTubeAuthManager.getFreshAccessToken(context, email)) {
            is YouTubeAuthManager.TokenResult.Success -> t.accessToken
            is YouTubeAuthManager.TokenResult.NeedsUserAction -> {
                error = "This channel's permission is needed again — remove it in Settings and connect it again"
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
                    error = "The channel's video list was not found — open Home and come back"
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
                ModeChip("All videos", !showDuplicates) { showDuplicates = false }
                ModeChip("🔁 Duplicates", showDuplicates) { showDuplicates = true }
            }
            if (!showDuplicates) OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search your videos (title, description, tag)") },
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
                    showDuplicates && groups.isEmpty() -> "No duplicates found ✅ (${videos.size} videos checked)"
                    showDuplicates -> "${groups.size} groups found • ${groups.sumOf { it.videos.size }} videos look the same. " +
                        "Review both first, then delete the unneeded one."
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
                Text(text = "No videos yet.", color = YtMuted, fontSize = 13.sp)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showDuplicates) {
                    if (groups.isNotEmpty()) {
                        item(key = "bulkAll") {
                            ActionButton("🤖 Generate new titles for all groups (${groups.sumOf { it.videos.size }} videos)") {
                                bulkGroups = groups.map { it.videos }
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
                                    text = "🤖 Generate new titles for this group only",
                                    color = YtAccent, fontSize = 13.sp,
                                    modifier = Modifier.clickable { bulkGroups = listOf(g.videos) }.padding(vertical = 6.dp)
                                )
                            }
                        }
                        val mostViews = g.videos.maxByOrNull { it.views }
                        val oldest = g.videos.minByOrNull { it.publishedAt }
                        items(g.videos, key = { "g$gi-${it.id}" }) { v ->
                            val badge = listOfNotNull(
                                if (v === mostViews) "⭐ most views" else null,
                                if (v === oldest) "🕒 oldest" else null
                            ).joinToString(" • ")
                            ManagedVideoRow(v, badge.ifBlank { null }) { editing = v }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { v ->
                        ManagedVideoRow(v, null) { editing = v }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    bulkGroups?.let { gs ->
        BulkRetitleDialog(
            groups = gs,
            allVideos = videos,
            tokenProvider = { token() },
            onUpdated = { updated ->
                videos = videos.map { v -> updated.firstOrNull { it.id == v.id } ?: v }
            },
            onDismiss = { bulkGroups = null }
        )
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


private class BulkRow(
    val video: ManagedVideo,
    val kept: Boolean,
    newTitle: String,
    val newDesc: String,
    apply: Boolean
) {
    var newTitle by mutableStateOf(newTitle)
    var apply by mutableStateOf(apply)
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

/**
 * Gives every duplicate its OWN new title (and description): the AI looks at each video's present title, description
 * and tags and writes different, related ones, so no two videos stay the same. Nothing is saved until the person
 * reviews (and can edit) the proposals and presses apply; the videos are then updated one after another.
 */
@Composable
private fun BulkRetitleDialog(
    groups: List<List<ManagedVideo>>,
    allVideos: List<ManagedVideo>,
    tokenProvider: suspend () -> String?,
    onUpdated: (List<ManagedVideo>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<BulkRow>() }
    var keepOldest by remember { mutableStateOf(true) }
    var changeDesc by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }

    fun generate() {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            rows.clear()
            var failed = 0
            groups.forEachIndexed { gi, group ->
                // group is sorted oldest first
                val targets = if (keepOldest) group.drop(1) else group
                if (group.isNotEmpty() && keepOldest) {
                    rows.add(BulkRow(group.first(), true, group.first().title, group.first().description, false))
                }
                if (targets.isEmpty()) return@forEachIndexed
                message = "AI is writing… group ${gi + 1}/${groups.size}"
                val prompt = buildString {
                    append("Below are several copies (duplicate videos) of the same video whose titles or descriptions are identical. ")
                    append("Write a DIFFERENT new title and a DIFFERENT new description for each video so no two videos look alike, ")
                    append("but all stay related to the video's real topic.\n")
                    append("Qaide:\n")
                    append("- Each title under 100 characters, clickable, in the same language and script as the old titles.\n")
                    append("- Each video's title should take a different angle and start with a different word; do not copy the old title or each other.\n")
                    append("- Do not change the real topic. Do not add any new story, name, claim or promise that is not in the old title/description.\n")
                    append("- Each description must be different (2 to 3 short paragraphs); do not repeat sentences from any other description.\n")
                    if (keepOldest) {
                        append("- This video stays as it is; the new titles must differ from it too: \"${group.first().title}\"\n")
                    }
                    append("\nVideos to write for:\n")
                    targets.forEachIndexed { i, v ->
                        append("${i + 1}) ID: ${v.id} | Title: ${v.title} | Description: ${v.description.take(400).replace('\n', ' ')} | Tags: ${v.tags.take(8).joinToString(", ")}\n")
                    }
                    append("\nAnswer as a JSON array only, no other words:\n")
                    append("[{\"id\":\"<ID>\",\"title\":\"...\",\"description\":\"...\"}]")
                }
                val parsed = when (val ai = YtiqAi.text(context, prompt)) {
                    is YtiqAiResult.Ok -> parseBulk(ai.text)
                    is YtiqAiResult.Fail -> {
                        message = "⚠ ${ai.message}"
                        emptyMap()
                    }
                }
                targets.forEach { v ->
                    val p = parsed[v.id]
                    if (p != null && p.first.isNotBlank()) {
                        rows.add(BulkRow(v, false, p.first, p.second, true))
                    } else {
                        failed++
                        rows.add(BulkRow(v, false, v.title, v.description, false))
                    }
                }
            }
            message = if (failed > 0) "⚠ AI did not return a new title for $failed videos — they were skipped; generate again or write them yourself"
            else "Review them, edit what you want to change, then tap Apply"
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            generate()
        }
    }

    fun applyAll() {
        if (busy) return
        val chosen = rows.filter { it.apply && !it.kept }
        if (chosen.isEmpty()) {
            message = "⚠ No video selected"
            return
        }
        // Every title must be valid and different from every other video's title.
        val others = allVideos.filter { v -> chosen.none { it.video.id == v.id } }.map { normText(it.title) }.toSet()
        val seen = HashSet<String>()
        for (r in chosen) {
            val t = r.newTitle.trim()
            val problem = YouTubeVideoManager.validate(t, r.video.description, r.video.tags)
            if (problem != null) {
                message = "⚠ \"${r.video.title.take(30)}\": $problem"
                return
            }
            val n = normText(t)
            if (n == normText(r.video.title) || n in others || !seen.add(n)) {
                message = "⚠ The title is the same: \"${t.take(40)}\" — every video needs a different title"
                return
            }
        }
        scope.launch {
            busy = true
            val token = tokenProvider()
            if (token == null) {
                message = "⚠ Login not found — remove the channel in Settings and connect it again"
                busy = false
                return@launch
            }
            val updated = ArrayList<ManagedVideo>()
            var firstError: String? = null
            chosen.forEachIndexed { i, r ->
                message = "Saving… ${i + 1}/${chosen.size}"
                val desc = if (changeDesc && r.newDesc.isNotBlank()) r.newDesc else r.video.description
                when (val res = YouTubeVideoManager.update(token, r.video, r.newTitle.trim(), desc, r.video.tags)) {
                    is YtResult.Ok -> updated.add(res.value)
                    is YtResult.Fail -> if (firstError == null) firstError = res.message
                }
            }
            busy = false
            // What was saved is shown in the list straight away; rows that worked are not applied twice.
            onUpdated(updated)
            val savedIds = updated.map { it.id }.toSet()
            rows.forEach { if (it.video.id in savedIds) it.apply = false }
            if (firstError == null) {
                onDismiss()
            } else {
                message = "⚠ ${updated.size} videos saved, the rest were not: $firstError"
            }
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
                    text = "New titles (for duplicates)", color = YtText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕", color = YtText, fontSize = 20.sp,
                    modifier = Modifier.clickable(enabled = !busy) { onDismiss() }.padding(8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(if (keepOldest) "✓ Do not change the oldest" else "Do not change the oldest", keepOldest) { if (!busy) keepOldest = !keepOldest }
                ModeChip(if (changeDesc) "✓ Description too" else "Description too", changeDesc) { if (!busy) changeDesc = !changeDesc }
            }
            message?.let { Text(text = it, color = YtText, fontSize = 13.sp) }
            ActionButton(if (busy) "Ruko…" else "🔄 Generate new titles", enabled = !busy) { generate() }
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = YtText, unfocusedTextColor = YtText,
                focusedBorderColor = YtAccent, unfocusedBorderColor = YtCard,
                focusedContainerColor = YtCard, unfocusedContainerColor = YtCard,
                focusedLabelColor = YtMuted, unfocusedLabelColor = YtMuted
            )
            rows.forEach { r ->
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(YtCard).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteImage(url = r.video.thumbUrl, modifier = Modifier.width(80.dp).height(45.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = (if (r.kept) "Stays as it is: " else "Old: ") + r.video.title, color = YtMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(text = "ID: ${r.video.id}", color = YtMuted, fontSize = 11.sp)
                        }
                    }
                    if (!r.kept) {
                        OutlinedTextField(
                            value = r.newTitle, onValueChange = { r.newTitle = it },
                            label = { Text("New title (${r.newTitle.length}/100)") },
                            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, enabled = !busy, colors = fieldColors
                        )
                        if (changeDesc && r.newDesc.isNotBlank()) {
                            Text(text = "New description: " + r.newDesc.replace('\n', ' ').take(140) + "…", color = YtMuted, fontSize = 11.sp)
                        }
                        Text(
                            text = if (r.apply) "☑ Change this" else "☐ Skip this",
                            color = YtText, fontSize = 13.sp,
                            modifier = Modifier.clickable(enabled = !busy) { r.apply = !r.apply }.padding(vertical = 4.dp)
                        )
                    }
                }
            }
            if (rows.any { !it.kept }) {
                ActionButton(
                    if (busy) "Ruko…" else "💾 Apply (${rows.count { it.apply && !it.kept }} videos on YouTube)",
                    enabled = !busy, primary = true
                ) { applyAll() }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
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
                message = "⚠ Login not found — remove the channel in Settings and connect it again"
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
            message = "AI is writing…"
            val prompt = "Below are a YouTube video's title and description. Rewrite them for better SEO: " +
                "the title clickable and under 100 characters, the description in 2-3 short paragraphs, 10 to 15 tags. " +
                "Write in the same language and script as the original title, and do not change the video's real topic, " +
                "do not add any new story or claim.\n\nTitle: ${title.trim()}\n\nDescription:\n${description.take(1500)}\n\n" +
                "Answer in exactly this format and nothing else:\nTITLE: <title>\nDESCRIPTION: <description>\nTAGS: <comma-separated tags>"
            when (val ai = YtiqAi.text(context, prompt)) {
                is YtiqAiResult.Fail -> message = "⚠ ${ai.message}"
                is YtiqAiResult.Ok -> {
                    val m = Regex("(?is)TITLE:\\s*(.*?)\\s*DESCRIPTION:\\s*(.*?)\\s*TAGS:\\s*(.*)").find(ai.text)
                    if (m == null) {
                        message = "⚠ Could not understand the AI's answer — try again"
                    } else {
                        title = m.groupValues[1].trim().take(100)
                        description = m.groupValues[2].trim()
                        tagsText = m.groupValues[3].trim()
                        message = "AI wrote a new version — review it and tap Save"
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
                    text = "Edit video", color = YtText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
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
                    message = "ID copied: ${video.id}"
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
                label = { Text("Tags (comma-separated)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !busy, colors = fieldColors
            )
            message?.let { Text(text = it, color = YtText, fontSize = 13.sp) }

            ActionButton(if (busy) "Ruko…" else "💾 Save (to YouTube)", enabled = !busy, primary = true) { save() }
            ActionButton("🤖 Improve with AI", enabled = !busy) { improveWithAi() }
            ActionButton("▶ Play video", enabled = !busy) { onPlay(video.id, video.title) }
            ActionButton("🗑 Delete video", enabled = !busy) { confirmDelete = true }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete video?") },
            text = { Text("\"${video.title}\" will be removed from YouTube permanently and cannot be recovered.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        message = null
                        val t = tokenProvider()
                        if (t == null) {
                            message = "⚠ Login not found — remove the channel in Settings and connect it again"
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
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("No") } }
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
