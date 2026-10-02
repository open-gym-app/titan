package com.titan.feature.progression.quests

import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.RecordTally
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.model.UnlockedAchievement
import com.titan.core.domain.model.UserProgress
import com.titan.core.domain.model.XpAward
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.QuestRepository
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
    val streak = MutableStateFlow(StreakState())

    override fun observeProgress(): Flow<UserProgress> = progress

    override fun observeUnlockedAchievements(): Flow<List<UnlockedAchievement>> = unlocked

    override fun observeStreak(): Flow<StreakState> = streak

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

    override suspend fun claimSessionLoot(sessionId: Long): Boolean = readOnly()

    override suspend fun updateStreak(advance: (state: StreakState) -> StreakUpdate): StreakUpdate = readOnly()
}

/**
 * The drop inventory, over a list a test writes to.
 *
 * [requestedLimit] is recorded rather than enforced, which is the point of keeping it: what the
 * screen has to get right is that it *asks* for a bounded read (the store treats a limit under 1
 * as no limit at all — see `CollectedItemRepository.observeRecent`), and a fake that silently
 * truncated would hide a ViewModel that asked for everything.
 */
class FakeCollectedItemReads : CollectedItemRepository {

    val recent = MutableStateFlow(emptyList<CollectedItem>())

    var requestedLimit: Int? = null
        private set

    override fun observeRecent(limit: Int): Flow<List<CollectedItem>> {
        requestedLimit = limit
        return recent
    }

    override suspend fun recordDrop(
        collectibleId: String,
        source: String,
        acquiredAt: Instant,
    ): Unit = readOnly()
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
        offset: Int,
        limit: Int,
    ) = emptyList<com.titan.core.domain.model.PersonalRecordEntry>()

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
