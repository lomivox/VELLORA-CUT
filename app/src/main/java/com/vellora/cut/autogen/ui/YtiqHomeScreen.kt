package com.vellora.cut.autogen.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.BuildConfig
import com.vellora.cut.autogen.network.YouTubeAccounts
import com.vellora.cut.autogen.network.YtVideo
import com.vellora.cut.update.AppUpdater
import kotlinx.coroutines.launch

/** Every screen of the YTIQ app. The bottom bar shows the first five; the menu (hamburger) reaches all of them. */
enum class YtiqScreen(val label: String, val icon: String) {
    Home("Home", "🏠"),
    Optimize("Optimize", "📈"),
    Research("Research", "🔍"),
    Thumbnails("Thumbnails", "🖼️"),
    Coach("AI Coach", "💬"),
    Daily("Daily Ideas", "💡"),
    Competitors("Competitors", "👁️"),
    Subscribers("Subscribers", "👥"),
    Learn("Learn", "🎓"),
    Create("Create", "✨"),
    Generate("Generate", "🪄"),
    Settings("Settings", "⚙️")
}

private val YtiqBottomTabs = listOf(
    YtiqScreen.Home, YtiqScreen.Optimize, YtiqScreen.Research, YtiqScreen.Thumbnails, YtiqScreen.Coach
)

