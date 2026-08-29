package com.vellora.cut.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.vellora.cut.autogen.ui.AutoGenHomeScreen
import com.vellora.cut.ui.theme.VelloraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VelloraTheme {
                VelloraApp()
            }
        }
    }
}

/** Top-level screens the Hub can navigate between. */
private sealed class AppScreen {
    object Hub : AppScreen()
    object Editor : AppScreen()
    object AutoGenerator : AppScreen()
}

@Composable
fun VelloraApp() {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Hub) }
    var currentVideoUri by remember { mutableStateOf<String?>(null) }

    // Mobile ka hardware/gesture back button: Hub par ho to app normally band ho
    // (isliye enabled = screen Hub nahi hai). Editor/AutoGenerator mein ho to
    // wahi step-back ho jo in-app "← Back" button karta hai — poori app band
    // nahi hoti. AutoGenerator ke apne andar ke screens (New Project, Prompt
    // Paste, Timeline waghera) apna BackHandler khud sambhalte hain
    // (AutoGenHomeScreen.kt) — jab wo apni root (ProjectList) par pahunch jate
    // hain to wahan se ye upar wala handler AutoGenerator -> Hub karta hai.
    BackHandler(enabled = screen !is AppScreen.Hub) {
        when (screen) {
            is AppScreen.Editor -> {
                if (currentVideoUri != null) currentVideoUri = null else screen = AppScreen.Hub
            }
            is AppScreen.AutoGenerator -> screen = AppScreen.Hub
            else -> {}
        }
    }

    when (screen) {
        is AppScreen.Hub -> HubScreen(
            onOpenEditor = { screen = AppScreen.Editor },
            onOpenAutoGenerator = { screen = AppScreen.AutoGenerator }
        )

        is AppScreen.Editor -> {
            if (currentVideoUri == null) {
                EditorHomeScreen(
                    onVideoSelected = { uri -> currentVideoUri = uri },
                    onBack = { screen = AppScreen.Hub }
                )
            } else {
                EditorScreen(
                    videoUri = currentVideoUri!!,
                    onBack = { currentVideoUri = null }
                )
            }
        }

        is AppScreen.AutoGenerator -> AutoGenHomeScreen(
            onBack = { screen = AppScreen.Hub }
        )
    }
}
