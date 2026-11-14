package com.vellora.cut.autogen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.autogen.network.YtAccount
import com.vellora.cut.autogen.network.YtChannel
import com.vellora.cut.autogen.network.YtVideo

// YTIQ look: deep navy background, soft cards, indigo accent.
val YtBg = Color(0xFF0D0E1A)
val YtCard = Color(0xFF171926)
val YtTrack = Color(0xFF262A3F)
val YtAccent = Color(0xFF6C7BFF)
val YtText = Color(0xFFF2F3FA)
val YtMuted = Color(0xFF9AA0B8)
private val YtAmber = Color(0xFFF5B800)
private val YtGreen = Color(0xFF3DDC84)
private val YtRed = Color(0xFFFF6B6B)

enum class YtiqTab(val icon: String, val label: String) {
    Home("🏠", "Home"),
    Videos("🎬", "Videos"),
    Optimize("✨", "Optimize"),
    Settings("⚙️", "Settings")
}

// ---------------------------------------------------------------- Home

@Composable
fun YtiqHomeTab(
    dash: YtiqDashboard,
    accounts: List<YtAccount>,
    selectedEmail: String?,
    onSelectAccount: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onOpenVideo: (YtVideo) -> Unit,
    updateVersion: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        ChannelBar(dash.channel, accounts, selectedEmail, dash.loading, onSelectAccount, onOpenSettings, onRefresh)

        // A newer YTIQ version was found: tap to open Settings, where the Update card downloads and installs it.
        if (updateVersion != null) UpdateBanner(updateVersion, onOpenSettings)

        if (accounts.isEmpty()) {
            ConnectCard(onOpenSettings)
        } else {
            dash.error?.let { ErrorCard(it, onRefresh) }
            val ch = dash.channel
            if (ch != null) {
                StatsCard(ch)
            } else if (dash.loading) {
                LoadingBox()
            }
            val latest = dash.videos.firstOrNull()
            if (latest != null) {
                Text(text = "Latest Video", color = YtText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                LatestVideoCard(latest, computePerformance(dash.videos)) { onOpenVideo(latest) }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ChannelBar(
    channel: YtChannel?,
    accounts: List<YtAccount>,
    selectedEmail: String?,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.email.equals(selectedEmail, ignoreCase = true) }
    val title = channel?.title?.takeIf { it.isNotBlank() } ?: selected?.label ?: "YTIQ"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                onClick = { open = true },
                shape = RoundedCornerShape(32.dp),
                color = YtCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Avatar(channel?.avatarUrl, title, 40)
                    Text(
                        text = title, color = YtText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text(text = "▾", color = YtMuted, fontSize = 18.sp)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                accounts.forEach { acc ->
                    DropdownMenuItem(
                        text = { Text(text = acc.label + "\n" + acc.email, fontSize = 13.sp) },
                        onClick = { open = false; onSelect(acc.email) }
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = "+ Dusra Gmail / Channel jodein") },
                    onClick = { open = false; onAdd() }
                )
            }
        }
        Surface(onClick = onRefresh, shape = CircleShape, color = YtCard, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(color = YtAccent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Text(text = "↻", color = YtText, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun UpdateBanner(version: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = YtAccent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "⬆️", fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Naya version $version available", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(text = "Update karne ke liye dabayein", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            Text(text = "›", color = Color.White, fontSize = 24.sp)
        }
    }
}

@Composable
private fun Avatar(url: String?, label: String, size: Int) {
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(YtAccent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold
        )
        if (!url.isNullOrBlank()) RemoteImage(url = url, modifier = Modifier.fillMaxSize(), placeholder = Color.Transparent)
    }
}

@Composable
private fun StatsCard(ch: YtChannel) {
    Surface(shape = RoundedCornerShape(26.dp), color = YtCard, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat("SUBSCRIBERS", if (ch.subsHidden) null else ch.subscribers, Modifier.weight(1f))
            Box(modifier = Modifier.width(1.dp).height(120.dp).background(YtTrack))
            Stat("VIEWS", ch.views, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: Long?, modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = label, color = YtMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            text = if (value == null) "Hidden" else formatCount(value),
            color = YtText, fontSize = 40.sp, fontWeight = FontWeight.Bold
        )
        if (value != null) {
            val (from, to) = milestoneRange(value)
            val fraction = ((value - from).toDouble() / (to - from).coerceAtLeast(1L)).toFloat().coerceIn(0.04f, 1f)
            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(YtTrack)) {
                Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(YtAccent))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = formatCount(from), color = YtMuted, fontSize = 12.sp)
                Text(text = formatCount(to), color = YtMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LatestVideoCard(video: YtVideo, perf: YtPerformance?, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = YtCard, modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteImage(
                url = video.thumbUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
            )
            Text(
                text = video.title, color = YtText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Performance", perf?.let { (if (it.percent > 0) "+" else "") + it.percent + "%" } ?: "—", perf?.let { perfColor(it.percent) } ?: YtText, Modifier.weight(1.3f))
                Metric("Views", formatCount(video.views), YtText, Modifier.weight(1f))
                Metric("Rank", perf?.let { "${it.rank}/${it.outOf}" } ?: "—", YtText, Modifier.weight(1f))
            }
            if (perf != null) PerformanceBar(perf.percent)
            Text(text = timeAgo(video.publishedAt), color = YtMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

private fun perfColor(percent: Int): Color = when {
    percent <= -20 -> YtAmber
    percent >= 20 -> YtGreen
    else -> YtAccent
}

@Composable
private fun Metric(label: String, value: String, valueColor: Color, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = YtMuted, fontSize = 14.sp)
        Text(text = value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PerformanceBar(percent: Int) {
    // -100% .. +100% mapped onto the bar; 0% (typical for this channel) sits in the middle.
    val position = (0.5f + percent / 200f).coerceIn(0.03f, 0.97f)
    val color = perfColor(percent)
    Column(modifier = Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            Box(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(YtTrack)
            )
            Box(
                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(position).height(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.45f))
            )
            Box(
                modifier = Modifier.align(Alignment.CenterStart).offset(x = maxWidth * position - 9.dp)
                    .size(18.dp).clip(CircleShape).background(color)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Underperforming", color = YtMuted, fontSize = 12.sp)
            Text(text = "Average", color = YtMuted, fontSize = 12.sp)
            Text(text = "Overperforming", color = YtMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ConnectCard(onOpenSettings: () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = YtCard, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "YouTube account jodein", color = YtText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Apne channel ke subscribers, views aur videos yahan dekhne ke liye Gmail connect karein.",
                color = YtMuted, fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Surface(onClick = onOpenSettings, shape = RoundedCornerShape(24.dp), color = YtAccent) {
                Text(
                    text = "Settings mein jaen", color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = YtCard, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "⚠ $message", color = YtRed, fontSize = 13.sp)
            Text(
                text = "Dobara koshish karein", color = YtAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onRetry() }
            )
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = YtAccent)
    }
}

// ---------------------------------------------------------------- Videos

@Composable
fun YtiqVideosTab(
    dash: YtiqDashboard,
    accounts: List<YtAccount>,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onOpenVideo: (YtVideo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Videos", color = YtText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                dash.channel?.let {
                    Text(text = "${it.title} • ${formatCount(it.videoCount)} videos", color = YtMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(onClick = onRefresh, shape = CircleShape, color = YtCard, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (dash.loading) CircularProgressIndicator(color = YtAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Text(text = "↻", color = YtText, fontSize = 20.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        when {
            accounts.isEmpty() -> ConnectCard(onOpenSettings)
            dash.videos.isEmpty() && dash.loading -> LoadingBox()
            dash.videos.isEmpty() -> {
                dash.error?.let { ErrorCard(it, onRefresh) }
                    ?: Text(text = "Abhi koi video nahi mili.", color = YtMuted, fontSize = 14.sp)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                dash.error?.let { item { ErrorCard(it, onRefresh) } }
                items(dash.videos, key = { it.id }) { VideoRow(it) { onOpenVideo(it) } }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun VideoRow(video: YtVideo, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = YtCard, modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteImage(
                url = video.thumbUrl,
                modifier = Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = video.title, color = YtText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "👁 ${formatCount(video.views)}   👍 ${formatCount(video.likes)}   💬 ${formatCount(video.comments)}",
                    color = YtMuted, fontSize = 12.sp
                )
                Text(text = timeAgo(video.publishedAt), color = YtMuted, fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- Bottom bar

@Composable
fun YtiqBottomBar(selected: YtiqTab, onSelect: (YtiqTab) -> Unit, settingsBadge: Boolean = false) {
    Box(modifier = Modifier.fillMaxWidth().background(YtBg).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(30.dp), color = YtCard, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                YtiqTab.values().forEach { tab ->
                    val active = tab == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(26.dp))
                            .background(if (active) YtTrack else Color.Transparent)
                            .clickable { onSelect(tab) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box {
                            Text(text = tab.icon, fontSize = 20.sp)
                            if (tab == YtiqTab.Settings && settingsBadge) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(YtRed)
                                )
                            }
                        }
                        Text(
                            text = tab.label, fontSize = 12.sp,
                            color = if (active) YtText else YtMuted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
