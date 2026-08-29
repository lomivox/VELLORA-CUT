package com.vellora.cut.autogen.ui.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.ui.theme.*

/**
 * EXTRACTED UI REFERENCE — copied out of the old manual Editor
 * (`com.vellora.cut.ui.EditorScreen`) before its removal, per the decision
 * to fold editor-like features into the Auto Generator's own Timeline
 * screen instead of keeping a separate Editor project.
 *
 * These three composables are the ONLY pieces kept from the old Editor —
 * everything else (EditorScreen, EditorHomeScreen, HubScreen, TimelineView,
 * AiUhdSheet, the `timeline` package's clip-editing engine, NativeEngine, and the
 * native `cpp/` folder) has been deleted.
 *
 * They are pure, parameterized, presentation-only composables — no
 * dependency on ExoPlayer or the old segment/timeline data model, so they
 * carry no logic from the deleted system. NOT wired into any screen yet;
 * this file exists purely so the visual structure isn't lost before the
 * next integration phase.
 */

// ─────────────────────────────────────────────────────────────
// 1) TOP BAR — close button, search, and a trailing action slot
//    (originally held the AI UHD dropdown + Export button; those were
//    editor-specific and were dropped, but the slot is kept generic)
// ─────────────────────────────────────────────────────────────
@Composable
fun EditorTopBarReference(
    onClose: () -> Unit,
    onSearch: () -> Unit,
    trailingActions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(onClick = onClose) {
                Text("✕", color = TextPrimary, fontSize = 20.sp)
            }
            IconButton(onClick = onSearch) {
                Text("🔍", color = TextPrimary, fontSize = 18.sp)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = trailingActions
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 2) MIDDLE CONTROLS — sits between the preview and the timeline:
//    fullscreen toggle, play/pause, snap indicator, undo/redo
// ─────────────────────────────────────────────────────────────
@Composable
fun PreviewMiddleControlsReference(
    isPlaying: Boolean,
    snapEnabled: Boolean = true,
    onFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        IconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.CenterStart)) {
            Text("⛶", color = TextPrimary, fontSize = 18.sp)
        }
        Box(
            modifier = Modifier.align(Alignment.Center)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause
                )
                .padding(8.dp)
        ) {
            Text(if (isPlaying) "⏸" else "▶", color = TextPrimary, fontSize = 16.sp)
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⧉", color = TextSecondary, fontSize = 16.sp)
                Text(
                    if (snapEnabled) "ON" else "OFF",
                    color = CyanPrimary,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "↩", color = TextSecondary, fontSize = 20.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onUndo
                    )
            )
            Text(
                "↪", color = TextSecondary, fontSize = 20.sp,
                modifier = Modifier.padding(end = 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRedo
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 3) BOTTOM TOOLBAR — CapCut-style scrollable icon row.
//    Real vector icons (kept in res/drawable/ic_trim.xml etc.) —
//    those drawables were NOT deleted, only this Compose row that
//    displayed them.
// ─────────────────────────────────────────────────────────────
data class ToolbarAction(
    val iconRes: Int,
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun BottomToolbarReference(actions: List<ToolbarAction>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .horizontalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(vertical = 11.dp)
    ) {
        actions.forEach { action ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = action.onClick
                    )
            ) {
                Icon(
                    painter = painterResource(id = action.iconRes),
                    contentDescription = action.label,
                    tint = TextPrimary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(action.label, color = TextSecondary, fontSize = 10.sp)
            }
        }
    }
}
