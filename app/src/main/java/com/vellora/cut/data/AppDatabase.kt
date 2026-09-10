package com.vellora.cut.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vellora.cut.autogen.data.AutoGenDao
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.autogen.data.PromptEntity

/**
 * v4 -> v5: added PromptEntity.manualDurationMs (per-image manual duration
 * override). This is the first REAL migration in the app — every version
 * bump before this used fallbackToDestructiveMigration(), which wipes and
 * recreates every table on any schema change, silently deleting every
 * saved project. From this point on, add one Migration object per future
 * schema change instead of relying on the destructive fallback, so
 * updating the app never deletes existing projects again.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE autogen_prompts ADD COLUMN manualDurationMs INTEGER")
    }
}

/** v5 -> v6: added real noise-reduction + volume-boost fields on the project. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE autogen_projects ADD COLUMN noiseReductionPercent INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE autogen_projects ADD COLUMN volumePercent INTEGER NOT NULL DEFAULT 100")
        db.execSQL("ALTER TABLE autogen_projects ADD COLUMN processedAudioPath TEXT")
    }
}

/** v6 -> v7: added real Whisper-transcribed captions (JSON) + enabled flag. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE autogen_projects ADD COLUMN captionsJson TEXT")
        db.execSQL("ALTER TABLE autogen_projects ADD COLUMN captionsEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        AutoGenProjectEntity::class, PromptEntity::class
    ],
    version = 7,
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // Safety net ONLY for a version jump with no migration
                    // listed above (e.g. someone on a version older than 4,
                    // or a future bump where a Migration was forgotten) —
                    // add a new MIGRATION_x_y above for every future schema
                    // change instead of leaning on this.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
