package com.vellora.cut.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val trackIndex: Int,
    val sourceUri: String,
    val startMs: Long,
    val endMs: Long,
    val timelinePositionMs: Long
)

@Entity(tableName = "keyframes")
data class KeyframeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clipId: Long,
    val timeMs: Long,
    val property: String,
    val value: Float
)
