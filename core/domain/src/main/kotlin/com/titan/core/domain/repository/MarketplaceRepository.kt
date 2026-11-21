package com.titan.core.domain.repository

import com.titan.core.domain.marketplace.MarketplaceTemplate

/**
 * Source of the community-shared templates.
 *
 * A one-shot read rather than a [kotlinx.coroutines.flow.Flow]: the catalogue is
 * someone else's data, fetched on demand, and nothing the user does locally changes it.
 * Filtering and ranking happen in
 * [com.titan.core.domain.usecase.SearchMarketplaceUseCase], so an implementation only
 * has to hand back what the catalogue holds, in any order.
 */
interface MarketplaceRepository {

    suspend fun getTemplates(): List<MarketplaceTemplate>
}
