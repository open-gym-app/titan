package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.model.StreakUpdate
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
 * idempotency test pass against a store that re-awards every session. [claimSessionXp] is the
 * same shape for the same reason: the cascade's re-entry contract rests entirely on the second
 * claim answering `false`.
 *
 * [updateStreak] applies the two rules the contract states — store what
 * [StreakResult.streakAfter] answers, advance `lastCountedAt` only when the result is not
 * [StreakResult.Unchanged] — rather than storing whatever it is told. A fake that advanced the
 * timestamp unconditionally would let a re-run of a counted session read as a new day.
 *
 * Single-threaded, like the tests that use it, so it does not reproduce the real store's
 * transactional serialisation; the awards below are never actually interleaved.
 */
class FakeGamificationRepository(initialXp: Long = 0L) : GamificationRepository {

    var totalXp: Long = initialXp
        private set

    /** The streak on file, and the session it was last counted from. */
    var streakDays: Int = 0
        private set

    var lastCountedAt: Instant? = null
        private set

    /** Sessions whose XP has been claimed — the marker row, such as it is. */
    private val xpClaims = mutableSetOf<Long>()

    /**
     * Thrown instead of banking, writing or reading, when set. The only way to reach the
     * cascade's failure contract: a store that refuses a write is not a state any argument a
     * caller can pass produces.
     */
    var addXpFailure: Throwable? = null

    var streakFailure: Throwable? = null

    var achievementFailure: Throwable? = null

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
        addXpFailure?.let { throw it }
        val previousLevel = levelForTotalXp(totalXp)
        totalXp += delta
        return XpAward(
            awarded = delta,
            totalXp = totalXp,
            previousLevel = previousLevel,
            level = levelForTotalXp(totalXp),
        ).also(awards::add)
    }

    override suspend fun unlockedAchievementIds(): Set<String> {
        achievementFailure?.let { throw it }
        return unlocked.keys.toSet()
    }

    override suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean = unlocked.putIfAbsent(achievementId, unlockedAt) == null

    override suspend fun claimSessionXp(sessionId: Long): Boolean = xpClaims.add(sessionId)

    override suspend fun updateStreak(
        finishedAt: Instant,
        computeStreak: (lastCountedAt: Instant?, currentStreak: Int) -> StreakResult,
    ): StreakUpdate {
        streakFailure?.let { throw it }
        val result = computeStreak(lastCountedAt, streakDays)
        streakDays = result.streakAfter(streakDays)
        // Only a session that counted moves the timestamp — the same-day rule is measured
        // from the first counted session of a day, not the latest one.
        if (result != StreakResult.Unchanged) lastCountedAt = finishedAt
        return StreakUpdate(result = result, days = streakDays)
    }

    /** Seeds a streak already on file, as a user with history would have. */
    fun seedStreak(
        days: Int,
        lastCountedAt: Instant,
    ) {
        streakDays = days
        this.lastCountedAt = lastCountedAt
    }
}
