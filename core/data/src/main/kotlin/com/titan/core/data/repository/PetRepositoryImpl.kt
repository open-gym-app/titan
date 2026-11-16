package com.titan.core.data.repository

import com.titan.core.data.db.dao.PetDao
import com.titan.core.data.mapper.toDomain
import com.titan.core.domain.gamification.PetNickname
import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.Pet
import com.titan.core.domain.repository.PetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
}
