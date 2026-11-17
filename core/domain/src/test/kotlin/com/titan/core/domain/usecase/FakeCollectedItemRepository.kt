package com.titan.core.domain.usecase

import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.repository.CollectedItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * The collectible inventory, in memory — a list, because that is what the table is.
 *
 * The property worth reproducing is the one the real store makes a point of: [recordDrop]
 * deduplicates **nothing** and every call is another row. A fake that collapsed two drops of the
 * same collectible into one would hide the very bug the roll's fence exists to prevent, since a
 * cascade that rolled twice would still read back as a single drop.
 *
 * [failNext] is how a test drives the failure half of the pipeline's contract: a store that
 * refuses a drop after every earlier step has already committed.
 */
class FakeCollectedItemRepository : CollectedItemRepository {

    private val drops = MutableStateFlow<List<CollectedItem>>(emptyList())

    /** Every drop written, oldest first, for asserting what a pass actually banked. */
    val recorded: List<CollectedItem> get() = drops.value

    /** When set, the next [recordDrop] throws it instead of writing. Cleared on use. */
    var failNext: Throwable? = null

    /**
     * Newest first, as the query's `ORDER BY acquired_at DESC, id DESC` returns them.
     *
     * Sorted rather than simply reversed, because insertion order is not acquisition order: a
     * cascade re-entered at boot for a session finished yesterday writes an older `acquiredAt`
     * after a newer one, and a fake that read the list backwards would hand that drop back as
     * the most recent one.
     */
    override fun observeRecent(limit: Int): Flow<List<CollectedItem>> {
        require(limit >= 1) { "a recent-drops read must ask for at least one, was $limit" }
        return drops.map { held ->
            held.sortedWith(compareByDescending<CollectedItem> { it.acquiredAt }.thenByDescending { it.id })
                .take(limit)
        }
    }

    override suspend fun recordDrop(
        collectibleId: String,
        source: String,
        acquiredAt: Instant,
        sessionId: Long?,
    ) {
        failNext?.let {
            failNext = null
            throw it
        }
        drops.value += CollectedItem(
            // The rowid the table would have handed out, so two drops of one collectible are
            // still two distinguishable rows.
            id = drops.value.size + 1L,
            collectibleId = collectibleId,
            source = source,
            acquiredAt = acquiredAt,
        )
    }

    /** Drop rows a pet has hatched from, which [observeUnhatchedEggs] leaves out. */
    val hatched = MutableStateFlow<Set<Long>>(emptySet())

    override suspend fun find(id: Long): CollectedItem? = drops.value.firstOrNull { it.id == id }

    override fun observeUnhatchedEggs(): Flow<List<CollectedItem>> =
        combine(drops, hatched) { held, gone ->
            held.filter { it.collectibleId == "pet_egg" && it.id !in gone }.sortedBy { it.acquiredAt }
        }
}
