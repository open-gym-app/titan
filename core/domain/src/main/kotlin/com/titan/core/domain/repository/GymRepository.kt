package com.titan.core.domain.repository

import com.titan.core.domain.model.Gym
import kotlinx.coroutines.flow.Flow

/**
 * The user's own gym directory, held on the device.
 *
 * Timestamps are the caller's ([Gym.createdAt]) and validation is the caller's too — see
 * [com.titan.core.domain.validation.GymValidator], the only thing that bounds a gym.
 */
interface GymRepository {

    /**
     * Every saved gym, re-emitted as the directory changes, ordered by name (case-insensitive)
     * and then by id so that two gyms sharing a name keep a stable order.
     */
    fun getAll(): Flow<List<Gym>>

    /** The gym with [id], re-emitted as it changes, or null when there is none. */
    fun getById(id: Long): Flow<Gym?>

    /**
     * Stores [gym] and returns its id: a new row when [Gym.id] is 0, otherwise the row with that
     * id is overwritten in full.
     */
    suspend fun save(gym: Gym): Long

    /** Removes the gym, or does nothing when no row has that id. */
    suspend fun delete(id: Long)
}
