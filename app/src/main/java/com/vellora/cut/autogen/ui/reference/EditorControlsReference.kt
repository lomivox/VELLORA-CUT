package com.vellora.cut.autogen.ui.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.R
import com.vellora.cut.ui.theme.*

/**
 * Copied from VELLORA-CUT's
 * `autogen/ui/reference/EditorControlsReference.kt` — same three pieces
 * extracted from the old manual Editor before its removal there. Paddings
 * and colors are untouched. Drawable strokeWidth 2 -> 2.6 in
 * res/drawable/ic_*.xml for a bolder look.
 *
 * Icon sizes are no longer fixed dp/sp values — each composable takes an
 * `iconSize: Dp` parameter that MainActivity computes from that bar's
 * actual on-screen height (screenHeightDp * bar's %), so icons scale
 * with the bar's real proportion of the screen on every device instead
 * of a fixed number.
 *
 * ROOT-CAUSE FIX (after repeated size/visibility bugs on Top Bar and
 * Middle Controls): those two composables used to draw their icons as
 * plain Unicode/emoji Text() characters (✕ 🔍 ⛶ ▶ ⏸ ⧉ ↩ ↪). Text-glyph
 * "icons" render inconsistently across devices/fonts/emoji sets — some
 * glyphs (like ▶) have a tiny visual shape inside their own font
 * metrics no matter how large the fontSize is, which is why the play
 * button kept disappearing regardless of sp/fontWeight tweaks. All of
 * those are now real vector drawables (ic_close, ic_search,
 * ic_fullscreen, ic_play, ic_pause, ic_split, ic_undo, ic_redo) drawn
 * with Icon()+Modifier.size(), exactly like the Bottom Toolbar already
 * did correctly from the start — same strokeWidth 2.6 style. This is
 * the only reliable way to control icon size/weight precisely.
 *
 * Also fixed: the Top Bar's own vertical padding (12dp -> 8dp) and the
 * AI UHD/Export pill's padding (6dp -> 4dp) and font size (12/13sp ->
 * 11sp), because the previous padding made the trailing pills taller
 * than the Top Bar's 6.8%-of-screen height, so they overflowed and got
 * visually cropped top and bottom.
 *
 * Nothing here is functional yet: all click callbacks are empty.
 */

// 1) TOP BAR
// iconSize is computed by the caller from this bar's actual height (see
// MainActivity: sectionHeightDp(6.8f) * fraction) so it scales with the
// bar's real percentage of the screen, instead of a fixed sp value.
@Composable
fun EditorTopBarReference(
    onClose: () -> Unit,
    onSearch: () -> Unit,
    iconSize: Dp = 20.dp,
    trailingActions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Close",
                    tint = TextPrimary,
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(onClick = onSearch) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = TextPrimary,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = trailingActions
        )
    }
}

// 2) MIDDLE CONTROLS (between preview and timeline)
// iconSize is computed by the caller from this bar's actual height (see
// MainActivity: sectionHeightDp(4.9f) * fraction).
@Composable
fun PreviewMiddleControlsReference(
    isPlaying: Boolean,
    snapEnabled: Boolean = true,
    onFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean = true,
    canRedo: Boolean = true,
    iconSize: Dp = 18.dp
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        IconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                painter = painterResource(id = R.drawable.ic_fullscreen),
                contentDescription = "Fullscreen",
                tint = TextPrimary,
                modifier = Modifier.size(iconSize)
            )
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
            Icon(
                painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = TextPrimary,
                modifier = Modifier.size(iconSize)
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_split),
                    contentDescription = "Snap",
                    tint = TextSecondary,
                    modifier = Modifier.size(iconSize * 0.9f)
                )
                Text(
                    if (snapEnabled) "ON" else "OFF",
                    color = CyanPrimary,
                    fontSize = 8.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.padding(horizontal = 2.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_undo),
                    contentDescription = "Undo",
                    tint = if (canUndo) TextSecondary else TextSecondary.copy(alpha = 0.35f),
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_redo),
                    contentDescription = "Redo",
                    tint = if (canRedo) TextSecondary else TextSecondary.copy(alpha = 0.35f),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

// 3) BOTTOM NAVIGATION TOOLBAR
data class ToolbarAction(
    val iconRes: Int,
    val label: String,
    val onClick: () -> Unit
)

// iconSize is computed by the caller from this bar's actual height (see
// MainActivity: sectionHeightDp(9.8f) * fraction) — leaves room below the
// icon for the label text and the bar's own vertical padding.
@Composable
fun BottomToolbarReference(
    actions: List<ToolbarAction>,
    iconSize: Dp = 20.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .horizontalScroll(rememberScrollState())
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
                    modifier = Modifier.size(iconSize)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(action.label, color = TextSecondary, fontSize = 10.sp)
            }
        }
    }
}
