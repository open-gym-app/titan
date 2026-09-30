package com.titan.core.data.repository

import com.titan.core.data.db.dao.QuestCompletionDao
import com.titan.core.data.db.entity.QuestCompletionEntity
import com.titan.core.domain.repository.QuestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * The quest completions, over Room.
 *
 * One table and one write, which is where the whole of the difficulty is — see
 * [QuestCompletionDao.insertIfAbsent] for how the write gets its atomicity, and
 * [QuestCompletionEntity] for why the period is half of every key.
 *
 * ### Periods cross the boundary as dates, and are stored as epoch days
 *
 * The domain speaks [LocalDate] because a period boundary is a date; the column is a `Long`
 * because that is what SQLite compares cheaply and what `week_start` already is. The conversion
 * is [LocalDate.toEpochDay], which is zone-free — the date has already had UTC applied to it by
 * [com.titan.core.domain.model.QuestPeriod], and applying a zone twice is how a boundary ends
 * up a day out.
 *
 * ### Instants are stored to the millisecond
 *
 * `completed_at` is epoch milliseconds, so an [Instant]'s sub-millisecond part is dropped on the
 * way in — the same trade [GamificationRepositoryImpl] makes, and nothing reads it back: a
 * completion is dated for display.
 */
class QuestRepositoryImpl @Inject constructor(private val questCompletionDao: QuestCompletionDao) : QuestRepository {

    override fun observeCompletedIds(periodStart: LocalDate): Flow<Set<String>> =
        questCompletionDao.observeCompletedIds(periodStart.toEpochDay()).map { it.toSet() }

    override suspend fun completeQuest(
        questId: String,
        periodStart: LocalDate,
        completedAt: Instant,
    ): Boolean =
        questCompletionDao.insertIfAbsent(
            QuestCompletionEntity(
                questId = questId,
                periodStart = periodStart.toEpochDay(),
                completedAt = completedAt.toEpochMilli(),
            ),
        )
}
