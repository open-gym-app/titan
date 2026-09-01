package com.opengym.core.domain.repository

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.RecordTally
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The personal records the PR engine ([com.opengym.core.domain.usecase.PrDetectionUseCase])
 * writes.
 *
 * Writable as of #30: [insertIfBetter] is the one way in, and nothing outside the engine may
 * call it — a record authored anywhere else would be a "personal record" nobody actually
 * achieved. Before this, the interface was read-only because there was no writer to give a
 * contract to; a screen that reads an empty table is still showing a user who has not
 * beaten anything yet, which was true before the engine existed and stays true for a fresh
 * install after it.
 */
interface PersonalRecordRepository {

    /**
     * The one record worth putting on a row, per exercise, keyed by exercise id.
     *
     * A list row has space for a single figure, and an exercise accumulates records of
     * several kinds — a heaviest, a most-reps, an estimated 1RM off the same set. Choosing
     * between them is a decision the catalogue would otherwise make differently on every
     * screen that draws a row, so it is made once, in the query: the heaviest lift if there
     * is one, else the most reps, else whatever kind of best the exercise has. Ties within a
     * kind go to the most recent, which for a record of a given kind is also the best one.
     *
     * A map rather than a list because every caller looks records up by exercise, and an
     * exercise has at most one entry here by construction.
     */
    fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>>

    /**
     * Every record on file for [exerciseId], **most recently achieved first**.
     *
     * The per-exercise drill-down's four cards, and #103's list once it lands. The whole
     * history rather than the current bests, because rows are only ever inserted (see
     * [insertIfBetter]) and the two readings come off the same rows: the first row of a given
     * [PrType] *is* that type's best, since a record of a kind is only written when it beats
     * the one before it.
     *
     * Ordering is the contract, not an implementation detail — a caller taking the first entry
     * per type is taking the best one, and a store that returned these in any other order
     * would quietly hand back a beaten record.
     *
     * A `Flow` for the reason [getRecordTally] is one: it is read by a screen that has to
     * repaint when the post-session cascade writes a record behind it.
     */
    fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>>

    /**
     * How many records the user has ever broken, counted per record and not per session.
     *
     * Every row, across every exercise and [PrType] — a session that beat its weight, rep and
     * volume records for one lift contributed three, matching the three
     * [com.opengym.core.domain.model.XpEvent.PrBroken] events it emitted. Rows are only ever
     * inserted, never replaced (see [insertIfBetter]), so the table *is* the history and its
     * size is the figure.
     *
     * A suspend one-shot for the same reason as
     * [SessionRepository.countFinished]: the post-session cascade needs the total including
     * the records it has just written, and it needs it in the same breath.
     */

    suspend fun count(): Int

    /**
     * One window of the records list: every record across every exercise, newest first, with the
     * figure each one beat and the name it was set on.
     *
     * ### Why a window and not a `PagingSource`
     *
     * Because this interface lives in `:core:domain`, which carries no Android dependencies —
     * see ARCHITECTURE.md. `PagingData` and `PagingSource` are AndroidX types, so a contract
     * shaped around them would put an androidx artifact in the dependency-free centre that every
     * other module is allowed to depend on. An offset and a limit say the same thing in plain
     * Kotlin, and `:feature:progression` builds its `PagingSource` over this.
     *
     * The cost is real and worth naming: Room invalidates its own `PagingSource` when the table
     * changes, and a hand-rolled one over this gets no such signal. That is what
     * [observeRecordCount] is for.
     *
     * @param type the kind to list, or **null** for all of them. Null rather than a set of all
     *   six, so "no filter" and "every filter selected" are the same query rather than two.
     * @param offset how many rows to skip. The caller's paging key.
     * @param limit how many to return.
     */
    suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry>

    /**
     * How many records exist, re-emitted whenever the table changes.
     *
     * The invalidation signal a hand-rolled `PagingSource` has to be given, since it cannot be
     * handed Room's. A count rather than a `Flow<Unit>` because it is the cheapest query that
     * changes when the thing being listed does, and because the screen has a use for the figure
     * itself — a list of every record wants to say how many there are.
     *
     * It does not catch every possible change: a record *edited* in place leaves the count alone.
     * Nothing edits records — the engine only ever inserts — so the signal is complete for the
     * writer that exists, and this note is here for the one that might not be.
     */
    fun observeRecordCount(): Flow<Int>

    /**
     * How many records were broken over [days], and across how many exercises.
     *
     * The progression screen's `PRs HIT` tile. A [kotlinx.coroutines.flow.Flow] rather than a
     * one-shot like [count], because it is read by a screen that has to repaint when the
     * post-session cascade writes a record behind it, where [count] is read *by* that cascade
     * and needs its own writes in the same breath.
     *
     * **Days, UTC, both ends inclusive** — the shape every progression aggregate takes, and the
     * same week arithmetic [com.opengym.core.domain.stats.TrainingWeek] documents. Records are
     * placed by when they were *achieved*, which is the set's own timestamp rather than the
     * session's closing one, so this and the volume series can disagree only about a workout
     * that ran across midnight on the boundary of a period.
     */
    fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally>

    /**
     * Writes [record] as [exerciseId]'s new record of [type], but only if it beats what is
     * already on file — and returns it (with its id) if so, or **null** if it did not.
     *
     * "What is already on file" is the most recently achieved row of that exercise and type,
     * which is also the best one — a record of a given kind is only ever written when it
     * beats the row before it, so there is nothing to rank between rows of the same type.
     * [isBetter] is handed that row (or **null**, while none has been set yet) and decides;
     * this repository does not re-derive "better" from the type, so that comparison stays in
     * one place, [com.opengym.core.domain.usecase.PrDetectionUseCase].
     *
     * The read of the current best and the write are one atomic operation — [isBetter] is
     * evaluated inside the same transaction the insert happens in, so two concurrent calls
     * for the same exercise and type cannot both read the same "current" row before either
     * writes: the second call's transaction only starts once the first has committed, and so
     * sees the first call's write. A caller that split this into a separate read-then-insert
     * would reopen exactly that race.
     *
     * Always an insert, never an update, when it does write: a broken record does not replace
     * the old one, it joins it, so an exercise's full history of records stays queryable
     * rather than only its current best.
     */
    suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord?
}
