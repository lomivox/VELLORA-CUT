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
import com.vellora.cut.autogen.data.ShortMetadataDao
import com.vellora.cut.autogen.data.ShortMetadataEntity

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

/** v7 -> v8: new table for the Shorts Metadata tool — a project here is any
 * Gallery video the person picked to generate a Title/Description/Tags/
 * Hashtags for, completely separate from AutoGen's own projects table. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `short_metadata_projects` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `videoUri` TEXT NOT NULL,
                `videoFileName` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `errorMessage` TEXT,
                `transcript` TEXT,
                `researchedKeywords` TEXT,
                `generatedTitle` TEXT,
                `generatedDescription` TEXT,
                `generatedTags` TEXT,
                `generatedHashtags` TEXT
            )
            """.trimIndent()
        )
    }
}

/** v8 -> v9: track a video's YouTube upload result (auto-upload feature). */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE short_metadata_projects ADD COLUMN youtubeVideoUrl TEXT")
    }
}

/** v9 -> v10: real Short/Long reshape choice, stored per project so retry
 * uses the same target the person originally picked. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE short_metadata_projects ADD COLUMN uploadTarget TEXT NOT NULL DEFAULT 'auto'")
    }
}

/** v10 -> v11: caption language (Urdu/Hindi script choice) + font. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE autogen_projects ADD COLUMN captionsLanguage TEXT NOT NULL DEFAULT 'ur'")
        db.execSQL("ALTER TABLE autogen_projects ADD COLUMN captionsFont TEXT NOT NULL DEFAULT 'system_default'")
    }
}

@Database(
    entities = [
        AutoGenProjectEntity::class, PromptEntity::class, ShortMetadataEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun autoGenDao(): AutoGenDao
    abstract fun shortMetadataDao(): ShortMetadataDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vellora.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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
