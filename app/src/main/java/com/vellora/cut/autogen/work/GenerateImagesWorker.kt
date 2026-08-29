package com.vellora.cut.autogen.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vellora.cut.autogen.data.AutoGenProjectStatus
import com.vellora.cut.autogen.data.PromptStatus
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.network.CloudflareApiException
import com.vellora.cut.autogen.network.CloudflareAiClient
import com.vellora.cut.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generates images for every prompt in a project that isn't already
 * `done`, in order, one at a time.
 *
 * This IS the resume system: the query that selects work
 * (`getRemainingPrompts`) skips anything already `done`, so re-running
 * this worker after a crash, dropped connection, or app restart picks up
 * exactly where it left off — nothing already generated is redone.
 *
 * A failure on one prompt marks it `failed` and moves on to the next;
 * it never aborts the whole batch.
 */
class GenerateImagesWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        if (projectId == -1L) return@withContext Result.failure()

        val credentials = SecureCredentialStore(applicationContext)
        if (!credentials.hasCredentials()) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Cloudflare credentials not set — open Settings first")
            )
        }

        val dao = AppDatabase.getInstance(applicationContext).autoGenDao()
        val client = CloudflareAiClient()

        val project = dao.getProject(projectId)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Project not found"))

        dao.updateProject(project.copy(status = AutoGenProjectStatus.GENERATING))

        val outputDir = File(applicationContext.filesDir, "autogen/$projectId/images").apply { mkdirs() }

        val remaining = dao.getRemainingPrompts(projectId)
        var successCount = 0
        var failCount = 0

        for (prompt in remaining) {
            if (isStopped) break

            dao.updatePrompt(prompt.copy(status = PromptStatus.GENERATING, errorMessage = null))

            try {
                val imageBytes = client.generateImage(
                    prompt = prompt.promptText,
                    accountId = credentials.accountId,
                    apiToken = credentials.apiToken,
                    model = credentials.imageModel
                )

                val imageFile = File(outputDir, "${prompt.label}.png")
                imageFile.writeBytes(imageBytes)

                dao.updatePrompt(
                    prompt.copy(
                        status = PromptStatus.DONE,
                        imagePath = imageFile.absolutePath,
                        errorMessage = null
                    )
                )
                successCount++
            } catch (e: CloudflareApiException) {
                dao.updatePrompt(prompt.copy(status = PromptStatus.FAILED, errorMessage = e.message))
                failCount++
            } catch (e: Exception) {
                dao.updatePrompt(prompt.copy(status = PromptStatus.FAILED, errorMessage = e.message ?: "Unknown error"))
                failCount++
            }

            setProgress(
                workDataOf(
                    KEY_DONE to successCount,
                    KEY_FAILED to failCount,
                    KEY_TOTAL to remaining.size
                )
            )
        }

        val stillRemaining = dao.getRemainingPrompts(projectId)
        val finalStatus = if (stillRemaining.isEmpty()) {
            AutoGenProjectStatus.READY
        } else {
            AutoGenProjectStatus.DRAFT
        }
        dao.updateProject(dao.getProject(projectId)!!.copy(status = finalStatus))

        Result.success(workDataOf(KEY_DONE to successCount, KEY_FAILED to failCount))
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ERROR = "error"
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        const val KEY_TOTAL = "total"

        fun uniqueWorkName(projectId: Long) = "autogen_generate_$projectId"
    }
}
