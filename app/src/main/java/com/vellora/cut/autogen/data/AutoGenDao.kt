package com.vellora.cut.autogen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoGenDao {

    @Insert
    suspend fun insertProject(project: AutoGenProjectEntity): Long

    @Update
    suspend fun updateProject(project: AutoGenProjectEntity)

    @Query("SELECT * FROM autogen_projects ORDER BY createdAt DESC")
    fun observeProjects(): Flow<List<AutoGenProjectEntity>>

    @Query("SELECT * FROM autogen_projects WHERE id = :projectId")
    suspend fun getProject(projectId: Long): AutoGenProjectEntity?

    @Query("DELETE FROM autogen_prompts WHERE projectId = :projectId")
    suspend fun deletePromptsForProject(projectId: Long)

    @Insert
    suspend fun insertPrompts(prompts: List<PromptEntity>)

    @Update
    suspend fun updatePrompt(prompt: PromptEntity)

    @Query("SELECT * FROM autogen_prompts WHERE projectId = :projectId ORDER BY orderIndex ASC")
    fun observePrompts(projectId: Long): Flow<List<PromptEntity>>

    @Query("SELECT * FROM autogen_prompts WHERE projectId = :projectId AND status != 'done' ORDER BY orderIndex ASC")
    suspend fun getRemainingPrompts(projectId: Long): List<PromptEntity>

    @Query("SELECT COUNT(*) FROM autogen_prompts WHERE projectId = :projectId")
    suspend fun countPrompts(projectId: Long): Int

    /** Images that are already generated (`done`) but whose AI energy
     * classification hasn't succeeded yet (`pending` — never tried — or
     * `failed` — tried and dropped). GenerateImagesWorker calls this on
     * every run, so a failed classification always gets retried the next
     * time the worker runs for this project — no manual retry step, no
     * re-generating the image itself. */
    @Query(
        "SELECT * FROM autogen_prompts WHERE projectId = :projectId AND status = 'done' " +
            "AND energyClassificationStatus != 'done' ORDER BY orderIndex ASC"
    )
    suspend fun getPromptsNeedingEnergyClassification(projectId: Long): List<PromptEntity>

    /** All generated images for a project, in order — what
     * SmartSequenceGenerator reads to assign Motion+Transition. */
    @Query("SELECT * FROM autogen_prompts WHERE projectId = :projectId AND status = 'done' ORDER BY orderIndex ASC")
    suspend fun getDonePrompts(projectId: Long): List<PromptEntity>
}
