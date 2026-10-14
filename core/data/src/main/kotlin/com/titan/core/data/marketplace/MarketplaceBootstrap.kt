package com.titan.core.data.marketplace

import android.database.sqlite.SQLiteException
import com.titan.core.data.di.IoDispatcher
import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.marketplace.BundleFormatException
import com.titan.core.domain.marketplace.IngestionSource
import com.titan.core.domain.marketplace.MarketplaceIngestionStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled marketplace in the background at launch, so the table is populated the first
 * time anything reads it and refreshed whenever an update ships a new bundle.
 *
 * Fire-and-forget: a failure leaves the table as it was and is not allowed to take the launch
 * with it. Nothing is read by the UI yet, so launch never waits on this.
 *
 * Behind [M8Feature.MARKETPLACE_INGESTION], off by default: a user who never opts in launches
 * with no read of the bundle and no write to the table. See `docs/m8-rollout.md`.
 */
@Singleton
class MarketplaceBootstrap @Inject constructor(
    private val strategy: MarketplaceIngestionStrategy,
    private val toggles: FeatureToggles,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun start(): Job =
        scope.launch {
            try {
                if (!toggles.isEnabled(M8Feature.MARKETPLACE_INGESTION)) return@launch
                strategy.ingest(BUNDLED).collect()
            } catch (ignored: BundleFormatException) {
                // The previous contents stand until the next launch.
            } catch (ignored: IOException) {
                // The asset is missing from this build.
            } catch (ignored: SQLiteException) {
                // The table could not be written; it is retried on the next launch.
            }
        }

    companion object {
        const val BUNDLED_ASSET = "marketplace/templates.json"
        val BUNDLED = IngestionSource.LocalBundle(BUNDLED_ASSET)
    }
}
