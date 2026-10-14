package com.titan.core.domain.repository

import com.titan.core.domain.model.Gym
import kotlinx.coroutines.flow.Flow

/** Where the user's saved gyms are kept — on the device, with no network involved. */
interface GymRepository {

    /** Every saved gym, by name, re-emitted whenever one is saved or deleted. */
    fun getAll(): Flow<List<Gym>>

    /** The gym with [id], or null if there is none. */
    suspend fun getById(id: Long): Gym?

    /**
     * Stores [gym] and returns its id: inserted when its id is 0, otherwise it replaces the
     * saved gym with that id.
     */
    suspend fun save(gym: Gym): Long

    /** Removes the gym with [id]. Deleting one that is not there does nothing. */
    suspend fun delete(id: Long)
}
