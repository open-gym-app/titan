package com.titan.core.data.repository

import com.titan.core.data.db.dao.GymDao
import com.titan.core.data.mapper.GymMapper
import com.titan.core.domain.model.Gym
import com.titan.core.domain.repository.GymRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The gym directory, over [GymDao]. Ordering is the DAO's. */
class GymRepositoryImpl @Inject constructor(
    private val gymDao: GymDao,
    private val gymMapper: GymMapper,
) : GymRepository {

    override fun getAll(): Flow<List<Gym>> = gymDao.getAll().map { rows -> rows.map(gymMapper::toDomain) }

    override fun getById(id: Long): Flow<Gym?> = gymDao.getById(id).map { row -> row?.let(gymMapper::toDomain) }

    override suspend fun save(gym: Gym): Long {
        val inserted = gymDao.upsert(gymMapper.toEntity(gym))
        return if (inserted == UPDATED) gym.id else inserted
    }

    override suspend fun delete(id: Long) {
        gymDao.delete(id)
    }

    private companion object {
        /** What `@Upsert` returns for a row it updated rather than inserted. */
        const val UPDATED = -1L
    }
}
