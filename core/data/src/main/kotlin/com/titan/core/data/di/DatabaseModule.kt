package com.titan.core.data.di

import android.content.Context
import androidx.room.Room
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.db.dao.AchievementDao
import com.titan.core.data.db.dao.BodyMeasurementDao
import com.titan.core.data.db.dao.CollectedItemDao
import com.titan.core.data.db.dao.ExerciseDao
import com.titan.core.data.db.dao.MuscleGroupDao
import com.titan.core.data.db.dao.PersonalRecordDao
import com.titan.core.data.db.dao.QuestCompletionDao
import com.titan.core.data.db.dao.SessionExerciseDao
import com.titan.core.data.db.dao.SessionLootClaimDao
import com.titan.core.data.db.dao.SessionXpClaimDao
import com.titan.core.data.db.dao.TagDao
import com.titan.core.data.db.dao.UserProgressDao
import com.titan.core.data.db.dao.WorkoutSessionDao
import com.titan.core.data.db.dao.WorkoutSetDao
import com.titan.core.data.db.dao.WorkoutTemplateDao
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
const val DATABASE_NAME = "titan.db"

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // addMigrations is vararg-only; this runs once per process at singleton
    // creation, so the spread's copy cost is irrelevant.
    @Suppress("SpreadOperator")
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TitanDatabase =
        Room.databaseBuilder(context, TitanDatabase::class.java, DATABASE_NAME)
            .addCallback(TitanDatabase.foreignKeysCallback)
            .addCallback(TitanDatabase.singleRowProgressCallback)
            .addCallback(TitanDatabase.seedCallback(context))
            .addMigrations(*TitanDatabase.MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun provideExerciseDao(db: TitanDatabase): ExerciseDao = db.exerciseDao()

    @Provides
    @Singleton
    fun provideMuscleGroupDao(db: TitanDatabase): MuscleGroupDao = db.muscleGroupDao()

    @Provides
    @Singleton
    fun provideTagDao(db: TitanDatabase): TagDao = db.tagDao()

    @Provides
    @Singleton
    fun provideWorkoutTemplateDao(db: TitanDatabase): WorkoutTemplateDao = db.workoutTemplateDao()

    @Provides
    @Singleton
    fun provideWorkoutSessionDao(db: TitanDatabase): WorkoutSessionDao = db.workoutSessionDao()

    @Provides
    @Singleton
    fun provideSessionExerciseDao(db: TitanDatabase): SessionExerciseDao = db.sessionExerciseDao()

    @Provides
    @Singleton
    fun provideWorkoutSetDao(db: TitanDatabase): WorkoutSetDao = db.workoutSetDao()

    @Provides
    @Singleton
    fun providePersonalRecordDao(db: TitanDatabase): PersonalRecordDao = db.personalRecordDao()

    @Provides
    @Singleton
    fun provideBodyMeasurementDao(db: TitanDatabase): BodyMeasurementDao = db.bodyMeasurementDao()

    @Provides
    @Singleton
    fun provideUserProgressDao(db: TitanDatabase): UserProgressDao = db.userProgressDao()

    @Provides
    @Singleton
    fun provideAchievementDao(db: TitanDatabase): AchievementDao = db.achievementDao()

    @Provides
    @Singleton
    fun provideSessionXpClaimDao(db: TitanDatabase): SessionXpClaimDao = db.sessionXpClaimDao()

    @Provides
    @Singleton
    fun provideSessionLootClaimDao(db: TitanDatabase): SessionLootClaimDao = db.sessionLootClaimDao()

    @Provides
    @Singleton
    fun provideQuestCompletionDao(db: TitanDatabase): QuestCompletionDao = db.questCompletionDao()

    @Provides
    @Singleton
    fun provideCollectedItemDao(db: TitanDatabase): CollectedItemDao = db.collectedItemDao()
}
