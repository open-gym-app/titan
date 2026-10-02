package com.titan.core.domain.usecase

import com.titan.core.domain.repository.QuestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/**
 * The quest completions, in memory, keyed the way the table is.
 *
 * The one property that matters is reproduced faithfully: [completeQuest] is
 * **insert-if-absent**, and returns whether *this* call wrote the row. That is what
 * `QuestCompletionUseCase` pays XP on, so a fake that returned `true` every time would let a
 * double-payment bug through, and one that returned `false` for a row it had just written
 * would hide a real completion.
 *
 * [failNext] is how a test drives the failure half of the pipeline's contract: a store that
 * refuses a write after earlier ones committed.
 */
class FakeQuestRepository : QuestRepository {

    private val completions = MutableStateFlow<Map<Key, Instant>>(emptyMap())

    /** Every completion written, in call order, for asserting what a pass actually banked. */
    val written = mutableListOf<Key>()

    /** When set, the next [completeQuest] throws it instead of writing. Cleared on use. */
    var failNext: Throwable? = null

    /**
     * When set, [completeQuest] throws once this many rows are on file — so a test can let the
     * first completions through and refuse a later one, which is the shape the pipeline's
     * "banked before the store refused" contract is about. The same knob
     * [FakePersonalRecordRepository.refuseWriteAfter] offers.
     */
    var refuseWriteAfter: Int? = null

    override fun observeCompletedIds(periodStart: LocalDate): Flow<Set<String>> =
        completions.map { rows ->
            rows.keys.filter { it.periodStart == periodStart }.map(Key::questId).toSet()
        }

    override suspend fun completeQuest(
        questId: String,
        periodStart: LocalDate,
        completedAt: Instant,
    ): Boolean {
        failNext?.let {
            failNext = null
            throw it
        }
        val key = Key(questId, periodStart)
        // Insert-if-absent, and the first write's timestamp is the one kept — the rule
        // `QuestCompletionDao.insertIfAbsent` states.
        if (key in completions.value) return false
        // After the absence check, so only writes that would actually have happened count
        // towards the threshold.
        if (written.size == refuseWriteAfter) error("the quest table is not writable")
        completions.value = completions.value + (key to completedAt)
        written += key
        return true
    }

    /** Marks [questId] as already completed in [periodStart]'s period, without going through a pass. */
    fun seedCompleted(
        questId: String,
        periodStart: LocalDate,
        completedAt: Instant = Instant.EPOCH,
    ) {
        completions.value = completions.value + (Key(questId, periodStart) to completedAt)
    }

    /** When [questId] was completed in [periodStart]'s period, or null if it was not. */
    fun completedAt(
        questId: String,
        periodStart: LocalDate,
    ): Instant? = completions.value[Key(questId, periodStart)]

    /** The pair a completion is identified by — see `QuestCompletionEntity`. */
    data class Key(
        val questId: String,
        val periodStart: LocalDate,
    )
}
