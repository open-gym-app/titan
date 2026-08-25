package com.opengym.core.data.di

import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.usecase.ConsistencyUseCase
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
 * Unscoped: each is a stateless read over one already-scoped repository, per
 * ARCHITECTURE.md's table. Neither holds the period it is asked about, so the screen that
 * owns the timeframe selector (#32) can call the same instance for every timeframe.
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
}
