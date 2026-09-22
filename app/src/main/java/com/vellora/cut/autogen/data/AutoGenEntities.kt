package com.vellora.cut.autogen.data

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File

/** Status values used by [AutoGenProjectEntity.status]. */
object AutoGenProjectStatus {
    const val DRAFT = "draft"
    const val GENERATING = "generating"
    const val READY = "ready"
    const val RENDERED = "rendered"
}

/** Status values used by [PromptEntity.status]. */
object PromptStatus {
    const val PENDING = "pending"
    const val GENERATING = "generating"
    const val DONE = "done"
    const val FAILED = "failed"
}

/** The "mood" of a generated image, guessed from its own prompt text by a
 * single Cloudflare AI text call at image-generation time — see
 * [PromptEntity.energyLabel]. Drives whether [SmartSequenceGenerator] picks
 * a slow/gentle motion+transition or a fast/punchy one for that image. */
object EnergyLevel {
    const val CALM = "calm"
    const val NEUTRAL = "neutral"
    const val ENERGETIC = "energetic"
}

/** Status of the (separate, optional) AI energy-classification call for one
 * image — see [PromptEntity.energyClassificationStatus]. This is
 * deliberately independent from [PromptStatus]: an image can be fully
 * `done` (generated, usable) while its classification is still `pending`
 * or `failed` — a classification problem must never block generation or
 * render. [FAILED] is always retried automatically the next time
 * GenerateImagesWorker runs for this project (see its classification
 * pass), so a dropped call recovers on its own without any manual step. */
object EnergyClassificationStatus {
    const val PENDING = "pending"
    const val DONE = "done"
    const val FAILED = "failed"
}

/** Overall look for [SmartSequenceGenerator]'s per-image Motion+Transition
 * picks — see [AutoGenProjectEntity.videoStyle]. */
object VideoStyle {
    /** Slow zooms/pans + soft dissolves/fades only — calm, editorial look. */
    const val CINEMATIC = "cinematic"
    /** Fast zooms/pans + cuts/short dissolves only — punchy, high-energy look. */
    const val DYNAMIC = "dynamic"
    /** Controlled mix of both pools, picked per-image by the image's own
     * [EnergyLevel] — the default for most videos. */
    const val MIXED_PRO = "mixed_pro"

    val ALL = listOf(CINEMATIC, DYNAMIC, MIXED_PRO)

    fun label(value: String): String = when (value) {
        CINEMATIC -> "Cinematic"
        DYNAMIC -> "Dynamic"
        MIXED_PRO -> "Mixed Pro"
        else -> value
    }
}

@Entity(tableName = "autogen_projects")
data class AutoGenProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val voiceOverUri: String?,
    val voiceOverDurationMs: Long,
    val imageDurationSec: Int,
    val resolution: String,
    val status: String,
    val createdAt: Long,
    /** How image durations reconcile against the voice-over length: "scale" or "hold_last". */
    val timelineMode: String = TimelineMode.SCALE,
    /** Local file path of the last successful render (Phase F), null until rendered once. */
    val renderedFilePath: String? = null,
    /** Transition style between consecutive images — see [TransitionType]. */
    val transitionType: String = TransitionType.CROSSFADE,
    /** Per-image motion effect — see [MotionEffect]. Used as the fallback
     * when an image has no [PromptEntity.generatedMotionEffect] yet (older
     * projects, or before SmartSequenceGenerator has run). */
    val motionEffect: String = MotionEffect.ZOOM_IN,
    /** Which pool [SmartSequenceGenerator] picks per-image Motion+Transition
     * from — see [VideoStyle]. */
    val videoStyle: String = VideoStyle.MIXED_PRO,
    /** Seed for [SmartSequenceGenerator]'s per-image randomization. Null
     * until the sequence is generated for the first time, at which point it
     * is set once (defaults to this project's own id) and never changes —
     * that's what makes re-rendering the same project always reproduce the
     * exact same Motion+Transition sequence. */
    val sequenceSeed: Long? = null,
    /** Real FFmpeg noise-reduction strength on the voice-over, 0-100 (maps
     * to afftdn's nr parameter, 0-97dB). 0 = no noise reduction applied. */
    val noiseReductionPercent: Int = 0,
    /** Real FFmpeg volume gain on the voice-over, 0-500 (100 = original
     * volume, unchanged). Applied via the `volume=` audio filter. */
    val volumePercent: Int = 100,
    /** Local path to the FFmpeg-processed voice-over (noise-reduction +
     * volume already baked in) — regenerated whenever either setting
     * changes. Null until first processed; both the live PreviewPlayer and
     * RenderEngine prefer this over the raw picked file when present. */
    val processedAudioPath: String? = null,
    /** Real Whisper-transcribed captions, stored as a JSON array of
     * {text,startMs,endMs} — see [CaptionSegment]. Null until generated. */
    val captionsJson: String? = null,
    /** Whether captions are burned into the final render / shown live in
     * Preview. Generating captions does NOT turn this on automatically —
     * the person reviews the transcription first, then enables it. */
    val captionsEnabled: Boolean = false,
    /** Whisper language code for caption transcription — "ur" (Urdu,
     * Nastaliq/Arabic script) or "hi" (Hindi, Devanagari script). Whisper
     * often defaults to Hindi script for Urdu speech since the two
     * languages sound almost identical, so this must be explicit. */
    val captionsLanguage: String = "ur",
    /** Font family name used to render/burn in captions — see CaptionFonts. */
    val captionsFont: String = CaptionFonts.DEFAULT
)

