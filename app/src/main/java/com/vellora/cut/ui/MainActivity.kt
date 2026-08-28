package com.vellora.cut.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
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
