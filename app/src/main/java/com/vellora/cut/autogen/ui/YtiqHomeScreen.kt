package com.vellora.cut.autogen.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vellora.cut.autogen.network.YouTubeAccounts
import com.vellora.cut.autogen.network.YtVideo
import com.vellora.cut.update.AppUpdater
import kotlinx.coroutines.launch

/**
 * Start screen of the YTIQ app (no video editor): Home (channel numbers + latest video),
 * Videos (all recent uploads), Optimize (SEO + upload) and Settings (accounts, keys, update).
 */
@Composable
fun YtiqHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dash = remember { YtiqDashboard() }
    var tab by remember { mutableStateOf(YtiqTab.Home) }
    var accounts by remember { mutableStateOf(YouTubeAccounts.list(context)) }
    var email by remember { mutableStateOf(YouTubeAccounts.selectedEmail(context)) }

    // Accounts can change in Settings / Optimize; pick that up whenever the tab changes.
    LaunchedEffect(tab) {
        YouTubeAccounts.migrateLegacy(context)
        accounts = YouTubeAccounts.list(context)
        email = YouTubeAccounts.selectedEmail(context)
    }
    LaunchedEffect(email) { dash.load(context, email) }

    // Tapping the "new version" notification opens Settings, where the Update card is.
    LaunchedEffect(AppUpdater.openRequest) {
        if (AppUpdater.openRequest) {
            tab = YtiqTab.Settings
            AppUpdater.openRequest = false
        }
    }

    BackHandler(enabled = tab != YtiqTab.Home) { tab = YtiqTab.Home }

    // New version found by the update check (and not ignored): banner on Home + red dot on Settings.
    val update = AppUpdater.info
    val updateName = update?.takeIf { it.code != AppUpdater.ignoredCode }?.name

    fun refresh() {
        scope.launch { dash.load(context, email) }
    }

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

    Column(modifier = Modifier.fillMaxSize().background(YtBg)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(WindowInsets.navigationBars)
        ) {
            when (tab) {
                YtiqTab.Home -> YtiqHomeTab(
                    dash = dash,
                    accounts = accounts,
                    selectedEmail = email,
                    onSelectAccount = { YouTubeAccounts.select(context, it); email = it },
                    onOpenSettings = { tab = YtiqTab.Settings },
                    onRefresh = { refresh() },
                    onOpenVideo = { openVideo(it) },
                    updateVersion = updateName
                )
                YtiqTab.Videos -> YtiqVideosTab(
                    dash = dash,
                    accounts = accounts,
                    onOpenSettings = { tab = YtiqTab.Settings },
                    onRefresh = { refresh() },
                    onOpenVideo = { openVideo(it) }
                )
                // These two screens do not handle the status bar themselves, so leave room for it here.
                YtiqTab.Optimize -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    ShortsMetadataScreen(onBack = { tab = YtiqTab.Home })
                }
                YtiqTab.Settings -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    SettingsScreen(onBack = { tab = YtiqTab.Home })
                }
            }
        }
        YtiqBottomBar(selected = tab, onSelect = { tab = it }, settingsBadge = updateName != null)
    }
}
