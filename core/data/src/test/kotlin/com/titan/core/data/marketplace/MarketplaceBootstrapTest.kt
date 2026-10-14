package com.titan.core.data.marketplace

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

    private fun run(strategy: MarketplaceIngestionStrategy) =
        runBlocking { MarketplaceBootstrap(strategy, Dispatchers.Unconfined).start().join() }

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
