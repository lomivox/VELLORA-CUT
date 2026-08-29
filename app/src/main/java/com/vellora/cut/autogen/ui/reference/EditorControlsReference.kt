package com.vellora.cut.autogen.ui.reference

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            IconButton(onClick = onClose) {
                Text("✕", color = TextPrimary, fontSize = 26.sp)
            }
            IconButton(onClick = onSearch) {
                Text("🔍", color = TextPrimary, fontSize = 22.sp)
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
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        IconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.CenterStart)) {
            Text("⛶", color = TextPrimary, fontSize = 24.sp)
        }
        Box(
            modifier = Modifier.align(Alignment.Center)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause
                )
                .padding(10.dp)
        ) {
            PlayPauseGlyph(isPlaying = isPlaying, tint = TextPrimary, size = 22.dp)
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⧉", color = TextSecondary, fontSize = 20.sp)
                Text(
                    if (snapEnabled) "ON" else "OFF",
                    color = CyanPrimary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "↩", color = TextSecondary, fontSize = 26.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onUndo
                    )
            )
            Text(
                "↪", color = TextSecondary, fontSize = 26.sp,
                modifier = Modifier.padding(end = 6.dp)
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

// Hand-drawn play/pause glyph (instead of the ▶/⏸ emoji characters) — some
// devices render those emoji with their own fixed colors (e.g. a yellow
// pause icon) that ignore the requested tint. Drawing it ourselves keeps
// both states in the exact same color.
@Composable
private fun PlayPauseGlyph(isPlaying: Boolean, tint: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        if (isPlaying) {
            val barWidth = this.size.width * 0.28f
            val barHeight = this.size.height * 0.82f
            val gap = this.size.width * 0.16f
            val top = (this.size.height - barHeight) / 2f
            drawRect(
                color = tint,
                topLeft = Offset(center.x - gap / 2f - barWidth, top),
                size = Size(barWidth, barHeight)
            )
            drawRect(
                color = tint,
                topLeft = Offset(center.x + gap / 2f, top),
                size = Size(barWidth, barHeight)
            )
        } else {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.24f, h * 0.12f)
                lineTo(w * 0.24f, h * 0.88f)
                lineTo(w * 0.86f, h * 0.5f)
                close()
            }
            drawPath(path, color = tint)
        }
    }
}

@Composable
fun BottomToolbarReference(actions: List<ToolbarAction>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .horizontalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(vertical = 14.dp)
    ) {
        actions.forEach { action ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
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
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(action.label, color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}
