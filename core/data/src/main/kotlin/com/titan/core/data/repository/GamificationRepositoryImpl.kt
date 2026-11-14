package com.titan.core.data.repository

import com.titan.core.data.db.dao.AchievementDao
import com.titan.core.data.db.dao.SessionLootClaimDao
import com.titan.core.data.db.dao.SessionXpClaimDao
import com.titan.core.data.db.dao.UserProgressDao
import com.titan.core.data.db.entity.AchievementEntity
import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.data.mapper.repaired
import com.titan.core.data.mapper.toDomain
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.model.UnlockedAchievement
import com.titan.core.domain.model.UserProgress
import com.titan.core.domain.model.XpAward
import com.titan.core.domain.repository.GamificationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.YearMonth
import javax.inject.Inject

/**
 * The gamification progress, over Room.
 *
 * Four tables rather than one, because they are three different kinds of fact: `user_progress`
 * is a single mutable row, `achievement` is a growing set of unlocks, and `session_xp_claim`
 * and `session_loot_claim` are sets of markers — one per un-idempotent step of the cascade,
 * each spent by the step it fences. Each of the contract's writes is one atomic operation, and
 * the atomicity is where all of the difficulty is — see the DAOs for how each one gets it.
 *
 * ### The rules stay in the domain
 *
 * [addXp] and [updateStreak] are handed the curve and the streak rule as functions and apply
 * them *inside* the transaction rather than deciding anything themselves. That is not
 * indirection for its own sake: it keeps `XpCalculator` and `StreakCalculator` the one place
 * either can be retuned, while keeping the read they operate on and the write that follows in
 * one operation, which no split across layers could do.
 *
 * The streak goes further than the curve does: [updateStreak]'s transform returns the *whole*
 * of the next state rather than a verdict this class then applies, so the anchor rule, the
 * weekly goal and the protection allowance have no second statement here to drift from the first.
 *
 * ### Instants are stored to the millisecond
 *
 * Every timestamp column is epoch milliseconds, so the sub-millisecond part of an [Instant] is
 * dropped on the way in. Nothing reads it back: the streak is judged in weeks, and an unlock is
 * dated for display. The one column that is not a timestamp or a count is
 * `protection_period`, a `YYYY-MM` string — see [UserProgressEntity] for why a month is stored
 * as what it is rather than as a number.
 */
