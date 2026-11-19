package com.titan.core.data.repository

import androidx.room.withTransaction
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.db.entity.PetEntity
import com.titan.core.data.db.entity.PetStateEntity
import com.titan.core.data.mapper.toDomain
import com.titan.core.data.mapper.toEntity
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.repository.TitanStateRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * The titan's state over `pet_state`.
 *
 * The seed for the titan's own choices is its pet id: stable for the life of the pet, and
 * different from one pet to the next.
 */
class TitanStateRepositoryImpl @Inject constructor(
    private val database: TitanDatabase,
    private val clock: Clock,
) : TitanStateRepository {

    private val petDao get() = database.petDao()
    private val stateDao get() = database.petStateDao()

    override fun observe(petId: Long): Flow<TitanState?> =
        combine(petDao.observeById(petId), stateDao.observe(petId)) { pet, row ->
            pet?.let { read(it, row, clock.nowMillis()) }
        }

    override suspend fun <R> modify(
        petId: Long,
        change: (state: TitanState, now: Long) -> TitanChange<R>,
    ): R? =
        database.withTransaction {
            val pet = petDao.find(petId) ?: return@withTransaction null
            val now = clock.nowMillis()
            val decided = change(read(pet, stateDao.get(petId), now), now)
            decided.state?.let { stateDao.upsert(it.toEntity(petId)) }
            decided.result
        }

    private fun read(
        pet: PetEntity,
        row: PetStateEntity?,
        now: Long,
    ): TitanState {
        val stored = row?.toDomain()?.let { TitanRules.repaired(it, now) }
            ?: TitanRules.starting(pet.hatchedAt.coerceAtMost(now))
        return TitanRules.advance(stored, to = now, seed = pet.id)
    }
}
