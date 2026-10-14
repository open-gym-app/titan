package com.titan.core.data.marketplace

import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.marketplace.BundleFormatException
import com.titan.core.domain.marketplace.IngestionResult
import com.titan.core.domain.marketplace.IngestionSource
import com.titan.core.domain.marketplace.MarketplaceIngestionStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MarketplaceBootstrapTest {

    private class Recording(private val failure: Throwable? = null) : MarketplaceIngestionStrategy {
        val sources = mutableListOf<IngestionSource>()

        override fun ingest(source: IngestionSource): Flow<IngestionResult> =
            flow {
                sources += source
                failure?.let { throw it }
                emit(IngestionResult(1, 0, 0))
            }
    }

    private class Toggles(private val on: Set<M8Feature>) : FeatureToggles {
        override fun observe(feature: M8Feature): Flow<Boolean> = flow { emit(feature in on) }

        override suspend fun isEnabled(feature: M8Feature) = feature in on

        override suspend fun setEnabled(
            feature: M8Feature,
            enabled: Boolean,
        ) = Unit
    }

    private fun run(
        strategy: MarketplaceIngestionStrategy,
        on: Set<M8Feature> = setOf(M8Feature.MARKETPLACE_INGESTION),
    ) = runBlocking { MarketplaceBootstrap(strategy, Toggles(on), Dispatchers.Unconfined).start().join() }

    @Test
    fun `a user who never opted in launches with no ingestion at all`() {
        val strategy = Recording()

        run(strategy, on = emptySet())

        assertTrue(strategy.sources.isEmpty())
    }

    @Test
    fun `only the ingestion switch lets the bundle in`() {
        val strategy = Recording()

        run(strategy, on = M8Feature.entries.toSet() - M8Feature.MARKETPLACE_INGESTION)

        assertTrue(strategy.sources.isEmpty())
    }

    @Test
    fun `launch ingests the bundled asset`() {
        val strategy = Recording()

        run(strategy)

        assertEquals(listOf<IngestionSource>(MarketplaceBootstrap.BUNDLED), strategy.sources)
        assertEquals("marketplace/templates.json", MarketplaceBootstrap.BUNDLED_ASSET)
    }

    @Test
    fun `a bad bundle, a missing asset or a failed write never reaches the launch`() {
        listOf(
            BundleFormatException("bad"),
            IOException("missing"),
            android.database.sqlite.SQLiteException("locked"),
        ).forEach { failure ->
            val strategy = Recording(failure)

            run(strategy)

            assertTrue(strategy.sources.isNotEmpty())
        }
    }
}
