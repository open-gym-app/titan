package com.titan.core.domain.repository

import com.titan.core.domain.model.PantryEntry
import kotlinx.coroutines.flow.Flow

/**
 * The food the user holds, and what the titan has eaten of it.
 *
 * ### The pantry is a count over rows
 *
 * A drop row is kept when its food is eaten, as an egg's is when it hatches: the drop happened.
 * Eating records a row of its own naming the drop, and "in stock" is every food drop no eating
 * row names. The eating row's id is unique, which is the fence: one item is eaten once.
 */
interface PantryRepository {

    /** Every food in the catalogue, in its order, with its stock — empty ones included. */
    fun observePantry(): Flow<List<PantryEntry>>

    /** The oldest drop of [foodId] still in stock, or null when there is none. */
    suspend fun oldestInStock(foodId: String): Long?

    /**
     * Records that [petId] ate drop row [collectedItemId] at [fedAt].
     *
     * @throws IllegalStateException if that row was already eaten: the store refuses it, and the
     *   caller reads the stock inside the same [atomically] block to avoid asking.
     */
    suspend fun consume(
        collectedItemId: Long,
        petId: Long,
        fedAt: Long,
    )

    /** Marks every drop of [foodId] held now as seen, so it is no longer new. */
    suspend fun markSeen(foodId: String)

    /**
     * Runs [block] in one transaction. Every call on this repository and on the titan's state
     * from inside it takes part, so a feed's consume and its vital write commit together.
     */
    suspend fun <T> atomically(block: suspend () -> T): T
}
