package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.HatchRates
import com.titan.core.domain.gamification.PetSpeciesCatalogue
import com.titan.core.domain.model.HatchResult
import com.titan.core.domain.model.PetSpecies
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Hatches one pet egg the user owns into a pet.
 *
 * ### The user's act, not the cascade's
 *
 * A drop belongs to the moment a workout finishes; a hatch belongs to the moment the user taps
 * "Hatch" on an egg. So this is called from a screen and never from `SessionCompletionPipeline`,
 * which would otherwise choose for the user which egg hatches and when.
 *
 * ### Two draws
 *
 * The rarity by [HatchRates], then uniformly within that band — the shape
 * `DropTableEvaluator.roll` has, so a species added to a band widens it and moves no odds.
 *
 * ### No claim table
 *
 * The roll and the insert are one uncancellable unit, for `LootRollUseCase`'s reason. Nothing
 * fences them from a second call except the store's own rule that an egg hatches once, which
 * sits on the result itself: a racing second call loses there and is answered
 * [HatchResult.AlreadyHatched] with the winner's pet.
 *
 * @property random where the two numbers come from; only a test passes a sequence.
 * @property clock when "now" is. A hatch has no earlier triggering event to be dated to.
 * @property species what can hatch. Defaulted to the shipped catalogue; a test pins its own.
 */
class HatchEggUseCase(
    private val collectedItemRepository: CollectedItemRepository,
    private val petRepository: PetRepository,
    private val random: RandomSource = RandomSource.SYSTEM,
    private val clock: Clock = Clock.SYSTEM,
    private val species: List<PetSpecies> = PetSpeciesCatalogue.ALL,
) {

    suspend operator fun invoke(collectedItemId: Long): HatchResult {
        val item = collectedItemRepository.find(collectedItemId) ?: return HatchResult.NotFound
        if (item.collectibleId != Collectibles.PET_EGG.id) return HatchResult.NotAnEgg
        petRepository.findByEgg(collectedItemId)?.let { return HatchResult.AlreadyHatched(it) }

        return withContext(NonCancellable) {
            val hatched = petRepository.hatch(
                collectedItemId = collectedItemId,
                speciesId = rollSpecies().id,
                hatchedAt = Instant.ofEpochMilli(clock.nowMillis()),
            )
            if (hatched.isNew) HatchResult.Hatched(hatched.pet) else HatchResult.AlreadyHatched(hatched.pet)
        }
    }

    private fun rollSpecies(): PetSpecies {
        val rarity = HatchRates.rarityFor(random.nextInt(HatchRates.TOTAL_WEIGHT))
        val band = species.filter { it.rarity == rarity }
        check(band.isNotEmpty()) { "no species is stocked for $rarity, so an egg has nothing to hatch into" }
        return band[random.nextInt(band.size)]
    }
}
