package com.opengym.core.data.di

import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.usecase.PrDetectionUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The PR engine's use case, wired the way [SessionUseCaseModule] wires the session lifecycle
 * — see that module's doc for why `:core:data` is where these are bound and why `@Provides`
 * rather than `@Inject constructor`.
 *
 * Unscoped: a stateless composition of three already-scoped repositories, per
 * ARCHITECTURE.md's table. Who calls it, and when relative to a session finishing, is #145's
 * to wire up — this module only makes it injectable.
 */
@Module
@InstallIn(SingletonComponent::class)
object PrUseCaseModule {

    @Provides
    fun providePrDetectionUseCase(
        sessionRepository: SessionRepository,
        personalRecordRepository: PersonalRecordRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): PrDetectionUseCase = PrDetectionUseCase(
        sessionRepository = sessionRepository,
        personalRecordRepository = personalRecordRepository,
        userPreferencesRepository = userPreferencesRepository,
    )
}
