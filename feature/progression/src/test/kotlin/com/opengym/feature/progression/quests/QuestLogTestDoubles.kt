package com.opengym.feature.progression.quests

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.model.UnlockedAchievement
import com.opengym.core.domain.model.UserProgress
import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.repository.GamificationRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.QuestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate

/*
 * Doubles for the quest log's ViewModel.
 *
 * Only the **reads** the screen makes are implemented. Every write throws: nothing the quest log
 * does should reach one, and a stub that quietly returned a plausible value would let a screen
 * that started writing pass this suite unnoticed.
 */

/** The gamification progress, over flows a test writes to. */
class FakeGamificationReads : GamificationRepository {

    val progress = MutableStateFlow(UserProgress())
    val unlocked = MutableStateFlow(emptyList<UnlockedAchievement>())

    override fun observeProgress(): Flow<UserProgress> = progress

    override fun observeUnlockedAchievements(): Flow<List<UnlockedAchievement>> = unlocked

    override suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward = readOnly()

    override suspend fun unlockedAchievementIds(): Set<String> = readOnly()

    override suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean = readOnly()

    override suspend fun claimSessionXp(sessionId: Long): Boolean = readOnly()

    override suspend fun updateStreak(
        finishedAt: Instant,
        computeStreak: (lastCountedAt: Instant?, currentStreak: Int) -> StreakResult,
    ): StreakUpdate = readOnly()
}

/** The record reads the quest board makes, and nothing else. */
class FakeRecordReads : PersonalRecordRepository {

    val tally = MutableStateFlow(RecordTally(records = 0, exercises = 0))

    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> = tally

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = flowOf(emptyMap())

    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> = flowOf(emptyList())

    override fun observeRecordCount(): Flow<Int> = flowOf(0)

    override suspend fun count(): Int = 0

    override suspend fun getRecordsPage(
        type: PrType?,
        limit: Int,
        offset: Int,
    ) = emptyList<com.opengym.core.domain.model.PersonalRecordEntry>()

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? = readOnly()
}

/** The completions, over a set a test writes to. */
class FakeQuestCompletions : QuestRepository {

    val completed = MutableStateFlow(emptySet<String>())

    override fun observeCompletedIds(periodStart: LocalDate): Flow<Set<String>> = completed

    override suspend fun completeQuest(
        questId: String,
        periodStart: LocalDate,
        completedAt: Instant,
    ): Boolean = readOnly()
}

private fun readOnly(): Nothing = throw UnsupportedOperationException("the quest log reads; it does not write")
