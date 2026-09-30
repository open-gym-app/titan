package com.titan.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.SessionExerciseEntity
import com.titan.core.data.db.entity.SessionLootClaimEntity
import com.titan.core.data.db.entity.SessionXpClaimEntity
import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.data.db.entity.WorkoutSetEntity

/**
 * Whole-table reads, inserts and deletes over the workout history — and the two claim tables
 * that fence it. See [CatalogueBackupDao] for why backup has DAOs of its own.
 *
 * The claims are here rather than with the gamification tables because that is what they are
 * about: one row per session whose XP has been paid and whose drop table has been rolled, both
 * cascading with the session. An export that carried the sessions and not these would restore a
 * history every one of whose workouts could be paid for a second time.
 */
interface HistoryBackupDao {

    @Query("SELECT * FROM workout_session ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readSessions(
        limit: Int,
        offset: Int,
    ): List<WorkoutSessionEntity>

    @Insert
    suspend fun insertSessions(rows: List<WorkoutSessionEntity>)

    @Query("DELETE FROM workout_session")
    suspend fun deleteSessions()

    @Query("SELECT * FROM session_exercise ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readSessionExercises(
        limit: Int,
        offset: Int,
    ): List<SessionExerciseEntity>

    @Insert
    suspend fun insertSessionExercises(rows: List<SessionExerciseEntity>)

    @Query("DELETE FROM session_exercise")
    suspend fun deleteSessionExercises()

    @Query("SELECT * FROM workout_set ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readSets(
        limit: Int,
        offset: Int,
    ): List<WorkoutSetEntity>

    @Insert
    suspend fun insertSets(rows: List<WorkoutSetEntity>)

    @Query("DELETE FROM workout_set")
    suspend fun deleteSets()

    @Query("SELECT * FROM session_xp_claim ORDER BY session_id LIMIT :limit OFFSET :offset")
    suspend fun readXpClaims(
        limit: Int,
        offset: Int,
    ): List<SessionXpClaimEntity>

    @Insert
    suspend fun insertXpClaims(rows: List<SessionXpClaimEntity>)

    @Query("DELETE FROM session_xp_claim")
    suspend fun deleteXpClaims()

    @Query("SELECT * FROM session_loot_claim ORDER BY session_id LIMIT :limit OFFSET :offset")
    suspend fun readLootClaims(
        limit: Int,
        offset: Int,
    ): List<SessionLootClaimEntity>

    @Insert
    suspend fun insertLootClaims(rows: List<SessionLootClaimEntity>)

    @Query("DELETE FROM session_loot_claim")
    suspend fun deleteLootClaims()
}
