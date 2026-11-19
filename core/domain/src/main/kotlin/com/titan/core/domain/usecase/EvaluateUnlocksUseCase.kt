package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Unlocks
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.UnlockRepository
import kotlinx.coroutines.flow.first

/**
 * Drops the titan's level-up rewards: one random place or piece of gear per level the user
 * gained in a session, from what has not dropped yet.
 *
 * Runs as the cascade's last step. Only for a user who owns a titan — the pool would otherwise
 * be spent before there was anyone to wear it. The levels come from the session's own XP
 * awards, which a re-entered cascade does not repeat, so a re-run drops nothing.
 *
 * Items tied to an achievement are not dropped here: they are owned as soon as the achievement
 * is held (see [com.titan.core.domain.model.UnlockCondition.Achievement]).
 */
class EvaluateUnlocksUseCase(
    private val petRepository: PetRepository,
    private val unlockRepository: UnlockRepository,
    private val random: RandomSource = RandomSource.SYSTEM,
) {

    /** @return the keys this call dropped. */
    suspend operator fun invoke(
        levelsGained: Int,
        at: Long,
    ): Set<String> {
        if (levelsGained <= 0 || petRepository.count() == 0) return emptySet()
        val drops = Unlocks.levelUpDrops(levelsGained, unlockRepository.observeUnlocked().first(), random)
        return if (drops.isEmpty()) emptySet() else unlockRepository.unlock(drops, at)
    }
}
