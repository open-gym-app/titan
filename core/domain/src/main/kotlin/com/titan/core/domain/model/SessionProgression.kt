package com.titan.core.domain.model

/**
 * Everything finishing one session moved: the records it broke, the XP it paid, what it did
 * to the streak, which achievements it unlocked, which quests it completed and what the drop
 * table handed over.
 *
 * The single value
 * [com.titan.core.domain.usecase.SessionCompletionPipeline] returns, and the reason the
 * summary screen does not re-query for any of it. Each figure here was produced *inside* the
 * write that persisted it — [XpAward.leveledUp] is only trustworthy on those terms, see its
 * doc — so a caller that re-read the progress row afterwards could observe a different total
 * and report a level-up against the wrong session.
 *
 * ### An empty one is a normal answer
 *
 * A session that broke nothing, on a day the streak was already counted for, by a user who
 * holds every achievement they qualify for and whose roll came up short, produces empty lists,
 * no unlocks and no drop. So does a **re-run** of a session already processed: the cascade is
 * safe to re-enter and reports nothing the second time, because nothing happened the second
 * time — see the pipeline's re-entry contract. Neither case is a failure, and neither is
 * distinguishable from the other here on purpose: what the value says is what this run
 * changed.
 *
 * ### Failures are carried, not thrown
 *
 * [failures] is how a step that could not run is reported. The session is already finished and
 * its sets are already saved by the time any of this is attempted, so a store that refuses an
 * XP write must not turn a saved workout into an error — see the pipeline for the whole of
 * that argument. A caller that wants to know whether the payoff is complete asks
 * [isComplete]; one that only wants to draw the recap can ignore it.
 *
 * @property sessionId the session this describes, so a result can be matched to its workout
 *   without the caller having to remember which one it asked about.
 * @property recordsBroken every personal record this session actually beat, one per
 *   [PrType] beaten per exercise. Empty on a re-run, since the second pass beats nothing.
 * @property xp every award banked, oldest first — the session's own events **and** the ones
 *   its achievement unlocks and quest completions paid, so this is the whole breakdown rather
 *   than the part of it the cascade emitted directly. [achievementsUnlocked] and
 *   [questsCompleted] carry the same awards again, attached to what earned them.
 * @property streak what the session did to the streak, or **null** if the streak step could
 *   not run (see [failures]). A finish with no cascade wired has no progression at all rather
 *   than an empty one — see [FinishedSession.progression] — so a null streak here always means
 *   the step was attempted and refused.
 * @property achievementsUnlocked the milestones this run unlocked — never ones the user
 *   already held.
 * @property questsCompleted the quests this run completed, each with the period it was
 *   completed into — never ones already on file for that period, so this is empty on a re-run
 *   just as [achievementsUnlocked] is.
 * @property lootDropped what the session's roll produced, or **null** for the ordinary session
 *   that produced nothing — which is most of them, since four triggers in five pay out nothing
 *   at all on the shipped rates. Null is also what a re-run answers, and what a loot step that
 *   failed leaves behind ([failures] is where the difference is stated); a caller drawing a
 *   recap treats all three the same, because none of them is a drop to show. The catalogue
 *   value rather than the stored row: the rarity a celebration styles on is what the roll
 *   decided, and nothing is read back to learn it.
 * @property failures the steps that threw, in the order they were attempted.
 */
data class SessionProgression(
    val sessionId: Long,
    val recordsBroken: List<PersonalRecord> = emptyList(),
    val xp: List<XpGrant> = emptyList(),
    val streak: StreakUpdate? = null,
    val achievementsUnlocked: List<AchievementUnlock> = emptyList(),
    val questsCompleted: List<QuestCompletion> = emptyList(),
    val lootDropped: Collectible? = null,
    val failures: List<SessionProgressionFailure> = emptyList(),
) {

    /** Points banked across every award in [xp] — zero on a re-run. */
    val xpAwarded: Int get() = xp.sumOf { it.award.awarded }

    /**
     * The lifetime total after the last award, or **null** when nothing was awarded.
     *
     * The last award's figure rather than a sum of the deltas: the awards are serialised by
     * the store, so the total the final one saw is the one actually held.
     */
    val totalXp: Long? get() = xp.lastOrNull()?.award?.totalXp

    /** The level held before the first of these awards, or **null** when none was made. */
    val levelBefore: Int? get() = xp.firstOrNull()?.award?.previousLevel

    /** The level held after the last of them, or **null** when none was made. */
    val levelAfter: Int? get() = xp.lastOrNull()?.award?.level

    /**
     * Whether any award in this run crossed a level threshold — what a celebration fires on.
     *
     * Asked of the awards rather than derived from [levelBefore] and [levelAfter], because
     * the awards are the only place the crossing was actually observed; see
     * [XpAward.leveledUp].
     */
    val leveledUp: Boolean get() = xp.any { it.award.leveledUp }

    /** Whether every step ran. `false` means [failures] holds what did not — and what was lost. */
    val isComplete: Boolean get() = failures.isEmpty()
}

/**
 * One [XpEvent] and what banking it was worth — the unit [SessionProgression.xp] is a list of.
 *
 * The event is kept beside the award so a recap can say *why* the points were paid ("5 sets",
 * "a record broken") without re-deriving it from the amounts, which would put
 * [com.titan.core.domain.gamification.XpCalculator]'s constants in a second place.
 */
data class XpGrant(
    val event: XpEvent,
    val award: XpAward,
)

/**
 * A cascade step that threw, and what it threw.
 *
 * Kept rather than logged and dropped so the caller can decide: a summary screen ignores it,
 * a diagnostics screen shows it, and a future retry knows which step to run again.
 */
data class SessionProgressionFailure(
    val step: SessionProgressionStep,
    val cause: Throwable,
)

/**
 * The steps of the post-session cascade, in the order they run.
 *
 * Declaration order **is** the running order, and the ordering is not free: XP for a record
 * cannot be paid before the record is detected, and an achievement that unlocks on a first
 * record or a four-week streak cannot be evaluated before either has been written. See
 * [com.titan.core.domain.usecase.SessionCompletionPipeline].
 */
enum class SessionProgressionStep {

    /** Detect and write the records the session broke. */
    PR_DETECTION,

    /** Pay for the session, its sets and each record broken. */
    XP,

    /** Apply the session to the streak. */
    STREAK,

    /** Evaluate the catalogue and unlock what the session newly qualifies for. */
    ACHIEVEMENTS,

    /**
     * Complete the quests the session's period has now met, and pay them.
     *
     * After [PR_DETECTION] because a quest can complete on a record, and after [ACHIEVEMENTS]
     * for no stronger reason than that one order has to be written down and quests have no
     * claim on going first. Unlike [ACHIEVEMENTS] it does not depend on [STREAK] — no quest
     * counts streak weeks — so a streak that could not be read does not take this step with it.
     */
    QUESTS,

    /**
     * Roll the finished session's drop table, and store whatever it produced.
     *
     * Last, and nothing above it moved to let it in: it reads none of the figures the earlier
     * steps write, and none of them reads a drop. What it needs instead is a fence of its own,
     * because a roll is the one step besides [XP] that a re-entry could repeat — see
     * [com.titan.core.domain.usecase.LootRollUseCase].
     */
    LOOT,
}
