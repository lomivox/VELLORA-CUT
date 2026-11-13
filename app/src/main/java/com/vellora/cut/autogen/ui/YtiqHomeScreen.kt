package com.vellora.cut.autogen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.ui.theme.BackgroundDark
import com.vellora.cut.ui.theme.CyanPrimary
import com.vellora.cut.ui.theme.SurfaceDark
import com.vellora.cut.ui.theme.SurfaceVariant
import com.vellora.cut.ui.theme.TextPrimary
import com.vellora.cut.ui.theme.TextSecondary
import com.vellora.cut.update.AppUpdater

private enum class YtiqScreen { Home, Seo, Settings }

/**
 * Start screen of the YTIQ app: no video editor, only the YouTube side
 * (SEO title/description/tags + upload, accounts, settings, update).
 */
@Composable
fun YtiqHomeScreen() {
    var screen by remember { mutableStateOf(YtiqScreen.Home) }

    // Tapping the "new version" notification opens Settings, where the Update card is.
    LaunchedEffect(AppUpdater.openRequest) {
        if (AppUpdater.openRequest) {
            screen = YtiqScreen.Settings
            AppUpdater.openRequest = false
        }
    }

    BackHandler(enabled = screen != YtiqScreen.Home) { screen = YtiqScreen.Home }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        when (screen) {
            YtiqScreen.Home -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "YTIQ", color = CyanPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(text = "YouTube SEO aur upload", color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                HomeCard("🔍  SEO + Upload", "Video chunein, title/description/tags banayein ya likhein, aur upload karein") {
                    screen = YtiqScreen.Seo
                }
                HomeCard("⚙️  Settings", "YouTube accounts, API keys, app update") {
                    screen = YtiqScreen.Settings
                }
            }
            YtiqScreen.Seo -> ShortsMetadataScreen(onBack = { screen = YtiqScreen.Home })
            YtiqScreen.Settings -> SettingsScreen(onBack = { screen = YtiqScreen.Home })
        }
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, SurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(text = subtitle, color = TextSecondary, fontSize = 12.sp)
        }
    }
}
