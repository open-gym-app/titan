package com.titan.core.domain.repository

import com.titan.core.domain.model.Pet
import kotlinx.coroutines.flow.Flow

/**
 * The pets the user has hatched — *titans*, on screen.
 *
 * ### One active pet at most, and the read does not trust that
 *
 * The store cannot express "at most one row flagged active" as a constraint, so [setActive] holds
 * it by clearing and setting inside one transaction. A restored file or a bug could still leave
 * two rows flagged; every read here degrades rather than trusts — **the lowest id is the active
 * one**, and the others read back as inactive.
 *
 * ### Values are degraded on the way out
 *
 * A row can hold what a hand-edited backup put there. XP and stats are clamped into their ranges
 * and a nickname that fails [com.titan.core.domain.gamification.PetNickname]'s rule reads as
 * null, so no caller meets a [Pet] its own constructor would refuse.
 */
interface PetRepository {

    /** Every pet, the active one first, then oldest hatched first. */
    fun observeAll(): Flow<List<Pet>>

    /** The active pet, or null when there is none. */
    fun observeActive(): Flow<Pet?>

    /** How many pets the user has. */
    suspend fun count(): Int

    /**
     * Makes [id] the active pet and every other pet inactive, in one transaction.
     *
     * @return false, having changed nothing, if no pet has that id.
     */
    suspend fun setActive(id: Long): Boolean

    /**
     * Sets what pet [id] is called.
     *
     * @param nickname a name already through
     *   [com.titan.core.domain.gamification.PetNickname.validate], or null to go back to the
     *   species' own name.
     * @return false, having changed nothing, if no pet has that id.
     * @throws IllegalArgumentException if [nickname] does not pass the rule as given. The rule is
     *   the caller's to apply, where its reason can be shown; this is the backstop.
     */
    suspend fun rename(
        id: Long,
        nickname: String?,
    ): Boolean
}
