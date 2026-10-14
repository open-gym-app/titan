package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate
import com.titan.core.domain.repository.MarketplaceRepository

/**
 * The marketplace listings every given [MarketplaceFilter] accepts, best-ranked first.
 */
class SearchMarketplaceUseCase(
    private val repository: MarketplaceRepository,
    private val ranker: MarketplaceRanker = MarketplaceRanker(),
) {

    suspend operator fun invoke(filters: List<MarketplaceFilter> = emptyList()): List<MarketplaceTemplate> =
        ranker.rank(repository.getAll().filter { listing -> filters.all { it.matches(listing) } })
}
