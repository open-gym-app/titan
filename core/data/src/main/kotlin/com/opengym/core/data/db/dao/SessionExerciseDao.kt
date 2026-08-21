package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.opengym.core.data.db.entity.SessionExerciseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionExerciseDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sessionExercise: SessionExerciseEntity): Long

    /**
     * Writes a session's whole layout in one transaction, returning the new ids in the
     * order the rows were given.
     *
     * A `@Insert` over a list rather than a loop at the call site, because this is how
     * a session is laid out at the moment it starts: a failure part way through would
     * otherwise leave the user in a workout holding some of the exercises they chose,
     * with the `(session_id, position)` index the only thing saying which are missing.
     *
     * `ABORT` on conflict is what makes that a failure rather than a silent
     * replacement — a second layout of the same session is a bug, not an update.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(sessionExercises: List<SessionExerciseEntity>): List<Long>

    /**
     * The position a new exercise on [sessionId] takes: one past the highest in use, and 0
     * for a session with none.
     *
     * One past the *highest* rather than the count, because a removal leaves a gap and only
     * one of the two cannot collide with the unique `(session_id, position)` index. Gaps are
     * legal — nothing reads position as a dense index, and everything that cares orders by it.
     */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM session_exercise WHERE session_id = :sessionId")
    suspend fun nextPosition(sessionId: Long): Int

    /**
     * Adds [rows] to the end of [sessionId]'s layout, in the order given, and returns their
     * new ids in that same order.
     *
     * The positions on [rows] are **ignored and replaced**: reading the next free one and
     * inserting have to happen together or two callers can compute the same position and the
     * second insert aborts on the unique index. A caller therefore does not number them, which
     * is the same reason [SessionExerciseDraft][com.opengym.core.domain.model.SessionExerciseDraft]
     * has no position field.
     *
     * This is also how a session is laid out at the moment it starts — the session is empty
     * then, so "append" and "lay out from zero" are the same operation, and there is no second
     * write path to keep in step.
     */
    @Transaction
    suspend fun appendAll(sessionId: Long, rows: List<SessionExerciseEntity>): List<Long> {
        val start = nextPosition(sessionId)
        return insertAll(rows.mapIndexed { index, row -> row.copy(position = start + index) })
    }

    @Update
    suspend fun update(sessionExercise: SessionExerciseEntity)

    @Update
    suspend fun updateAll(sessionExercises: List<SessionExerciseEntity>)

    /**
     * Moves one row to a position nothing else can be holding, without touching anything else
     * on it.
     *
     * The first half of [rewriteLayout] and useless on its own — see it for what the negative
     * position is for. Deliberately does not stamp `updated_at`: the row is on its way to the
     * position it will keep, and the write that puts it there carries the timestamp.
     */
    @Query("UPDATE session_exercise SET position = :position WHERE id = :id")
    suspend fun park(id: Long, position: Int)

    /**
     * Rewrites [rows] — a reorder, in practice — in one transaction and without ever putting
     * two exercises of one session on the same position.
     *
     * `session_exercise` is unique on `(session_id, position)`, so swapping two exercises row by
     * row aborts on the second write: the first row lands on a position the second still holds.
     * Every row being written is therefore parked out of the way first, at `-(position + 1)` —
     * distinct per row because the positions it is derived from are, and negative, so it cannot
     * collide with a row that is *not* being written and keeps the position it has.
     *
     * Both halves in one transaction because the intermediate state is not a layout: a workout
     * whose exercises are at negative positions must never be observable, and a failure between
     * the two halves would leave one that is.
     *
     * The caller decides which rows move. Handing over the whole layout would work and would
     * restamp `updated_at` on every exercise a reorder did not touch.
     */
    @Transaction
    suspend fun rewriteLayout(rows: List<SessionExerciseEntity>) {
        rows.forEach { row -> park(id = row.id, position = -(row.position + 1)) }
        updateAll(rows)
    }

    @Query("DELETE FROM session_exercise WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM session_exercise WHERE session_id = :sessionId ORDER BY position ASC")
    fun getBySession(sessionId: Long): Flow<List<SessionExerciseEntity>>
}
