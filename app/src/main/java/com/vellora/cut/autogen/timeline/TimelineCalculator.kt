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
 * - [TimelineMode.SCALE]: every image gets an equal share of the voice-over
 *   length (voiceOverMs / count). Smooth, no single image is stretched more
 *   than another.
 * - [TimelineMode.HOLD_LAST]: every image keeps [baseDurationMs] except the
 *   last one, which absorbs whatever time remains (can be shorter or
 *   longer than the others). Predictable per-image pacing, only the tail
 *   varies.
 *
 * If [voiceOverMs] is 0 (no voice-over duration detected) every image
 * simply uses [baseDurationMs].
 */
fun computeTimeline(
    images: List<PromptEntity>,
    voiceOverMs: Long,
    baseDurationMs: Long,
    mode: String
): List<TimelineImage> {
    if (images.isEmpty()) return emptyList()
    if (voiceOverMs <= 0) {
        return images.map { TimelineImage(it, baseDurationMs) }
    }

    return when (mode) {
        TimelineMode.HOLD_LAST -> {
            val allButLast = images.size - 1
            val usedByOthers = allButLast * baseDurationMs
            val lastDuration = (voiceOverMs - usedByOthers).coerceAtLeast(0L)
            images.mapIndexed { index, prompt ->
                val duration = if (index == images.lastIndex) lastDuration else baseDurationMs
                TimelineImage(prompt, duration)
            }
        }
        else -> { // SCALE
            val equalShare = voiceOverMs / images.size
            val remainder = voiceOverMs - (equalShare * images.size)
            images.mapIndexed { index, prompt ->
                // last image absorbs the rounding remainder so the sum matches exactly
                val duration = if (index == images.lastIndex) equalShare + remainder else equalShare
                TimelineImage(prompt, duration)
            }
        }
    }
}

fun totalDurationMs(images: List<TimelineImage>): Long = images.sumOf { it.durationMs }

/** Cumulative start time (ms) of each image, in the same order as [images]. */
fun timelineStartOffsets(images: List<TimelineImage>): List<Long> {
    var acc = 0L
    return images.map { val start = acc; acc += it.durationMs; start }
}
