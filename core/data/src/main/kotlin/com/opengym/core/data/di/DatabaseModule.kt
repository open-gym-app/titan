package com.opengym.core.data.di

import android.content.Context
import androidx.room.Room
import com.opengym.core.data.db.OpenGymDatabase
import com.opengym.core.data.db.dao.AchievementDao
import com.opengym.core.data.db.dao.BodyMeasurementDao
import com.opengym.core.data.db.dao.CollectedItemDao
import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.dao.MuscleGroupDao
import com.opengym.core.data.db.dao.PersonalRecordDao
import com.opengym.core.data.db.dao.QuestCompletionDao
import com.opengym.core.data.db.dao.SessionExerciseDao
import com.opengym.core.data.db.dao.SessionLootClaimDao
import com.opengym.core.data.db.dao.SessionXpClaimDao
import com.opengym.core.data.db.dao.TagDao
import com.opengym.core.data.db.dao.UserProgressDao
import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.data.db.dao.WorkoutTemplateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The database file's name, on disk under the app's own database directory.
 *
 * Named once and shared rather than written twice: `LocalDataRepositoryImpl` has to stat the
 * file *by name* to put a size on the backup screen, and a rename here that it did not follow
 * would turn that figure into a permanent zero — a wrong number nobody would notice.
 *
 * Public rather than `internal` for a second reader outside this module: `:app`'s
 * `BackupRulesTest` builds the paths it expects Android's backup rules to exclude out of this
 * constant and the others like it, so a rename here fails that test instead of silently
 * putting the user's training history back on Google's servers.
 */
const val DATABASE_NAME = "open-gym.db"

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // addMigrations is vararg-only; this runs once per process at singleton
    // creation, so the spread's copy cost is irrelevant.
    @Suppress("SpreadOperator")
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpenGymDatabase =
        Room.databaseBuilder(context, OpenGymDatabase::class.java, DATABASE_NAME)
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .addCallback(OpenGymDatabase.singleRowProgressCallback)
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

    @Provides
    @Singleton
    fun provideUserProgressDao(db: OpenGymDatabase): UserProgressDao = db.userProgressDao()

    @Provides
    @Singleton
    fun provideAchievementDao(db: OpenGymDatabase): AchievementDao = db.achievementDao()

    @Provides
    @Singleton
    fun provideSessionXpClaimDao(db: OpenGymDatabase): SessionXpClaimDao = db.sessionXpClaimDao()

    @Provides
    @Singleton
    fun provideSessionLootClaimDao(db: OpenGymDatabase): SessionLootClaimDao = db.sessionLootClaimDao()

    @Provides
    @Singleton
    fun provideQuestCompletionDao(db: OpenGymDatabase): QuestCompletionDao = db.questCompletionDao()

    @Provides
    @Singleton
    fun provideCollectedItemDao(db: OpenGymDatabase): CollectedItemDao = db.collectedItemDao()
}
