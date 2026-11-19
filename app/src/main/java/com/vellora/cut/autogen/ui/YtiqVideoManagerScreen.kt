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
                    groups.forEachIndexed { gi, g ->
                        item(key = "header$gi") {
                            Text(
                                text = "Group ${gi + 1} • ${g.videos.size} videos\n" + g.reasons.joinToString(" • "),
                                color = YtText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
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
                    items(filtered, key = { it.id }) { v ->
                        ManagedVideoRow(v, null) { editing = v }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
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
