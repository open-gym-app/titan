package com.titan.core.data.repository

import com.titan.core.data.db.dao.GymDao
import com.titan.core.data.mapper.toDomain
import com.titan.core.data.mapper.toEntity
import com.titan.core.domain.model.Gym
import com.titan.core.domain.repository.GymRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Room-backed [GymRepository]. Purely local: nothing here, or in the feature, touches the network. */
class GymRepositoryImpl @Inject constructor(private val gymDao: GymDao) : GymRepository {

    override fun getAll(): Flow<List<Gym>> = gymDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: Long): Gym? = gymDao.getById(id)?.toDomain()

    override suspend fun save(gym: Gym): Long {
        val id = gymDao.upsert(gym.toEntity())
        // An upsert that replaced an existing row reports -1 on some Room versions; the id was given.
        return if (id == -1L) gym.id else id
    }

    override suspend fun delete(id: Long) = gymDao.deleteById(id)
}
