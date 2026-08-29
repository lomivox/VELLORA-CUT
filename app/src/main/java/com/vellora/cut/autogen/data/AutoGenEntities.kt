package com.vellora.cut.autogen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val timelineMode: String = TimelineMode.SCALE
)

/** Values for [AutoGenProjectEntity.timelineMode]. */
object TimelineMode {
    /** Every image's duration is stretched/shrunk equally so total = voice-over length. */
    const val SCALE = "scale"
    /** Every image keeps its set duration; only the last image absorbs the remainder. */
    const val HOLD_LAST = "hold_last"
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
    val errorMessage: String? = null
)
