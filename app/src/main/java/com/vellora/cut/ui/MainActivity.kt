package com.vellora.cut.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
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

@Composable
fun VelloraApp() {
    var currentVideoUri by remember { mutableStateOf<String?>(null) }

    if (currentVideoUri == null) {
        HomeScreen(onVideoSelected = { uri ->
            currentVideoUri = uri
        })
    } else {
        EditorScreen(
            videoUri = currentVideoUri!!,
            onBack = { currentVideoUri = null }
        )
    }
}
