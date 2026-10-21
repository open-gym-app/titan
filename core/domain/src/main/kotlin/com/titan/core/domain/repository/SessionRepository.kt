package com.titan.core.domain.repository

import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SessionTotals
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import kotlinx.coroutines.flow.Flow

/*
 * Twenty-two methods, past detekt's threshold, and deliberately so: this is the store for a
 * whole aggregate — sessions, their exercises and their sets — and the alternative is three
 * interfaces whose implementations would all wrap the same database and whose callers would all
 * take two of them. The read/write pairs are what make it long, not a lack of a seam.
 */
@Suppress("TooManyFunctions")
interface SessionRepository {
    fun getAll(): Flow<List<WorkoutSession>>
    fun getById(id: Long): Flow<WorkoutSession?>
    fun getActive(): Flow<WorkoutSession?>
    fun getExercises(sessionId: Long): Flow<List<SessionExercise>>
    fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>>

    /**
     * Every set logged against [sessionId], across all of its exercises.
     *
     * Distinct from [getSets], which takes a *session exercise* id: reading a whole
     * session through that would be one query per exercise. Implementations must
     * satisfy this with a single join on `session_exercise`, since the caller
     * (session summary, export) wants the session in one read. [WorkoutSet] carries
     * `sessionExerciseId`, so callers can still group by exercise without a
     * second query.
     */
    fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>>

    /**
     * What was done, last time, for every exercise [sessionId] holds — keyed by **catalogue**
     * exercise id, each list in set order.
     *
     * The reference the active session draws in its `PREV` column, and the reason it is one
     * query rather than a lookup per exercise: a workout is ten exercises and the screen wants
     * all ten at once, which is the same shape argument [getSetsForSession] makes and the same
     * fan-out it exists to avoid. Implementations must satisfy this with a single query — the
     * session's own exercises are a sub-select over `session_exercise`, not a list the caller
     * loops over.
     *
     * Keyed by catalogue id rather than by `session_exercise` id because that is what "the same
     * exercise" means across two sessions; a session holding one exercise twice reads the same
     * reference against both slots, which is the truth about it.
     *
     * ### What counts as "last time"
     *
     * The most recent `session_exercise` row for that exercise, **excluding [sessionId]'s own**,
     * that has at least one qualifying set. A row rather than a session, so that a workout which
     * held the exercise twice contributes the later of its two slots instead of the two of them
     * merged into a table numbered `1, 2, 1, 2`.
     *
     * ### What counts as a set
     *
     * Completed, and not a warm-up.
     *
     * *Completed* is the filter every aggregate in this codebase applies: a `workout_set` row can
     * hold planned-but-unperformed work (see PERSISTENCE.md), and a reference built from those
     * would quote a weight nobody lifted.
     *
     * *Not a warm-up* is this method's own decision, and it is the one worth stating. `SetType`
     * carries five values and only [com.titan.core.domain.model.SetType.WARMUP] names work
     * that is not the work: a warm-up is chosen to be easy, so showing last week's 40 kg against
     * today's first working set answers a question nobody asked and, worse, answers it low.
     * `DROP_SET`, `FAILURE` and `INTERVAL` all stay — each of those is a set the lifter actually
     * performed to some standard, and dropping them would silently shorten the reference for
     * anybody who trains that way.
     *
     * ### Absence means never
     *
     * An exercise with no qualifying set anywhere in the user's history has **no entry**, rather
     * than an empty list. The two are one thing here — there is nothing to show either way — but
     * the caller has to draw a "first time" state for it and not a blank column, so the absence
     * is what it reads. An exercise whose entire history is warm-ups reads as never performed,
     * which is what it is.
     */
    fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>>

    /**
     * The set count and volume of **every** session, keyed by session id.
     *
     * The history list's read. It shows a figure per row and there is no upper bound on how
     * many rows a user accumulates, so the only shape that works is one aggregate over the
     * whole table: implementations must satisfy this with a single grouped query, never with
     * a [getSetsForSession] per session. That fan-out is an observer per row of a list that
     * grows for the life of the install.
     *
     * A session with no completed sets — one abandoned, or one still being logged — has no
     * entry rather than a zeroed one. The absence is the honest answer for a row whose
     * figures are not yet a fact, and a caller drawing a list treats it as such; see
     * [SessionTotals] for why the duration is not here.
     */
    fun getTotals(): Flow<Map<Long, SessionTotals>>

    /**
     * How many sessions the user has ever finished — closed out, not merely started.
     *
     * "Finished" is the pair [com.titan.core.domain.usecase.FinishSessionUseCase] writes
     * together, `is_in_progress = 0` with a `completed_at`, the same definition the analytics
     * queries count by. A session still in progress is not one shown up for yet, and one
     * discarded left no row at all.
     *
     * A suspend one-shot rather than a `Flow`, and a count rather than a list, because its
     * caller is the post-session cascade: it needs the figure *now*, for the session it has
     * just closed out, and a hot flow's latest emission is not guaranteed to include a write
     * from a moment ago. Counting in the store rather than over [getAll] keeps the cost flat
     * as the history grows — see [com.titan.core.domain.model.AchievementProgress] for why
     * the figure has to be a lifetime total and not a delta.
     */
    suspend fun countFinished(): Int