class GamificationRepositoryImpl @Inject constructor(
    private val userProgressDao: UserProgressDao,
    private val achievementDao: AchievementDao,
    private val sessionXpClaimDao: SessionXpClaimDao,
    private val sessionLootClaimDao: SessionLootClaimDao,
) : GamificationRepository {

    /**
     * The one progress row, mapped, with its absence carried through as null — see the
     * contract: a fresh install has no row, and that is a state a screen renders differently
     * from a user who has trained and holds no points.
     */
    override fun observeProgress(): Flow<UserProgress?> = userProgressDao.observe().map { entity -> entity?.toDomain() }

    /**
     * Both levels come from [levelForTotalXp], on the totals either side of [delta], and the
     * one for the new total is what is stored beside it — never the level column read off the
     * row, which was written by whatever curve was in force when it was last touched.
     *
     * `previousLevel` escapes the transform in a local rather than being recomputed after the
     * fact. The two would agree, since the transform is pure over the snapshot the transaction
     * read, but assigning it here keeps "the level before the award" a value produced by the
     * same pass that produced the one after it.
     *
     * The row is [repaired] before the curve sees it, because this reads the entity rather than
     * the mapped [toDomain] value, and the new total is held at [XpCalculator.MAX_TOTAL_XP]. A
     * restored total past what the curve can price would otherwise fail every award.
     */
    override suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward {
        var previousLevel = 0
        val updated = userProgressDao.mutate { stored ->
            val current = stored.repaired()
            previousLevel = levelForTotalXp(current.totalXp)
            val total = (current.totalXp + delta).coerceIn(0, XpCalculator.MAX_TOTAL_XP)
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

    override fun observeUnlockedAchievements(): Flow<List<UnlockedAchievement>> =
        achievementDao.getAll().map { rows ->
            rows.map { row ->
                UnlockedAchievement(
                    achievementId = row.achievementId,
                    unlockedAt = Instant.ofEpochMilli(row.unlockedAt),
                )
            }
        }

    override suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean =
        achievementDao.insertIfAbsent(
            AchievementEntity(achievementId = achievementId, unlockedAt = unlockedAt.toEpochMilli()),
        )

    override suspend fun claimSessionXp(sessionId: Long): Boolean = sessionXpClaimDao.claim(sessionId)

    override suspend fun claimSessionLoot(sessionId: Long): Boolean = sessionLootClaimDao.claim(sessionId)

    /**
     * Reads the stored state, hands it to the rule, writes back what the rule answers with.
     *
     * There is no rule on this side. [advance] returns the whole of the next
     * [com.titan.core.domain.model.StreakState] — the streak, the best one ever held, the
     * anchor that keeps a session from counting twice, where the week in progress stands and
     * what is left of the protection allowance — and this persists it as given. That is the
     * point of the shape: `StreakCalculator` is the one place any of it can be retuned, and the
     * read it operates on and the write that follows are still one transaction, which no split
     * across the two layers could give.
     *
     * `longestStreak` moved into the domain with the rest of it. It used to be maintained
     * here, on the argument that only the store has seen the streaks that came before — but
     * the store hands the rule every one of those figures now, so keeping a `maxOf` on this
     * side would have been a second place the state is decided.
     *
     * What the rule is handed is the [repaired] row, so a restored allowance below zero or a
     * period that is not a month cannot fail the step for every session that follows.
     */
    override suspend fun updateStreak(advance: (state: StreakState) -> StreakUpdate): StreakUpdate {
        lateinit var update: StreakUpdate
        userProgressDao.mutate { stored ->
            val current = stored.repaired()
            update = advance(current.toStreakState())
            current.with(update.state)
        }
        return update
    }

    /**
     * The stored state, or a fresh one until anything has ever been written.
     *
     * `UserProgressDao.observe` answers null for an untouched table, deliberately, so the XP
     * surfaces can tell "nothing banked" from "nothing written". The streak has no such
     * distinction to draw — a user with no row and a user with no streak are the same lifter —
     * so the null is collapsed here rather than pushed to every screen.
     *
     * Distinct emissions only. Room invalidates by *table*, and `user_progress` carries the XP
     * total beside the streak, so a finished session's dozen awards each re-run this query and
     * each hand back a [StreakState] identical to the last one. A screen collecting this would
     * recompose a dozen times for a streak that moved once.
     */
    override fun observeStreak(): Flow<StreakState> =
        userProgressDao.observe().map { it?.repaired()?.toStreakState() ?: StreakState() }.distinctUntilChanged()
}

/** The streak columns of the progress row, as the domain's value. */
private fun UserProgressEntity.toStreakState(): StreakState =
    StreakState(
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        lastCountedAt = lastCountedAt?.let(Instant::ofEpochMilli),
        weekSessions = weekSessions,
        weekGoalMet = weekGoalMet,
        protection = StreakProtection(
            remaining = protectionRemaining,
            period = protectionPeriod?.let(YearMonth::parse),
            lastConsumedAt = protectionConsumedAt?.let(Instant::ofEpochMilli),
        ),
    )

/**
 * This row with [state] written over its streak columns, and the XP columns left alone.
 *
 * A `copy` rather than a new entity: the progress row carries the XP total and the level
 * beside the streak, and a streak write that constructed a whole row would zero them.
 */
private fun UserProgressEntity.with(state: StreakState): UserProgressEntity =
    copy(
        currentStreak = state.currentStreak,
        longestStreak = state.longestStreak,
        lastCountedAt = state.lastCountedAt?.toEpochMilli(),
        weekSessions = state.weekSessions,
        weekGoalMet = state.weekGoalMet,
        protectionRemaining = state.protection.remaining,
        protectionPeriod = state.protection.period?.toString(),
        protectionConsumedAt = state.protection.lastConsumedAt?.toEpochMilli(),
    )
