package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate

/**
 * The writes one ingestion makes, decided before any of them happens.
 *
 * @property inserts templates the table does not have.
 * @property updates templates to overwrite; each is also marked seen and made visible again.
 * @property touches ids present and unchanged, whose only change is being marked seen.
 * @property hides ids to soft-delete.
 */
data class IngestionPlan(
    val inserts: List<MarketplaceTemplate>,
    val updates: List<MarketplaceTemplate>,
    val touches: List<String>,
    val hides: List<String>,
) {
    fun toResult(): IngestionResult = IngestionResult(inserts.size, updates.size, hides.size)
}
