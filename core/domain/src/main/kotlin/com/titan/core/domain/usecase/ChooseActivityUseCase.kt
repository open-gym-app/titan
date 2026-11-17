package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.ChooseActivityResult
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.repository.TitanStateRepository

/**
 * The user picks what the titan does next, from the Titan tab's "What next".
 *
 * Read, advance to now, apply, store — one transaction, so the energy it charges is the energy
 * the titan has at the moment of the tap. A refusal stores nothing.
 */
class ChooseActivityUseCase(private val titanStateRepository: TitanStateRepository) {

    suspend operator fun invoke(
        petId: Long,
        activity: TitanActivity,
    ): ChooseActivityResult =
        titanStateRepository.modify(petId) { state, now ->
            val result = TitanRules.choose(state, activity, now)
            TitanChange(state = (result as? ChooseActivityResult.Chosen)?.state, result = result)
        } ?: ChooseActivityResult.NotFound
}
