package com.titan.core.domain.usecase

import com.titan.core.domain.marketplace.MarketplaceFilter
import com.titan.core.domain.marketplace.MarketplaceRanker
import com.titan.core.domain.marketplace.MarketplaceTemplate
import com.titan.core.domain.repository.MarketplaceRepository

/**
 * The marketplace templates that pass every one of [filters], best ranked first.
 *
 * Filters are ANDed: each is checked independently, so a search with several returns
 * the intersection of what each alone would return, and no filters returns the whole
 * catalogue ranked. Filtering runs before ranking so the ranker only scores what will
 * be shown.
 */
class SearchMarketplaceUseCase(
    private val marketplaceRepository: MarketplaceRepository,
    private val ranker: MarketplaceRanker = MarketplaceRanker(),
) {

    suspend operator fun invoke(filters: List<MarketplaceFilter> = emptyList()): List<MarketplaceTemplate> {
        val matching = marketplaceRepository.getTemplates().filter { template ->
            filters.all { it.matches(template) }
        }
        return ranker.rank(matching)
    }
}
