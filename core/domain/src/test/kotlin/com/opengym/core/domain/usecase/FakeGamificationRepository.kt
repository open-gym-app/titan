package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.repository.GamificationRepository
import java.time.Instant

/**
 * The progress row, in memory.
 *
 * Both levels are derived by calling [GamificationRepository.addXp]'s `levelForTotalXp` on the
 * totals either side of the delta, exactly as the contract requires of a real implementation —
 * a fake that stored a level of its own choosing, or that reused a level column for the
 * previous one, could let a test pass on a total and level the database could never hold
 * together.
 *
 * [unlockAchievement] is likewise insert-if-absent and reports whether it wrote, since that
 * answer is what the trigger use case pays XP on; a fake that always said `true` would let an
 * idempotency test pass against a store that re-awards every session.
 *
 * Single-threaded, like the tests that use it, so it does not reproduce the real store's
 * transactional serialisation; the awards below are never actually interleaved.
 */
class FakeGamificationRepository(initialXp: Long = 0L) : GamificationRepository {

    var totalXp: Long = initialXp
        private set

    /** Every award granted, oldest first. */
    val awards = mutableListOf<XpAward>()

    private val unlocked = mutableMapOf<String, Instant>()

    /**
     * What has been unlocked, and when — the `achievement` table, such as it is.
     *
     * A copy, so a test can hold one of these and compare a later state against it rather
     * than against a view that moved with the store.
     */
    val unlockedAchievements: Map<String, Instant> get() = unlocked.toMap()

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

    override suspend fun unlockedAchievementIds(): Set<String> = unlocked.keys.toSet()

    override suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean = unlocked.putIfAbsent(achievementId, unlockedAt) == null
}
