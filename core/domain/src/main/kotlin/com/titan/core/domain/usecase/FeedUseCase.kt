package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.CollectibleKind
import com.titan.core.domain.model.FeedResult
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.repository.PantryRepository
import com.titan.core.domain.repository.TitanStateRepository

/**
 * Feeds the titan one item of a food from the pantry.
 *
 * The **oldest** copy is eaten, so a newer drop keeps its "new" mark. The consume, the vital
 * write and the switch to eating are one transaction, so two feeds of the last item give one
 * [FeedResult.Fed] and one [FeedResult.OutOfStock]. The effect applied is exactly
 * [TitanRules.previewFeed]'s for the same state, which is what the feed sheet shows before the
 * tap.
 */
class FeedUseCase(
    private val pantryRepository: PantryRepository,
    private val titanStateRepository: TitanStateRepository,
) {

    suspend operator fun invoke(
        petId: Long,
        foodId: String,
    ): FeedResult {
        val food = Collectibles.byId(foodId)?.takeIf { it.kind == CollectibleKind.FOOD } ?: return FeedResult.NotFood

        return pantryRepository.atomically {
            val item = pantryRepository.oldestInStock(foodId) ?: return@atomically FeedResult.OutOfStock
            var fedAt: Long? = null
            val result = titanStateRepository.modify(petId) { state, now ->
                if (state.vitals.fuel >= TitanVitals.MAX) {
                    TitanChange(state = null, result = FeedResult.Full)
                } else {
                    fedAt = now
                    val (fed, effect) = TitanRules.feed(state, food, now)
                    TitanChange(state = fed, result = FeedResult.Fed(effect))
                }
            } ?: return@atomically FeedResult.NotFound
            fedAt?.let { pantryRepository.consume(item, petId, it) }
            result
        }
    }
}
