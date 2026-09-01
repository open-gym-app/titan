package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.AchievementDao
import com.opengym.core.data.db.dao.SessionXpClaimDao
import com.opengym.core.data.db.dao.UserProgressDao
import com.opengym.core.data.db.entity.AchievementEntity
import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.repository.GamificationRepository
import java.time.Instant
import javax.inject.Inject

/**
 * The gamification progress, over Room.
 *
 * Three tables rather than one, because they are three different kinds of fact: `user_progress`
 * is a single mutable row, `achievement` is a growing set of unlocks, and `session_xp_claim` is
 * a set of markers. Each of the contract's writes is one atomic operation, and the atomicity is
 * where all of the difficulty is — see the DAOs for how each one gets it.
 *
 * ### The rules stay in the domain
 *
 * [addXp] and [updateStreak] are handed the curve and the streak rule as functions and apply
 * them *inside* the transaction rather than deciding anything themselves. That is not
 * indirection for its own sake: it keeps `XpCalculator` and `StreakCalculator` the one place
 * either can be retuned, while keeping the read they operate on and the write that follows in
 * one operation, which no split across layers could do.
 *
 * ### Instants are stored to the millisecond
 *
 * Every timestamp column is epoch milliseconds, so the sub-millisecond part of an [Instant] is
 * dropped on the way in. Nothing reads it back: the streak is judged in days and whole hours,
 * and an unlock is dated for display.
 */
class GamificationRepositoryImpl @Inject constructor(
    private val userProgressDao: UserProgressDao,
    private val achievementDao: AchievementDao,
    private val sessionXpClaimDao: SessionXpClaimDao,
) : GamificationRepository {

    /**
     * Both levels come from [levelForTotalXp], on the totals either side of [delta], and the
     * one for the new total is what is stored beside it — never the level column read off the
     * row, which was written by whatever curve was in force when it was last touched.
     *
     * `previousLevel` escapes the transform in a local rather than being recomputed after the
     * fact. The two would agree, since the transform is pure over the snapshot the transaction
     * read, but assigning it here keeps "the level before the award" a value produced by the
     * same pass that produced the one after it.
     */
    override suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward {
        var previousLevel = 0
        val updated = userProgressDao.mutate { current ->
            previousLevel = levelForTotalXp(current.totalXp)
            val total = current.totalXp + delta
            current.copy(totalXp = total, currentLevel = levelForTotalXp(total))
        }
        return XpAward(
            awarded = delta,
            totalXp = updated.totalXp,
            previousLevel = previousLevel,
            level = updated.currentLevel,
        )
    }

    override suspend fun unlockedAchievementIds(): Set<String> = achievementDao.getAllIdsOnce().toSet()

    override suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean =
        achievementDao.insertIfAbsent(
            AchievementEntity(achievementId = achievementId, unlockedAt = unlockedAt.toEpochMilli()),
        )

    override suspend fun claimSessionXp(sessionId: Long): Boolean = sessionXpClaimDao.claim(sessionId)

    /**
     * Applies the two rules the contract states, and no others: the streak becomes whatever
     * [StreakResult.streakAfter] answers, and `lastCountedAt` advances to [finishedAt] unless
     * the result is [StreakResult.Unchanged].
     *
     * That second rule is the whole of the same-day guarantee. A store that advanced the
     * timestamp on every session would slide the grace window forward with each workout of a
     * two-a-day, and a re-run of an already-counted session would stop being a no-op.
     *
     * `longestStreak` is maintained here rather than by the calculator because it is a property
     * of the *history* rather than of the session being applied: only the store has seen the
     * streaks that came before. It never falls — a reset lowers the current streak and leaves
     * the best one standing, which is what makes it worth a column of its own.
     */
    override suspend fun updateStreak(
        finishedAt: Instant,
        computeStreak: (lastCountedAt: Instant?, currentStreak: Int) -> StreakResult,
    ): StreakUpdate {
        lateinit var result: StreakResult
        val updated = userProgressDao.mutate { current ->
            val computed = computeStreak(
                current.lastCountedAt?.let(Instant::ofEpochMilli),
                current.currentStreak,
            )
            result = computed
            val days = computed.streakAfter(current.currentStreak)
            // Only a session that counted moves the timestamp — see the doc above.
            val countedAt =
                if (computed == StreakResult.Unchanged) current.lastCountedAt else finishedAt.toEpochMilli()
            current.copy(
                currentStreak = days,
                longestStreak = maxOf(current.longestStreak, days),
                lastCountedAt = countedAt,
            )
        }
        return StreakUpdate(result = result, days = updated.currentStreak)
    }
}
