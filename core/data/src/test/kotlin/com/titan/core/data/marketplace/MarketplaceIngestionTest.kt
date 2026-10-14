package com.titan.core.data.marketplace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.entity.MarketplaceTemplateEntity
import com.titan.core.data.repository.MarketplaceIngestionStoreImpl
import com.titan.core.data.repository.MarketplaceRepositoryImpl
import com.titan.core.domain.marketplace.BundleFormatException
import com.titan.core.domain.marketplace.IngestionResult
import com.titan.core.domain.marketplace.IngestionSource
import com.titan.core.domain.marketplace.MarketplaceReconciler
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * End to end: a JSON asset, through the real [AssetBundleOpener], the parser, the reconciler and
 * the Room store, to the repository that serves it.
 */
class MarketplaceIngestionTest : RoomTestBase() {

    private var now = START
    private var overrides: Map<String, String> = emptyMap()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val assets = AssetBundleOpener(ApplicationProvider.getApplicationContext())

    private val opener = BundleOpener { path -> overrides[path] ?: assets.read(path) }

    private fun strategy(reconciler: MarketplaceReconciler = MarketplaceReconciler()) =
        LocalBundleIngestionStrategy(
            opener = opener,
            store = MarketplaceIngestionStoreImpl(db),
            clock = Clock { now },
            dispatcher = Dispatchers.Unconfined,
            reconciler = reconciler,
        )

    private val repository by lazy { MarketplaceRepositoryImpl(db.marketplaceTemplateDao()) }

    private fun ingest(
        path: String = TEST_BUNDLE,
        reconciler: MarketplaceReconciler = MarketplaceReconciler(),
    ): IngestionResult = runBlocking { strategy(reconciler).ingest(IngestionSource.LocalBundle(path)).first() }

    private fun bundleWithout(vararg ids: String): String {
        val text = context.assets.open(TEST_BUNDLE).bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(text)
        val kept = org.json.JSONArray()
        val all = root.getJSONArray("templates")
        for (i in 0 until all.length()) {
            if (all.getJSONObject(i).getString("remote_id") !in ids) kept.put(all.getJSONObject(i))
        }
        return root.put("templates", kept).toString()
    }

    private fun hiddenCount() = runBlocking { db.marketplaceTemplateDao().getAll().count { it.isHidden } }

    @Test
    fun `the first ingestion fills the table from the asset`() {
        val result = ingest()

        assertEquals(IngestionResult(inserted = 6, updated = 0, softDeleted = 0), result)
        assertEquals(6, countRows("marketplace_template"))
        assertEquals(6, runBlocking { repository.getAll() }.size)
    }

    @Test
    fun `ingested templates keep their content`() {
        ingest()

        val one = runBlocking { repository.getAll() }.first { it.id == "t-one" }
        assertEquals("Test One", one.title)
        assertEquals("Ann", one.author)
        assertEquals(listOf("chest", "barbell"), one.tags)
        assertEquals(100, one.downloadCount)
        assertEquals(4.5, one.averageRating, 0.0)
        assertEquals(45, one.durationMinutes)
    }

    @Test
    fun `ingesting the same bundle again adds no rows and reports no changes`() {
        ingest()
        now += TimeUnit.HOURS.toMillis(1)

        val again = ingest()

        assertEquals(IngestionResult(0, 0, 0), again)
        assertEquals(6, countRows("marketplace_template"))
    }

    @Test
    fun `a changed template is updated in place`() {
        ingest()
        overrides = mapOf(
            TEST_BUNDLE to bundleWithout().replace("\"download_count\":100,", "\"download_count\":999,"),
        )

        val result = ingest()

        assertEquals(IngestionResult(0, 1, 0), result)
        assertEquals(6, countRows("marketplace_template"))
        assertEquals(999, runBlocking { repository.getAll() }.first { it.id == "t-one" }.downloadCount)
    }

