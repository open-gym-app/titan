package com.titan.core.data.repository

import com.titan.core.data.db.dao.MarketplaceTemplateDao
import com.titan.core.data.mapper.toDomain
import com.titan.core.domain.model.MarketplaceTemplate
import com.titan.core.domain.repository.MarketplaceRepository
import javax.inject.Inject

/**
 * The ingested marketplace, minus what has been soft-deleted.
 *
 * A row that no longer satisfies the domain's invariants - possible only through a restore from
 * a file edited by hand - is left out rather than failing the whole listing; the next ingestion
 * sees it as not stored and overwrites it.
 */
class MarketplaceRepositoryImpl @Inject constructor(private val dao: MarketplaceTemplateDao) :
    MarketplaceRepository {

    override suspend fun getAll(): List<MarketplaceTemplate> =
        dao.getVisible().mapNotNull { row -> runCatching { row.toDomain() }.getOrNull() }
}
