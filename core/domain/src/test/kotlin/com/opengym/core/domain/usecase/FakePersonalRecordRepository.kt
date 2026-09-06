package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

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
    private val names = mutableMapOf<Long, String>()
    private var nextId = 1L

    /**
     * Records achieved at or after this count as *recent* — the `NEW` badge's condition.
     *
     * A timestamp rather than a session id because this fake holds no sessions: the real query
     * marks the rows set in the most recently finished one, and "everything from this moment on"
     * is the same set for a test that seeds its records in order. Null marks nothing, which is
     * what a store with no finished session behind it would answer.
     */
    var recentSince: Long? = null

    /** Every row written, oldest first, for assertions a `Flow` read would be clumsy for. */
    val inserted: List<PersonalRecord> get() = records.value

    /**
     * Thrown by [insertIfBetter] instead of writing, when set — the only way to fail PR
     * detection, which is otherwise correct for every session a caller can construct.
     *
     * Fails from the first write, so it models a store that is simply unavailable. Use
     * [refuseWriteAfter] for the other shape, a store that gives out part way through.
     */
    var insertFailure: Throwable? = null

    /**
     * Throw from the write once this many rows are on file, or never when null.
     *
     * A count rather than a flag because the case worth testing is a store that refuses
     * *part way* through a session's records: a seam that failed from the first write could
     * not tell a caller that reports what it wrote from one that reports nothing.
     */
    var refuseWriteAfter: Int? = null

    override suspend fun count(): Int = records.value.size

    /**
     * Puts [record] on file without going through [insertIfBetter], for a test that wants a
     * history rather than to exercise the detection rule — the quest board reading a period's
     * records, say. Stamped with an id, as a stored row is.
     */
    fun seed(record: PersonalRecord) {
        records.value += record.copy(id = nextId++)
    }

    /**
     * The period's tally, derived from what has been written rather than tracked separately —
     * the same rule the rest of this fake follows, so it cannot report a record the store does
     * not hold.
     *
     * The range is turned into UTC milliseconds exactly as the real repository turns it, so a
     * record achieved on the last day of the period is inside it and one achieved a millisecond
     * into the next day is not.
     */
    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> {
        val start = days.start.toEpochDay() * MILLIS_PER_DAY
        val end = (days.endInclusive.toEpochDay() + 1) * MILLIS_PER_DAY
        return records.map { rows ->
            val inPeriod = rows.filter { it.achievedAt in start until end }
            RecordTally(
                records = inPeriod.size,
                exercises = inPeriod.distinctBy(PersonalRecord::exerciseId).size,
            )
        }
    }

    /**
     * The exercise's records newest first, which is the ordering the interface promises and
     * the ordering callers take "the best of this kind" off. Sorted rather than reversed:
     * [insertIfBetter] writes in call order, and a test that seeds an older record after a
     * newer one would otherwise get a list the real query could never return.
     */
    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> =
        records.map { rows ->
            rows.filter { it.exerciseId == exerciseId }
                .sortedByDescending(PersonalRecord::achievedAt)
        }

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> =
        records.map { rows ->
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
        insertFailure?.let { throw it }
        val current = records.value
            .filter { it.exerciseId == exerciseId && it.type == type }
            .maxByOrNull(PersonalRecord::achievedAt)
        if (!isBetter(current)) return null
        // After the comparison, so only rows that would actually have been written count
        // towards the threshold — the same rows the caller is told about.
        if (records.value.size == refuseWriteAfter) error("the records table is not writable")
        val stored = record.copy(id = nextId++)
        records.value += stored
        return stored
    }

    /**
     * One window of the records list: every record newest first, with the figure it beat and
     * the name it was set on.
     *
     * The previous figure is derived rather than stored, on the same rule the rest of this fake
     * follows: it is the row of the same exercise and type achieved immediately before this one,
     * which is what the real query's window function finds. A fake that carried it as a field
     * could report an improvement over a record the store does not hold.
     *
     * Names come from [nameExercise] and are **null** until a test supplies one — the state a
     * record whose exercise has since been deleted is in, which is a case the callers draw
     * differently and so worth being the default.
     */
    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> =
        records.value
            .filter { type == null || it.type == type }
            .sortedByDescending(PersonalRecord::achievedAt)
            .drop(offset)
            .take(limit)
            .map { record ->
                PersonalRecordEntry(
                    record = record,
                    exerciseName = names[record.exerciseId],
                    previousValue = previousValueOf(record),
                    isRecent = recentSince?.let { record.achievedAt >= it } == true,
                )
            }

    override fun observeRecordCount(): Flow<Int> = records.map { it.size }

    /** What [exerciseId] is called, for the rows a list joins the catalogue onto. */
    fun nameExercise(
        exerciseId: Long,
        name: String,
    ) {
        names[exerciseId] = name
    }

    /**
     * The figure [record] beat: the previous row of its exercise and type, read through the
     * same `when` [PersonalRecordEntry.value] uses so the two cannot disagree about which
     * column a kind's figure lives in.
     */
    private fun previousValueOf(record: PersonalRecord): Long? =
        records.value
            .filter {
                it.exerciseId == record.exerciseId &&
                    it.type == record.type &&
                    it.achievedAt < record.achievedAt
            }
            .maxByOrNull(PersonalRecord::achievedAt)
            ?.let { previous -> previous.asEntry().value }

    /** A record as a bare row, so its own figure can be read through the model's `when`. */
    private fun PersonalRecord.asEntry() =
        PersonalRecordEntry(record = this, exerciseName = null, previousValue = null, isRecent = false)
}

/** What a day is, in the milliseconds a record's `achievedAt` is stored in. */
private const val MILLIS_PER_DAY = 86_400_000L
