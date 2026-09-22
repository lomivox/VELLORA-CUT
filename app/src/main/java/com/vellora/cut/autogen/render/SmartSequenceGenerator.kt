package com.vellora.cut.autogen.render

import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.data.EnergyLevel
import com.vellora.cut.autogen.data.MotionEffect
import com.vellora.cut.autogen.data.PromptEntity
import com.vellora.cut.autogen.data.TransitionType
import com.vellora.cut.autogen.data.VideoStyle
import kotlin.random.Random

/**
 * Picks a Motion+Transition for every image in a project, per the
 * architecture agreed in chat:
 *
 *   Style -> Motion Pool + Transition Pool -> Smart Sequence Generator -> Fixed Seed -> Per-image Manual Override
 *
 * Rules this follows:
 *  - Each image's own [EnergyLevel] (calm/neutral/energetic, guessed by AI
 *    from that image's own prompt — see CloudflareAiClient.classifyImageEnergy)
 *    decides which pool (Cinematic-leaning slow, or Dynamic-leaning fast)
 *    its pick is weighted toward. MIXED_PRO style blends both pools this way;
 *    pure CINEMATIC/DYNAMIC styles ignore energy and always use their one pool.
 *  - The SAME motion never repeats on two images in a row, and the SAME
 *    transition never repeats on two edges in a row — but picks are NOT
 *    forced to be maximally different every time either (that looks just
 *    as artificial as repeating). Everything else is weighted-random.
 *  - Deterministic: same project id + same energy labels + same style
 *    always produces the exact same sequence (see [AutoGenProjectEntity.sequenceSeed]).
 *    Re-rendering without changing any of those inputs never reshuffles.
 *  - An image already carrying a manual override (`isManualEffect` — the
 *    per-image Auto/Manual toggle) is left completely untouched: its
 *    existing [PromptEntity.generatedMotionEffect] /
 *    [PromptEntity.generatedTransitionType] pass through unchanged.
 */
object SmartSequenceGenerator {

    /** Motion effects considered "slow/gentle" — Cinematic style's pool. */
    private val CINEMATIC_MOTIONS = listOf(
        MotionEffect.ZOOM_IN,
        MotionEffect.ZOOM_OUT,
        MotionEffect.CINEMATIC_ZOOM,
        MotionEffect.PAN_LEFT,
        MotionEffect.PAN
    )

    /** Motion effects considered "fast/punchy" — Dynamic style's pool. */
    private val DYNAMIC_MOTIONS = listOf(
        MotionEffect.PUNCH_ZOOM,
        MotionEffect.ZOOM_IN_PAN_RIGHT,
        MotionEffect.ZOOM_IN_PAN_LEFT,
        MotionEffect.PAN_UP,
        MotionEffect.PAN_DOWN,
        MotionEffect.DIAGONAL
    )

    /** Transitions considered "soft" — Cinematic style's pool ("Soft Cross
     * Dissolve" and "Fade" from the agreed spec). */
    private val CINEMATIC_TRANSITIONS = listOf(
        TransitionType.DISSOLVE,
        TransitionType.CROSSFADE,
        TransitionType.FADE_BLACK
    )

    /** Transitions considered "sharp" — Dynamic style's pool ("Short
     * Dissolve" and "Cut" from the agreed spec; SLIDE stands in for the
     * quick push/slide feel alongside CUT). */
    private val DYNAMIC_TRANSITIONS = listOf(
        TransitionType.CUT,
        TransitionType.SLIDE,
        TransitionType.SLIDE_RIGHT,
        TransitionType.DISSOLVE
    )

    /**
     * Returns a new list of [PromptEntity] (same order as [prompts]) with
     * [PromptEntity.generatedMotionEffect] / [PromptEntity.generatedTransitionType]
     * filled in for every prompt that doesn't already have a manual
     * override. Does NOT write to the database — the caller persists the
     * result (see RenderEngine / GenerateImagesWorker call sites).
     */
    fun generate(
        project: AutoGenProjectEntity,
        prompts: List<PromptEntity>
    ): List<PromptEntity> {
        if (prompts.isEmpty()) return prompts
        val seed = project.sequenceSeed ?: project.id
        val random = Random(seed)

        var lastMotion: String? = null
        var lastTransition: String? = null

        return prompts.mapIndexed { index, prompt ->
            // Manual override: this image's own choice, never touched by
            // the generator — including on re-render.
            if (prompt.isManualEffect == true) {
                lastMotion = prompt.generatedMotionEffect ?: lastMotion
                lastTransition = prompt.generatedTransitionType ?: lastTransition
                return@mapIndexed prompt
            }

            // Already generated (cached) — keep it exactly as-is so adding
            // a NEW image later, or a fresh energy classification arriving
            // for a DIFFERENT image, never reshuffles this one. Just carry
            // its values forward for the next image's repeat-avoidance.
            if (prompt.generatedMotionEffect != null) {
                lastMotion = prompt.generatedMotionEffect
                lastTransition = prompt.generatedTransitionType ?: lastTransition
                return@mapIndexed prompt
            }

            val (motionPool, transitionPool) = poolsFor(project.videoStyle, prompt.energyLabel)

            val motion = pickAvoidingRepeat(random, motionPool, lastMotion)
            lastMotion = motion

            // First image has no incoming transition (nothing plays
            // before it) — leave it null, RenderEngine skips edge 0.
            val transition = if (index == 0) {
                null
            } else {
                pickAvoidingRepeat(random, transitionPool, lastTransition).also { lastTransition = it }
            }

            prompt.copy(
                generatedMotionEffect = motion,
                generatedTransitionType = transition
            )
        }
    }

    /** Which Motion/Transition pools an image draws from, given the
     * project's [VideoStyle] and that image's own [EnergyLevel] (null =
     * not classified yet / classification failed — treated as NEUTRAL,
     * which for MIXED_PRO means a 50/50 blend of both pools). */
    private fun poolsFor(videoStyle: String, energyLabel: String?): Pair<List<String>, List<String>> {
        return when (videoStyle) {
            VideoStyle.CINEMATIC -> CINEMATIC_MOTIONS to CINEMATIC_TRANSITIONS
            VideoStyle.DYNAMIC -> DYNAMIC_MOTIONS to DYNAMIC_TRANSITIONS
            else -> when (energyLabel) {
                EnergyLevel.CALM -> CINEMATIC_MOTIONS to CINEMATIC_TRANSITIONS
                EnergyLevel.ENERGETIC -> DYNAMIC_MOTIONS to DYNAMIC_TRANSITIONS
                // NEUTRAL or unclassified: blend both pools so Mixed Pro's
                // "controlled combination" rule still applies even before
                // classification finishes.
                else -> (CINEMATIC_MOTIONS + DYNAMIC_MOTIONS) to (CINEMATIC_TRANSITIONS + DYNAMIC_TRANSITIONS)
            }
        }
    }

    /** Picks a random entry from [pool], re-rolling (deterministically,
     * from the same [random] stream) up to a few times if it lands on
     * [avoid] — so the same effect never repeats back-to-back, without a
     * strict round-robin that would look mechanical. A 1-item pool always
     * returns that item (nothing to avoid it with). */
    private fun pickAvoidingRepeat(random: Random, pool: List<String>, avoid: String?): String {
        if (pool.size <= 1) return pool.first()
        var pick = pool[random.nextInt(pool.size)]
        var attempts = 0
        while (pick == avoid && attempts < 5) {
            pick = pool[random.nextInt(pool.size)]
            attempts++
        }
        return pick
    }
}