    suspend fun insert(session: WorkoutSession): Long

    /**
     * Adds [exercises] to the **end** of [sessionId]'s layout, in the order given, and
     * returns the ids of the rows written in that same order.
     *
     * One call rather than an insert per exercise, and applied in a single
     * transaction: a session is laid out once, at the moment it starts, and a failure
     * halfway would leave the user in a workout holding three of the eight exercises
     * they chose with no way to tell which five are missing.
     *
     * Numbering is the store's job for the reason [SessionExerciseDraft] has no
     * position field — `session_exercise` carries a unique `(session_id, position)`
     * index, and a caller that hand-numbers is one reorder away from violating it.
     * **The list must therefore be in the order the exercises will be performed**,
     * which is also the order the superset grouping is read in.
     *
     * Appending rather than laying out from zero is what lets an exercise be added to a
     * session that is already running ([com.titan.core.domain.usecase.AddSessionExercisesUseCase]).
     * It is the same operation at the moment a session *starts*, since the session is
     * empty then — which is why there is one write path here rather than two that would
     * have to keep agreeing about how a position is chosen.
     *
     * An empty list writes nothing and returns nothing. A session with no exercises is
     * a legal, if unusual, starting point — the user adds them as they go — so it is
     * not an error to lay out none.
     *
     * @param createdAt stamped on every row's `created_at` *and* `updated_at`. Supplied
     *   by the caller rather than read off a clock in here, as [insert] takes the
     *   session's own timestamps: the use cases own the clock in this codebase, which is
     *   what makes them testable without one.
     */
    suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long>

    /**
     * Writes [set] against the `session_exercise` row it names, and returns the id of
     * the row that now holds it.
     *
     * One set at a time, unlike [insertExercises], because that is how they arrive: a
     * session is laid out once and then logged a set at a time over an hour, so there
     * is no batch to write and nothing for a transaction to make atomic.
     *
     * **The bounds are not checked here.** `SetValidator` is a check a caller runs
     * rather than a gate the write path forces it through — see PERSISTENCE.md — so
     * anything reaching this must have run it first.
     * [com.titan.core.domain.usecase.LogSetUseCase] is the path that does, and is
     * what a screen should be calling.
     *
     * @param set with `id = 0`, the value [WorkoutSet] defaults to: the row does not
     *   exist yet, and the store assigns its id. `setNumber` is the caller's, unlike
     *   an exercise's position, because a set can legitimately be written against an
     *   exercise that already has some — there is no list here for the store to take
     *   an order from. Keeping those numbers contiguous is therefore also the
     *   caller's; [updateSets] is what a renumbering is written through.
     */
    suspend fun insertSet(set: WorkoutSet): Long

    /**
     * Writes several sets **atomically**, and returns their ids in the order given.
     *
     * The batch counterpart of [insertSet], and it exists for one caller:
     * [com.titan.core.domain.usecase.StartPlannedSessionUseCase], which lays a template's
     * whole plan down as the session starts. A loop over [insertSet] would let a failure
     * halfway leave a lifter in a workout holding the first two sets of a five-set exercise,
     * with nothing to say which three are missing — the same argument [insertExercises] makes
     * one level up, and the reason both are one call.
     *
     * **The bounds are not checked here**, per [insertSet]. Nor are the numbers: `setNumber`
     * is the caller's, as it is for a single insert, and a caller writing a table from
     * nothing is the one case where numbering it from 1 is trivially correct.
     *
     * An empty list writes nothing and returns nothing — an exercise that plans no sets is
     * ordinary, and laying out none of them is not a failure.
     */
    suspend fun insertSets(sets: List<WorkoutSet>): List<Long>

    /**
     * Replaces one `workout_set` row.
     *
     * What a correction is written through — a mistyped weight, a rating added after the
     * fact. Narrow for the reason [updateExercise] is narrow: what edits one row at a time
     * is a change that concerns one row, and a change that renumbers a whole exercise's
     * table is [updateSets].
     *
     * **The bounds are not checked here** either, for the reason [insertSet] gives.
     * [com.titan.core.domain.usecase.EditSetUseCase] is the path that checks them.
     *
     * @param set carrying the id of the row to replace. Its `sessionExerciseId` and
     *   `createdAt` are expected to be the ones already stored — this replaces a set, it
     *   does not move one between exercises.
     */
    suspend fun updateSet(set: WorkoutSet)

    /**
     * Replaces several `workout_set` rows **atomically**.
     *
     * This is what a renumbering is written through: deleting the second of four sets
     * leaves `1, 3, 4`, and the three writes that close that gap are one change to one
     * exercise's table. Half of them applied is a table numbered `1, 2, 4`, which is worse
     * than either end state — the "set 3 of 4" a card draws would name a set that is not
     * there.
     *
     * Unlike [updateExercises] there is no parking step, and the difference is worth
     * knowing: `session_exercise` carries a unique `(session_id, position)` index, so
     * moving two rows past each other has to go through a position nothing can be holding.
     * `workout_set` has no such index on `(session_exercise_id, set_number)` — the numbers
     * are kept contiguous by the use cases rather than by the schema — so the rows can be
     * written where they belong in one pass.
     *
     * Only the rows that change are handed over, so a set the renumbering did not move
     * keeps the `updatedAt` it had.
     */
    suspend fun updateSets(sets: List<WorkoutSet>)

