package com.vellora.cut.autogen.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.network.YouTubeCompetitorResearch
import com.vellora.cut.autogen.network.NicheInsights
import com.vellora.cut.autogen.network.YouTubeViralResearch
import com.vellora.cut.autogen.network.YouTubeSuggest
import com.vellora.cut.autogen.network.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val YqGreen = Color(0xFF3DDC84)
private val YqAmber = Color(0xFFF5B800)
private val YqRed = Color(0xFFFF6B6B)

// ---------------------------------------------------------------- small shared pieces

@Composable
private fun YqCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = YtCard, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun YqButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) YtAccent else YtTrack)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun YqChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(YtTrack)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(text = label, color = YtText, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun YqInput(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSend: () -> Unit = {}
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = YtText, fontSize = 15.sp),
        cursorBrush = SolidColor(YtAccent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(YtCard)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                if (value.isEmpty()) Text(text = hint, color = YtMuted, fontSize = 15.sp)
                inner()
            }
        }
    )
}

@Composable
private fun YqTitle(text: String) {
    Text(text = text, color = YtText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun YqNote(text: String) {
    Text(text = text, color = YtMuted, fontSize = 14.sp)
}

@Composable
private fun YqBusy(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = YtAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text = label, color = YtMuted, fontSize = 14.sp)
    }
}

private fun yqScoreColor(n: Int): Color = when {
    n >= 80 -> YqGreen
    n >= 55 -> YqAmber
    else -> YqRed
}

// ---------------------------------------------------------------- AI Coach

@Composable
fun YtiqCoachScreen(coach: YtiqCoachState, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(coach.msgs.size, coach.busy) {
        listState.animateScrollToItem(coach.msgs.size)
    }
    val suggestions = listOf("Mere views kyun kam hen?", "Agli video ka idea do", "Title behtar karo", "Description likho")

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(coach.msgs) { m ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (m.fromUser) YtAccent else YtCard,
                        modifier = Modifier.widthIn(max = 330.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = m.text,
                                color = if (m.fromUser) Color.White else YtText,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
            item { if (coach.busy) YqBusy("Coach soch raha hai…") else Spacer(modifier = Modifier.height(1.dp)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { s -> YqChip(s) { onSend(s) } }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YqInput(
                value = input,
                onChange = { input = it },
                hint = "Ask AI Coach",
                modifier = Modifier.weight(1f),
                onSend = { if (input.isNotBlank()) { onSend(input); input = "" } }
            )
            YqButton("Send", enabled = !coach.busy && input.isNotBlank()) {
                onSend(input)
                input = ""
            }
        }
    }
}

// ---------------------------------------------------------------- Thumbnails

private fun yqSaveBitmap(context: Context, bmp: Bitmap): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ytiq_thumbnail_" + System.currentTimeMillis() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YTIQ")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) {
        false
    }
}

