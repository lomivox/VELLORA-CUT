package com.vellora.cut.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vellora.cut.autogen.data.AutoGenDao
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.data.PromptEntity

@Database(
    entities = [
        ProjectEntity::class, ClipEntity::class, KeyframeEntity::class,
        AutoGenProjectEntity::class, PromptEntity::class
    ],
    version = 2, // v2: added AutoGenProjectEntity.renderedFilePath (Phase F)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    // Editor DAOs added in Phase 1 once timeline editing lands
    abstract fun autoGenDao(): AutoGenDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vellora.db"
                )
                    // Pre-release, no shipped schema yet — safe to rebuild on change.
                    // Revisit once the app has real users with local data to preserve.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
