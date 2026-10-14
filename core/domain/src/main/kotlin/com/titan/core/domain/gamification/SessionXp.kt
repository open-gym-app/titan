package com.titan.core.domain.gamification

import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.XpEvent

/**
 * Which [XpEvent]s a finished session has earned — the rules that stop XP being something to
 * farm, where [XpCalculator] says what each event is worth.
 *
 * Pure: the sets and the records are handed in, so every rule here is a test over two lists.
 *
 * ### The rules
 *
 * - **Only work pays.** A set pays when it is completed, is not a warm-up, and carries a rep, a
 *   duration or a distance. A set ticked off with nothing logged on it, and a warm-up, are
 *   [isPaidSet]-false.
 * - **A session pays on a floor and a ceiling.** Up to [MAX_PAID_SETS] working sets are paid, so
 *   a ninety-set session is worth what a twenty-set one is; and the session itself pays only from
 *   [MIN_SETS_FOR_SESSION_XP] working sets, so starting and finishing an empty workout is worth
 *   nothing.
 * - **Only a beaten record pays.** The caller passes the records that beat an earlier figure; of
 *   those, at most [MAX_PAID_RECORDS] are paid, the best-priced first.
 */
object SessionXp {

    /** The most working sets one session pays for. */
    const val MAX_PAID_SETS = 20

    /** The fewest working sets a session needs before finishing it is worth anything. */
    const val MIN_SETS_FOR_SESSION_XP = 3

    /** The most beaten records one session pays for. */
    const val MAX_PAID_RECORDS = 5

    /**
     * The events [sets] and [beatenRecords] earn, in the order they are banked: the session, then
     * the sets, then the records.
     *
     * @param sets every set of the session, in any state — this decides which count.
     * @param beatenRecords the records the session set that **improved on an earlier one**; a
     *   first-ever figure for an exercise is not among them (see [XpEvent.PrBroken]).
     */
    fun eventsFor(
        sets: List<WorkoutSet>,
        beatenRecords: List<PersonalRecord>,
    ): List<XpEvent> {
        val paidSets = sets.count(::isPaidSet)

        return buildList {
            if (paidSets >= MIN_SETS_FOR_SESSION_XP) add(XpEvent.SessionFinished)
            repeat(paidSets.coerceAtMost(MAX_PAID_SETS)) { add(XpEvent.SetLogged) }
            beatenRecords
                .map { XpEvent.PrBroken(it.type) }
                .sortedByDescending(XpCalculator::xpFor)
                .take(MAX_PAID_RECORDS)
                .forEach(::add)
        }
    }

    /** Whether [set] is a set that pays: done, not a warm-up, and with some work logged on it. */
    fun isPaidSet(set: WorkoutSet): Boolean =
        set.isCompleted &&
            set.setType != SetType.WARMUP &&
            ((set.reps ?: 0) > 0 || (set.duration ?: 0L) > 0L || (set.distance ?: 0L) > 0L)
}
