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
    val suggestions = listOf("Why are my views low?", "Give me the next video idea", "Improve my title", "Description likho")

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
            item { if (coach.busy) YqBusy("Coach is thinking…") else Spacer(modifier = Modifier.height(1.dp)) }
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
        YqNote("Describe the thumbnail idea and AI will create the picture.")
        YqInput(value = idea, onChange = { idea = it }, hint = "Describe your thumbnail idea", modifier = Modifier.fillMaxWidth(), onSend = { generate() })
        YqButton(if (busy) "Generating…" else "Create", enabled = !busy && idea.isNotBlank()) { generate() }
        if (busy) YqBusy("Generating the image, please wait…")
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
                        if (ok) "Saved to Gallery (Pictures/YTIQ)" else "Could not save (needs Android 10+)",
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
    ownChannelTitle: String? = null,
    ownSubscribers: Long? = null,
    ownAccountKey: String? = null
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
    // What the AI understood about this channel (topic, audience search phrases, country) — used for every search.
    var profile by remember { mutableStateOf<ChannelNiche.Profile?>(null) }
    var autoQueries by remember { mutableStateOf<List<String>>(emptyList()) }
    var channelsNote by remember { mutableStateOf<String?>(null) }

    fun search(force: Boolean = true) {
        val s = seed.trim()
        if (s.isEmpty() || busy) return
        // The channel's own phrases are searched together when the topic is the automatic one; a typed phrase alone otherwise.
        val queries = if (s == autoQuery && autoQueries.isNotEmpty()) autoQueries else listOf(s)
        val cacheKey = queries.joinToString("|")
        if (!force && ResearchCache.fresh(cacheKey)) {
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
            // The channel's profile (country, language, topic words) keeps every search inside this channel's world.
            val p = if (competitorsMode && key.isNotBlank()) {
                profile ?: ChannelNiche.load(context, ownAccountKey, ownChannelTitle, ownTitles, key).also { profile = it }
            } else profile
            val r = if (key.isBlank()) null else withContext(Dispatchers.IO) {
                YouTubeCompetitorResearch.research(s, key, regionCode = p?.country, relevanceLanguage = p?.relevanceLanguage)
            }
            ranked = r?.rankedKeywords.orEmpty().take(15)
            titles = r?.competitorTitles.orEmpty().take(10)
            note = when {
                key.isBlank() -> "Add a YouTube API Key in Settings for competitor data."
                r == null -> "Competitor data not found (check the key or your internet)."
                else -> null
            }
            if (competitorsMode && key.isNotBlank()) {
                val options = YouTubeViralResearch.Options(
                    regionCode = p?.country,
                    relevanceLanguage = p?.relevanceLanguage,
                    days = 90,
                    order = "relevance"
                )
                val outcome = withContext(Dispatchers.IO) {
                    YouTubeViralResearch.researchQueries(queries, key, ownSubscribers, options, p?.keywords.orEmpty())
                }
                val channelOutcome = withContext(Dispatchers.IO) {
                    YouTubeViralResearch.similarChannels(queries.first(), key, ownSubscribers, options)
                }
                val own = ownChannelTitle?.trim().orEmpty()
                // Our own channel is not a competitor.
                viral = outcome.result?.videos.orEmpty()
                    .filter { own.isBlank() || !it.channelTitle.equals(own, ignoreCase = true) }.take(10)
                bigChannels = channelOutcome.result?.channels.orEmpty()
                    .filter { own.isBlank() || !it.title.equals(own, ignoreCase = true) }.take(10)
                    .ifEmpty { outcome.result?.channels.orEmpty().filter { own.isBlank() || !it.title.equals(own, ignoreCase = true) }.take(8) }
                viralNote = outcome.error
                channelsNote = if (bigChannels.isEmpty()) channelOutcome.error else null
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
            ResearchCache.query = cacheKey
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

    // Opens with the competition for THIS channel already shown: the AI first understands what the channel is about
    // (from its own videos), then those exact audience phrases are searched — no typing needed.
    LaunchedEffect(ownTitles.isNotEmpty()) {
        if (competitorsMode && ownTitles.isNotEmpty() && seed.isBlank() && autoQuery.isBlank()) {
            busy = true
            val key = withContext(Dispatchers.IO) {
                try { SecureCredentialStore(context.applicationContext).youtubeApiKey } catch (e: Exception) { "" }
            }
            val p = ChannelNiche.load(context, ownAccountKey, ownChannelTitle, ownTitles, key)
            busy = false
            profile = p
            if (p.queries.isNotEmpty()) {
                autoQueries = p.queries
                autoQuery = p.queries.first()
                seed = autoQuery
                search(force = false)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        YqNote(
            if (competitorsMode) "Write a topic and see which videos are ranking right now."
            else "Write a keyword or topic to get real YouTube search suggestions."
        )
        if (autoQuery.isNotBlank() && seed == autoQuery) {
            val p = profile
            YqNote(
                (if (p != null && p.niche.isNotBlank()) "Your channel's topic: ${p.niche}\n" else "") +
                    "These phrases were picked automatically from your channel's videos: " + autoQueries.joinToString("  •  ") +
                    "\nYou can change them and search yourself — it will also follow your channel's language/country."
            )
        }
        YqInput(value = seed, onChange = { seed = it }, hint = "Search keywords…", modifier = Modifier.fillMaxWidth(), onSend = { search() })
        YqButton(if (busy) "Searching…" else "Search", enabled = !busy && seed.isNotBlank()) { search() }
        if (busy) YqBusy("Fetching data…")
        note?.let { YqNote(it) }

        if (searched) {
            if (competitorsMode) {
                viralNote?.let { YqNote(it) }
                if (viral.isNotEmpty()) {
                    YqCard {
                        YqTitle("🔥 Viral videos (last 30 days)")
                        YqNote("Score = views ÷ channel subscribers. 5x means the video got 5 times more views than the channel's subscribers. Tap a video = play it in the app, 🤖 = AI learns its style and writes titles for you.")
                        viral.forEach { v ->
                            ViralRow(
                                v = v,
                                onPlay = { onPlay(v.id, v.title) },
                                onAsk = { onAsk("This video is viral on this topic: \"${v.title}\". Explain its title style (opening words, question or number) and give me 3 titles for my channel in the same style, but in your own words. Do not use this channel's name.") }
                            )
                        }
                    }
                }
                if (viral.isNotEmpty()) {
                    YqButton("💡 5 ideas for my channel from these videos") {
                        onAsk(
                            "My channel's recent titles:\n" + ownTitles.take(8).joinToString("\n") { "- $it" } +
                                "\n\nThese videos are viral on this topic right now:\n" + viral.take(6).joinToString("\n") { "- ${it.title}" } +
                                "\n\nUnderstand the real angles of these viral videos and give me 5 new video ideas for my channel. " +
                                "For each idea give a title and a one-line reason why it will work. Do not copy any competitor's title or channel name."
                        )
                    }
                }
                channelsNote?.let { YqNote(it) }
                if (bigChannels.isNotEmpty()) {
                    YqCard {
                        YqTitle("📺 Competitor channels like yours")
                        YqNote("Channels closest to your size first, then bigger ones. Tap = open the channel.")
                        bigChannels.forEach { c ->
                            ChannelRow(c, ownSubscribers) {
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
                    YqTitle("Top videos (ranking now)")
                    titles.forEach { t -> YqRowItem(t) { onAsk("Look at this competitor video's title and give me a better title for my channel: $t") } }
                }
            }
            if (ranked.isNotEmpty()) {
                YqCard {
                    YqTitle("Top keywords (competitors' tags)")
                    ranked.forEach { k -> YqRowItem(k) { onAsk("Give me 3 video ideas and titles for my channel on the keyword '$k'") } }
                }
            }
            if (!competitorsMode || (titles.isEmpty() && ranked.isEmpty())) {
                YqCard {
                    YqTitle("YouTube search suggestions")
                    if (suggestions.isEmpty()) YqNote("No suggestions found.")
                    suggestions.forEach { k -> YqRowItem(k) { onAsk("Give me 3 video ideas and titles for my channel on the keyword '$k'") } }
                }
            }
            if (!competitorsMode && titles.isNotEmpty()) {
                YqCard {
                    YqTitle("Top videos (ranking now)")
                    titles.forEach { t -> YqRowItem(t) { onAsk("Look at this competitor video's title and give me a better title for my channel: $t") } }
                }
            }
            YqNote("Tap any line and the AI Coach will advise you on it.")
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
private fun ChannelRow(c: YouTubeViralResearch.BigChannel, ownSubs: Long?, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp)) {
        Text(text = c.title, color = YtText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val ratio = if (ownSubs != null && ownSubs > 0) {
            val r = c.subscribers.toDouble() / ownSubs.toDouble()
            " • " + (if (r >= 10) r.toInt().toString() else String.format(java.util.Locale.US, "%.1f", r)) + "x yours"
        } else ""
        Text(text = "${formatCount(c.subscribers)} subscribers$ratio", color = YtMuted, fontSize = 11.sp)
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
                YqButton("New video: Title, Tags and Upload", modifier = Modifier.fillMaxWidth()) { onOpenCreate() }
                YqNote("The title score is an estimate (length, number, punctuation). It is not YouTube's official score.")
                if (dash.loading && dash.videos.isEmpty()) YqBusy("Loading videos…")
                dash.error?.let { Text(text = it, color = YqRed, fontSize = 14.sp) }
                if (!dash.loading && dash.videos.isEmpty() && dash.error == null) {
                    YqNote("To see videos, first connect a YouTube channel from Account Settings in the menu.")
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
                    YqChip("Improve my title") { onAsk("Improve this video's title and give 5 options (in the same language as the current title): " + v.title) }
                    YqChip("Description likho") { onAsk("Write a description for this video that includes keywords and a subscribe line: " + v.title) }
                    YqChip("Give tags") { onAsk("Give 15 tags and 5 hashtags for this video: " + v.title) }
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
            if (dash.loading) YqBusy("Loading channel data…")
            else YqNote("No channel data. Connect a YouTube channel from Account Settings in the menu.")
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
                if (best != null) YqNote("Best: ${best.title} (${formatCount(best.views)})")
                computePerformance(vids)?.let {
                    val sign = if (it.percent >= 0) "+" else ""
                    YqNote("Latest video is $sign${it.percent}% vs your average views (rank ${it.rank}/${it.outOf})")
                }
            }
        }
        YqButton("Subscribers kaise barhayen? (AI Coach)", modifier = Modifier.fillMaxWidth()) {
            onAsk("Look at my channel's data and tell me how to grow subscribers, with 5 practical steps")
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
                is YtiqAiResult.Fail -> "Problem: " + r.message
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
        if (busy) YqBusy("AI is writing the answer…")
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
    YtiqAiAction("5 video ideas for today", "Give 5 new video ideas for this channel. For each idea write a catchy title and a one-line reason."),
    YtiqAiAction("5 Shorts ideas", "Give 5 Shorts (30-second) ideas for this channel, with the hook in the first line of each."),
    YtiqAiAction("A new idea like my best video", "Give one new idea like my most-viewed recent video and explain why it will work.")
)

val YtiqLearnActions = listOf(
    YtiqAiAction("The first 15-second hook", "Based on my channel's topics, give 5 ways to write a strong first 15-second hook, with examples."),
    YtiqAiAction("Title and thumbnail match", "Look at my channel's video titles and tell me how to match the title with the thumbnail, with 5 practical tips."),
    YtiqAiAction("Subscribers from Shorts", "Give a plan to gain subscribers from Shorts and bring views to long videos, based on my channel's data."),
    YtiqAiAction("My channel's biggest mistake", "Look at my channel's data and name the 3 biggest weaknesses, with a fix for each.")
)

val YtiqGenerateActions = listOf(
    YtiqAiAction("10 video ideas", "Generate 10 video ideas for my channel, with a title for each."),
    YtiqAiAction("Shorts script (30 sec)", "Write a full 30-second Shorts script on my channel's topic: hook, middle part, closing line."),
    YtiqAiAction("Hashtags and tags", "Generate 20 tags and 8 hashtags for my channel."),
    YtiqAiAction("Description template", "Write a description template for my channel with keywords, a subscribe line and hashtags.")
)
