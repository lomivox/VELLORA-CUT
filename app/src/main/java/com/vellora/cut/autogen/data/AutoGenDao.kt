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

    @Query("SELECT COUNT(*) FROM autogen_prompts WHERE projectId = :projectId")
    suspend fun countPrompts(projectId: Long): Int
}
