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
        AutoGenProjectEntity::class, PromptEntity::class
    ],
    version = 5, // v5: added PromptEntity.manualDurationMs (per-image manual duration override)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
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
