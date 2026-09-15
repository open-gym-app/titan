package com.opengym.core.data.di

import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.usecase.ConsistencyUseCase
import com.opengym.core.domain.usecase.ExerciseProgressionUseCase
import com.opengym.core.domain.usecase.NextLoadUseCase
import com.opengym.core.domain.usecase.VolumeTrendUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The progression aggregations, wired the way [PrUseCaseModule] wires the PR engine — see
 * [SessionUseCaseModule]'s doc for why `:core:data` is where these are bound and why
 * `@Provides` rather than `@Inject constructor`.
 *
 * Unscoped: each is a stateless read over already-scoped repositories, per ARCHITECTURE.md's
 * table. None holds the period or the exercise it is asked about, so the screen that owns the
 * timeframe selector (#32) can call the same instance for every timeframe, and the active
 * session can ask one [NextLoadUseCase] about every exercise it holds.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProgressionUseCaseModule {

    @Provides
    fun provideVolumeTrendUseCase(progressionRepository: ProgressionRepository): VolumeTrendUseCase =
        VolumeTrendUseCase(progressionRepository = progressionRepository)

    @Provides
    fun provideConsistencyUseCase(progressionRepository: ProgressionRepository): ConsistencyUseCase =
        ConsistencyUseCase(progressionRepository = progressionRepository)

    @Provides
    fun provideExerciseProgressionUseCase(
        progressionRepository: ProgressionRepository,
        personalRecordRepository: PersonalRecordRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): ExerciseProgressionUseCase =
        ExerciseProgressionUseCase(
            progressionRepository = progressionRepository,
            personalRecordRepository = personalRecordRepository,
            userPreferencesRepository = userPreferencesRepository,
        )

    @Provides
    fun provideNextLoadUseCase(
        progressionRepository: ProgressionRepository,
        exerciseRepository: ExerciseRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): NextLoadUseCase =
        NextLoadUseCase(
            progressionRepository = progressionRepository,
            exerciseRepository = exerciseRepository,
            userPreferencesRepository = userPreferencesRepository,
        )
}
