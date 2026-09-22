package com.vellora.cut.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.vellora.cut.autogen.ui.AutoGenHomeScreen
import com.vellora.cut.ui.theme.VelloraTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way —
            RenderKeepAliveService's foreground-service state itself still keeps the process
            alive even if this is denied; without it, only the notification's own content
            won't display. Nothing in the export/generation flow depends on this being granted. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ needs this granted for RenderKeepAliveService's
        // "export/generation ho rahi hai" notification to actually show —
        // asked once, upfront, so it's already granted by the time a long
        // export or Shorts Metadata generation runs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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