    /**
     * Removes one set.
     *
     * Nothing cascades from `workout_set` — it is the leaf of the session's tree — so this
     * is the one delete in this interface that takes nothing with it.
     *
     * The remaining sets are **not** renumbered here, which is the opposite of what
     * [deleteExercise] does with positions and for the opposite reason: a set number is
     * read as "set 3 of 4" and a gap in it is visible, so the renumbering is required
     * rather than pointless. It belongs to the caller because the caller is also what has
     * to be able to undo it — see
     * [com.titan.core.domain.usecase.DeleteSetUseCase] and
     * [com.titan.core.domain.usecase.RestoreSetUseCase].
     */
    suspend fun deleteSet(setId: Long)

    /**
     * Replaces one `session_exercise` row.
     *
     * Narrow on purpose: what edits it one row at a time is a change that concerns one row —
     * a superset repaired after a removal, an exercise swapped for another. A change that
     * rewrites `position` across the layout is [updateExercises], which is not a loop over
     * this for the reason its own contract gives.
     */
    suspend fun updateExercise(exercise: SessionExercise)

    /**
     * Replaces several `session_exercise` rows **atomically**, and safely against the unique
     * `(session_id, position)` index.
     *
     * This is what a reorder is written through. A loop over [updateExercise] cannot express
     * one: `session_exercise` is unique on `(session_id, position)`, so swapping two exercises
     * one row at a time puts two rows on the same position at the moment the first is written
     * and the index aborts — see PERSISTENCE.md § Identity and uniqueness. Implementations
     * therefore have to move the rows out of the way before writing them back, in one
     * transaction, and a half-applied reorder must not be observable: unlike a grouping, where
     * a torn write leaves a group of one that the screens dissolve on sight, a torn *ordering*
     * is a workout whose exercises are in an order nobody asked for.
     *
     * Only the rows that change are handed over — the caller works out which — so an exercise
     * that stays where it is keeps the `updated_at` it had.
     *
     * @param exercises the rows as they should end up, each carrying its new `position`,
     *   `supersetGroup` and `updatedAt`. Their positions must not collide with each other or
     *   with a row that is *not* in the list, which is a caller's rule and not something an
     *   implementation can check without reading the whole layout back.
     */
    suspend fun updateExercises(exercises: List<SessionExercise>)

    /**
     * Removes one exercise from its session, and its sets with it — the schema cascades from
     * `session_exercise` to `workout_set`.
     *
     * That cascade is why the caller checks first.
     * [com.titan.core.domain.usecase.RemoveSessionExerciseUseCase] refuses an exercise that
     * has anything logged against it, because silently deleting sets a user performed is the
     * one outcome no confirmation makes acceptable; this method is the blunt instrument under
     * that rule and enforces nothing itself.
     *
     * The surviving exercises keep their positions, so a removal leaves a gap. Nothing reads
     * position as a dense index — everything that cares orders by it — and a renumbering write
     * across every surviving row would be a second thing to get wrong for no reader's benefit.
     */
    suspend fun deleteExercise(sessionExerciseId: Long)

    suspend fun update(session: WorkoutSession)
    suspend fun delete(id: Long)

    /**
     * Runs [block] as one unit of work against the store: every write it makes lands, or none
     * does.
     *
     * The batch writes above each make one *operation* atomic — a layout, a plan, a renumbering
     * — and that is the right seam for them. What none of them can cover is a use case that is
     * several operations in a row.
     * [com.titan.core.domain.usecase.AddSessionExercisesUseCase] lays a warm-up out, writes
     * its sets, then moves it to the front, and a process killed between any two of those
     * leaves a workout nobody asked for: a warm-up at the end, or one with no sets. A
     * compensating delete, which is how [com.titan.core.domain.usecase.StartPlannedSessionUseCase]
     * handles a *failure*, cannot reach that case — nothing is running to compensate. This is
     * the seam for it.
     *
     * Every call on this repository from inside [block] takes part: a read sees the writes made
     * before it, and a throw out of the block rolls all of them back before it is rethrown.
     * Calls already atomic on their own ([insertExercises], [updateExercises], [insertSets],
     * [updateSets]) join the enclosing transaction rather than opening a second one, so the
     * outer call owns the commit.
     *
     * What it is **not** is a lock. Two callers on two coroutines still interleave at the
     * store's discretion; [com.titan.core.domain.usecase.SessionLifecycleLock] is what keeps
     * a read-modify-write over the layout from racing another. Nor is it a place for anything
     * slow: the store is single-writer, and a transaction held open across a network call or a
     * user prompt is every other write on the app waiting behind it.
     */
    suspend fun <T> atomically(block: suspend () -> T): T
}
