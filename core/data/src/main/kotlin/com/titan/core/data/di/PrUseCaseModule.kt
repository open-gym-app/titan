package com.titan.core.data.di

import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.usecase.LatestPersonalRecordUseCase
import com.titan.core.domain.usecase.PrDetectionUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The PR engine's use case, wired the way [SessionUseCaseModule] wires the session lifecycle
 * — see that module's doc for why `:core:data` is where these are bound and why `@Provides`
 * rather than `@Inject constructor`.
 *
 * Unscoped: each is a stateless read over already-scoped repositories, per
 * ARCHITECTURE.md's table. Who calls the engine, and when relative to a session finishing, is
 * #145's to wire up — this module only makes it injectable.
 */
@Module
@InstallIn(SingletonComponent::class)
object PrUseCaseModule {

    @Provides
    fun provideLatestPersonalRecordUseCase(
        personalRecordRepository: PersonalRecordRepository,
    ): LatestPersonalRecordUseCase = LatestPersonalRecordUseCase(personalRecordRepository = personalRecordRepository)

    @Provides
    fun providePrDetectionUseCase(
        sessionRepository: SessionRepository,
        personalRecordRepository: PersonalRecordRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): PrDetectionUseCase =
        PrDetectionUseCase(
            sessionRepository = sessionRepository,
            personalRecordRepository = personalRecordRepository,
            userPreferencesRepository = userPreferencesRepository,
        )
}
