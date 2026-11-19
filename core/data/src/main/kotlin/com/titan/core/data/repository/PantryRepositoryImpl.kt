package com.titan.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.db.dao.InStockFood
import com.titan.core.data.db.entity.PetFeedEntity
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.model.PantryEntry
import com.titan.core.domain.model.PantrySource
import com.titan.core.domain.repository.PantryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PantryRepositoryImpl @Inject constructor(private val database: TitanDatabase) : PantryRepository {

    private val dao get() = database.pantryDao()

    override fun observePantry(): Flow<List<PantryEntry>> =
        dao.observeInStock(Collectibles.FOOD.map { it.id }).map { rows ->
            val byFood = rows.groupBy(InStockFood::collectibleId)
            Collectibles.FOOD.map { food ->
                val held = byFood[food.id].orEmpty()
                // Rows come oldest first, so the newest in stock is the last.
                val newest = held.lastOrNull()
                PantryEntry(
                    food = food,
                    count = held.size,
                    isNew = held.any { it.id > it.seenUpTo },
                    newestFrom = newest?.sessionId?.let {
                        PantrySource(
                            it,
                            newest.sessionName,
                            newest.sessionFinishedAt,
                        )
                    },
                )
            }
        }

    override suspend fun oldestInStock(foodId: String): Long? = dao.oldestInStock(foodId)

    override suspend fun consume(
        collectedItemId: Long,
        petId: Long,
        fedAt: Long,
    ) {
        try {
            dao.insertFeed(PetFeedEntity(collectedItemId = collectedItemId, petId = petId, fedAt = fedAt))
        } catch (failure: SQLiteConstraintException) {
            throw IllegalStateException("drop row $collectedItemId was already eaten, or is not there", failure)
        }
    }

    override suspend fun markSeen(foodId: String) = dao.markSeen(foodId)

    override suspend fun <T> atomically(block: suspend () -> T): T = database.withTransaction { block() }
}
