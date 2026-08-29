package com.vellora.cut.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vellora.cut.autogen.ui.AutoGenHomeScreen
import com.vellora.cut.ui.theme.VelloraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VelloraTheme {
                // The old manual Editor (and the Hub screen that chose between
                // it and Auto Generator) has been removed — the app is now a
                // single project: Auto Generator. This opens straight into it.
                //
                // A few reusable UI pieces from the old Editor (top bar, the
                // row of controls between preview and timeline, and the
                // bottom icon toolbar) were kept as plain reference
                // composables in
                // autogen/ui/reference/EditorControlsReference.kt for reuse
                // inside Auto Generator's own Timeline screen later —
                // everything else from the old Editor was deleted.
                AutoGenHomeScreen()
            }
        }
    }
}
