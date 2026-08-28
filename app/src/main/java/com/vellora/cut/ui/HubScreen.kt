package com.vellora.cut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.ui.theme.*

/**
 * True app entry point. Shows the two independent projects that live inside
 * this app: the manual video Editor, and the Auto Generator pipeline.
 */
@Composable
fun HubScreen(
    onOpenEditor: () -> Unit,
    onOpenAutoGenerator: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "VELLORA",
                color = CyanPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose a project",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            HubProjectCard(
                emoji = "🎬",
                title = "VELLORA CUT",
                subtitle = "Manual video editor — cut, trim, timeline",
                onClick = onOpenEditor
            )

            Spacer(modifier = Modifier.height(20.dp))

            HubProjectCard(
                emoji = "⚙️",
                title = "Auto Generator",
                subtitle = "Prompts → AI images → video → upload",
                onClick = onOpenAutoGenerator
            )
        }
    }
}

@Composable
private fun HubProjectCard(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 30.sp)

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        Text(text = "›", color = CyanPrimary, fontSize = 22.sp)
    }
}
