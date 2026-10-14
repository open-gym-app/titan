package com.titan.app

import com.titan.core.data.marketplace.MarketplaceBootstrap
import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.health.ImportFromHealthConnectUseCase
import com.titan.core.domain.health.SyncToHealthConnectUseCase
import com.titan.core.domain.model.HealthSkipReason
import com.titan.core.domain.model.HealthSyncOutcome
import com.titan.core.domain.repository.MarketplaceRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * A user who never opts in to anything M8 added sees and runs what they did before it.
 *
 * On the real graph, with nothing switched on: no switch is on, the launch ingests nothing, and
 * Health Connect is neither asked for permission nor written to. The core session journey is
 * `EndToEndJourneyTest`, which runs on this same graph with the same defaults.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class M8DefaultsTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var toggles: FeatureToggles

    @Inject
    lateinit var bootstrap: MarketplaceBootstrap

    @Inject
    lateinit var marketplace: MarketplaceRepository

    @Inject
    lateinit var syncToHealthConnect: SyncToHealthConnectUseCase

    @Inject
    lateinit var importFromHealthConnect: ImportFromHealthConnectUseCase

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `every switch starts off`() =
        runBlocking {
            M8Feature.entries.forEach { assertFalse("$it", toggles.isEnabled(it)) }
        }

    @Test
    fun `a launch ingests no marketplace until the user opts in`() =
        runBlocking {
            bootstrap.start().join()

            assertTrue(marketplace.getAll().isEmpty())
        }

    @Test
    fun `the ingestion switch is what lets the bundle in`() =
        runBlocking {
            toggles.setEnabled(M8Feature.MARKETPLACE_INGESTION, true)

            bootstrap.start().join()

            assertTrue(marketplace.getAll().isNotEmpty())
        }

    @Test
    fun `health connect is never touched while it is off`() =
        runBlocking {
            val disabled = HealthSyncOutcome.Skipped(HealthSkipReason.DISABLED)

            assertEquals(disabled, syncToHealthConnect(1L).getOrThrow())
            assertEquals(disabled, importFromHealthConnect().getOrThrow())
        }
}
