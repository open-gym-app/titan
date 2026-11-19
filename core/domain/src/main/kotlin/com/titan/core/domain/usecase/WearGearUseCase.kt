package com.titan.core.domain.usecase

import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.WearResult
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.LoadoutRepository
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.UnlockRepository
import kotlinx.coroutines.flow.first

/**
 * Puts a piece of gear on a pet, or takes it off.
 *
 * WEAR on an occupied slot replaces what was there; tapping WORN takes it off. A piece not owned
 * is never shown, and is refused if asked for anyway. Gear is cosmetic: nothing here touches a stat, a vital
 * or power.
 */
class WearGearUseCase(
    private val petRepository: PetRepository,
    private val unlockRepository: UnlockRepository,
    private val gamificationRepository: GamificationRepository,
    private val loadoutRepository: LoadoutRepository,
) {

    /** Wears [gearKey] on [petId]. */
    suspend fun wear(
        petId: Long,
        gearKey: String,
    ): WearResult {
        val gear = Gear.byKey(gearKey) ?: return WearResult.NotGear
        if (petRepository.observeAll().first().none { it.id == petId }) return WearResult.NotFound
        val owned = gear.isOwned(
            unlockRepository.observeUnlocked().first(),
            gamificationRepository.unlockedAchievementIds(),
        )
        if (!owned) return WearResult.Locked

        val replaced = loadoutRepository.observeLoadout(petId).first()[gear.slot.key]?.let(Gear::byKey)
        loadoutRepository.wear(petId, gear.slot, gear.key)
        return WearResult.Worn(replaced = replaced?.takeIf { it != gear })
    }

    /** Takes [gearKey] off [petId] if it is on; does nothing otherwise. */
    suspend fun takeOff(
        petId: Long,
        gearKey: String,
    ) {
        val slot = loadoutRepository.observeLoadout(
            petId,
        ).first().entries.firstOrNull { it.value == gearKey }?.key ?: return
        loadoutRepository.takeOff(petId, slot)
    }
}
