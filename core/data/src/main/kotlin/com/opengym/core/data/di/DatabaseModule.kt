package com.opengym.core.data.di

import android.content.Context
import androidx.room.Room
import com.opengym.core.data.db.OpenGymDatabase
import com.opengym.core.data.db.dao.BodyMeasurementDao
import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.dao.MuscleGroupDao
import com.opengym.core.data.db.dao.PersonalRecordDao
import com.opengym.core.data.db.dao.SessionExerciseDao
import com.opengym.core.data.db.dao.TagDao
import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.data.db.dao.WorkoutTemplateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // addMigrations is vararg-only; this runs once per process at singleton
    // creation, so the spread's copy cost is irrelevant.
    @Suppress("SpreadOperator")
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpenGymDatabase =
        Room.databaseBuilder(context, OpenGymDatabase::class.java, "open-gym.db")
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .addCallback(OpenGymDatabase.seedCallback(context.assets))
            .addMigrations(*OpenGymDatabase.MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun provideExerciseDao(db: OpenGymDatabase): ExerciseDao = db.exerciseDao()

    @Provides
    @Singleton
    fun provideMuscleGroupDao(db: OpenGymDatabase): MuscleGroupDao = db.muscleGroupDao()

    @Provides
    @Singleton
    fun provideTagDao(db: OpenGymDatabase): TagDao = db.tagDao()

    @Provides
    @Singleton
    fun provideWorkoutTemplateDao(db: OpenGymDatabase): WorkoutTemplateDao = db.workoutTemplateDao()

    @Provides
    @Singleton
    fun provideWorkoutSessionDao(db: OpenGymDatabase): WorkoutSessionDao = db.workoutSessionDao()

    @Provides
    @Singleton
    fun provideSessionExerciseDao(db: OpenGymDatabase): SessionExerciseDao = db.sessionExerciseDao()

    @Provides
    @Singleton
    fun provideWorkoutSetDao(db: OpenGymDatabase): WorkoutSetDao = db.workoutSetDao()

    @Provides
    @Singleton
    fun providePersonalRecordDao(db: OpenGymDatabase): PersonalRecordDao = db.personalRecordDao()

    @Provides
    @Singleton
    fun provideBodyMeasurementDao(db: OpenGymDatabase): BodyMeasurementDao = db.bodyMeasurementDao()
}
