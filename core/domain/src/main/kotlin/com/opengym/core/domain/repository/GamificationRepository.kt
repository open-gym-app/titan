package com.opengym.core.domain.repository

import com.opengym.core.domain.model.XpAward
import java.time.Instant

/**
 * The progress the gamification features accumulate — the XP total and the level it buys, and
 * the set of achievements the user has unlocked.
 *
 * Narrow on purpose: a method appears here when something exists to call it, not in advance
 * of one — the principle [PersonalRecordRepository] was built on.
 * #33 asked for [addXp]; #34 adds the two achievement methods below — including this
 * interface's first read, [unlockedAchievementIds] — because
 * [com.opengym.core.domain.usecase.AchievementTriggerUseCase] calls them. The streak itself
 * is not here yet: #34 computes it as a value
 * ([com.opengym.core.domain.gamification.StreakCalculator]) and #145 is what will store it,
 * so the read/write pair belongs to whichever of those lands the cascade. The reads the home
 * snapshot (#23) and the progression screen (#32) need arrive the same way. #35 implements
 * whatever this names by then.
 */
interface GamificationRepository {

    /**
     * Adds [delta] points to the lifetime total and returns what that award did.
     *
     * The read of the current total, the addition, the re-derivation of the level and the
     * write are **one atomic operation**, not a read followed by a write from the caller.
     * A finished session awards a dozen events in quick succession, and PR detection runs
     * alongside them (#145 decides in what order); two awards that both read the same total
     * before either wrote would each store `total + their own delta` and lose one of them.
     * That is also what makes [XpAward.leveledUp] trustworthy: exactly one award per level
     * can observe the threshold being crossed, because the awards are serialised.
     *
     * [levelForTotalXp] is applied inside that same operation, rather than this repository
     * deriving a level itself, so the curve stays in one place —
     * [com.opengym.core.domain.gamification.XpCalculator], which is where it can be tuned
     * without a data-layer change. Implementations call it **twice per award, on the total
     * either side of [delta]**:
     *
     * - on the total *before* [delta], for [XpAward.previousLevel];
     * - on the total *after* [delta], for [XpAward.level] — and that second answer is what
     *   is stored beside the new total. A stored level that disagrees with the stored total
     *   is the one inconsistency this contract exists to rule out.
     *
     * Both come from the curve rather than from the level column read off the row, because
     * the column was written by whatever curve was in force when it was last touched. Reusing
     * it as [XpAward.previousLevel] would make the first award after a retune of
     * `XP_PER_LEVEL` compare a new-curve level against an old-curve one, and report a level-up
     * — or a level *drop* — that the user did not earn, which is exactly what #27's
     * celebration fires on.
     *
     * @param delta the points to add, never negative — nothing in the app spends XP.
     */
    suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward

    /**
     * The ids of every achievement the user has already unlocked.
     *
     * A suspend read of the whole set rather than a `Flow` or a per-id lookup: its one caller
     * evaluates the entire catalogue against it in one pass, right after a session finishes,
     * and a milestone unlocked seconds ago must be in the answer — which a hot flow's latest
     * emission is not guaranteed to be. The set is bounded by the catalogue, so reading all
     * of it costs nothing worth optimising. A `Flow` for the screen that *lists* unlocks
     * (#104) is a different method, added when that screen exists.
     */
    suspend fun unlockedAchievementIds(): Set<String>

    /**
     * Records [achievementId] as unlocked at [unlockedAt], and returns whether **this call**
     * is what unlocked it.
     *
     * `true` only when a row was actually written; `false` when one was already there. That
     * answer is the whole contract, because it is what the caller awards XP on: the check for
     * "already unlocked" and the write are one atomic operation (#35's
     * `AchievementDao.insertIfAbsent`), so two cascades running for two sessions at once
     * cannot both be told they were the one to unlock it, and cannot both pay for it. A
     * caller that read the unlocked set and then inserted would have exactly that race, which
     * is why the boolean exists rather than a `Unit` return and a re-read.
     *
     * Idempotent by the same token: called again for an achievement already on file, it
     * writes nothing, leaves [unlockedAt] as first recorded, and answers `false`.
     */
    suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean
}
