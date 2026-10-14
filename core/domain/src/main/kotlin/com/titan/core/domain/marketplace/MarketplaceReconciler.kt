package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate
import java.util.concurrent.TimeUnit

/**
 * Decides what an ingestion changes, as a pure function of what is stored and what arrived.
 *
 * - **Deduplication.** A template is identified by [MarketplaceTemplate.id], its `remoteId`. One
 *   already stored is updated in place, never inserted a second time.
 * - **Staleness.** A stored template absent from the incoming list is *not* hidden at once: a
 *   bundle that loses a template for a release is not the same as the template being withdrawn.
 *   It is hidden only when it has gone unseen for at least [ttlMillis], and then it is hidden,
 *   never deleted - the row stays, so a template that comes back is simply updated and shown.
 *   An already-hidden template is left alone rather than counted again.
 *
 * @property ttlMillis how long a template may go unseen before it is hidden.
 */
class MarketplaceReconciler(private val ttlMillis: Long = DEFAULT_TTL_MILLIS) {

    init {
        require(ttlMillis >= 0) { "ttlMillis cannot be negative, was $ttlMillis" }
    }

    fun plan(
        stored: List<StoredTemplate>,
        incoming: List<MarketplaceTemplate>,
        nowMillis: Long,
    ): IngestionPlan {
        val storedById = stored.associateBy { it.template.id }
        val inserts = mutableListOf<MarketplaceTemplate>()
        val updates = mutableListOf<MarketplaceTemplate>()
        val touches = mutableListOf<String>()
        for (template in incoming.distinctBy { it.id }) {
            val existing = storedById[template.id]
            when {
                existing == null -> inserts += template
                existing.isHidden || existing.template != template -> updates += template
                else -> touches += template.id
            }
        }
        val incomingIds = incoming.mapTo(HashSet()) { it.id }
        val hides = stored
            .filter { !it.isHidden && it.template.id !in incomingIds && nowMillis - it.lastSeenAt >= ttlMillis }
            .map { it.template.id }
        return IngestionPlan(inserts, updates, touches, hides)
    }

    companion object {
        const val DEFAULT_TTL_DAYS = 30L
        val DEFAULT_TTL_MILLIS: Long = TimeUnit.DAYS.toMillis(DEFAULT_TTL_DAYS)
    }
}
