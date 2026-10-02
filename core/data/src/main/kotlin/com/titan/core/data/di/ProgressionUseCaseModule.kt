package com.titan.core.data.di

import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.ProgressionRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.usecase.ConsistencyUseCase
import com.titan.core.domain.usecase.ExerciseProgressionUseCase
import com.titan.core.domain.usecase.NextLoadUseCase
import com.titan.core.domain.usecase.SuggestWarmupUseCase
import com.titan.core.domain.usecase.VolumeTrendUseCase
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

    /**
     * The warm-up suggestion (#214). Here beside [NextLoadUseCase] rather than in
     * [SessionUseCaseModule] because it is the same kind of thing: a stateless read that answers
     * a question *about* a session without writing one. Accepting its answer is a write, and
     * that goes through `AddSessionExercisesUseCase`, which is bound over there.
     */
    @Provides
    fun provideSuggestWarmupUseCase(
        exerciseRepository: ExerciseRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): SuggestWarmupUseCase =
        SuggestWarmupUseCase(
            exerciseRepository = exerciseRepository,
            userPreferencesRepository = userPreferencesRepository,
        )
}
