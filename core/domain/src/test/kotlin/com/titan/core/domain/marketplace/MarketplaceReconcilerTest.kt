package com.titan.core.domain.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MarketplaceReconcilerTest {

    private val reconciler = MarketplaceReconciler()

    private fun stored(
        id: String,
        lastSeenAt: Long = NOW,
        hidden: Boolean = false,
        downloads: Int = 100,
    ) = StoredTemplate(listing(id, downloads = downloads), lastSeenAt, hidden)

    @Test
    fun `the default TTL is thirty days`() {
        assertEquals(TimeUnit.DAYS.toMillis(30), MarketplaceReconciler.DEFAULT_TTL_MILLIS)
    }

    @Test
    fun `into an empty table everything is an insert`() {
        val plan = reconciler.plan(emptyList(), listOf(listing("a"), listing("b")), NOW)

        assertEquals(listOf("a", "b"), plan.inserts.map { it.id })
        assertEquals(IngestionResult(2, 0, 0), plan.toResult())
    }

    @Test
    fun `an identical template is only touched, so re-ingesting changes nothing`() {
        val plan = reconciler.plan(listOf(stored("a"), stored("b")), listOf(listing("a"), listing("b")), NOW + 1)

        assertEquals(listOf("a", "b"), plan.touches)
        assertTrue(plan.inserts.isEmpty() && plan.updates.isEmpty() && plan.hides.isEmpty())
        assertEquals(IngestionResult(0, 0, 0), plan.toResult())
    }

    @Test
    fun `a template with the same remote id and new content is an update, not a second row`() {
        val plan = reconciler.plan(listOf(stored("a", downloads = 100)), listOf(listing("a", downloads = 900)), NOW)

        assertTrue(plan.inserts.isEmpty())
        assertEquals(900, plan.updates.single().downloadCount)
        assertEquals(IngestionResult(0, 1, 0), plan.toResult())
    }

    @Test
    fun `a hidden template that comes back is updated so it shows again`() {
        val plan = reconciler.plan(listOf(stored("a", hidden = true)), listOf(listing("a")), NOW)

        assertEquals(listOf("a"), plan.updates.map { it.id })
        assertTrue(plan.touches.isEmpty())
    }

    @Test
    fun `a template absent for less than the TTL stays visible`() {
        val almost = NOW - MarketplaceReconciler.DEFAULT_TTL_MILLIS + 1

        val plan = reconciler.plan(listOf(stored("gone", lastSeenAt = almost)), emptyList(), NOW)

        assertTrue(plan.hides.isEmpty())
    }

    @Test
    fun `a template absent for exactly the TTL is hidden`() {
        val plan = reconciler.plan(
            listOf(stored("gone", lastSeenAt = NOW - MarketplaceReconciler.DEFAULT_TTL_MILLIS)),
            emptyList(),
            NOW,
        )

        assertEquals(listOf("gone"), plan.hides)
        assertEquals(IngestionResult(0, 0, 1), plan.toResult())
    }

    @Test
    fun `an already hidden template is not hidden or counted again`() {
        val plan = reconciler.plan(listOf(stored("gone", lastSeenAt = 0, hidden = true)), emptyList(), NOW)

        assertTrue(plan.hides.isEmpty())
    }

    @Test
    fun `a template that is present is never hidden however old its last sighting`() {
        val plan = reconciler.plan(listOf(stored("a", lastSeenAt = 0)), listOf(listing("a")), NOW)

        assertTrue(plan.hides.isEmpty())
        assertEquals(listOf("a"), plan.touches)
    }

    @Test
    fun `a custom TTL is honoured`() {
        val short = MarketplaceReconciler(ttlMillis = 1_000)

        val plan = short.plan(listOf(stored("gone", lastSeenAt = NOW - 1_000)), emptyList(), NOW)

        assertEquals(listOf("gone"), plan.hides)
    }

    @Test
    fun `a repeated id in the incoming list is planned once`() {
        val plan = reconciler.plan(emptyList(), listOf(listing("a"), listing("a")), NOW)

        assertEquals(1, plan.inserts.size)
    }

    @Test
    fun `a negative TTL is refused`() {
        assertThrows(IllegalArgumentException::class.java) { MarketplaceReconciler(ttlMillis = -1) }
    }

    @Test
    fun `result counts and source paths are validated`() {
        assertThrows(IllegalArgumentException::class.java) { IngestionResult(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { IngestionResult(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { IngestionResult(0, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { IngestionSource.LocalBundle(" ") }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
