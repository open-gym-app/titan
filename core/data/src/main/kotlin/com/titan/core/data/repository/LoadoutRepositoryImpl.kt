package com.titan.core.data.repository

import com.titan.core.data.db.dao.LoadoutDao
import com.titan.core.data.db.entity.PetLoadoutEntity
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.repository.LoadoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LoadoutRepositoryImpl @Inject constructor(private val dao: LoadoutDao) : LoadoutRepository {

    override fun observeLoadout(petId: Long): Flow<Map<String, String>> =
        dao.observe(petId).map { rows -> rows.associate { it.slot to it.gear } }

    override suspend fun wear(
        petId: Long,
        slot: GearSlot,
        gearKey: String,
    ) = dao.upsert(PetLoadoutEntity(petId = petId, slot = slot.key, gear = gearKey))

    override suspend fun takeOff(
        petId: Long,
        slotKey: String,
    ) = dao.delete(petId, slotKey)
}
