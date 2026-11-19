package com.titan.core.domain.repository

import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanState
import kotlinx.coroutines.flow.Flow

/**
 * A pet's vitals and current activity, stored with the moment they describe.
 *
 * ### Read, advance, write
 *
 * A stored state is always behind: it is the titan as of its last write. Every read here carries
 * it forward to now through `TitanRules.advance`, and **a read never writes** — two reads at the
 * same moment give the same titan whether or not anything was stored between them. Writes go
 * through [modify], which reads, advances to now, applies and stores in one transaction, so two
 * concurrent writes cannot lose an update.
 *
 * A pet with no state row — hatched on a build before this one — reads as the starting state
 * from the moment it hatched, carried forward like any other.
 */
interface TitanStateRepository {

    /**
     * [petId]'s state, advanced to the moment of each emission, or nothing for a pet that is not
     * there.
     *
     * Emits when the stored row changes. Time passing on its own is not an emission: a screen
     * that wants the titan to move while it is open re-reads on its own tick.
     */
    fun observe(petId: Long): Flow<TitanState?>

    /**
     * Reads [petId]'s state, advances it to now and hands it to [change], in one transaction.
     *
     * @param change answers with the state to store — or null to store nothing — and a result
     *   for the caller. `now` is the moment the state was advanced to.
     * @return [change]'s result, or null without calling it for a pet that is not there.
     */
    suspend fun <R> modify(
        petId: Long,
        change: (state: TitanState, now: Long) -> TitanChange<R>,
    ): R?
}
