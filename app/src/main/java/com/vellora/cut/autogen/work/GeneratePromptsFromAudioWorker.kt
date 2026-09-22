package com.vellora.cut.autogen.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vellora.cut.autogen.captions.AutoPromptGenerator
import com.vellora.cut.autogen.captions.CaptionGenerator
import com.vellora.cut.autogen.data.PromptEntity
import com.vellora.cut.autogen.data.PromptStatus
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.network.CloudflareApiException
import com.vellora.cut.autogen.network.CloudflareAiClient
import com.vellora.cut.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 3: "audio se khudkar prompts" — the person's own voice-over is
 * the ONLY input. This worker:
 *
 *  1. Transcribes it via the existing real Whisper pipeline (same
 *     [CaptionGenerator]/WhisperChunkedTranscriber captions already use —
 *     real spoken timestamps, not a guess).
 *  2. Merges the (often very short) Whisper segments into scene-length
 *     chunks (see [AutoPromptGenerator]) — this is what fixes the "kuch
 *     images bohot chhoti, kuch bohot lambi ban jaati hain" drift the
 *     person described: each scene's on-screen duration comes straight
 *     from where its narration actually starts/ends in the audio.
 *  3. Turns each scene's narration text into a real AI-written image
 *     prompt (CloudflareAiClient.generateImagePromptFromNarration). If
 *     that call fails for a scene, it falls back to the scene's own
 *     narration text as the prompt — the pipeline never stalls or drops a
 *     scene just because one AI call failed.
 *  4. Saves the result as this project's prompt list, `pending`, each
 *     with [PromptEntity.manualDurationMs] already set from its own scene
 *     — exactly what GenerateImagesWorker (unchanged) then generates
 *     images for, and what SmartSequenceGenerator then picks Motion+
 *     Transition for. Nothing downstream needed to change for this.
 *
 * This REPLACES this project's current prompt list (same as manually
 * pasting prompts does) — meant for a project whose prompts haven't been
 * hand-written, or where the person wants to regenerate them from audio.
 */
class GeneratePromptsFromAudioWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        if (projectId == -1L) return@withContext Result.failure()

        val credentials = SecureCredentialStore(applicationContext)
        val accounts = credentials.accounts
        if (accounts.isEmpty()) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Koi Cloudflare account save nahi hai — pehle Settings mein add karein")
            )
        }

        val dao = AppDatabase.getInstance(applicationContext).autoGenDao()
        val project = dao.getProject(projectId)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Project not found"))

        val voiceOverUri = project.voiceOverUri
        if (voiceOverUri == null) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Is project mein voice-over set nahi hai")
            )
        }

        setProgress(workDataOf(KEY_STAGE to STAGE_TRANSCRIBING))
        val transcription = CaptionGenerator.generate(
            context = applicationContext,
            voiceOverUri = voiceOverUri,
            processedAudioPath = project.processedAudioPath,
            accounts = accounts,
            language = project.captionsLanguage
        )
        val segments = transcription.getOrElse { e ->
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Voice-over sun kar samajh nahi paya: ${e.message}")
            )
        }
        if (segments.isEmpty()) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Voice-over mein koi bola hua lafz nahi mila")
            )
        }

        val scenes = AutoPromptGenerator.merge(segments, totalAudioDurationMs = project.voiceOverDurationMs)

        setProgress(workDataOf(KEY_STAGE to STAGE_WRITING_PROMPTS, KEY_TOTAL to scenes.size, KEY_DONE to 0))

        val client = CloudflareAiClient()
        val exhaustedAccountIds = mutableSetOf<String>()
        val prompts = mutableListOf<PromptEntity>()

        for ((index, scene) in scenes.withIndex()) {
            if (isStopped) {
                // Cancelled mid-run: don't save a partial/truncated prompt
                // list silently — that would leave the project with fewer
                // images than its own audio actually needs, with no error
                // shown anywhere. Bail out with nothing written instead;
                // the existing prompt list (if any) is left untouched.
                return@withContext Result.failure(workDataOf(KEY_ERROR to "Cancel ho gaya"))
            }

            val usableAccounts = accounts.filter { it.accountId !in exhaustedAccountIds }
            var promptText: String? = null
            for (account in usableAccounts) {
                try {
                    promptText = client.generateImagePromptFromNarration(
                        narrationText = scene.narrationText,
                        accountId = account.accountId,
                        apiToken = account.apiToken
                    )
                    break
                } catch (e: CloudflareApiException) {
                    if (e.isQuotaExceeded) {
                        exhaustedAccountIds += account.accountId
                        continue
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    break
                }
            }

            // AI prompt-writing failed for this one scene — fall back to
            // its own narration text rather than dropping the scene or
            // stalling the whole batch. Image quality for this one slide
            // will be plainer, but timing/sync stays intact and nothing
            // else in the batch is affected.
            prompts += PromptEntity(
                projectId = projectId,
                orderIndex = index,
                label = "%03d".format(index + 1),
                promptText = promptText ?: scene.narrationText,
                status = PromptStatus.PENDING,
                manualDurationMs = scene.durationMs
            )

            setProgress(workDataOf(KEY_STAGE to STAGE_WRITING_PROMPTS, KEY_TOTAL to scenes.size, KEY_DONE to index + 1))
        }

        dao.deletePromptsForProject(projectId)
        dao.insertPrompts(prompts)

        Result.success(workDataOf(KEY_DONE to prompts.size, KEY_TOTAL to scenes.size))
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ERROR = "error"
        const val KEY_STAGE = "stage"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val STAGE_TRANSCRIBING = "transcribing"
        const val STAGE_WRITING_PROMPTS = "writing_prompts"

        fun uniqueWorkName(projectId: Long) = "autogen_prompts_from_audio_$projectId"
    }
}
