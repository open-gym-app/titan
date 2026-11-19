package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.SparRules
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.SparBout
import com.titan.core.domain.model.SparResult
import com.titan.core.domain.model.SparRival
import com.titan.core.domain.model.SparRivalKind
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.SparRepository
import com.titan.core.domain.repository.TitanStateRepository
import com.titan.core.domain.repository.UnlockRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Picks a past self and spars with it.
 *
 * The energy debit, the spirit credit and the record are one transaction; a refusal changes
 * nothing. After a bout the unlocks are evaluated, since the dojo and the scarf open on spars
 * won. Sparring pays the titan only.
 */
class SparUseCase(
    private val petRepository: PetRepository,
    private val sparRepository: SparRepository,
    private val titanStateRepository: TitanStateRepository,
    private val unlockRepository: UnlockRepository,
    private val evaluateUnlocks: EvaluateUnlocksUseCase,
    private val random: RandomSource = RandomSource.SYSTEM,
    private val clock: Clock = Clock.SYSTEM,
) {

    /** The past selves on offer to [petId], as of each emission's moment. Empty for a missing pet. */
    fun observeRivals(petId: Long): Flow<List<SparRival>> =
        combine(
            petRepository.observeAll(),
            sparRepository.observeSnapshots(petId),
            sparRepository.observeBouts(petId),
        ) { pets, snapshots, bouts ->
            pets.firstOrNull { it.id == petId }?.let {
                SparRules.rivals(
                    it,
                    clock.nowMillis(),
                    snapshots,
                    bouts,
                )
            }.orEmpty()
        }

    /** Spars [petId] against its [kind] past self. */
    suspend operator fun invoke(
        petId: Long,
        kind: SparRivalKind,
    ): SparResult {
        val pet = petRepository.observeAll().first().firstOrNull { it.id == petId } ?: return SparResult.NotFound
        if (TitanPlace.RING !in TitanPlace.owned(
                unlockRepository.observeUnlocked().first(),
            )
        ) {
            return SparResult.RingLocked
        }
        val rival = rivalsNow(pet).firstOrNull { it.kind == kind } ?: return SparResult.NoSuchRival
        val power = SparRules.powerOf(pet)

        var foughtAt: Long? = null
        val result = sparRepository.atomically {
            val decided = titanStateRepository.modify(petId) { state, now ->
                if (state.vitals.energy < SparRules.ENERGY_COST) {
                    TitanChange(state = null, result = SparResult.NotAffordable(SparRules.ENERGY_COST))
                } else {
                    val (won, roundsWon, roundsLost) = SparRules.fight(rival, random)
                    foughtAt = now
                    val gained = if (won) SparRules.SPIRIT_ON_WIN else SparRules.SPIRIT_ON_LOSS
                    TitanChange(
                        state = SparRules.afterBout(state, won, now),
                        result = SparResult.Fought(rival, won, roundsWon, roundsLost, spiritGained = gained),
                    )
                }
            } ?: SparResult.NotFound
            if (decided is SparResult.Fought) {
                sparRepository.recordBout(
                    petId = petId,
                    bout = SparBout(kind = kind.key, rivalWeek = rival.weekStart, won = decided.won),
                    power = power,
                    rivalPower = rival.power,
                    roundsWon = decided.roundsWon,
                    roundsLost = decided.roundsLost,
                    foughtAt = checkNotNull(foughtAt),
                )
            }
            decided
        }
        foughtAt?.let { evaluateUnlocks(it) }
        return result
    }

    private suspend fun rivalsNow(pet: Pet): List<SparRival> =
        SparRules.rivals(
            pet,
            clock.nowMillis(),
            sparRepository.observeSnapshots(pet.id).first(),
            sparRepository.observeBouts(pet.id).first(),
        )
}