    @Test
    fun `a template dropped from the bundle stays visible until the TTL elapses`() {
        ingest()
        overrides = mapOf(TEST_BUNDLE to bundleWithout("t-six"))
        now = START + TimeUnit.DAYS.toMillis(29)

        val result = ingest()

        assertEquals(IngestionResult(0, 0, 0), result)
        assertEquals(0, hiddenCount())
        assertEquals(6, runBlocking { repository.getAll() }.size)
    }

    @Test
    fun `after the TTL the dropped template is soft deleted, not removed`() {
        ingest()
        overrides = mapOf(TEST_BUNDLE to bundleWithout("t-six"))
        now = START + TimeUnit.DAYS.toMillis(30)

        val result = ingest()

        assertEquals(IngestionResult(0, 0, 1), result)
        assertEquals(6, countRows("marketplace_template"))
        assertEquals(1, hiddenCount())
        assertFalse(runBlocking { repository.getAll() }.any { it.id == "t-six" })
    }

    @Test
    fun `a soft deleted template is not counted again by later ingestions`() {
        ingest()
        overrides = mapOf(TEST_BUNDLE to bundleWithout("t-six"))
        now = START + TimeUnit.DAYS.toMillis(31)
        ingest()
        now += TimeUnit.DAYS.toMillis(5)

        assertEquals(IngestionResult(0, 0, 0), ingest())
    }

    @Test
    fun `a template that returns after being hidden is shown again`() {
        ingest()
        overrides = mapOf(TEST_BUNDLE to bundleWithout("t-six"))
        now = START + TimeUnit.DAYS.toMillis(31)
        ingest()
        overrides = emptyMap()
        now += TimeUnit.DAYS.toMillis(1)

        val result = ingest()

        assertEquals(IngestionResult(0, 1, 0), result)
        assertEquals(0, hiddenCount())
        assertTrue(runBlocking { repository.getAll() }.any { it.id == "t-six" })
    }

    @Test
    fun `the TTL is configurable`() {
        val shortLived = MarketplaceReconciler(ttlMillis = TimeUnit.DAYS.toMillis(1))
        ingest(reconciler = shortLived)
        overrides = mapOf(TEST_BUNDLE to bundleWithout("t-six"))
        now = START + TimeUnit.DAYS.toMillis(1)

        assertEquals(IngestionResult(0, 0, 1), ingest(reconciler = shortLived))
    }

    @Test
    fun `a malformed bundle fails before anything is written or hidden`() {
        ingest()
        overrides = mapOf(TEST_BUNDLE to "{ not json")
        now = START + TimeUnit.DAYS.toMillis(90)

        assertThrows(BundleFormatException::class.java) { ingest() }

        assertEquals(6, countRows("marketplace_template"))
        assertEquals(0, hiddenCount())
    }

    @Test
    fun `a missing asset fails the flow`() {
        assertThrows(FileNotFoundException::class.java) { ingest("marketplace/does_not_exist.json") }
        assertEquals(0, countRows("marketplace_template"))
    }

    @Test
    fun `a stored row that breaks the domain's rules is left out of the listing and repaired by ingestion`() {
        runBlocking {
            db.marketplaceTemplateDao().upsertAll(
                listOf(
                    MarketplaceTemplateEntity("t-one", "Broken", "", "", "", 0, 9.0, 10, 1, START, false),
                ),
            )
        }
        assertTrue(runBlocking { repository.getAll() }.isEmpty())

        val result = ingest()

        assertEquals(IngestionResult(6, 0, 0), result)
        assertEquals("Test One", runBlocking { repository.getAll() }.first { it.id == "t-one" }.title)
    }

    @Test
    fun `the bundle shipped in the APK is valid and large enough to be worth showing`() {
        val result = ingest(MarketplaceBootstrap.BUNDLED_ASSET)

        assertTrue(result.inserted >= 5)
        assertEquals(result.inserted, countRows("marketplace_template"))
    }

    private companion object {
        const val TEST_BUNDLE = "marketplace/test_bundle.json"
        val START = TimeUnit.DAYS.toMillis(20_000)
    }
}
