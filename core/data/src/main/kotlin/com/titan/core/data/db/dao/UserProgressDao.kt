package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.titan.core.data.db.entity.UserProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * The one progress row: read it, watch it, or change it under a transaction.
 *
 * Every query here is written against a table the schema pins to a single row (see
 * [UserProgressEntity]), so "the row" is unambiguous and none of them takes an id.
 */
@Dao
interface UserProgressDao {

    /**
     * Writes [progress], inserting the row on a fresh install and replacing it thereafter.
     *
     * Public because a test seeds through it; the app changes progress through [mutate],
     * which is the only shape that keeps a read-modify-write atomic.
     *
     * Throws `SQLiteConstraintException` for a row whose id is not
     * [UserProgressEntity.SINGLE_ROW_ID] — the `CHECK` is a constraint failure rather than a
     * uniqueness one, so Room's upsert does not mistake it for a conflict to update over.
     */
    @Upsert
    suspend fun upsert(progress: UserProgressEntity)

    /**
     * The progress row as it changes, or null until anything has ever been written.
     *
     * Null rather than a synthesised zero row: "nothing has been banked yet" is a state a
     * surface may want to render differently from a user who has trained and holds no XP,
     * and a DAO that invented the empty row would take that choice away. [mutate] supplies
     * the same default on the write side, where it is not a choice.
     */
    @Query("SELECT * FROM user_progress WHERE id = ${UserProgressEntity.SINGLE_ROW_ID}")
    fun observe(): Flow<UserProgressEntity?>

    /**
     * The progress row now, or null if there is none.
     *
     * A suspend one-shot rather than a `Flow`: [mutate] reads inside a transaction, and a
     * flow has no "now" — collecting one there would not make the read part of it.
     */
    @Query("SELECT * FROM user_progress WHERE id = ${UserProgressEntity.SINGLE_ROW_ID}")
    suspend fun getOnce(): UserProgressEntity?

    /**
     * Applies [transform] to the stored row and writes the result back, **as one atomic
     * operation**, and returns what was written.
     *
     * The whole reason the DAO exposes a transform rather than a setter. XP awards and
     * streak updates are read-modify-writes, and a caller that read the row, computed a new
     * one and upserted it would have two concurrent awards both reading the same total
     * before either wrote — each storing `total + its own delta`, losing one of them, and
     * making a level-up report untrustworthy in the process. Inside `@Transaction` the two
     * serialise, which is what the repository contracts promise.
     *
     * [transform] is handed [UserProgressEntity]'s defaults when the table is empty, so the
     * first award on a fresh install is an ordinary write rather than a special case. It
     * must be pure and must not touch the database: it runs inside the transaction, and
     * anything it did there would deadlock or escape the atomicity it was called for.
     */
    @Transaction
    suspend fun mutate(transform: (current: UserProgressEntity) -> UserProgressEntity): UserProgressEntity {
        val updated = transform(getOnce() ?: UserProgressEntity())
        upsert(updated)
        return updated
    }
}
