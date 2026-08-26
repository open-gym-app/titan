package com.opengym.core.domain.repository

import com.opengym.core.domain.model.XpAward

/**
 * The single row of progress the gamification features accumulate into — today, the XP total
 * and the level it buys.
 *
 * Write-only for now, and narrow on purpose: #33 has one thing to persist, so it asks for one
 * method. Streaks (#34) and the reads the home snapshot (#23) and the progression screen (#32)
 * need are added here as those land, on the same principle
 * [PersonalRecordRepository] was built on — an interface gains a method when something exists
 * to call it, not in advance of one. #35 implements whatever this names by then.
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
     * [levelForTotalXp] is applied to the new total inside that same operation, rather than
     * this repository deriving a level itself, so the curve stays in one place —
     * [com.opengym.core.domain.gamification.XpCalculator], which is where it can be tuned
     * without a data-layer change. Implementations must call it exactly once per award, on
     * the total *after* [delta], and store what it returns alongside that total; a stored
     * level that disagrees with the stored total is the one inconsistency this contract
     * exists to rule out.
     *
     * @param delta the points to add, never negative — nothing in the app spends XP.
     */
    suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward
}
