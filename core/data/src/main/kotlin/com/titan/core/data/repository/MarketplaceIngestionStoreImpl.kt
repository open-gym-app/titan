package com.titan.core.data.repository

import androidx.room.withTransaction
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.mapper.toEntity
import com.titan.core.data.mapper.toStored
import com.titan.core.domain.marketplace.IngestionPlan
import com.titan.core.domain.marketplace.StoredTemplate
import com.titan.core.domain.repository.MarketplaceIngestionStore
import javax.inject.Inject

/** Room-backed [MarketplaceIngestionStore]; the read, the plan and the writes are one transaction. */
class MarketplaceIngestionStoreImpl @Inject constructor(private val database: TitanDatabase) :
    MarketplaceIngestionStore {

    override suspend fun reconcile(
        nowMillis: Long,
        planner: (List<StoredTemplate>) -> IngestionPlan,
    ): IngestionPlan =
        database.withTransaction {
            val dao = database.marketplaceTemplateDao()
            // A stored row the domain refuses to load is treated as not stored: the plan inserts
            // the incoming version, and the upsert replaces the bad row.
            val stored = dao.getAll().mapNotNull { row -> runCatching { row.toStored() }.getOrNull() }
            val plan = planner(stored)

            dao.upsertAll(
                plan.inserts.map { it.toEntity(lastSeenAt = nowMillis) } +
                    plan.updates.map { it.toEntity(lastSeenAt = nowMillis) },
            )
            plan.touches.chunked(SQLITE_ID_CHUNK).forEach { dao.markSeen(it, nowMillis) }
            plan.hides.chunked(SQLITE_ID_CHUNK).forEach { dao.hide(it) }
            plan
        }

    private companion object {
        /** Well under SQLite's 999 bound-variable limit, which an `IN (...)` list counts against. */
        const val SQLITE_ID_CHUNK = 500
    }
}
