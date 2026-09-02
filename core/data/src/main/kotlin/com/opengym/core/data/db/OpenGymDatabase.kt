package com.opengym.core.data.db

import android.content.res.AssetManager
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opengym.core.data.db.dao.AchievementDao
import com.opengym.core.data.db.dao.BodyMeasurementDao
import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.dao.LocalDataDao
import com.opengym.core.data.db.dao.MuscleGroupDao
import com.opengym.core.data.db.dao.PersonalRecordDao
import com.opengym.core.data.db.dao.QuestCompletionDao
import com.opengym.core.data.db.dao.SessionExerciseDao
import com.opengym.core.data.db.dao.SessionXpClaimDao
import com.opengym.core.data.db.dao.TagDao
import com.opengym.core.data.db.dao.UserProgressDao
import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.data.db.dao.WorkoutTemplateDao
import com.opengym.core.data.db.entity.AchievementEntity
import com.opengym.core.data.db.entity.BodyMeasurementEntity
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.ExerciseTagEntity
import com.opengym.core.data.db.entity.MuscleGroupEntity
import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.data.db.entity.QuestCompletionEntity
import com.opengym.core.data.db.entity.SessionExerciseEntity
import com.opengym.core.data.db.entity.SessionXpClaimEntity
import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.TemplateSetEntity
import com.opengym.core.data.db.entity.UserProgressEntity
import com.opengym.core.data.db.entity.WorkoutSessionEntity
import com.opengym.core.data.db.entity.WorkoutSetEntity
import com.opengym.core.data.db.entity.WorkoutTemplateEntity

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
        QuestCompletionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OpenGymDatabase : RoomDatabase() {

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
    abstract fun questCompletionDao(): QuestCompletionDao

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
         * Seeds the bundled exercise catalogue on a fresh install. Takes the
         * [AssetManager] rather than a `Context` because reading the generated
         * SQL asset is the only thing the seed needs from the platform.
         */
        fun seedCallback(assets: AssetManager) =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DatabaseSeed.insertInto(db, assets)
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
