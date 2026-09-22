package com.vellora.cut.autogen.captions

import com.vellora.cut.autogen.data.CaptionSegment

/** One scene: a merged run of consecutive [CaptionSegment]s long enough to
 * deserve its own image, with its own real narration-timed window. */
data class ScenePrompt(
    val narrationText: String,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Turns Whisper's raw (often very short, few-words-each) caption segments
 * into scene-length chunks worth generating one image for.
 *
 * Whisper segments are sentence/phrase-level, so a 5-10 minute voice-over
 * can produce a hundred+ of them — one image per raw segment would be far
 * too many, too-short clips. This merges consecutive segments until each
 * merged scene reaches [MIN_SCENE_DURATION_MS], so the final image count
 * and per-image duration is driven by the audio's own actual pacing
 * instead of a fixed image-count guess — which is exactly the "aage peeche
 * ho jaate hain" (images drifting out of sync with what's being said)
 * problem this whole feature exists to remove.
 */
object AutoPromptGenerator {

    /** No merged scene is shorter than this — a person's real narration
     * segment is often 1-3s, far too short for one Ken Burns image to
     * read as intentional rather than flickery. */
    const val MIN_SCENE_DURATION_MS = 3500L

    /** No merged scene runs longer than this even if segments keep coming
     * in short — a very long single image would look static/boring next
     * to fast-changing narration, so a new scene starts anyway past this. */
    const val MAX_SCENE_DURATION_MS = 12000L

    fun merge(segments: List<CaptionSegment>, totalAudioDurationMs: Long = 0L): List<ScenePrompt> {
        if (segments.isEmpty()) return emptyList()
        val scenes = mutableListOf<ScenePrompt>()
        var bucket = mutableListOf<CaptionSegment>()
        // Continuous cursor — NOT bucket.first().startMs. Using the actual
        // segment start would silently drop any silence/gap between the
        // previous scene's end and this bucket's first word: that gap
        // would belong to no image's duration at all, so the video would
        // start drifting out of sync with the audio the moment any pause
        // in speech occurs. Carrying sceneStart forward with zero gaps
        // guarantees every millisecond of audio is covered by exactly one
        // image, in order, with nothing skipped.
        var sceneStart = 0L

        fun flush() {
            if (bucket.isEmpty()) return
            val end = bucket.last().endMs
            scenes += ScenePrompt(
                narrationText = bucket.joinToString(" ") { it.text.trim() }.trim(),
                startMs = sceneStart,
                endMs = end
            )
            sceneStart = end
            bucket = mutableListOf()
        }

        for (seg in segments) {
            bucket += seg
            val bucketDuration = bucket.last().endMs - sceneStart
            if (bucketDuration >= MIN_SCENE_DURATION_MS || bucketDuration >= MAX_SCENE_DURATION_MS) {
                flush()
            }
        }
        // Leftover shorter than MIN_SCENE_DURATION_MS: fold into the
        // previous scene (extend its endMs) instead of becoming its own
        // too-short image — unless it's the ONLY scene there is.
        if (bucket.isNotEmpty()) {
            if (scenes.isNotEmpty()) {
                val last = scenes.removeAt(scenes.lastIndex)
                scenes += last.copy(
                    narrationText = (last.narrationText + " " + bucket.joinToString(" ") { it.text.trim() }).trim(),
                    endMs = bucket.last().endMs
                )
            } else {
                flush()
            }
        }

        // Extend the LAST scene to the real end of the audio (trailing
        // silence after the final word is common and, left unhandled,
        // means the sum of image durations lands short of the voice-over
        // — RenderEngine maps both streams with `-shortest`, so a short
        // video track would cut the tail of the narration off entirely).
        if (totalAudioDurationMs > 0 && scenes.isNotEmpty()) {
            val last = scenes.removeAt(scenes.lastIndex)
            scenes += last.copy(endMs = maxOf(totalAudioDurationMs, last.startMs + 1))
        }

        return scenes
    }
}
