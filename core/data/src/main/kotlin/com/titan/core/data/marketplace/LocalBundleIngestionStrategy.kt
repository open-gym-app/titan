package com.titan.core.data.marketplace

import com.titan.core.domain.marketplace.IngestionResult
import com.titan.core.domain.marketplace.IngestionSource
import com.titan.core.domain.marketplace.MarketplaceBundleParser
import com.titan.core.domain.marketplace.MarketplaceIngestionStrategy
import com.titan.core.domain.marketplace.MarketplaceReconciler
import com.titan.core.domain.repository.MarketplaceIngestionStore
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Ingests the curated JSON bundle shipped in the APK into `marketplace_template`.
 *
 * The bundle is read and parsed in full before the store is touched, so an unreadable or
 * malformed bundle fails the flow with nothing written - in particular it cannot make every
 * stored template look absent. A flow that completes has applied the whole plan in one
 * transaction.
 *
 * ### Extension point for a remote API
 *
 * A remote catalogue is a second [MarketplaceIngestionStrategy]: fetch, parse to
 * `MarketplaceTemplate`s, then run the same `reconciler` and `store` and emit the
 * [IngestionResult]. See [IngestionSource].
 */
class LocalBundleIngestionStrategy(
    private val opener: BundleOpener,
    private val store: MarketplaceIngestionStore,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    private val reconciler: MarketplaceReconciler = MarketplaceReconciler(),
) : MarketplaceIngestionStrategy {

    override fun ingest(source: IngestionSource): Flow<IngestionResult> =
        flow {
            val assetPath = when (source) {
                is IngestionSource.LocalBundle -> source.assetPath
            }
            val incoming = MarketplaceBundleParser.parse(opener.read(assetPath))
            val now = clock.nowMillis()
            val plan = store.reconcile(now) { stored -> reconciler.plan(stored, incoming, now) }
            emit(plan.toResult())
        }.flowOn(dispatcher)
}
