package com.vellora.cut.autogen.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller

/**
 * Draws BOTH timeline tracks — video/image ([Timeline.clips]) and audio
 * ([Timeline.audioClips]) — onto a Canvas. Only clips intersecting the
 * current viewport are drawn — never one Android View per clip.
 *
 * Phase 3: the playhead is a FIXED vertical line at the horizontal center
 * of the view at all times; timeline content scrolls right-to-left
 * underneath it. [viewport]'s scroll offset is derived from [playheadMs]
 * every frame so that `msToPx(playheadMs) == width/2` always holds.
 *
 * Phase 4: tap a clip (on either track) to select it; long-press to
 * request its deletion (via [onClipLongPress] — the host performs the
 * actual delete + ripple, since that's a Timeline-model operation, not a
 * view concern). Split is NOT done here either — the host (MainActivity)
 * does it using the exact same [playheadMs] this view already draws and
 * reports, which is the point: there is only ONE variable for "current
 * time" anywhere in this app, used for drawing, audio sync, AND editing
 * math. Both tracks share this same logic — a clip's row (video vs
 * audio) is decided purely by which list ([timeline].clips or
 * [timeline].audioClips) it's in, and by the Y-coordinate of a touch.
 *
 * Selection style (both tracks, identical): an UNSELECTED clip has no
 * border at all — just its fill. A SELECTED clip gets a full white
 * rectangle border (all four sides), not a partial one. All clip corners
 * are square, everywhere, on both tracks — no rounded corners.
 *
 * Touch: single-finger horizontal drag scrubs (moves the playhead).
 * Two-finger pinch zooms (changes pixelsPerMs). A tap selects a clip; a
 * plain tap does NOT pause playback (only an actual drag does, via
 * [onScrubStart], fired on the first real movement — not on every touch
 * down, so tapping to select a clip during playback doesn't stop it).
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var timeline: Timeline = Timeline()
        set(value) {
            field = value
            // Drop density bookkeeping for clips that no longer exist —
            // otherwise this map would grow forever across a long
            // session's worth of split/trim/delete. See
            // requestThumbnailsIfNeeded below.
            val liveIds = (value.clips + value.audioClips).mapTo(HashSet()) { it.id }
            lastRequestedFrameCount.keys.retainAll(liveIds)
            invalidate()
        }

    /** Current playback position, in ms. Always drawn centered — see class doc. */
    var playheadMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    /** Currently selected clip's id, or null. Works for either track —
     * ids are unique across both (UUIDs), so one id unambiguously
     * identifies a clip in [Timeline.clips] OR [Timeline.audioClips].
     * Settable by the host too (e.g. to clear selection after a
     * split/delete). */
    var selectedClipId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Called continuously while the user drags, with the new playhead position. */
    var onScrub: ((Long) -> Unit)? = null

    /** Called once when an actual drag begins (not on a plain tap) — hosts should pause playback here. */
    var onScrubStart: (() -> Unit)? = null

    /** Called when the user taps a clip (id) or taps empty space (null). */
    var onClipSelected: ((String?) -> Unit)? = null

    /** Called when the user long-presses a clip — host should confirm/perform delete + ripple. */
    var onClipLongPress: ((String) -> Unit)? = null

    /** Called continuously while dragging the selected clip's LEFT trim
     * handle, with the proposed new boundary time (ms). Host validates/
     * clamps and applies it — see MainActivity's trimClipEdge(). */
    var onTrimLeft: ((clipId: String, newTimeMs: Long) -> Unit)? = null

    /** Same as [onTrimLeft] but for the RIGHT trim handle. */
    var onTrimRight: ((clipId: String, newTimeMs: Long) -> Unit)? = null

    /** Fired once, when the user releases a drag that moved the SELECTED
     * clip's body (not a trim handle) by a nonzero amount. [offsetMs] is
     * signed: positive = dragged later in time, negative = dragged
     * earlier. The host (MainActivity) turns this into a REORDER — since
     * both tracks are always contiguous (ripple keeps clips back-to-back,
     * see TimelineClip/rippleClips), there's no such thing as a free
     * on-timeline position to drop into; dragging a clip far enough past
     * a neighbor swaps their order, then the whole track re-ripples. See
     * MainActivity.reorderClip(). Never fired for a plain tap (offset
     * below a small pixel threshold is treated as "didn't move"). */
    var onClipMoved: ((clipId: String, offsetMs: Long) -> Unit)? = null

    /** Called when the user taps the small square icon at the boundary
     * between two adjacent VIDEO clips — host will eventually open a
     * transition picker here; for now it just confirms the tap. (Audio
     * clip joins don't have this yet — out of scope for this pass.) */
    var onJoinTapped: ((leftClipId: String, rightClipId: String) -> Unit)? = null

    /** Real decoded amplitude buckets (0f..1f each) for the picked audio
     * FILE, evenly spread across [audioDurationMs] — i.e. indexed by
     * absolute position in the ORIGINAL file, not by timeline position.
     * Each audio clip in [Timeline.audioClips] reads its own slice of
     * this array using its own sourceOffsetMs, so the waveform drawn
     * inside a clip is always the correct segment even after that clip
     * has been split, trimmed, or moved on the timeline. */
    var waveform: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    /** Total duration of the ORIGINAL audio file the waveform buckets
     * are spread across (NOT the sum of audio clips on the timeline). */
    var audioDurationMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    /** Cached filmstrip frames per VIDEO clip id (see
     * BitmapLoader.loadThumbnails, which the host generates and keeps
     * this map filled). Missing/empty entry just means "not decoded
     * yet" — that clip draws its plain fill + label as before; the
     * filmstrip appears the moment the host supplies it, no separate
     * signal needed since setting this property already triggers
     * [invalidate]. */
    var clipThumbnails: Map<String, List<Bitmap>> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    /** Fired when a visible VIDEO clip's filmstrip should be decoded (or
     * re-decoded denser) at [desiredCount] frames. The host
     * (MainActivity) does the actual work via BitmapLoader and feeds
     * results back through [clipThumbnails] — this view only decides
     * WHEN and AT WHAT DENSITY, from the clip's REAL on-screen pixel
     * width right now (see [desiredFrameCountFor]), instead of a fixed
     * frames-per-second regardless of zoom. See [requestThumbnailsIfNeeded]
     * for the debouncing that keeps this from firing on every tick of a
     * pinch-zoom gesture. */
    var onThumbnailsNeeded: ((clipId: String, desiredCount: Int) -> Unit)? = null

    /** Last frame count actually requested for each clip id — lets
     * [requestThumbnailsIfNeeded] tell "just redrawing at an unchanged
     * zoom" apart from "zoomed in enough that this clip deserves a
     * denser filmstrip now". Pruned in [timeline]'s setter above when a
     * clip stops existing. */
    private val lastRequestedFrameCount = mutableMapOf<String, Int>()

    /** How many filmstrip frames [clip] is worth right now: its own
     * on-screen width at the CURRENT zoom, divided into
     * [minFilmstripTileWidthPx]-wide tiles — the same tile width
     * [drawFilmstrip] uses to decide how many of the decoded frames it
     * can actually fit. A clip zoomed down to 40px on screen has no use
     * for anywhere near the ~28-frames-per-second-of-clip a full editor
     * would decode; a clip zoomed in to fill the whole screen deserves
     * far more than that. Floor of 4 keeps a bare minimum filmstrip feel
     * even for a sliver of a clip; ceiling matches loadThumbnails' own
     * [BitmapLoader]-side safety cap. */
    private fun desiredFrameCountFor(clip: TimelineClip): Int {
        val widthPx = clip.durationMs * viewport.pixelsPerMs
        return (widthPx / minFilmstripTileWidthPx).toInt().coerceIn(4, 300)
    }

    /** Asks the host for [clip]'s filmstrip if it either has none yet,
     * or the current zoom now warrants meaningfully MORE frames than
     * were last requested for it (>25% more) — a plain re-draw at an
     * unchanged (or lesser) zoom never re-fires this, so a pinch-zoom
     * gesture doesn't kick off a fresh decode on every single tick. */
    private fun requestThumbnailsIfNeeded(clip: TimelineClip) {
        if (!clip.isVideo || clip.sourceUri == null) return
        val desired = desiredFrameCountFor(clip)
        val last = lastRequestedFrameCount[clip.id]
        if (last == null || desired > last * 1.25f) {
            lastRequestedFrameCount[clip.id] = desired
            onThumbnailsNeeded?.invoke(clip.id, desired)
        }
    }

    val viewport = TimelineViewport()

    private val rulerHeightPx = 48f   // dedicated band for tick marks + time labels
    private val trackTopPx = rulerHeightPx + 8f

    /** Video/image track height — set from outside using the blueprint's
     * measured proportion (149px of a 2436px-tall reference screen ≈
     * 6.12% of screen height). Falls back to a reasonable default if the
     * host never sets it. */
    var trackHeightPx: Float = 120f
        set(value) {
            field = value
            invalidate()
        }

    /** Audio track height — set from outside using the blueprint's
     * measured proportion (90px of 2436px ≈ 3.69% of screen height, i.e.
     * video-track-height / 1.656). */
    var waveformHeightPx: Float = 60f
        set(value) {
            field = value
            invalidate()
        }

    private val audioTrackTopPx: Float
        get() = trackTopPx + trackHeightPx + 10f

    /** Every clip corner is square — no rounded corners anywhere, on
     * either track. Kept as a named constant (rather than removing the
     * radius parameter entirely) so it's obvious this is a deliberate
     * choice, not a leftover default, if it ever needs revisiting. */
    private val cornerRadiusPx = 0f

    private val minPixelsPerMs = 0.01f  // zoomed out: ~10px/sec
    private val maxPixelsPerMs = 0.5f   // zoomed in: ~500px/sec

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#0A0A0A") // BackgroundDark
    }

    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#161616") // SurfaceDark
        style = Paint.Style.FILL
    }

    /** Full rectangle border, all four sides, drawn ONLY on the selected
     * clip (either track) — unselected clips get no border at all. */
    private val selectedClipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // TextPrimary
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.MITER // sharp square corners, matches the no-rounded-corners rule
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // TextPrimary
        textSize = 28f
    }

    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888") // TextSecondary
        strokeWidth = 1.5f
    }

    private val rulerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888") // TextSecondary
        textSize = 22f
    }

    // Per-frame sub-ticks (1..20 within each second) — only drawn when
    // zoomed in enough that they'd actually be readable. Deliberately
    // dimmer/smaller than the whole-second ruler ticks/labels above so
    // the two never compete for attention — the second ticks are always
    // the primary read, frame numbers are a zoomed-in detail on top.
    private val frameTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        strokeWidth = 1f
    }
    private val frameLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        textSize = 14f
    }

    // "00:01 / 00:44"-style readout — current time bold/white, total
    // time dim gray, sitting to the LEFT of the ruler at a fixed screen
    // position (it does NOT scroll — only the ruler's own tick labels
    // do). This is new; it wasn't part of the ruler when that was built.
    private val timeDisplayCurrentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // TextPrimary
        textSize = 26f
        isFakeBoldText = true
    }
    private val timeDisplayTotalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888") // TextSecondary
        textSize = 26f
    }
    private val timeDisplayBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0A0A") // BackgroundDark — hides ruler ticks behind it
        style = Paint.Style.FILL
    }

    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // TextPrimary
        strokeWidth = 3f
    }

    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C8C8") // CyanPrimary
        strokeWidth = 2f
        alpha = 200
    }

    /** The trim handle's outer box — solid white, square corners, spans
     * the FULL height of whichever row (video or audio track) the
     * selected clip is on. Confirmed against the reference photo
     * (1000507289.jpg): a plain white box, not a thin pill. */
    private val trimHandleBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }

    /** The small light-gray pill-shaped grip drawn dead-center inside
     * the white box (both axes) — this is the only part that visually
     * reads as "grab me". Matches the reference photo's grip color
     * (a light gray, not pure white, so it's visible against the white
     * box behind it). */
    private val trimHandleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9C9C9")
        style = Paint.Style.FILL
    }

    private val joinIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C8C8") // CyanPrimary
        style = Paint.Style.FILL
    }
    // Exact measurement from the reference blueprint (not a guessed
    // proportion of clip height anymore).
    private val joinIconWidthPx = 59f
    private val joinIconHeightPx = 60f

    // Blueprint (1000506608_blueprint.json): outer white box is 63 x 149
    // against a 2436px-tall reference screen — i.e. a width:height ratio
    // of 63/149. Applied here as a RATIO of the timeline's own combined
    // (video+audio) height, rather than a fixed px, so it stays the
    // correct shape on any device. The inner gray grip ratios were
    // halved from the first confirmed demo (26/63 wide, 140/149 tall)
    // per feedback against the real on-device screenshot — the grip was
    // reading too large relative to the white box around it.
    private val trimHandleBoxWidthToHeightRatio = 63f / 149f
    private val trimHandleGripWidthToBoxWidthRatio = 13f / 63f
    private val trimHandleGripHeightToBoxHeightRatio = 70f / 149f

    /** Matches BitmapLoader.loadThumbnails' real decode rate — the ruler's
     * per-frame sub-ticks (see drawFrameMarkers) count in the same units
     * the filmstrip was actually decoded at, so "frame 14" on the ruler
     * really does correspond to the 14th decoded filmstrip frame. */
    private val framesPerSecondForRuler = 28

    // ── Touch handling ──────────────────────────────────────────
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newZoom = (viewport.pixelsPerMs * detector.scaleFactor)
                    .coerceIn(minPixelsPerMs, maxPixelsPerMs)
                viewport.pixelsPerMs = newZoom
                invalidate()
                return true
            }
        }
    )

    /** True once an actual drag (not just a down/tap) has started this gesture. */
    private var dragStarted = false

    /** Drives the "flick and let go, it keeps scrolling and eases to a
     * stop" momentum feel — a plain drag (onScroll) has no notion of
     * momentum by itself, it only tracks the finger while it's actually
     * down. [onFling] hands the release velocity to this, and
     * [computeScroll] (called every frame by the View system while it's
     * still decelerating) turns that into playhead motion the same way
     * a manual drag does. */
    private val scroller = OverScroller(context)

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                dragStarted = false
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val maxMs = timeline.totalDurationMs.coerceAtLeast(0L)
                val startPx = (playheadMs * viewport.pixelsPerMs).toInt()
                val maxPx = (maxMs * viewport.pixelsPerMs).toInt()
                scroller.forceFinished(true)
                // Sign flip: onScroll's distanceX is positive when the
                // finger moves LEFT (which is what makes time move
                // FORWARD — see onScroll below), but GestureDetector's
                // velocityX is positive when the finger moves RIGHT — the
                // opposite convention. Negate it so a fast left-swipe
                // (the "send it forward" flick) produces positive
                // px-space velocity here, same direction as forward time.
                scroller.fling(
                    startPx, 0,
                    (-velocityX).toInt(), 0,
                    0, maxPx,
                    0, 0
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!dragStarted) {
                    dragStarted = true
                    onScrubStart?.invoke()
                }
                // distanceX > 0 means the finger moved LEFT — that reveals
                // later content, so time should move forward.
                val deltaMs = (distanceX / viewport.pixelsPerMs).toLong()
                val maxMs = timeline.totalDurationMs
                val newMs = (playheadMs + deltaMs).coerceIn(0L, maxMs.coerceAtLeast(0L))
                playheadMs = newMs
                onScrub?.invoke(newMs)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val join = hitTestJoin(e.x, e.y)
                if (join != null) {
                    onJoinTapped?.invoke(join.first.id, join.second.id)
                    return true
                }
                val tapped = hitTestClip(e.x, e.y)
                selectedClipId = tapped?.id
                onClipSelected?.invoke(selectedClipId)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val tapped = hitTestClip(e.x, e.y) ?: return
                selectedClipId = tapped.id
                onClipSelected?.invoke(tapped.id)
                onClipLongPress?.invoke(tapped.id)
            }
        }
    )

    /** Same left/right math as [drawClip] — the one place that decides
     * clip screen bounds, used for both drawing and hit-testing so they
     * can never disagree. Checks the VIDEO row first, then the AUDIO
     * row, based on the touch's Y — a clip in either track can be hit,
     * never both (the rows don't overlap vertically). */
    private fun hitTestClip(x: Float, y: Float): TimelineClip? {
        return when {
            y in trackTopPx..(trackTopPx + trackHeightPx) ->
                timeline.clips.firstOrNull { clip ->
                    x in viewport.msToPx(clip.startMs)..viewport.msToPx(clip.endMs)
                }
            y in audioTrackTopPx..(audioTrackTopPx + waveformHeightPx) ->
                timeline.audioClips.firstOrNull { clip ->
                    x in viewport.msToPx(clip.startMs)..viewport.msToPx(clip.endMs)
                }
            else -> null
        }
    }

    /** Which edge (if any) of the SELECTED clip is currently being
     * dragged via its trim handle. Null means no trim is in progress —
     * touch falls through to normal scrub/pinch/tap handling below. */
    private enum class TrimEdge { LEFT, RIGHT }
    private var activeTrimEdge: TrimEdge? = null

    /** Half-width of the touch hit area around each trim handle. The
     * visible white box's width is derived from THAT track's own row
     * height (see [trimHandleBoxWidthToHeightRatio] and
     * [drawTrimHandles] — each track's handle is sized to its own row,
     * never combined with the other track), so the hit area follows
     * suit — floored at 44px so it's never smaller than a comfortable
     * finger target even on a very short row. */
    private fun handleTouchPaddingPx(rowHeight: Float): Float =
        (rowHeight * trimHandleBoxWidthToHeightRatio / 2f).coerceAtLeast(44f)

    // ── Move (drag-to-reorder) ──────────────────────────────────
    /** Id of the clip a move-drag is currently in progress on, or null
     * when no move is active. Set on ACTION_DOWN when the touch lands
     * inside the SELECTED clip's body (not on a trim handle); cleared on
     * release. */
    private var moveClipId: String? = null
    private var moveDownX = 0f
    /** Live horizontal drag distance in px for the in-progress move, used
     * ONLY to draw the dragged clip's ghost at [onDraw] time — the real
     * edit only happens once, on release (see [onClipMoved]). */
    private var moveOffsetPx = 0f
    /** Below this many px of drag, a release is treated as "didn't
     * move" (a plain tap on an already-selected clip) rather than firing
     * [onClipMoved] — avoids reordering from finger jitter. */
    private val moveCommitThresholdPx = 6f

    /** Snaps [proposedMs] to the nearest OTHER clip boundary (start/end,
     * on the SAME track as [excludeClipId]) or to the current playhead,
     * if one is within [snapThresholdPx] screen pixels — magnetic
     * snapping for trim handles, matching CapCut's timeline feel. Falls
     * through to [proposedMs] unchanged when nothing is close enough. */
    private val snapThresholdPx = 18f
    private fun snappedTimeMs(proposedMs: Long, excludeClipId: String?): Long {
        val onVideoTrack = timeline.clips.any { it.id == excludeClipId }
        val track = if (onVideoTrack) timeline.clips else timeline.audioClips
        val candidates = ArrayList<Long>(track.size * 2 + 1)
        for (c in track) {
            if (c.id == excludeClipId) continue
            candidates.add(c.startMs)
            candidates.add(c.endMs)
        }
        candidates.add(playheadMs)
        val proposedPx = viewport.msToPx(proposedMs)
        val closest = candidates.minByOrNull { kotlin.math.abs(viewport.msToPx(it) - proposedPx) }
            ?: return proposedMs
        return if (kotlin.math.abs(viewport.msToPx(closest) - proposedPx) <= snapThresholdPx) closest else proposedMs
    }

    /** Finds the selected clip in EITHER track and returns its on-screen
     * rect using that track's own top/height — so trim handles and the
     * selection border land in the right row regardless of which track
     * the selected clip belongs to. */
    private fun selectedClipRect(): RectF? {
        timeline.clips.firstOrNull { it.id == selectedClipId }?.let { clip ->
            return RectF(
                viewport.msToPx(clip.startMs), trackTopPx,
                viewport.msToPx(clip.endMs), trackTopPx + trackHeightPx
            )
        }
        timeline.audioClips.firstOrNull { it.id == selectedClipId }?.let { clip ->
            return RectF(
                viewport.msToPx(clip.startMs), audioTrackTopPx,
                viewport.msToPx(clip.endMs), audioTrackTopPx + waveformHeightPx
            )
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A fresh touch always stops any momentum still playing
                // out from a previous flick — matching how every other
                // scrollable view behaves (touching it grabs it, it
                // doesn't keep sliding underneath your finger).
                scroller.forceFinished(true)
                val rect = selectedClipRect()
                activeTrimEdge = if (rect != null && event.y in rect.top..rect.bottom) {
                    val padding = handleTouchPaddingPx(rect.height())
                    when {
                        kotlin.math.abs(event.x - rect.left) <= padding -> TrimEdge.LEFT
                        kotlin.math.abs(event.x - rect.right) <= padding -> TrimEdge.RIGHT
                        else -> null
                    }
                } else {
                    null
                }
                if (activeTrimEdge != null) {
                    onScrubStart?.invoke() // trimming should pause playback too
                    return true // claim the gesture now — don't let scrub/pinch see it
                }

                // No trim handle hit — if the touch still landed inside the
                // SELECTED clip's body, this is a move-candidate. Claimed
                // immediately (same as trim above) since a plain tap here
                // has no effect anyway (the clip is already selected).
                moveClipId = if (rect != null && event.x in rect.left..rect.right && event.y in rect.top..rect.bottom) {
                    moveDownX = event.x
                    moveOffsetPx = 0f
                    onScrubStart?.invoke() // a move should pause playback too
                    selectedClipId
                } else {
                    null
                }
                if (moveClipId != null) return true
            }

            MotionEvent.ACTION_MOVE -> {
                val edge = activeTrimEdge
                val clipId = selectedClipId
                if (edge != null && clipId != null) {
                    val newTimeMs = snappedTimeMs(viewport.pxToMs(event.x), clipId)
                    if (edge == TrimEdge.LEFT) onTrimLeft?.invoke(clipId, newTimeMs)
                    else onTrimRight?.invoke(clipId, newTimeMs)
                    return true
                }

                if (moveClipId != null) {
                    moveOffsetPx = event.x - moveDownX
                    invalidate() // redraw the dragged clip's ghost at its new offset
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeTrimEdge != null) {
                    activeTrimEdge = null
                    return true
                }
                val movingId = moveClipId
                if (movingId != null) {
                    if (kotlin.math.abs(moveOffsetPx) > moveCommitThresholdPx) {
                        val offsetMs = (moveOffsetPx / viewport.pixelsPerMs).toLong()
                        onClipMoved?.invoke(movingId, offsetMs)
                    }
                    moveClipId = null
                    moveOffsetPx = 0f
                    invalidate()
                    return true
                }
            }
        }

        // No trim in progress — fall through to the normal gestures.
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    /** Called automatically by the View system on every frame while
     * [scroller] is still decelerating after a fling — advances the
     * playhead to match, exactly like a manual drag does, then asks for
     * another frame until the deceleration finishes on its own. */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val maxMs = timeline.totalDurationMs.coerceAtLeast(0L)
            val newMs = (scroller.currX / viewport.pixelsPerMs).toLong().coerceIn(0L, maxMs)
            playheadMs = newMs
            onScrub?.invoke(newMs)
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        // The playhead is always at centerX — solve for the scroll offset
        // that makes that true, instead of the other way around.
        viewport.scrollOffsetPx = playheadMs * viewport.pixelsPerMs - centerX

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawSecondMarkers(canvas)
        drawFrameMarkers(canvas)
        drawTimeDisplay(canvas)

        for (clip in timeline.clips) {
            if (!viewport.isVisible(clip.startMs, clip.endMs, width)) continue
            requestThumbnailsIfNeeded(clip) // only visible clips ever need a filmstrip
            val dragPx = if (clip.id == moveClipId) moveOffsetPx else 0f
            drawClip(canvas, clip, trackTopPx, trackHeightPx, isAudio = false, dragOffsetPx = dragPx)
        }

        for (audioClip in timeline.audioClips) {
            if (!viewport.isVisible(audioClip.startMs, audioClip.endMs, width)) continue
            val dragPx = if (audioClip.id == moveClipId) moveOffsetPx else 0f
            drawClip(canvas, audioClip, audioTrackTopPx, waveformHeightPx, isAudio = true, dragOffsetPx = dragPx)
        }

        // Hidden while a move-drag is in progress — the handles sit at
        // the clip's committed (un-shifted) position and would visually
        // detach from the ghost being dragged above.
        if (moveClipId == null) {
            selectedClipRect()?.let { rect ->
                timeline.clips.firstOrNull { it.id == selectedClipId }?.let { drawTrimHandles(canvas, rect) }
                timeline.audioClips.firstOrNull { it.id == selectedClipId }?.let { drawTrimHandles(canvas, rect) }
            }
        }
        drawJoinIcons(canvas)

        canvas.drawLine(centerX, 0f, centerX, height.toFloat(), playheadPaint)
    }

    /** Small square icon at the boundary between every two adjacent
     * VIDEO clips — tap opens a transition picker (host-side, not built
     * yet). Only drawn where a boundary is actually on-screen. Audio
     * clip boundaries don't have this yet — out of scope for this pass. */
    private fun drawJoinIcons(canvas: Canvas) {
        val clips = timeline.clips.sortedBy { it.startMs }
        if (clips.size < 2) return
        val midY = trackTopPx + trackHeightPx / 2f
        for (i in 0 until clips.size - 1) {
            val boundaryMs = clips[i].endMs // == clips[i + 1].startMs (no-gap invariant)
            val x = viewport.msToPx(boundaryMs)
            if (x < -joinIconWidthPx || x > width + joinIconWidthPx) continue
            val rect = RectF(
                x - joinIconWidthPx / 2f, midY - joinIconHeightPx / 2f,
                x + joinIconWidthPx / 2f, midY + joinIconHeightPx / 2f
            )
            canvas.drawRect(rect, joinIconPaint) // square corners, per the no-rounded-corners rule
        }
    }

    /** Hit-tests a tap against every clip-boundary join icon. Generous
     * vertical tolerance since the icon is small. */
    private fun hitTestJoin(x: Float, y: Float): Pair<TimelineClip, TimelineClip>? {
        val midY = trackTopPx + trackHeightPx / 2f
        if (y < midY - joinIconHeightPx / 2f || y > midY + joinIconHeightPx / 2f) return null
        val clips = timeline.clips.sortedBy { it.startMs }
        for (i in 0 until clips.size - 1) {
            val boundaryX = viewport.msToPx(clips[i].endMs)
            if (kotlin.math.abs(x - boundaryX) <= joinIconWidthPx / 2f) {
                return clips[i] to clips[i + 1]
            }
        }
        return null
    }

    /** White box + centered gray grip handles straddling both edges of
     * the given rect — draggable to trim (resize) that edge. Works for
     * either track's selected clip since the caller passes the
     * already-correct rect (see [selectedClipRect]).
     *
     * CORRECTED per explicit follow-up: the white box's height must be
     * EXACTLY that track's OWN row height — not smaller, not bigger,
     * and NOT the two tracks combined (that combined version was a
     * misread of the earlier instruction and has been reverted). Video
     * clips get a handle sized to the video row; audio clips get a
     * handle sized to the audio row — each track always has its own,
     * separately-sized handle. The box's width is derived from that
     * row's own height using the blueprint's 63:149 ratio. The gray
     * grip inside is centered on both axes, at half the width/height
     * ratio confirmed against the on-device screenshot. */
    private fun drawTrimHandles(canvas: Canvas, rect: RectF) {
        val boxHeight = rect.height()
        val boxWidth = boxHeight * trimHandleBoxWidthToHeightRatio
        val boxTop = rect.top
        val boxBottom = rect.bottom
        val boxCenterY = (boxTop + boxBottom) / 2f

        val leftBox = RectF(rect.left - boxWidth / 2f, boxTop, rect.left + boxWidth / 2f, boxBottom)
        val rightBox = RectF(rect.right - boxWidth / 2f, boxTop, rect.right + boxWidth / 2f, boxBottom)

        canvas.drawRect(leftBox, trimHandleBoxPaint) // square corners — no drawRoundRect
        canvas.drawRect(rightBox, trimHandleBoxPaint)

        val gripWidth = boxWidth * trimHandleGripWidthToBoxWidthRatio
        val gripHeight = boxHeight * trimHandleGripHeightToBoxHeightRatio
        val gripCornerRadius = gripWidth / 2f // pill: fully rounded ends

        val leftGrip = RectF(rect.left - gripWidth / 2f, boxCenterY - gripHeight / 2f, rect.left + gripWidth / 2f, boxCenterY + gripHeight / 2f)
        val rightGrip = RectF(rect.right - gripWidth / 2f, boxCenterY - gripHeight / 2f, rect.right + gripWidth / 2f, boxCenterY + gripHeight / 2f)

        canvas.drawRoundRect(leftGrip, gripCornerRadius, gripCornerRadius, trimHandleGripPaint)
        canvas.drawRoundRect(rightGrip, gripCornerRadius, gripCornerRadius, trimHandleGripPaint)
    }

    /**
     * Draws the ruler: tick marks + "Ns" / "M:SS" time labels. The step
     * between labeled ticks adapts to the current zoom (pixelsPerMs) so
     * labels never overlap — zoomed out shows every 5s or 10s, zoomed in
     * shows every 1s.
     */
    private fun drawSecondMarkers(canvas: Canvas) {
        val pixelsPerSecond = viewport.pixelsPerMs * 1000f
        val stepSeconds = when {
            pixelsPerSecond >= 80f -> 1
            pixelsPerSecond >= 30f -> 2
            pixelsPerSecond >= 12f -> 5
            else -> 10
        }
        val stepMs = stepSeconds * 1000L

        val visibleStartMs = viewport.pxToMs(0f)
        val visibleEndMs = viewport.pxToMs(width.toFloat())
        var t = (visibleStartMs / stepMs) * stepMs
        if (t < 0) t = 0

        while (t <= visibleEndMs) {
            val x = viewport.msToPx(t)
            canvas.drawLine(x, rulerHeightPx - 14f, x, rulerHeightPx, rulerPaint)
            canvas.drawText(formatRulerLabel(t), x + 6f, rulerHeightPx - 18f, rulerLabelPaint)
            t += stepMs
        }

        // baseline under the ruler band, separating it from the clip track
        canvas.drawLine(0f, rulerHeightPx, width.toFloat(), rulerHeightPx, rulerPaint)
    }

    /**
     * Sub-ticks WITHIN each second, numbered 1..[framesPerSecondForRuler]
     * (28, matching the filmstrip's real decode rate — see
     * BitmapLoader.loadThumbnails), so scrubbing frame-by-frame at high
     * zoom has an actual frame count to read, not just seconds.
     *
     * CORRECTED (per explicit follow-up — the earlier even/odd
     * label-vs-dot scheme was NOT what was asked for): this is now a
     * direct, literal mirror of [drawSecondMarkers] — same thresholds
     * (80/30/12), same steps (1/2/5/10), just applied to [pxPerFrame]
     * instead of pixelsPerSecond. The ask was specifically that
     * frame-to-frame spacing scale with zoom EXACTLY the way
     * second-to-second spacing already does — not a separate,
     * frame-specific scheme (no dots, no "Nf" suffix, no 4/7/14 steps).
     * Also, like [drawSecondMarkers], there's no cutoff that stops
     * drawing at very low zoom — it always attempts a tick, same as
     * seconds do.
     *
     * The tick landing exactly on a whole second (frame 1) is always
     * skipped — [drawSecondMarkers] already draws that one, taller and
     * brighter.
     */
    private fun drawFrameMarkers(canvas: Canvas) {
        val pixelsPerSecond = viewport.pixelsPerMs * 1000f
        val pxPerFrame = pixelsPerSecond / framesPerSecondForRuler
        val stepFrames = when {
            pxPerFrame >= 80f -> 1
            pxPerFrame >= 30f -> 2
            pxPerFrame >= 12f -> 5
            else -> 10
        }

        val frameDurationMs = 1000.0 / framesPerSecondForRuler
        val visibleStartMs = viewport.pxToMs(0f)
        val visibleEndMs = viewport.pxToMs(width.toFloat())

        var frameIndex = kotlin.math.floor(visibleStartMs / frameDurationMs).toLong().coerceAtLeast(0L)
        // Align to the step grid so ticks land on the same frame numbers
        // regardless of scroll position, same reasoning as
        // drawSecondMarkers aligning `t` to stepMs.
        frameIndex -= frameIndex % stepFrames
        while (true) {
            val t = (frameIndex * frameDurationMs).toLong()
            if (t > visibleEndMs) break
            val frameNumberWithinSecond = (frameIndex % framesPerSecondForRuler).toInt() + 1 // 1..28
            if (frameNumberWithinSecond != 1) { // frame 1 == the whole-second tick, already drawn
                val x = viewport.msToPx(t)
                canvas.drawLine(x, rulerHeightPx - 8f, x, rulerHeightPx, frameTickPaint)
                canvas.drawText(frameNumberWithinSecond.toString(), x + 2f, rulerHeightPx - 1f, frameLabelPaint)
            }
            frameIndex += stepFrames
        }
    }

    private fun formatRulerLabel(ms: Long): String {
        val totalSec = ms / 1000
        return if (totalSec < 60) {
            "${totalSec}s"
        } else {
            val min = totalSec / 60
            val sec = totalSec % 60
            "%d:%02d".format(min, sec)
        }
    }

    /** "00:01 / 00:44" — current position (bold white) / total duration
     * (dim gray). Drawn at a FIXED screen position to the left of the
     * ruler — unlike the ruler's own tick labels, this never scrolls, so
     * it's always readable regardless of zoom or scroll position. Its
     * own opaque background covers whatever ruler ticks would otherwise
     * be directly behind it. */
    private fun drawTimeDisplay(canvas: Canvas) {
        val currentText = formatTimeMMSS(playheadMs)
        val totalText = formatTimeMMSS(timeline.totalDurationMs)
        val separator = " / "

        val currentWidth = timeDisplayCurrentPaint.measureText(currentText)
        val separatorWidth = timeDisplayTotalPaint.measureText(separator)
        val totalWidth = timeDisplayTotalPaint.measureText(totalText)

        val leftX = 12f
        val baselineY = rulerHeightPx - 16f

        canvas.drawRect(
            0f, 0f, leftX + currentWidth + separatorWidth + totalWidth + 12f, rulerHeightPx,
            timeDisplayBackgroundPaint
        )

        var x = leftX
        canvas.drawText(currentText, x, baselineY, timeDisplayCurrentPaint)
        x += currentWidth
        canvas.drawText(separator, x, baselineY, timeDisplayTotalPaint)
        x += separatorWidth
        canvas.drawText(totalText, x, baselineY, timeDisplayTotalPaint)
    }

    /** Always "MM:SS" with leading zeros (e.g. "00:01"), unlike
     * [formatRulerLabel] which drops the minutes under 60s ("1s") — the
     * fixed time readout should always show the full MM:SS shape. */
    private fun formatTimeMMSS(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    /** Draws ONE clip on either track: square-cornered fill, a full
     * white rectangle border ONLY if it's the selected clip (nothing at
     * all otherwise), its label, and — for audio clips only — the
     * correct slice of the decoded waveform clipped inside its own box.
     */
    private fun drawClip(canvas: Canvas, clip: TimelineClip, top: Float, rowHeight: Float, isAudio: Boolean, dragOffsetPx: Float = 0f) {
        val left = viewport.msToPx(clip.startMs) + dragOffsetPx
        val right = viewport.msToPx(clip.endMs) + dragOffsetPx
        val rect = RectF(left, top, right, top + rowHeight)

        // Being dragged right now — drawn semi-transparent so it reads as
        // a "ghost" preview rather than the clip's real, committed
        // position (which doesn't change until release; see onClipMoved).
        val wasFillAlpha = clipPaint.alpha
        if (dragOffsetPx != 0f) clipPaint.alpha = 160
        canvas.drawRect(rect, clipPaint) // square corners everywhere — no drawRoundRect
        clipPaint.alpha = wasFillAlpha

        canvas.save()
        canvas.clipRect(rect)

        if (isAudio) {
            drawWaveformSlice(canvas, clip, rect)
            canvas.drawText(clip.label, left + 12f, top + 20f, rulerLabelPaint)
        } else {
            val frames = clipThumbnails[clip.id]
            if (!frames.isNullOrEmpty()) {
                // Original engine only drew a filmstrip for real VIDEO
                // clips (isVideo == true) since a still image never
                // needed more than one frame. VELLORA-CUT's clips are
                // always still images, so this draws whenever a thumbnail
                // was supplied — a single-frame list just fills the whole
                // clip rect with that one picture (see drawFilmstrip).
                drawFilmstrip(canvas, frames, rect)
            }
            canvas.drawText(clip.label, left + 16f, top + rowHeight / 2f + 10f, labelPaint)
        }

        canvas.restore()

        // Drawn AFTER (on top of) the filmstrip/waveform content above —
        // otherwise a video clip's thumbnail frames would paint straight
        // over the selection border and hide it.
        if (clip.id == selectedClipId) {
            canvas.drawRect(rect, selectedClipBorderPaint) // full 4-side border, selected only
        }
    }

    /** Minimum on-screen width per drawn tile — below this, two adjacent
     * tiles would be visually indistinguishable anyway, so there's no
     * point drawing them separately. Keeps the DRAW loop cheap even
     * though [frames] can now be a dense 28-per-second decode (see
     * BitmapLoader.loadThumbnails) — decoding stays dense (the real
     * frames exist, cached, ready), but rendering adapts to however wide
     * the clip actually is on screen right now (which changes with
     * zoom), same idea as only drawing clips that are actually visible. */
    private val minFilmstripTileWidthPx = 6f

    /** Tiles a SUBSET of [frames] left-to-right across [rect] as
     * equal-width slices — the standard "filmstrip" look, so a video
     * clip is recognizable at a glance instead of being a plain fill
     * indistinguishable from an empty clip. Each frame is CENTER-CROPPED
     * (never stretched) to its tile, so frames never look squashed
     * regardless of the clip's on-screen width or the source video's
     * aspect ratio. Evenly subsamples down to however many tiles
     * actually fit at [minFilmstripTileWidthPx] each — see that field's
     * doc for why this doesn't conflict with a dense 28fps decode. */
    private fun drawFilmstrip(canvas: Canvas, frames: List<Bitmap>, rect: RectF) {
        val maxTiles = (rect.width() / minFilmstripTileWidthPx).toInt().coerceAtLeast(1)
        val step = (frames.size / maxTiles).coerceAtLeast(1)
        val visibleFrames = if (step <= 1) frames else frames.filterIndexed { i, _ -> i % step == 0 }
        val tileWidth = rect.width() / visibleFrames.size
        for ((i, frame) in visibleFrames.withIndex()) {
            if (frame.isRecycled) continue
            val tileLeft = rect.left + tileWidth * i
            val destRect = RectF(tileLeft, rect.top, tileLeft + tileWidth, rect.bottom)
            val srcAspect = frame.width.toFloat() / frame.height.toFloat()
            val dstAspect = destRect.width() / destRect.height()
            val srcRect = if (srcAspect > dstAspect) {
                // Source frame is relatively wider than the tile — crop its left/right.
                val cropWidth = (frame.height * dstAspect).toInt().coerceIn(1, frame.width)
                val xOffset = (frame.width - cropWidth) / 2
                Rect(xOffset, 0, xOffset + cropWidth, frame.height)
            } else {
                // Source frame is relatively taller than the tile — crop its top/bottom.
                val cropHeight = (frame.width / dstAspect).toInt().coerceIn(1, frame.height)
                val yOffset = (frame.height - cropHeight) / 2
                Rect(0, yOffset, frame.width, yOffset + cropHeight)
            }
            canvas.drawBitmap(frame, srcRect, destRect, null)
        }
    }

    /** Draws the portion of the decoded [waveform] that belongs to THIS
     * audio clip's own slice of the original file — using
     * [TimelineClip.sourceOffsetMs] (fixed) rather than [TimelineClip.startMs]
     * (which moves with ripple/trim), so the waveform shape stays
     * correct no matter where the clip has been moved/split/trimmed to
     * on the timeline. */
    private fun drawWaveformSlice(canvas: Canvas, clip: TimelineClip, rect: RectF) {
        if (waveform.isEmpty() || audioDurationMs <= 0L) return

        val midY = rect.top + rect.height() / 2f + 8f
        val maxBarHeight = rect.height() / 2f - 14f
        val left = rect.left.coerceAtLeast(0f)
        val right = rect.right.coerceAtMost(width.toFloat())
        val stepPx = 3f

        var x = left
        while (x <= right) {
            // Position within THIS clip's timeline span, then mapped back
            // to the original file's absolute time via sourceOffsetMs.
            val msWithinClip = viewport.pxToMs(x) - clip.startMs
            val sourceMs = clip.sourceOffsetMs + msWithinClip
            if (sourceMs in 0..audioDurationMs) {
                val bucketIndex = ((sourceMs.toFloat() / audioDurationMs) * waveform.size)
                    .toInt().coerceIn(0, waveform.size - 1)
                val barHeight = (waveform[bucketIndex] * maxBarHeight).coerceAtLeast(2f)
                canvas.drawLine(x, midY - barHeight, x, midY + barHeight, waveformPaint)
            }
            x += stepPx
        }
    }
}
