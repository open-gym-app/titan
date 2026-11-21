package com.titan.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.titan.core.data.db.dao.AchievementDao
import com.titan.core.data.db.dao.BackupDao
import com.titan.core.data.db.dao.BodyMeasurementDao
import com.titan.core.data.db.dao.CollectedItemDao
import com.titan.core.data.db.dao.ExerciseDao
import com.titan.core.data.db.dao.LocalDataDao
import com.titan.core.data.db.dao.MuscleGroupDao
import com.titan.core.data.db.dao.PersonalRecordDao
import com.titan.core.data.db.dao.ProgressPhotoDao
import com.titan.core.data.db.dao.QuestCompletionDao
import com.titan.core.data.db.dao.SessionExerciseDao
import com.titan.core.data.db.dao.SessionLootClaimDao
import com.titan.core.data.db.dao.SessionXpClaimDao
import com.titan.core.data.db.dao.TagDao
import com.titan.core.data.db.dao.UserProgressDao
import com.titan.core.data.db.dao.WorkoutSessionDao
import com.titan.core.data.db.dao.WorkoutSetDao
import com.titan.core.data.db.dao.WorkoutTemplateDao
import com.titan.core.data.db.entity.AchievementEntity
import com.titan.core.data.db.entity.BodyMeasurementEntity
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.data.db.entity.ExerciseEntity
import com.titan.core.data.db.entity.ExerciseMuscleGroupEntity
import com.titan.core.data.db.entity.ExerciseTagEntity
import com.titan.core.data.db.entity.MuscleGroupEntity
import com.titan.core.data.db.entity.PersonalRecordEntity
import com.titan.core.data.db.entity.ProgressPhotoEntity
import com.titan.core.data.db.entity.QuestCompletionEntity
import com.titan.core.data.db.entity.SessionExerciseEntity
import com.titan.core.data.db.entity.SessionLootClaimEntity
import com.titan.core.data.db.entity.SessionXpClaimEntity
import com.titan.core.data.db.entity.TagEntity
import com.titan.core.data.db.entity.TemplateExerciseEntity
import com.titan.core.data.db.entity.TemplateSetEntity
import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.data.db.entity.WorkoutSetEntity
import com.titan.core.data.db.entity.WorkoutTemplateEntity

@Database(
    entities = [
        ExerciseEntity::class,
        MuscleGroupEntity::class,
        ExerciseMuscleGroupEntity::class,
        TagEntity::class,
        ExerciseTagEntity::class,
        WorkoutTemplateEntity::class,
        TemplateExerciseEntity::class,
        TemplateSetEntity::class,
        WorkoutSessionEntity::class,
        SessionExerciseEntity::class,
        WorkoutSetEntity::class,
        PersonalRecordEntity::class,
        BodyMeasurementEntity::class,
        UserProgressEntity::class,
        AchievementEntity::class,
        SessionXpClaimEntity::class,
        SessionLootClaimEntity::class,
        QuestCompletionEntity::class,
        CollectedItemEntity::class,
        ProgressPhotoEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TitanDatabase : RoomDatabase() {

    abstract fun exerciseDao(): ExerciseDao
    abstract fun localDataDao(): LocalDataDao
    abstract fun muscleGroupDao(): MuscleGroupDao
    abstract fun tagDao(): TagDao
    abstract fun workoutTemplateDao(): WorkoutTemplateDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun sessionExerciseDao(): SessionExerciseDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun personalRecordDao(): PersonalRecordDao
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun userProgressDao(): UserProgressDao
    abstract fun achievementDao(): AchievementDao
    abstract fun sessionXpClaimDao(): SessionXpClaimDao
    abstract fun sessionLootClaimDao(): SessionLootClaimDao
    abstract fun questCompletionDao(): QuestCompletionDao
    abstract fun collectedItemDao(): CollectedItemDao
    abstract fun progressPhotoDao(): ProgressPhotoDao

    /**
     * Every table, whole, for export and import — see [BackupDao] for why backup gets a DAO of
     * its own rather than reaching through the ones the screens use.
     */
    abstract fun backupDao(): BackupDao

    companion object {
        val foreignKeysCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        /**
         * Re-creates `user_progress` with the `CHECK` that pins it to one row.
         *
         * Room has no way to express a `CHECK` in `@Entity` (2.8), so the table it generates
         * accepts any id and the single-row rule would live in the callers instead — which is
         * exactly what [UserProgressEntity] refuses to rely on. This drops the table Room has
         * just created and puts back the same one with the constraint on it, from
         * [UserProgressEntity.CREATE_TABLE_SQL].
         *
         * Safe because it runs in `onCreate`, moments after `createAllTables` and before
         * anything can have written a row: there is never data to lose. Room's own schema
         * validation compares columns, indices and foreign keys and does not look at check
         * constraints, so the rebuilt table still validates as the one the entities describe —
         * which also means a column added to [UserProgressEntity] and not to the statement
         * fails that validation rather than passing silently.
         *
         * Anything that opens the database must add this callback, tests included, or the
         * constraint is simply absent there.
         */
        val singleRowProgressCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `user_progress`")
                db.execSQL(UserProgressEntity.CREATE_TABLE_SQL)
            }
        }

        /**
         * Seeds the bundled exercise catalogue and the default templates on a fresh install.
         * Takes a `Context` because the seed needs two things from the platform: the generated
         * SQL asset, and the string resources the default templates are named from.
         */
        fun seedCallback(context: Context) =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DatabaseSeed.insertInto(db, context)
                }
            }

        /**
         * Every version bump beyond 1 must add its `Migration` here and a matching
         * test in the migration test suite. The database is deliberately *not*
         * built with a destructive fallback: a version bump without a migration
         * here throws at startup instead of silently wiping user history.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