/** Caption font choices. [assetPath] is where the actual .ttf must be
 * placed for it to really be used — see CaptionFonts' KDoc for exactly
 * where and how to add one; anything missing falls back to the system
 * default font rather than crashing. */
object CaptionFonts {
    const val SYSTEM_DEFAULT = "system_default"
    const val JAMEEL_NOORI_NASTALEEQ = "jameel_noori_nastaleeq"
    const val NOTO_NASTALIQ_URDU = "noto_nastaliq_urdu"
    const val ROBOTO = "roboto"
    const val POPPINS = "poppins"
    const val DEFAULT = SYSTEM_DEFAULT

    /** (id, display label, is this an Urdu/Nastaliq-script font) */
    val ALL = listOf(
        Triple(SYSTEM_DEFAULT, "System Default", false),
        Triple(JAMEEL_NOORI_NASTALEEQ, "Jameel Noori Nastaleeq", true),
        Triple(NOTO_NASTALIQ_URDU, "Noto Nastaliq Urdu", true),
        Triple(ROBOTO, "Roboto", false),
        Triple(POPPINS, "Poppins", false)
    )

    fun label(id: String): String = ALL.firstOrNull { it.first == id }?.second ?: id

    /** Path under app/src/main/assets/ where this font's real .ttf file
     * must be placed — null for SYSTEM_DEFAULT, which needs no file. */
    fun assetPath(id: String): String? = when (id) {
        JAMEEL_NOORI_NASTALEEQ -> "fonts/JameelNooriNastaleeq.ttf"
        NOTO_NASTALIQ_URDU -> "fonts/NotoNastaliqUrdu-Regular.ttf"
        ROBOTO -> "fonts/Roboto-Regular.ttf"
        POPPINS -> "fonts/Poppins-Regular.ttf"
        else -> null
    }

    // ---- user-imported fonts (CapCut-style "Import Font" button) ----------
    // Bundled fonts (above) need a rebuild+reinstall to add. Imported fonts
    // are picked by the user at runtime (a .ttf/.otf, or a .zip containing
    // one) and saved into app-private storage, so no rebuild is needed and
    // they survive app updates (cleared only on uninstall / clear-data).

    private const val IMPORTED_PREFIX = "imported:"

    /** Folder where imported font files live; created on first use. */
    fun importedFontsDir(context: Context): File =
        File(context.filesDir, "fonts/imported").apply { mkdirs() }

    fun isImported(id: String): Boolean = id.startsWith(IMPORTED_PREFIX)

    /** The real file for an imported font id, or null if id isn't one / file is gone. */
    fun importedFile(context: Context, id: String): File? {
        if (!isImported(id)) return null
        val file = File(importedFontsDir(context), id.removePrefix(IMPORTED_PREFIX))
        return if (file.exists() && file.length() > 0) file else null
    }

    /** All fonts currently importable-and-present, as (id, label, isUrduGuess) —
     * same Triple shape as [ALL] so UI can just concatenate the two lists. */
    fun listImported(context: Context): List<Triple<String, String, Boolean>> =
        importedFontsDir(context)
            .listFiles { f -> f.isFile && f.extension.lowercase() in listOf("ttf", "otf") }
            ?.sortedBy { it.name.lowercase() }
            ?.map { f ->
                val label = f.nameWithoutExtension
                // Rough heuristic only, purely for the (unused-here) isUrdu
                // flag's sake — real script comes from whatever the person
                // actually imported, this never blocks anything.
                val looksUrdu = label.contains("urdu", ignoreCase = true) ||
                    label.contains("nastaliq", ignoreCase = true) ||
                    label.contains("nastaleeq", ignoreCase = true)
                Triple(IMPORTED_PREFIX + f.name, label, looksUrdu)
            }
            ?: emptyList()