@Composable
fun YtiqThumbnailScreen(thumbs: SnapshotStateList<Bitmap>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var idea by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun generate() {
        val text = idea.trim()
        if (text.isEmpty() || busy) return
        scope.launch {
            busy = true
            error = null
            val full = text + ", YouTube thumbnail, 16:9, bold colours, high contrast, dramatic lighting, sharp focus, no text"
            val (bmp, err) = YtiqAi.image(context, full)
            if (bmp != null) thumbs.add(0, bmp) else error = err
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        YqNote("Thumbnail ka idea likhein, AI tasveer banayega.")
        YqInput(value = idea, onChange = { idea = it }, hint = "Describe your thumbnail idea", modifier = Modifier.fillMaxWidth(), onSend = { generate() })
        YqButton(if (busy) "Ban rahi hai…" else "Create", enabled = !busy && idea.isNotBlank()) { generate() }
        if (busy) YqBusy("Image ban rahi hai, thora intezar karen…")
        error?.let { Text(text = it, color = YqRed, fontSize = 14.sp) }
        thumbs.forEach { bmp ->
            YqCard {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
                )
                Spacer(modifier = Modifier.height(10.dp))
                YqButton("Save to Gallery") {
                    val ok = yqSaveBitmap(context, bmp)
                    Toast.makeText(
                        context,
                        if (ok) "Gallery (Pictures/YTIQ) mein save ho gayi" else "Save nahi hui (Android 10+ chahiye)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- Research + Competitors

/** The last research results, kept in memory for a few hours so opening the tab again does not spend YouTube quota. */
private object ResearchCache {
    var query: String = ""
    var savedAt: Long = 0L
    var suggestions: List<String> = emptyList()
    var ranked: List<String> = emptyList()
    var titles: List<String> = emptyList()
    var note: String? = null
    var viral: List<YouTubeViralResearch.ViralVideo> = emptyList()
    var big: List<YouTubeViralResearch.BigChannel> = emptyList()
    var viralNote: String? = null

    fun fresh(q: String): Boolean = query == q && System.currentTimeMillis() - savedAt < 6L * 60 * 60 * 1000
}

@Composable
fun YtiqResearchScreen(
    competitorsMode: Boolean,
    onAsk: (String) -> Unit,
    onPlay: (videoId: String, title: String) -> Unit = { _, _ -> },
    ownTitles: List<String> = emptyList(),
    ownChannelTitle: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var ranked by remember { mutableStateOf<List<String>>(emptyList()) }
    var titles by remember { mutableStateOf<List<String>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var viral by remember { mutableStateOf<List<YouTubeViralResearch.ViralVideo>>(emptyList()) }
    var bigChannels by remember { mutableStateOf<List<YouTubeViralResearch.BigChannel>>(emptyList()) }
    var viralNote by remember { mutableStateOf<String?>(null) }
    // The topic worked out from this channel's own titles (searched automatically when the screen opens).
    var autoQuery by remember { mutableStateOf("") }

    fun search(force: Boolean = true) {
        val s = seed.trim()
        if (s.isEmpty() || busy) return
        if (!force && ResearchCache.fresh(s)) {
            suggestions = ResearchCache.suggestions
            ranked = ResearchCache.ranked
            titles = ResearchCache.titles
            note = ResearchCache.note
            viral = ResearchCache.viral
            bigChannels = ResearchCache.big
            viralNote = ResearchCache.viralNote
            searched = true
            return
        }
        scope.launch {
            busy = true
            note = null
            val key = withContext(Dispatchers.IO) {
                try { SecureCredentialStore(context.applicationContext).youtubeApiKey } catch (e: Exception) { "" }
            }
            suggestions = withContext(Dispatchers.IO) { YouTubeSuggest.fetch(s) }
            val r = if (key.isBlank()) null else withContext(Dispatchers.IO) { YouTubeCompetitorResearch.research(s, key) }
            ranked = r?.rankedKeywords.orEmpty().take(15)
            titles = r?.competitorTitles.orEmpty().take(10)
            note = when {
                key.isBlank() -> "Competitor data ke liye Settings mein YouTube API Key lagayen."
                r == null -> "Competitor data nahi mila (key ya internet check karen)."
                else -> null
            }
            if (competitorsMode && key.isNotBlank()) {
                val outcome = withContext(Dispatchers.IO) { YouTubeViralResearch.research(s, key) }
                val own = ownChannelTitle?.trim().orEmpty()
                // Our own channel is not a competitor.
                viral = outcome.result?.videos.orEmpty()
                    .filter { own.isBlank() || !it.channelTitle.equals(own, ignoreCase = true) }.take(10)
                bigChannels = outcome.result?.channels.orEmpty()
                    .filter { own.isBlank() || !it.title.equals(own, ignoreCase = true) }.take(8)
                viralNote = outcome.error
                // What was learned about this channel's own niche is kept, so the AI can use it later (title writer + coach).
                if (s == autoQuery && viral.isNotEmpty()) {
                    NicheInsights.save(
                        context.applicationContext,
                        NicheInsights.Snapshot(
                            savedAt = System.currentTimeMillis(),
                            niche = s,
                            titles = viral.filter { (it.score ?: 0.0) >= 1.0 }.take(10).map { it.title }
                                .ifEmpty { viral.take(6).map { it.title } },
                            channels = bigChannels.map { it.title }
                        )
                    )
                }
            } else {
                viral = emptyList()
                bigChannels = emptyList()
                viralNote = null
            }
            ResearchCache.query = s
            ResearchCache.savedAt = System.currentTimeMillis()
            ResearchCache.suggestions = suggestions
            ResearchCache.ranked = ranked
            ResearchCache.titles = titles
            ResearchCache.note = note
            ResearchCache.viral = viral
            ResearchCache.big = bigChannels
            ResearchCache.viralNote = viralNote
            searched = true
            busy = false
        }
    }

    // Opens with the competition for THIS channel's topic already shown — no search needed.
    LaunchedEffect(ownTitles.isNotEmpty()) {
        if (competitorsMode && ownTitles.isNotEmpty() && seed.isBlank() && autoQuery.isBlank()) {
            val q = NicheInsights.nicheQuery(ownTitles, ownChannelTitle)
            if (q.isNotBlank()) {
                autoQuery = q
                seed = q
                search(force = false)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        YqNote(
            if (competitorsMode) "Topic likhein, dekhein abhi kaun si videos rank kar rahi hen."
            else "Keyword ya topic likhein, asal YouTube search suggestions milengi."
        )
        if (autoQuery.isNotBlank() && seed == autoQuery) {
            YqNote("Aap ke channel ki videos se topic khud chuna gaya: \"$autoQuery\". Badal kar dobara Search kar sakte hain.")
        }
        YqInput(value = seed, onChange = { seed = it }, hint = "Search keywords…", modifier = Modifier.fillMaxWidth(), onSend = { search() })
        YqButton(if (busy) "Dhoond raha hai…" else "Search", enabled = !busy && seed.isNotBlank()) { search() }
        if (busy) YqBusy("Data aa raha hai…")
        note?.let { YqNote(it) }

        if (searched) {
            if (competitorsMode) {
                viralNote?.let { YqNote(it) }
                if (viral.isNotEmpty()) {
                    YqCard {
                        YqTitle("🔥 Viral videos (pichle 30 din)")
                        YqNote("Score = views ÷ channel ke subscribers. 5x ka matlab: video ko channel ke subscribers se 5 guna zyada views mile. Video par tap = app mein chalaye, 🤖 = AI us ka andaaz seekh kar aap ke liye title banaye.")
                        viral.forEach { v ->
                            ViralRow(
                                v = v,
                                onPlay = { onPlay(v.id, v.title) },
                                onAsk = { onAsk("Ye video is topic par viral hai: \"${v.title}\". Is ke title ka andaaz (shuru ke lafz, sawal ya number) samjha kar mere channel ke liye isi andaaz mein, lekin apne alfaz mein, 3 title do. Is channel ka naam use mat karna.") }
                            )
                        }
                    }
                }
                if (bigChannels.isNotEmpty()) {
                    YqCard {
                        YqTitle("📺 Bade channels (is topic par active)")
                        bigChannels.forEach { c ->
                            ChannelRow(c) {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/channel/${c.id}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (e: Exception) {
                                    // no app can open the link
                                }
                            }
                        }
                    }
                }
            }
            if (competitorsMode && titles.isNotEmpty()) {
                YqCard {
                    YqTitle("Top videos (abhi rank par)")
                    titles.forEach { t -> YqRowItem(t) { onAsk("Is competitor video ka title dekh kar mere channel ke liye behtar title do: $t") } }
                }
            }
            if (ranked.isNotEmpty()) {
                YqCard {
                    YqTitle("Top keywords (competitors ke tags)")
                    ranked.forEach { k -> YqRowItem(k) { onAsk("'$k' keyword par mere channel ke liye 3 video ideas aur title do") } }
                }
            }
            if (!competitorsMode || (titles.isEmpty() && ranked.isEmpty())) {
                YqCard {
                    YqTitle("YouTube search suggestions")
                    if (suggestions.isEmpty()) YqNote("Koi suggestion nahi mili.")
                    suggestions.forEach { k -> YqRowItem(k) { onAsk("'$k' keyword par mere channel ke liye 3 video ideas aur title do") } }
                }
            }
            if (!competitorsMode && titles.isNotEmpty()) {
                YqCard {
                    YqTitle("Top videos (abhi rank par)")
                    titles.forEach { t -> YqRowItem(t) { onAsk("Is competitor video ka title dekh kar mere channel ke liye behtar title do: $t") } }
                }
            }
            YqNote("Kisi bhi line par tap karen to AI Coach us par salah dega.")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ViralRow(v: YouTubeViralResearch.ViralVideo, onPlay: () -> Unit, onAsk: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).clickable(onClick = onPlay)) {
            Text(text = v.title, color = YtText, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subsText = v.subscribers?.let { "${formatCount(it)} subs" } ?: "subs hidden"
            val scoreText = v.score?.let {
                " • " + (if (it >= 10) it.toInt().toString() else String.format(java.util.Locale.US, "%.1f", it)) + "x"
            } ?: ""
            Text(
                text = "${v.channelTitle} • $subsText • ${formatCount(v.views)} views$scoreText",
                color = YtMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "🤖",
            fontSize = 18.sp,
            modifier = Modifier.clickable(onClick = onAsk).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun ChannelRow(c: YouTubeViralResearch.BigChannel, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp)) {
        Text(text = c.title, color = YtText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = "${formatCount(c.subscribers)} subscribers • is topic par ${c.videosFound} video",
            color = YtMuted, fontSize = 11.sp
        )
    }
}

@Composable
private fun YqRowItem(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = YtText,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp)
    )
}

// ---------------------------------------------------------------- Optimize

@Composable
fun YtiqOptimizeScreen(
    dash: YtiqDashboard,
    onAsk: (String) -> Unit,
    onOpenCreate: () -> Unit,
    onOpenVideo: (YtVideo) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                YqButton("Naya video: Title, Tags aur Upload", modifier = Modifier.fillMaxWidth()) { onOpenCreate() }
                YqNote("Title score ek andaza hai (length, number, punctuation). Ye YouTube ka official score nahi.")
                if (dash.loading && dash.videos.isEmpty()) YqBusy("Videos aa rahi hen…")
                dash.error?.let { Text(text = it, color = YqRed, fontSize = 14.sp) }
                if (!dash.loading && dash.videos.isEmpty() && dash.error == null) {
                    YqNote("Videos dekhne ke liye pehle menu mein Account Settings se YouTube channel jodein.")
                }
            }
        }
        items(dash.videos) { v ->
            val score = ytiqTitleScore(v.title)
            YqCard {
                com.vellora.cut.autogen.ui.RemoteImage(
                    url = v.thumbUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenVideo(v) }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Title", color = YtMuted, fontSize = 13.sp)
                    Text(text = score.toString(), color = yqScoreColor(score), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(text = formatCount(v.views) + " views", color = YtMuted, fontSize = 13.sp)
                    Text(text = timeAgo(v.publishedAt), color = YtMuted, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = v.title, color = YtText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    YqChip("Title behtar karo") { onAsk("Is video ka title behtar karo, 5 options do (Roman Urdu ya Urdu title): " + v.title) }
                    YqChip("Description likho") { onAsk("Is video ki description likho jis mein keywords aur subscribe ki line ho: " + v.title) }
                    YqChip("Tags do") { onAsk("Is video ke liye 15 tags aur 5 hashtags do: " + v.title) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Subscribers

@Composable
fun YtiqSubscribersScreen(dash: YtiqDashboard, onAsk: (String) -> Unit) {
    val ch = dash.channel
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (ch == null) {
            if (dash.loading) YqBusy("Channel ka data aa raha hai…")
            else YqNote("Channel data nahi hai. Menu mein Account Settings se YouTube channel jodein.")
            return@Column
        }
        YqCard {
            Text(text = "Subscribers", color = YtMuted, fontSize = 14.sp)
            Text(
                text = if (ch.subsHidden) "Hidden" else formatCount(ch.subscribers),
                color = YtText, fontSize = 38.sp, fontWeight = FontWeight.Bold
            )
            if (!ch.subsHidden) {
                val (prev, next) = milestoneRange(ch.subscribers)
                val frac = ((ch.subscribers - prev).toFloat() / (next - prev).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                Spacer(modifier = Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(YtTrack)) {
                    Box(modifier = Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(8.dp)).background(YtAccent))
                }
                Spacer(modifier = Modifier.height(6.dp))
                YqNote("Agla goal ${formatCount(next)} — ${formatCount((next - ch.subscribers).coerceAtLeast(0L))} baqi")
            }
        }
        YqCard {
            Text(text = "Total views", color = YtMuted, fontSize = 14.sp)
            Text(text = formatCount(ch.views), color = YtText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            YqNote("${ch.videoCount} videos")
        }
        val vids = dash.videos
        if (vids.isNotEmpty()) {
            val last = vids.take(10)
            val avg = last.sumOf { it.views } / last.size
            val best = last.maxByOrNull { it.views }
            val likes = last.sumOf { it.likes }
            val views = last.sumOf { it.views }
            val rate = if (views > 0) likes * 100.0 / views else 0.0
            YqCard {
                YqTitle("Pichli ${last.size} videos")
                Spacer(modifier = Modifier.height(8.dp))
                YqNote("Average views: ${formatCount(avg)}")
                YqNote("Likes / views: " + String.format(java.util.Locale.US, "%.1f", rate) + "%")
                if (best != null) YqNote("Sab se achi: ${best.title} (${formatCount(best.views)})")
                computePerformance(vids)?.let {
                    val sign = if (it.percent >= 0) "+" else ""
                    YqNote("Latest video apne aam views se $sign${it.percent}% (rank ${it.rank}/${it.outOf})")
                }
            }
        }
        YqButton("Subscribers kaise barhayen? (AI Coach)", modifier = Modifier.fillMaxWidth()) {
            onAsk("Mere channel ke data ko dekh kar batao subscribers kaise barhayen, 5 amli qadam do")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- Daily Ideas / Learn / Generate

class YtiqAiAction(val label: String, val task: String)

@Composable
fun YtiqAiToolScreen(dash: YtiqDashboard, intro: String, actions: List<YtiqAiAction>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf("") }

    fun run(a: YtiqAiAction) {
        if (busy) return
        scope.launch {
            busy = true
            shown = a.label
            output = ""
            output = when (val r = YtiqAi.text(context, ytiqToolPrompt(a.task, dash.channel, dash.videos))) {
                is YtiqAiResult.Ok -> r.text
                is YtiqAiResult.Fail -> "Masla: " + r.message
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        YqNote(intro)
        actions.forEach { a -> YqButton(a.label, modifier = Modifier.fillMaxWidth(), enabled = !busy) { run(a) } }
        if (busy) YqBusy("AI jawab bana raha hai…")
        if (output.isNotEmpty()) {
            YqCard {
                YqTitle(shown)
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer { Text(text = output, color = YtText, fontSize = 15.sp, lineHeight = 22.sp) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

val YtiqDailyActions = listOf(
    YtiqAiAction("Aaj ke 5 video ideas", "Is channel ke liye 5 naye video ideas do. Har idea ke saath ek catchy title aur ek line mein wajah likho."),
    YtiqAiAction("5 Shorts ke ideas", "Is channel ke liye 5 Shorts (30 second) ke ideas do, har ek ka hook pehli line mein likho."),
    YtiqAiAction("Best video jaisa naya idea", "Haal ki sab se zyada views wali video jaisa ek naya idea do aur batao wo kyun chalega.")
)

val YtiqLearnActions = listOf(
    YtiqAiAction("Pehle 15 second ka hook", "Mere channel ke topics ke hisab se pehle 15 second ka mazboot hook likhne ke 5 tareeqe misal ke saath batao."),
    YtiqAiAction("Title aur thumbnail ka jorr", "Mere channel ki videos ke titles dekh kar batao title aur thumbnail ko kaise milana chahiye, 5 amli tips do."),
    YtiqAiAction("Shorts se subscribers", "Shorts se subscribers aur long videos tak views laane ka plan batao, mere channel ke data ke hisab se."),
    YtiqAiAction("Meri channel ki sab se badi ghalti", "Mere channel ke data ko dekh kar 3 sab se badi kamzoriyan batao aur har ek ka hal likho.")
)

val YtiqGenerateActions = listOf(
    YtiqAiAction("10 video ideas", "Mere channel ke liye 10 video ideas generate karo, har ek ke saath title do."),
    YtiqAiAction("Shorts script (30 sec)", "Mere channel ke topic par 30 second ki Shorts ki poori script likho: hook, beech ka hissa, aakhri line."),
    YtiqAiAction("Hashtags aur tags", "Mere channel ke liye 20 tags aur 8 hashtags generate karo."),
    YtiqAiAction("Description template", "Mere channel ke liye ek description template likho jis mein keywords, subscribe ki line aur hashtags hon.")
)
