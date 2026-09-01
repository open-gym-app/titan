package com.opengym.core.domain.repository

import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.model.StreakState
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.model.XpAward
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The progress the gamification features accumulate — the XP total and the level it buys, the
 * streak, and the set of achievements the user has unlocked.
 *
 * Narrow on purpose: a method appears here when something exists to call it, not in advance
 * of one — the principle [PersonalRecordRepository] was built on.
 * #33 asked for [addXp]; #34 added the two achievement methods because
 * [com.opengym.core.domain.usecase.AchievementTriggerUseCase] calls them. #145 adds the last
 * two, both for [com.opengym.core.domain.usecase.SessionCompletionPipeline]: [updateStreak],
 * which is the store #34 computed a streak with nowhere to put, and [claimSessionXp], which
 * is what makes the cascade safe to re-enter. #106 added [observeStreak] — the one method
 * here that exists for a screen rather than for the cascade, and the one exception to the
 * paragraph above: a protection allowance nothing can read is a mechanic that never happened.
 * The reads the home snapshot (#23) and the progression screen (#32) need arrive the same
 * way. #35 implements whatever this names by then.
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

    /**
     * Claims the right to award [sessionId]'s XP, and returns whether **this call** claimed it.
     *
     * `true` only when no claim was on file; `false` for every call after the first. The check
     * and the write are one atomic operation, for the same reason [unlockAchievement]'s are:
     * two callers must not both be told they may pay.
     *
     * ### Why this exists at all
     *
     * Every other step of the cascade is idempotent by construction — PR detection compares
     * *strictly* better and so beats nothing on a second pass, an already-counted day leaves
     * the streak [StreakResult.Unchanged], and an achievement's row is insert-if-absent. XP is
     * the one that is not: [addXp] banks what it is handed, deliberately (#33), so re-running
     * a finished session's events would pay for it twice. This is the fence that stops it, and
     * it is per-session rather than per-event because "this session's own awards" is the whole
     * unit the cascade re-runs.
     *
     * ### Claimed before the awards, not after
     *
     * The cascade takes the claim *before* banking anything, which chooses under-paying over
     * double-paying: a process death between the claim and the last award leaves the rest of
     * that session's XP owed forever, since no later run gets past the claim. That is the same
     * trade [com.opengym.core.domain.usecase.AchievementTriggerUseCase] already makes for an
     * unlock's XP, made for the same reason — the alternative re-pays on every crash-recovery
     * boot, and there is no bound on how many of those a device can produce. Closing the gap
     * properly means the claim and the awards in one transaction, which is #35's to give.
     *
     * Achievement XP is *not* covered by this claim: it is fenced by the achievement's own row
     * and is paid even on a run that finds the session's claim already taken, since an
     * achievement unlocked on a re-entry is one that was never paid for.
     */
    suspend fun claimSessionXp(sessionId: Long): Boolean

    /**
     * Applies one finished session to the stored streak, and returns where that left it.
     *
     * The read of the stored state, the decision, and the write are **one atomic operation** —
     * the shape [addXp] uses, for the same reason: two sessions finishing at once must not
     * both read the same streak before either writes, or one increment is lost. It matters
     * more here than for XP, because a protection is a *scarce* resource: two interleaved
     * reads of an allowance of one would each be told they may spend it.
     *
     * [advance] is handed the stored state and returns the whole of the next one, rather than
     * this repository deciding anything, so the rule stays in
     * [com.opengym.core.domain.gamification.StreakCalculator] — where the grace window, the
     * same-day guarantee, the protection threshold and the monthly rollover are stated once
     * and can be retuned without a data-layer change. Implementations persist
     * [StreakUpdate.state] exactly as given and return the update unchanged; there is no
     * second rule on this side of the boundary to keep in step, which is what the previous
     * shape of this method — a transform returning a bare result, plus two written rules here
     * about what to do with it — could not promise.
     *
     * The session's own moment is not a parameter: [advance] closes over it. A store has no
     * use for it beyond handing it back, and a signature that took it could be called with an
     * instant the transform does not agree with.
     *
     * @param advance the transition, given the state on file — [StreakState]'s defaults when
     *   nothing has ever been written. Must be pure: it runs inside the transaction, and a
     *   store read from in there would deadlock or escape the atomicity it was called for.
     */
    suspend fun updateStreak(advance: (state: StreakState) -> StreakUpdate): StreakUpdate

    /**
     * The stored streak state as it changes — the streak, the best one ever held, and what is
     * left of this month's protection allowance.
     *
     * A `Flow` rather than a suspend read, unlike everything else on this interface: its
     * consumers are screens rather than the cascade, and a screen open while a session is
     * finished elsewhere in the app must not keep drawing yesterday's streak. #104's quest log
     * is the first of them; this is the read its acceptance criterion asks for, and it is
     * declared now rather than with the screen because the mechanic is worthless if the state
     * it accumulates cannot be looked at.
     *
     * [StreakState]'s defaults for a user who has never trained, rather than a null: unlike
     * `UserProgressDao.observe`, which distinguishes "nothing written" from "nothing earned"
     * because the XP surfaces may want to, a streak of zero days with a full allowance is
     * exactly what a fresh install has and there is no second reading of it.
     *
     * ### The allowance is as stored, not as of now
     *
     * The monthly rollover is applied when a session is *written* (see
     * [com.opengym.core.domain.gamification.StreakCalculator.advance]), so a state read in a
     * month the user has not yet trained in still carries the previous month's remainder. A
     * consumer that renders the count must apply
     * [com.opengym.core.domain.model.StreakProtection.replenishedFor] for the month it is
     * drawing in. That is deliberate rather than an omission: the alternative is a store that
     * reads a clock, which would make this flow's output depend on when it was collected and
     * would emit a stale count anyway the moment the month turned under an open screen.
     */
    fun observeStreak(): Flow<StreakState>
}
