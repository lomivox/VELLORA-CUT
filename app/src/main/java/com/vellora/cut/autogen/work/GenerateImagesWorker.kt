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
        val accounts = credentials.accounts
        if (accounts.isEmpty()) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Koi Cloudflare account save nahi hai — pehle Settings mein add karein")
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

        // Accounts whose daily quota ran out during THIS run — skipped for
        // every prompt after that (quota only resets once a day, so there's
        // no point re-trying a dead account on every single prompt).
        val exhaustedAccountIds = mutableSetOf<String>()

        for (prompt in remaining) {
            if (isStopped) break

            dao.updatePrompt(prompt.copy(status = PromptStatus.GENERATING, errorMessage = null))

            val usableAccounts = accounts.filter { it.accountId !in exhaustedAccountIds }
            var imageBytes: ByteArray? = null
            var lastError: String? = null

            if (usableAccounts.isEmpty()) {
                lastError = "Sab ${accounts.size} accounts ka aaj ka quota khatam ho chuka hai"
            } else {
                for (account in usableAccounts) {
                    try {
                        imageBytes = client.generateImage(
                            prompt = prompt.promptText,
                            accountId = account.accountId,
                            apiToken = account.apiToken,
                            model = credentials.imageModel
                        )
                        break // this account worked, stop trying others
                    } catch (e: CloudflareApiException) {
                        lastError = e.message
                        if (e.isQuotaExceeded) {
                            exhaustedAccountIds += account.accountId
                            continue // try the next pooled account
                        } else {
                            break // a real error (bad prompt etc.) — don't burn through every account for it
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "Unknown error"
                        break
                    }
                }
            }

            if (imageBytes != null) {
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
            } else {
                dao.updatePrompt(prompt.copy(status = PromptStatus.FAILED, errorMessage = lastError))
                failCount++
            }

            setProgress(
                workDataOf(
                    KEY_DONE to successCount,
                    KEY_FAILED to failCount,
                    KEY_TOTAL to remaining.size,
                    KEY_ACCOUNTS_EXHAUSTED to exhaustedAccountIds.size,
                    KEY_ACCOUNTS_TOTAL to accounts.size
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
        const val KEY_ACCOUNTS_EXHAUSTED = "accounts_exhausted"
        const val KEY_ACCOUNTS_TOTAL = "accounts_total"

        fun uniqueWorkName(projectId: Long) = "autogen_generate_$projectId"
    }
}
