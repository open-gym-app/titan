package com.titan.core.domain.repository

import com.titan.core.domain.model.PowerSnapshot
import com.titan.core.domain.model.SparBout
import kotlinx.coroutines.flow.Flow

/**
 * The titan's past selves and the bouts fought against them.
 *
 * Snapshots are written by the growth step, inside its own write; this reads them. Bouts are
 * written by the spar. The spars-won count is the [SparTally] unlocks are judged on.
 */
interface SparRepository : SparTally {

    /** [petId]'s weekly snapshots, oldest first. */
    fun observeSnapshots(petId: Long): Flow<List<PowerSnapshot>>

    /** [petId]'s bouts, oldest first. */
    fun observeBouts(petId: Long): Flow<List<SparBout>>

    /** Records one bout. */
    suspend fun recordBout(
        petId: Long,
        bout: SparBout,
        power: Int,
        rivalPower: Int,
        roundsWon: Int,
        roundsLost: Int,
        foughtAt: Long,
    )

    /**
     * Runs [block] in one transaction. The titan's state write and the bout's record from inside
     * it commit together.
     */
    suspend fun <T> atomically(block: suspend () -> T): T
}
