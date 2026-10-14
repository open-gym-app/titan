package com.titan.core.domain.repository

import com.titan.core.domain.model.MarketplaceTemplate

/**
 * Where marketplace listings come from.
 *
 * Unordered and unfiltered: ranking and search are
 * [com.titan.core.domain.marketplace.SearchMarketplaceUseCase]'s, so they behave the same
 * whatever supplies the listings. How listings arrive is a separate decision (M8-04).
 */
interface MarketplaceRepository {

    suspend fun getAll(): List<MarketplaceTemplate>
}
