package com.opengym.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opengym.core.data.db.dao.BodyWeightLogDao
import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.dao.MuscleGroupDao
import com.opengym.core.data.db.dao.PersonalRecordDao
import com.opengym.core.data.db.dao.SessionExerciseDao
import com.opengym.core.data.db.dao.TagDao
import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.data.db.dao.WorkoutTemplateDao
import com.opengym.core.data.db.entity.BodyWeightLogEntity
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.ExerciseTagEntity
import com.opengym.core.data.db.entity.MuscleGroupEntity
import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.data.db.entity.SessionExerciseEntity
import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.data.db.entity.TemplateExerciseEntity
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
        WorkoutSessionEntity::class,
        SessionExerciseEntity::class,
        WorkoutSetEntity::class,
        PersonalRecordEntity::class,
        BodyWeightLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OpenGymDatabase : RoomDatabase() {

    abstract fun exerciseDao(): ExerciseDao
    abstract fun muscleGroupDao(): MuscleGroupDao
    abstract fun tagDao(): TagDao
    abstract fun workoutTemplateDao(): WorkoutTemplateDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun sessionExerciseDao(): SessionExerciseDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun personalRecordDao(): PersonalRecordDao
    abstract fun bodyWeightLogDao(): BodyWeightLogDao

    companion object {
        val foreignKeysCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        val seedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DatabaseSeed.insertInto(db)
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
