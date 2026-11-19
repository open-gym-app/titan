package com.titan.core.domain.repository

import com.titan.core.domain.model.GearSlot
import kotlinx.coroutines.flow.Flow

/**
 * What each pet is wearing: one gear key per slot.
 *
 * Keys are stored as written. A row naming a slot or a gear this build does not know — a newer
 * build's, restored — is kept; readers skip what they cannot name, and nothing deletes a row for
 * being unrecognised.
 */
interface LoadoutRepository {

    /** [petId]'s worn gear, by slot, as stored keys — unknown ones included. */
    fun observeLoadout(petId: Long): Flow<Map<String, String>>

    /** Puts [gearKey] in [slot] on [petId], replacing what was there. */
    suspend fun wear(
        petId: Long,
        slot: GearSlot,
        gearKey: String,
    )

    /** Empties [slot] on [petId]. */
    suspend fun takeOff(
        petId: Long,
        slotKey: String,
    )
}
