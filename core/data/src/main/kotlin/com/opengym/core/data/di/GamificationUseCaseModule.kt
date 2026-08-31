package com.opengym.core.data.di

import com.opengym.core.domain.repository.GamificationRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.usecase.AchievementTriggerUseCase
import com.opengym.core.domain.usecase.AwardXpUseCase
import com.opengym.core.domain.usecase.PrDetectionUseCase
import com.opengym.core.domain.usecase.SessionCompletionPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The post-session cascade, composed.
 *
 * [PrUseCaseModule] left the question of "who calls the PR engine, and when" to #145; this is
 * the answer, now that [GamificationRepository] has a Room-backed binding to build the rest of
 * the cascade on. The pipeline is handed to `FinishSessionUseCase` in [SessionUseCaseModule],
 * which is what makes a finished workout actually pay — see that module for why the wiring
 * waited.
 *
 * Unscoped, like the engine it wraps: stateless compositions over already-scoped repositories,
 * per ARCHITECTURE.md's table.
 */
@Module
@InstallIn(SingletonComponent::class)
object GamificationUseCaseModule {

    @Provides
    fun provideAwardXpUseCase(gamificationRepository: GamificationRepository): AwardXpUseCase =
        AwardXpUseCase(gamificationRepository = gamificationRepository)

    /**
     * The catalogue is left at its default, [com.opengym.core.domain.model.Achievements.ALL]:
     * which achievements exist is a property of the app rather than of a build's wiring, and a
     * module that named a subset here would be a second place the catalogue is decided.
     */
    @Provides
    fun provideAchievementTriggerUseCase(
        gamificationRepository: GamificationRepository,
        awardXp: AwardXpUseCase,
    ): AchievementTriggerUseCase =
        AchievementTriggerUseCase(
            gamificationRepository = gamificationRepository,
            awardXp = awardXp,
        )

    @Provides
    fun provideSessionCompletionPipeline(
        detectPersonalRecords: PrDetectionUseCase,
        awardXp: AwardXpUseCase,
        triggerAchievements: AchievementTriggerUseCase,
        sessionRepository: SessionRepository,
        personalRecordRepository: PersonalRecordRepository,
        gamificationRepository: GamificationRepository,
    ): SessionCompletionPipeline =
        SessionCompletionPipeline(
            detectPersonalRecords = detectPersonalRecords,
            awardXp = awardXp,
            triggerAchievements = triggerAchievements,
            sessionRepository = sessionRepository,
            personalRecordRepository = personalRecordRepository,
            gamificationRepository = gamificationRepository,
        )
}
