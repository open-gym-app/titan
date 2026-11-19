package com.titan.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.titan.core.data.db.dao.PetDao
import com.titan.core.data.db.entity.PetEntity
import com.titan.core.data.mapper.toDomain
import com.titan.core.domain.gamification.PetNickname
import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.HatchedPet
import com.titan.core.domain.model.Pet
import com.titan.core.domain.repository.PetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class PetRepositoryImpl @Inject constructor(private val petDao: PetDao) : PetRepository {

    override fun observeAll(): Flow<List<Pet>> =
        petDao.observeAll().map { rows ->
            // Two flagged rows is a state a restore can produce. The lowest id is the active one,
            // and the query has already put it first.
            val activeId = rows.filter { it.isActive }.minOfOrNull { it.id }
            rows.map { it.toDomain(isActive = it.id == activeId) }
                .sortedWith(compareByDescending<Pet> { it.isActive }.thenBy { it.id })
        }

    override fun observeActive(): Flow<Pet?> = petDao.observeActive().map { it?.toDomain(isActive = true) }

    override suspend fun count(): Int = petDao.count()

    override suspend fun setActive(id: Long): Boolean = petDao.makeActive(id)

    override suspend fun rename(
        id: Long,
        nickname: String?,
    ): Boolean {
        require(nickname == null || PetNickname.validate(nickname) == PetNicknameResult.Valid(nickname)) {
            "a nickname reaches the store already validated and trimmed"
        }
        return petDao.rename(id, nickname) > 0
    }

    override suspend fun findByEgg(collectedItemId: Long): Pet? = petDao.findByEgg(collectedItemId)?.toActiveAware()

    override suspend fun hatch(
        collectedItemId: Long,
        speciesId: String,
        hatchedAt: Instant,
    ): HatchedPet {
        val isNew = try {
            petDao.hatch(
                PetEntity(
                    speciesId = speciesId,
                    hatchedFromItemId = collectedItemId,
                    hatchedAt = hatchedAt.toEpochMilli(),
                ),
            )
            true
        } catch (_: SQLiteConstraintException) {
            // The unique index on the egg, lost to a racing call — or the foreign key, for a row
            // that is not there. The read below tells the two apart.
            false
        }
        val pet = checkNotNull(findByEgg(collectedItemId)) { "drop row $collectedItemId hatched nothing" }
        return HatchedPet(pet = pet, isNew = isNew)
    }

    /**
     * One row as a [Pet], active only if it is the flagged row with the lowest id — the same
     * degrade the observed reads apply.
     */
    private suspend fun PetEntity.toActiveAware(): Pet = toDomain(isActive = isActive && petDao.activeId() == id)
}