/**
 * Start screen of the YTIQ app: bottom bar (Home, Optimize, Research, Thumbnails, Coach) plus a side menu
 * with the other tools. Everything shows the real channel (YouTube login) and real AI (Cloudflare accounts from Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtiqHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dash = remember { YtiqDashboard() }
    val coach = remember { YtiqCoachState() }
    val thumbs = remember { mutableStateListOf<Bitmap>() }
    var screen by remember { mutableStateOf(YtiqScreen.Home) }
    var accounts by remember { mutableStateOf(YouTubeAccounts.list(context)) }
    var email by remember { mutableStateOf(YouTubeAccounts.selectedEmail(context)) }
    var showSheet by remember { mutableStateOf(false) }
    var channelMenuOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(true) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Accounts can change in Settings / Create; pick that up whenever the screen changes.
    LaunchedEffect(screen) {
        YouTubeAccounts.migrateLegacy(context)
        accounts = YouTubeAccounts.list(context)
        email = YouTubeAccounts.selectedEmail(context)
    }
    LaunchedEffect(email) { dash.load(context, email) }

    // Tapping the "new version" notification opens Settings, where the Update card is.
    LaunchedEffect(AppUpdater.openRequest) {
        if (AppUpdater.openRequest) {
            screen = YtiqScreen.Settings
            AppUpdater.openRequest = false
        }
    }

    // Back: close the side menu first, then go back to Home, and on Home close the app (2nd press).
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = screen != YtiqScreen.Home && !drawerState.isOpen) { screen = YtiqScreen.Home }
    ExitOnDoubleBack(enabled = screen == YtiqScreen.Home && !drawerState.isOpen)

    val update = AppUpdater.info
    val updateName = update?.takeIf { it.code != AppUpdater.ignoredCode }?.name

    fun refresh() {
        scope.launch { dash.load(context, email) }
    }

    // The top bar shows the channel's own name + logo; the Gmail is shown only at the bottom of the side menu.
    val channelName: String? = dash.channel?.title?.takeIf { it.isNotBlank() }
        ?: accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }?.label
    val connectedGmail: String? = accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }?.gmail

    fun openVideo(v: YtVideo) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${v.id}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // no app can open the link
        }
    }

    fun go(s: YtiqScreen) {
        screen = s
        scope.launch { drawerState.close() }
    }

    fun send(q: String) {
        scope.launch { coach.send(context, dash, q) }
    }

    /** Opens the Coach and sends [q] to it. */
    fun ask(q: String) {
        screen = YtiqScreen.Coach
        send(q)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = YtBg) {
                Column(modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Spacer(modifier = Modifier.height(12.dp))
                        YtiqDrawerItem("🏠", "Home", screen == YtiqScreen.Home) { go(YtiqScreen.Home) }
                        YtiqDrawerItem("📈", "Optimize", screen == YtiqScreen.Optimize) { go(YtiqScreen.Optimize) }
                        YtiqDrawerItem("✏️", "New Chat", false) {
                            coach.reset()
                            go(YtiqScreen.Coach)
                        }
                        YtiqDrawerItem("🔍", "Research", screen == YtiqScreen.Research) { go(YtiqScreen.Research) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { toolsOpen = !toolsOpen }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "More tools", color = YtMuted, fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = if (toolsOpen) "⌃" else "⌄", color = YtMuted, fontSize = 16.sp)
                        }
                        if (toolsOpen) {
                            listOf(
                                YtiqScreen.Daily, YtiqScreen.Competitors, YtiqScreen.Subscribers, YtiqScreen.Learn,
                                YtiqScreen.Create, YtiqScreen.Thumbnails, YtiqScreen.Generate
                            ).forEach { s ->
                                YtiqDrawerItem(s.icon, s.label, screen == s) { go(s) }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    // Account row: tap for account settings / update
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(YtCard)
                            .clickable { showSheet = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "✉️", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            // The Gmail YTIQ is connected with — shown only here, nowhere else in the app.
                            Text(
                                text = connectedGmail ?: "YouTube jodein",
                                color = YtText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().background(YtBg)) {
            // Top bar: hamburger + screen name
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(YtCard)
                        .clickable { scope.launch { drawerState.open() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "☰", color = YtText, fontSize = 20.sp)
                    if (updateName != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6B6B))
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                // Channel logo + name; tap it to switch between the connected channels.
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(enabled = accounts.isNotEmpty()) { channelMenuOpen = true }
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (channelName != null) {
                            RemoteImage(
                                url = dash.channel?.avatarUrl,
                                modifier = Modifier.size(32.dp).clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = channelName ?: screen.label,
                            color = YtText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (channelName != null && accounts.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "▾", color = YtMuted, fontSize = 14.sp)
                        }
                    }
                    DropdownMenu(
                        expanded = channelMenuOpen,
                        onDismissRequest = { channelMenuOpen = false },
                        modifier = Modifier.background(YtCard)
                    ) {
                        accounts.forEach { acc ->
                            val isSelected = acc.email.equals(email, ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = acc.label, color = YtText, fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Text(text = if (isSelected) "✓" else "📺", color = YtText, fontSize = 15.sp) },
                                onClick = {
                                    channelMenuOpen = false
                                    YouTubeAccounts.select(context, acc.email)
                                    email = acc.email
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(text = "+ Channel jodein", color = YtText, fontSize = 15.sp) },
                            onClick = { channelMenuOpen = false; go(YtiqScreen.Settings) }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeWindowInsets(WindowInsets.navigationBars)
                    .consumeWindowInsets(WindowInsets.statusBars)
            ) {
                when (screen) {
                    YtiqScreen.Home -> YtiqHomeTab(
                        dash = dash,
                        accounts = accounts,
                        selectedEmail = email,
                        onSelectAccount = { YouTubeAccounts.select(context, it); email = it },
                        onOpenSettings = { screen = YtiqScreen.Settings },
                        onRefresh = { refresh() },
                        onOpenVideo = { openVideo(it) },
                        updateVersion = updateName
                    )
                    YtiqScreen.Optimize -> YtiqOptimizeScreen(
                        dash = dash,
                        onAsk = { ask(it) },
                        onOpenCreate = { screen = YtiqScreen.Create },
                        onOpenVideo = { openVideo(it) }
                    )
                    YtiqScreen.Research -> YtiqResearchScreen(competitorsMode = false, onAsk = { ask(it) })
                    YtiqScreen.Competitors -> YtiqResearchScreen(competitorsMode = true, onAsk = { ask(it) })
                    YtiqScreen.Thumbnails -> YtiqThumbnailScreen(thumbs)
                    YtiqScreen.Coach -> YtiqCoachScreen(coach = coach, onSend = { send(it) })
                    YtiqScreen.Subscribers -> YtiqSubscribersScreen(dash = dash, onAsk = { ask(it) })
                    YtiqScreen.Daily -> YtiqAiToolScreen(
                        dash, "Aap ke channel ke asal data se roz naye ideas.", YtiqDailyActions
                    )
                    YtiqScreen.Learn -> YtiqAiToolScreen(
                        dash, "Seekhein: har jawab aap ke apne channel ke hisab se.", YtiqLearnActions
                    )
                    YtiqScreen.Generate -> YtiqAiToolScreen(
                        dash, "Ek tap par ideas, script, tags aur description.", YtiqGenerateActions
                    )
                    YtiqScreen.Create -> Box(modifier = Modifier.fillMaxSize()) {
                        ShortsMetadataScreen(onBack = { screen = YtiqScreen.Home })
                    }
                    YtiqScreen.Settings -> Box(modifier = Modifier.fillMaxSize()) {
                        SettingsScreen(onBack = { screen = YtiqScreen.Home })
                    }
                }
            }
            YtiqNavBar(selected = screen, onSelect = { go(it) })
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = YtCard) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                YtiqDrawerItem("⚙️", "Account Settings", false) {
                    showSheet = false
                    go(YtiqScreen.Settings)
                }
                YtiqDrawerItem("🔄", "Update App", false) {
                    showSheet = false
                    go(YtiqScreen.Settings)
                }
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    color = YtMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun YtiqDrawerItem(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) YtTrack else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, color = YtText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun YtiqNavBar(selected: YtiqScreen, onSelect: (YtiqScreen) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().background(YtBg).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(30.dp), color = YtCard, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                YtiqBottomTabs.forEach { tab ->
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
                        Text(text = tab.icon, fontSize = 20.sp)
                        Text(
                            text = if (tab == YtiqScreen.Coach) "Coach" else tab.label,
                            fontSize = 11.sp,
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
