package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The records table, in memory.
 *
 * The "current best" [insertIfBetter] hands [isBetter] is derived from [inserted] rather than
 * tracked separately, on the same rule the real query follows: the most recently achieved row
 * of a given exercise and type is the best one, because a row is only ever written when it
 * beats what came before it. A fake that tracked "the best" independently of "what was
 * inserted" could drift from that rule and let a test pass on a state the real store could
 * never reach.
 *
 * This fake's tests run single-threaded, so it does not need the real repository's
 * transactional guarantee against concurrent calls racing each other — the read and the
 * write below are never actually interleaved with another call here the way they could be
 * against a real database.
 */
class FakePersonalRecordRepository : PersonalRecordRepository {

    private val records = MutableStateFlow<List<PersonalRecord>>(emptyList())
    private var nextId = 1L

    /** Every row written, oldest first, for assertions a `Flow` read would be clumsy for. */
    val inserted: List<PersonalRecord> get() = records.value

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = records.map { rows ->
        rows.groupBy(PersonalRecord::exerciseId).mapValues { (_, group) ->
            group.maxBy(PersonalRecord::achievedAt)
        }
    }

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? {
        val current = records.value
            .filter { it.exerciseId == exerciseId && it.type == type }
            .maxByOrNull(PersonalRecord::achievedAt)
        if (!isBetter(current)) return null
        val stored = record.copy(id = nextId++)
        records.value += stored
        return stored
    }
}
