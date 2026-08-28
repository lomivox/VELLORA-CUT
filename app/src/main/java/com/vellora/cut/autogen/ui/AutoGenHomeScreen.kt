package com.vellora.cut.autogen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.ui.theme.*

/**
 * Entry screen for the Auto Generator project.
 * Phase A: placeholder only. Phase B will replace the center content with
 * the real "New Auto Project" form (name, voice-over upload, duration,
 * resolution) and a list of existing auto-gen projects.
 */
@Composable
fun AutoGenHomeScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp)
        ) {
            Text(text = "← Hub", color = TextSecondary, fontSize = 13.sp)
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "AUTO GENERATOR",
                color = CyanPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Phase B is next: project creation +\nbulk prompt paste screen",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}
