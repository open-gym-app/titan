package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.repository.GamificationRepository

/**
 * The progress row, in memory.
 *
 * Both levels are derived by calling [GamificationRepository.addXp]'s `levelForTotalXp` on the
 * totals either side of the delta, exactly as the contract requires of a real implementation —
 * a fake that stored a level of its own choosing, or that reused a level column for the
 * previous one, could let a test pass on a total and level the database could never hold
 * together.
 *
 * Single-threaded, like the tests that use it, so it does not reproduce the real store's
 * transactional serialisation; the awards below are never actually interleaved.
 */
class FakeGamificationRepository(initialXp: Long = 0L) : GamificationRepository {

    var totalXp: Long = initialXp
        private set

    /** Every award granted, oldest first. */
    val awards = mutableListOf<XpAward>()

    override suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward {
        val previousLevel = levelForTotalXp(totalXp)
        totalXp += delta
        return XpAward(
            awarded = delta,
            totalXp = totalXp,
            previousLevel = previousLevel,
            level = levelForTotalXp(totalXp),
        ).also(awards::add)
    }
}
