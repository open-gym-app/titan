package com.titan.core.data.repository

import com.titan.core.data.db.dao.PetUnlockDao
import com.titan.core.data.db.entity.PetUnlockEntity
import com.titan.core.domain.repository.UnlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UnlockRepositoryImpl @Inject constructor(private val dao: PetUnlockDao) : UnlockRepository {

    override fun observeUnlocked(): Flow<Set<String>> = dao.observeKeys().map { it.toSet() }

    override suspend fun unlock(
        keys: Set<String>,
        at: Long,
    ): Set<String> {
        val ordered = keys.toList()
        val ids = dao.insertOrIgnore(ordered.map { PetUnlockEntity(key = it, unlockedAt = at) })
        return ordered.filterIndexed { index, _ -> ids[index] != NOT_INSERTED }.toSet()
    }

    private companion object {
        const val NOT_INSERTED = -1L
    }
}
