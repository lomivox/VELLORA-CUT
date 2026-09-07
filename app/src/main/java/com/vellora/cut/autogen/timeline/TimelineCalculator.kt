package com.vellora.cut.autogen.timeline

import com.vellora.cut.autogen.data.PromptEntity
import com.vellora.cut.autogen.data.TimelineMode

/** One image placed on the auto-gen timeline, with its computed on-screen duration. */
data class TimelineImage(
    val prompt: PromptEntity,
    val durationMs: Long
)

/**
 * Computes each image's duration so the sequence's total length matches the
 * voice-over — the voice-over is always the master/authoritative length.
 *
 * Images with [PromptEntity.manualDurationMs] set keep EXACTLY that duration
 * (user override, always wins). Only images WITHOUT an override ("automatic"
 * images) get their duration computed by [mode]:
 * - [TimelineMode.SCALE]: the automatic images equally split whatever time
 *   is left after subtracting every manual image's duration from the
 *   voice-over length.
 * - [TimelineMode.HOLD_LAST]: every automatic image keeps [baseDurationMs]
 *   except the LAST automatic image, which absorbs the remaining time.
 *
 * If every image has a manual override, the automatic-distribution step is
 * skipped entirely — the total may then be shorter or longer than the
 * voice-over, which is expected (the user's explicit per-image choice
 * always takes priority over auto-sync).
 *
 * If [voiceOverMs] is 0 (no voice-over duration detected), every automatic
 * image simply uses [baseDurationMs] (manual images still keep their
 * override).
 */
fun computeTimeline(
    images: List<PromptEntity>,
    voiceOverMs: Long,
    baseDurationMs: Long,
    mode: String
): List<TimelineImage> {
    if (images.isEmpty()) return emptyList()

    if (voiceOverMs <= 0) {
        return images.map { TimelineImage(it, it.manualDurationMs ?: baseDurationMs) }
    }

    val manualTotalMs = images.sumOf { it.manualDurationMs ?: 0L }
    val autoImages = images.filter { it.manualDurationMs == null }
    val remainingForAutoMs = (voiceOverMs - manualTotalMs).coerceAtLeast(0L)

    val autoDurationsById: Map<Long, Long> = when {
        autoImages.isEmpty() -> emptyMap()
        mode == TimelineMode.HOLD_LAST -> {
            val allButLast = autoImages.size - 1
            val usedByOthers = allButLast * baseDurationMs
            val lastDuration = (remainingForAutoMs - usedByOthers).coerceAtLeast(0L)
            autoImages.mapIndexed { index, prompt ->
                prompt.id to (if (index == autoImages.lastIndex) lastDuration else baseDurationMs)
            }.toMap()
        }
        else -> { // SCALE
            val equalShare = remainingForAutoMs / autoImages.size
            val remainder = remainingForAutoMs - (equalShare * autoImages.size)
            autoImages.mapIndexed { index, prompt ->
                // last auto image absorbs the rounding remainder so the sum matches exactly
                prompt.id to (if (index == autoImages.lastIndex) equalShare + remainder else equalShare)
            }.toMap()
        }
    }

    return images.map { prompt ->
        val duration = prompt.manualDurationMs ?: autoDurationsById[prompt.id] ?: baseDurationMs
        TimelineImage(prompt, duration)
    }
}

fun totalDurationMs(images: List<TimelineImage>): Long = images.sumOf { it.durationMs }

/** Cumulative start time (ms) of each image, in the same order as [images]. */
fun timelineStartOffsets(images: List<TimelineImage>): List<Long> {
    var acc = 0L
    return images.map { val start = acc; acc += it.durationMs; start }
}
