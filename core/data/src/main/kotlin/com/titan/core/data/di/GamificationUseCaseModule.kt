package com.titan.core.data.di

import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.ProgressionRepository
import com.titan.core.domain.repository.QuestRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.AchievementTriggerUseCase
import com.titan.core.domain.usecase.AwardXpUseCase
import com.titan.core.domain.usecase.LootRollUseCase
import com.titan.core.domain.usecase.PrDetectionUseCase
import com.titan.core.domain.usecase.QuestCompletionUseCase
import com.titan.core.domain.usecase.QuestProgressUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
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
     * The catalogue is left at its default, [com.titan.core.domain.model.Achievements.ALL]:
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

    /**
     * The catalogue is left at its default, for the reason [provideAchievementTriggerUseCase]
     * leaves the achievement catalogue at its own: which quests exist is a property of the app
     * rather than of a build's wiring.
     *
     * The clock is the graph's, not the use case's default. The board decides which week and
     * which month it is reporting, and the screens that draw it — Home and the quest log — read
     * "today" from the same [ClockModule] binding for everything else they show. Left at
     * [Clock.SYSTEM], a test that pins the graph's clock (the store screenshots do) would get a
     * week strip from the pinned day over a quest board from the real one.
     */
    @Provides
    fun provideQuestProgressUseCase(
        progressionRepository: ProgressionRepository,
        personalRecordRepository: PersonalRecordRepository,
        questRepository: QuestRepository,
        clock: Clock,
    ): QuestProgressUseCase =
        QuestProgressUseCase(
            progressionRepository = progressionRepository,
            personalRecordRepository = personalRecordRepository,
            questRepository = questRepository,
            clock = clock,
        )

    @Provides
    fun provideQuestCompletionUseCase(
        questProgress: QuestProgressUseCase,
        questRepository: QuestRepository,
        awardXp: AwardXpUseCase,
    ): QuestCompletionUseCase =
        QuestCompletionUseCase(
            questProgress = questProgress,
            questRepository = questRepository,
            awardXp = awardXp,
        )

    /**
     * The drop engine, on its own defaults: the real [com.titan.core.domain.random.RandomSource]
     * and [com.titan.core.domain.gamification.Collectibles.ALL].
     *
     * The same call [provideAchievementTriggerUseCase] and [provideQuestProgressUseCase] both
     * make about their catalogues, plus one this module could get wrong on its own: a seeded
     * generator named here would make every install roll the same collectibles in the same
     * order. Only a test substitutes either.
     */
    @Provides
    fun provideDropTableEvaluator(): DropTableEvaluator = DropTableEvaluator()

    @Provides
    fun provideLootRollUseCase(
        dropTable: DropTableEvaluator,
        collectedItemRepository: CollectedItemRepository,
        gamificationRepository: GamificationRepository,
    ): LootRollUseCase =
        LootRollUseCase(
            dropTable = dropTable,
            collectedItemRepository = collectedItemRepository,
            gamificationRepository = gamificationRepository,
        )

    @Provides
    fun provideSessionCompletionPipeline(
        detectPersonalRecords: PrDetectionUseCase,
        awardXp: AwardXpUseCase,
        triggerAchievements: AchievementTriggerUseCase,
        completeQuests: QuestCompletionUseCase,
        rollLoot: LootRollUseCase,
        sessionRepository: SessionRepository,
        personalRecordRepository: PersonalRecordRepository,
        gamificationRepository: GamificationRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): SessionCompletionPipeline =
        SessionCompletionPipeline(
            detectPersonalRecords = detectPersonalRecords,
            awardXp = awardXp,
            triggerAchievements = triggerAchievements,
            completeQuests = completeQuests,
            rollLoot = rollLoot,
            sessionRepository = sessionRepository,
            personalRecordRepository = personalRecordRepository,
            gamificationRepository = gamificationRepository,
            userPreferencesRepository = userPreferencesRepository,
        )
}
