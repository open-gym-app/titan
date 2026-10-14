package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate

/**
 * A marketplace template as the local table holds it.
 *
 * @property lastSeenAt epoch milliseconds of the last ingestion that carried it; the clock the
 *   TTL runs against.
 * @property isHidden soft-deleted: kept in the table, left out of search.
 */
data class StoredTemplate(
    val template: MarketplaceTemplate,
    val lastSeenAt: Long,
    val isHidden: Boolean,
)
