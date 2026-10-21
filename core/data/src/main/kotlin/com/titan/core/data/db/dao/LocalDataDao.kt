package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * How many rows of the user's own there are, per kind.
 *
 * The one DAO that is not "the DAO for an entity", because what it answers is not about an
 * entity: `DataFootprint` is a fact about the *database* — how much of this device is the
 * user's work — and splitting these six counts across the six DAOs that own the tables would
 * scatter one screen's single question over half the data layer.
 *
 * Six queries rather than one row of sub-selects. Room derives which tables a `@Query`
 * observes by parsing it, and the failure mode of a mis-parsed sub-select is a flow that
 * simply stops emitting — a confirmation dialog quoting counts from before the last workout,
 * with nothing to indicate it. Six trivial `COUNT(*)`s over indexed tables are cheap, and the
 * repository combines them; see `LocalDataRepositoryImpl`.
 */
@Dao
interface LocalDataDao {

    @Query("SELECT COUNT(*) FROM workout_session")
    fun countSessions(): Flow<Int>

    @Query("SELECT COUNT(*) FROM workout_set")
    fun countSets(): Flow<Int>

    @Query("SELECT COUNT(*) FROM workout_template")
    fun countTemplates(): Flow<Int>

    @Query("SELECT COUNT(*) FROM personal_record")
    fun countPersonalRecords(): Flow<Int>

    /**
     * Exercises the user added themselves.
     *
     * The seeded catalogue is deliberately excluded: it is app content that a wipe restores,
     * so counting it would put a thousand rows into a dialog listing what the user is about
     * to lose.
     */
    @Query("SELECT COUNT(*) FROM exercise WHERE is_custom = 1")
    fun countCustomExercises(): Flow<Int>

    @Query("SELECT COUNT(*) FROM body_measurement")
    fun countBodyMeasurements(): Flow<Int>

    /**
     * When the oldest session on the device was started, or **null** on a device that has
     * never logged one.
     *
     * `MIN(created_at)` over `workout_session`, which is not one of the six counts and is not
     * a seventh count either — but it answers the same screen's question and is read from the
     * same table, so a DAO of its own would be a file holding one query. Nullable because
     * `MIN` over no rows is NULL, which is exactly the answer wanted; a `COUNT`-shaped query
     * would have returned 0 and dated a fresh install to 1970.
     *
     * `created_at` rather than `completed_at`: a session started and abandoned still marks
     * when this device began being used, and `completed_at` is null on every one of them.
     */
    @Query("SELECT MIN(created_at) FROM workout_session")
    fun firstSessionAt(): Flow<Long?>
}