    /** Every font the person can currently pick: built-in + imported. */
    fun allAvailable(context: Context): List<Triple<String, String, Boolean>> =
        ALL + listImported(context)

    fun label(context: Context, id: String): String =
        allAvailable(context).firstOrNull { it.first == id }?.second ?: label(id)

    fun deleteImported(context: Context, id: String): Boolean {
        val file = importedFile(context, id) ?: return false
        return file.delete()
    }
}

/** One real Whisper-transcribed line, with its exact spoken timing. */
data class CaptionSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/** JSON (de)serialization for [AutoGenProjectEntity.captionsJson]. */
object CaptionSegments {
    fun toJson(segments: List<CaptionSegment>): String {
        val array = org.json.JSONArray()
        segments.forEach { seg ->
            array.put(
                org.json.JSONObject().apply {
                    put("text", seg.text)
                    put("startMs", seg.startMs)
                    put("endMs", seg.endMs)
                }
            )
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<CaptionSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CaptionSegment(
                    text = obj.optString("text"),
                    startMs = obj.optLong("startMs"),
                    endMs = obj.optLong("endMs")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Values for [AutoGenProjectEntity.timelineMode]. */
object TimelineMode {
    /** Every image's duration is stretched/shrunk equally so total = voice-over length. */
    const val SCALE = "scale"
    /** Every image keeps its set duration; only the last image absorbs the remainder. */
    const val HOLD_LAST = "hold_last"
}

/** Values for [AutoGenProjectEntity.transitionType] — how one image hands off to the next. */
object TransitionType {
    /** One image fades out while the next fades in, overlapping. */
    const val CROSSFADE = "crossfade"
    /** The next image slides in from the right, pushing the current one out (kept under its
     * original name for backward compatibility with projects saved before the other 8 were added). */
    const val SLIDE = "slide"
    const val SLIDE_RIGHT = "slide_right"
    const val SLIDE_UP = "slide_up"
    const val SLIDE_DOWN = "slide_down"
    const val WIPE_LEFT = "wipe_left"
    const val WIPE_RIGHT = "wipe_right"
    const val CIRCLE_OPEN = "circle_open"
    const val DISSOLVE = "dissolve"
    const val PIXELIZE = "pixelize"
    // ---- batch 2: trending/professional (CapCut-style) additions ----
    const val FADE_BLACK = "fade_black"
    const val FADE_WHITE = "fade_white"
    const val RADIAL = "radial"
    const val BLUR = "blur"
    const val SMOOTH_LEFT = "smooth_left"
    const val SMOOTH_RIGHT = "smooth_right"
    const val CIRCLE_CLOSE = "circle_close"
    const val SQUEEZE = "squeeze"
    const val DIAGONAL = "diagonal"
    const val DISTANCE = "distance"
    /** A real hard cut — no crossfade at all, the Dynamic style's "Cut"
     * choice. Handled by RenderEngine as an xfade with a near-zero
     * duration (FFmpeg's xfade needs a nonzero duration) rather than a
     * plain concat, so it can stay in the same per-edge xfade chain as
     * every other transition without a separate code path. */
    const val CUT = "cut"

    /** Every option, in the order they should be offered — all but [CUT]
     * map to FFmpeg's built-in `xfade` transition names (see
     * RenderEngine), so every one of these is a REAL transition, not a
     * fake/simulated one. */
    val ALL = listOf(
        CROSSFADE, SLIDE, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN,
        WIPE_LEFT, WIPE_RIGHT, CIRCLE_OPEN, DISSOLVE, PIXELIZE,
        FADE_BLACK, FADE_WHITE, RADIAL, BLUR, SMOOTH_LEFT,
        SMOOTH_RIGHT, CIRCLE_CLOSE, SQUEEZE, DIAGONAL, DISTANCE, CUT
    )

    fun label(value: String): String = when (value) {
        CROSSFADE -> "Crossfade"
        SLIDE -> "Slide Left"
        SLIDE_RIGHT -> "Slide Right"
        SLIDE_UP -> "Slide Up"
        SLIDE_DOWN -> "Slide Down"
        WIPE_LEFT -> "Wipe Left"
        WIPE_RIGHT -> "Wipe Right"
        CIRCLE_OPEN -> "Circle Open"
        DISSOLVE -> "Dissolve"
        PIXELIZE -> "Pixelize"
        FADE_BLACK -> "Fade to Black"
        FADE_WHITE -> "Fade to White"
        RADIAL -> "Radial"
        BLUR -> "Blur"
        SMOOTH_LEFT -> "Smooth Left"
        SMOOTH_RIGHT -> "Smooth Right"
        CIRCLE_CLOSE -> "Circle Close"
        SQUEEZE -> "Squeeze"
        DIAGONAL -> "Diagonal"
        DISTANCE -> "Distance"
        CUT -> "Cut"
        else -> value
    }
}

/** Values for [AutoGenProjectEntity.motionEffect] — subtle movement applied to every still image. */
object MotionEffect {
    /** Slow, continuous zoom-in over the image's on-screen duration (classic Ken Burns). */
    const val ZOOM_IN = "zoom_in"
    /** Fixed slight zoom, camera pans left-to-right across the image (kept under its original
     * name for backward compatibility with projects saved before the other 8 were added). */
    const val PAN = "pan"
    const val ZOOM_OUT = "zoom_out"
    const val PAN_LEFT = "pan_left"
    const val PAN_UP = "pan_up"
    const val PAN_DOWN = "pan_down"
    const val ZOOM_IN_PAN_LEFT = "zoom_in_pan_left"
    const val ZOOM_IN_PAN_RIGHT = "zoom_in_pan_right"
    const val ZOOM_OUT_PAN = "zoom_out_pan"
    /** No motion at all — the image just holds still. */
    const val STATIC = "static"
    // ---- batch 2: trending/professional (CapCut-style) additions ----
    /** Fast zoom burst in the first ~15% of the clip, then holds — the
     * "punch-in" look common in fast-cut Reels/TikTok edits. */
    const val PUNCH_ZOOM = "punch_zoom"
    /** Tiny continuous jitter (a few px) — an authentic handheld-camera feel. */
    const val SHAKE = "shake"
    /** Zoom-in while panning both diagonally (up + left) at once. */
    const val DIAGONAL = "diagonal"
    /** A gentler zoom range (1.0→1.15 instead of 1.3) — understated,
     * corporate/cinematic look rather than an obvious "zoom effect". */
    const val CINEMATIC_ZOOM = "cinematic_zoom"
    /** Starts tightly zoomed in (1.5x) and pulls back to reveal the full
     * image — a dramatic "reveal" opener. */
    const val DRAMATIC_REVEAL = "dramatic_reveal"

    /** Every option, in the order they should be offered — all 15 are REAL
     * FFmpeg `zoompan` (or plain scale/crop for STATIC) formulas, see
     * RenderEngine, matched live in Preview by applyLiveMotionAndTransition. */
    val ALL = listOf(
        ZOOM_IN, ZOOM_OUT, PAN, PAN_LEFT, PAN_UP, PAN_DOWN,
        ZOOM_IN_PAN_LEFT, ZOOM_IN_PAN_RIGHT, ZOOM_OUT_PAN, STATIC,
        PUNCH_ZOOM, SHAKE, DIAGONAL, CINEMATIC_ZOOM, DRAMATIC_REVEAL
    )

    fun label(value: String): String = when (value) {
        ZOOM_IN -> "Zoom In"
        ZOOM_OUT -> "Zoom Out"
        PAN -> "Pan Right"
        PAN_LEFT -> "Pan Left"
        PAN_UP -> "Pan Up"
        PAN_DOWN -> "Pan Down"
        ZOOM_IN_PAN_LEFT -> "Zoom+Pan Left"
        ZOOM_IN_PAN_RIGHT -> "Zoom+Pan Right"
        ZOOM_OUT_PAN -> "Zoom Out+Pan"
        STATIC -> "Static"
        PUNCH_ZOOM -> "Punch Zoom"
        SHAKE -> "Handheld Shake"
        DIAGONAL -> "Ken Burns Diagonal"
        CINEMATIC_ZOOM -> "Cinematic Zoom"
        DRAMATIC_REVEAL -> "Dramatic Reveal"
        else -> value
    }
}

@Entity(tableName = "autogen_prompts")
data class PromptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val orderIndex: Int,
    val label: String,
    val promptText: String,
    val status: String,
    val imagePath: String? = null,
    val errorMessage: String? = null,
    /** User-set duration override for this image, in ms. Null = automatic
     * (Scale/Hold-Last sync mode decides it, as before). Deliberately has
     * nothing to do with motion/transition — duration is decided by
     * voice/script timing first, and SmartSequenceGenerator fits its pick
     * inside whatever duration this image already has. */
    val manualDurationMs: Long? = null,
    /** This image's mood, guessed by Cloudflare AI from [promptText] once,
     * right after the image itself is generated — see [EnergyLevel]. Null
     * until classified. Used by SmartSequenceGenerator; never re-guessed
     * once set to CALM/NEUTRAL/ENERGETIC. */
    val energyLabel: String? = null,
    /** See [EnergyClassificationStatus]. Starts PENDING as soon as the
     * image itself is DONE; GenerateImagesWorker retries anything left
     * PENDING or FAILED on every run, so a dropped/failed AI call always
     * gets another chance without deleting or re-generating the image. */
    val energyClassificationStatus: String = EnergyClassificationStatus.PENDING,
    /** This image's Motion+Transition as picked by SmartSequenceGenerator
     * — see [MotionEffect]. Null until the sequence has been generated at
     * least once; cached here (rather than recomputed every time) so
     * Preview and RenderEngine always show/render the exact same pick, and
     * so a later single-image regeneration doesn't reshuffle every other
     * image's effect. Falls back to the project's [AutoGenProjectEntity.motionEffect]
     * when null. */
    val generatedMotionEffect: String? = null,
    /** This image's incoming Transition (the transition that plays as this
     * image enters, i.e. the edge between the previous image and this
     * one — meaningless for the first image) as picked by
     * SmartSequenceGenerator — see [TransitionType]. Same caching/fallback
     * rules as [generatedMotionEffect]. */
    val generatedTransitionType: String? = null,
    /** Per-image Auto/Manual toggle. Null/false = Auto — SmartSequenceGenerator
     * is free to (re)pick this image's [generatedMotionEffect] /
     * [generatedTransitionType] on every sequence generation. true = Manual —
     * the user chose these two values themselves in the editor; the generator
     * always skips this image and leaves them exactly as they are. */
    val isManualEffect: Boolean? = false
)

// ============================================================================
// Shorts Metadata Tool — a SEPARATE feature from AutoGen: pick any video
// already in the phone's Gallery (not just AutoGen-rendered ones) and
// generate a real, research-backed Title/Description/Tags/Hashtags for it.
// ============================================================================

object ShortMetadataStatus {
    const val IDLE = "idle"
    const val EXTRACTING_AUDIO = "extracting_audio"
    const val TRANSCRIBING = "transcribing"
    const val RESEARCHING_KEYWORDS = "researching_keywords"
    const val GENERATING = "generating"
    const val DONE = "done"
    const val UPLOADING = "uploading"
    const val UPLOADED = "uploaded"
    const val ERROR = "error"
}

object UploadTarget {
    /** No reshape — upload whatever shape/duration the source already is. */
    const val AUTO = "auto"
    const val SHORT = "short"
    const val LONG = "long"
}

@Entity(tableName = "short_metadata_projects")
data class ShortMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** content:// URI of the Gallery video this was generated for — blank
     * for a manual-topic-only entry (metadata drafted from typed text,
     * no video attached, so nothing to upload). */
    val videoUri: String,
    val videoFileName: String,
    val createdAt: Long,
    val status: String = ShortMetadataStatus.IDLE,
    val errorMessage: String? = null,
    /** Real Whisper transcript of the video's audio track — or, for a
     * manual-topic entry, the person's own typed topic text used the same
     * way (nothing here is ever a placeholder). */
    val transcript: String? = null,
    /** Real YouTube search-suggest keywords fetched for this video's topic
     * (comma-separated) — what the AI's Title/Tags were actually grounded in. */
    val researchedKeywords: String? = null,
    val generatedTitle: String? = null,
    val generatedDescription: String? = null,
    /** Comma-separated, for YouTube's own Tags field. */
    val generatedTags: String? = null,
    /** Space-separated #hashtags, ready to paste into the description. */
    val generatedHashtags: String? = null,
    /** Set once this video was actually uploaded to YouTube. */
    val youtubeVideoUrl: String? = null,
    /** [UploadTarget] — whether upload should reshape the video to Short
     * (vertical, <3min) or Long (widescreen) before sending it, or leave
     * it exactly as picked. */
    val uploadTarget: String = UploadTarget.AUTO
)
