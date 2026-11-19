package com.titan.core.data.repository

import com.titan.core.data.db.dao.CollectedItemDao
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.repository.CollectedItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

/**
 * The collectible inventory, over Room.
 *
 * One table and no atomicity to arrange, which is what makes this the plainest of the
 * gamification stores. [GamificationRepositoryImpl] and [QuestRepositoryImpl] are both built
 * around a write that must happen exactly once, because their answer is what XP is paid on; a
 * drop is not deduplicated at all, so there is nothing here but a mapping in each direction.
 *
 * ### Instants are stored to the millisecond
 *
 * `acquired_at` is epoch milliseconds, so an [Instant]'s sub-millisecond part is dropped on the
 * way in — the same trade [QuestRepositoryImpl] and [GamificationRepositoryImpl] make. Nothing
 * reads it back at that resolution: a drop is dated for display and ordered against other
 * drops, and two of those would have to land inside one millisecond for it to matter, which the
 * rowid tie-break in [CollectedItemDao.observeRecent] already settles.
 */
class CollectedItemRepositoryImpl @Inject constructor(private val collectedItemDao: CollectedItemDao) :
    CollectedItemRepository {

    /**
     * The bound is checked here rather than left to SQLite, which reads a negative `LIMIT` as
     * *no limit at all* — a caller that computed one from an empty screen would silently get
     * the whole inventory instead of nothing.
     */
    override fun observeRecent(limit: Int): Flow<List<CollectedItem>> {
        require(limit >= 1) { "a recent-drops read must ask for at least one, was $limit" }
        return collectedItemDao.observeRecent(limit).map { rows -> rows.map(CollectedItemEntity::toDomain) }
    }

    override suspend fun find(id: Long): CollectedItem? = collectedItemDao.find(id)?.toDomain()

    override fun observeUnhatchedEggs(): Flow<List<CollectedItem>> =
        collectedItemDao.observeUnhatched(Collectibles.PET_EGG.id)
            .map { rows -> rows.map(CollectedItemEntity::toDomain) }

    override suspend fun recordDrop(
        collectibleId: String,
        source: String,
        acquiredAt: Instant,
        sessionId: Long?,
    ) {
        collectedItemDao.insert(
            CollectedItemEntity(
                collectibleId = collectibleId,
                source = source,
                acquiredAt = acquiredAt.toEpochMilli(),
                sessionId = sessionId,
            ),
        )
    }
}

/** The stored drop as the domain's value. Both ids cross unchanged; see [CollectedItemEntity]. */
private fun CollectedItemEntity.toDomain(): CollectedItem =
    CollectedItem(
        id = id,
        collectibleId = collectibleId,
        source = source,
        acquiredAt = Instant.ofEpochMilli(acquiredAt),
    )
