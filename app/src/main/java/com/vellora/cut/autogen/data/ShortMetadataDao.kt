package com.vellora.cut.autogen.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortMetadataDao {

    @Insert
    suspend fun insert(entity: ShortMetadataEntity): Long

    @Update
    suspend fun update(entity: ShortMetadataEntity)

    @Delete
    suspend fun delete(entity: ShortMetadataEntity)

    @Query("SELECT * FROM short_metadata_projects ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ShortMetadataEntity>>

    @Query("SELECT * FROM short_metadata_projects WHERE id = :id")
    suspend fun getById(id: Long): ShortMetadataEntity?
}
